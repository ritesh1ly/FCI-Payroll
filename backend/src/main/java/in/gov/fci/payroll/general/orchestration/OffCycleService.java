package in.gov.fci.payroll.general.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.employee.api.EmployeeDirectory;
import in.gov.fci.payroll.general.business.GeneralErrorCode;
import in.gov.fci.payroll.general.business.Inputs;
import in.gov.fci.payroll.general.business.model.OffCyclePayment;
import in.gov.fci.payroll.general.persistence.GeneralRepositories;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.payroll.api.PayrollRecords;
import in.gov.fci.payroll.payroll.api.PendingSalary;
import in.gov.fci.payroll.platform.approval.ActionType;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.ApprovalStatuses;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.OtpProof;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.config.ApiProperties;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.error.RecordedRefusal;
import in.gov.fci.payroll.platform.export.ExportReader;
import in.gov.fci.payroll.platform.export.ExportSink;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.persistence.RowCounts;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * I-20 Off-Cycle / Exceptional Salary Processing (SRS-G-10; SRS v0.5 use case "Raise and Process an
 * Off-Cycle / Exceptional Salary Payment"; DDL {@code off_cycle_payment}).
 *
 * <ol>
 *   <li><b>Capture</b> (Payroll Maker): member, case type, salary month, approval reference. A
 *       salary month already month-end locked is refused - it is paid through I-23 (SRS-G-10). The
 *       amount is computed with the regular run's inputs and rules ({@link PendingSalary}), never
 *       typed; the duplicate-payment check runs and its result is recorded on the case.
 *   <li><b>Order</b>: the approval note or order is attached (SRS-G-16, SRS-G-30).
 *   <li><b>Submit</b>: needs the order and a passing duplicate check (re-run now); routed
 *       I20_CREATE to the Payroll Checker of the member's unit.
 *   <li><b>Approve</b> ({@link #applyDecision}): the check runs a third time in the decision's
 *       transaction - a failure aborts the approval, no role can override. On approval the amount
 *       is excluded from the salary month's regular run ({@code excluded_from_yymm}).
 *   <li><b>Cancel</b>: never deleted; free until the invoice is sent to APPS (SRS-G-37).
 * </ol>
 *
 * Disbursement and the APPS invoice are module 23 (the invoice stays NOT_RAISED until then).
 */
@Service
@Loggable
public class OffCycleService {

    public static final String SCREEN = "I-20";
    static final String ENTITY = "off_cycle_payment";
    static final String ACTION = "I20_CREATE";
    private static final Set<String> ENDED = Set.of("SEPARATED", "DISMISSED");

    /**
     * A case as the screen shows it; {@code status} is derived (the DDL holds no workflow status).
     */
    public record CaseView(
            Long id,
            String reference,
            String eis,
            String memberName,
            String caseType,
            String salaryYymm,
            String processingYymm,
            BigDecimal amount,
            String approvalReference,
            String duplicateCheck,
            String status,
            String invoiceStatus,
            String invoiceNo,
            Instant invoiceSentToAppsDate,
            Instant appsCancelConfirmedAt,
            String excludedFromYymm,
            String cancelReason,
            int orders,
            Long approvalId) {}

    private final GeneralRepositories.OffCyclePayments cases;
    private final EmployeeDirectory employees;
    private final OrgDirectory org;
    private final PayrollRecords payroll;
    private final PendingSalary pending;
    private final DocumentStore documents;
    private final MakerChecker approvals;
    private final ApprovalStatuses approvalStatuses;
    private final Authorization policy;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final ApiProperties api;
    private final Clock clock;

    private final ExportReader exportReader;

    public OffCycleService(
            ExportReader exportReader,
            GeneralRepositories.OffCyclePayments cases,
            EmployeeDirectory employees,
            OrgDirectory org,
            PayrollRecords payroll,
            PendingSalary pending,
            DocumentStore documents,
            MakerChecker approvals,
            ApprovalStatuses approvalStatuses,
            Authorization policy,
            AuditService audit,
            JdbcTemplate jdbc,
            ApiProperties api,
            Clock clock) {
        this.exportReader = exportReader;
        this.cases = cases;
        this.employees = employees;
        this.org = org;
        this.payroll = payroll;
        this.pending = pending;
        this.documents = documents;
        this.approvals = approvals;
        this.approvalStatuses = approvalStatuses;
        this.policy = policy;
        this.audit = audit;
        this.jdbc = jdbc;
        this.api = api;
        this.clock = clock;
    }

    /** Captures a case: checks, computes the amount and runs the duplicate check. */
    @Transactional
    public CaseView capture(
            AccessContext context,
            String eis,
            String caseType,
            String salaryYymm,
            String approvalReference) {
        EmployeeDirectory.Member member =
                employees
                        .memberByEis(eis)
                        .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
        policy.requireEmployeeRecord(context, SCREEN, "CREATE", member.id(), member.locationId());
        if (!OffCyclePayment.CASE_TYPES.contains(caseType)) {
            throw new FciException(GeneralErrorCode.OFFCYCLE_INPUT);
        }
        // A separated member is paid off-cycle only for a death in service (the settlement that
        // "cannot wait").
        if (ENDED.contains(member.employmentStatus()) && !"DEATH_IN_SERVICE".equals(caseType)) {
            throw new FciException(GeneralErrorCode.OFFCYCLE_INPUT);
        }
        YearMonth month = Inputs.month(salaryYymm, GeneralErrorCode.OFFCYCLE_INPUT);
        YearMonth now = YearMonth.from(today());
        if (month.isAfter(now)) {
            throw new FciException(
                    GeneralErrorCode.OFFCYCLE_INPUT); // a salary not yet earned is not "pending"
        }
        PayrollRecords.Cycle cycle = requireMonthOpen(member.locationId(), month);
        PendingSalary.Result salary = Salaries.payable(pending, member.id(), month);
        OffCyclePayment c =
                cases.saveAndFlush(
                        OffCyclePayment.capture(
                                member.id(),
                                cycle == null ? null : cycle.cycleInstanceId(),
                                caseType,
                                salaryYymm,
                                Inputs.yymm(now),
                                salary.net(),
                                Inputs.text(
                                        approvalReference, 100, GeneralErrorCode.OFFCYCLE_INPUT)));
        c.duplicateCheck(duplicateFailure(c), clock.instant());
        cases.save(c);
        audited(
                context.loginId(),
                c,
                "record_status",
                null,
                c.getRecordStatus(),
                caseType
                        + " case for "
                        + salaryYymm
                        + ", duplicate check "
                        + c.getDuplicateCheckResult(),
                null);
        return view(c, Map.of());
    }

    /** Attaches the approval note or order (SRS-G-16 / SRS-G-30). */
    @Transactional
    public DocumentStore.AttachmentView attachOrder(
            AccessContext context,
            Long id,
            String documentType,
            String reference,
            LocalDate orderDate,
            String authority,
            String fileName,
            byte[] content) {
        OffCyclePayment c = authorised(context, id, "CREATE");
        requireEditable(c);
        return documents.attach(
                context.loginId(),
                new DocumentStore.NewAttachment(
                        ENTITY,
                        String.valueOf(c.getId()),
                        documentType,
                        reference,
                        orderDate,
                        authority,
                        fileName,
                        content,
                        false));
    }

    /**
     * Submits the case to the Payroll Checker; blocked without an order or with a failing check.
     */
    @Transactional(noRollbackFor = RecordedRefusal.class)
    public CaseView submit(AccessContext context, Long id) {
        OffCyclePayment c = authorised(context, id, "SUBMIT");
        requireEditable(c);
        if (documents.countActive(ENTITY, String.valueOf(c.getId())) == 0) {
            // SRS-20-27: the blocked submission is audited.
            audit.submissionBlocked(
                    context.loginId(), ENTITY, String.valueOf(c.getId()), SCREEN, "order copy");
            throw new FciException(GeneralErrorCode.OFFCYCLE_ORDER_REQUIRED);
        }
        // The month may have been locked since capture: it is then paid through I-23 (SRS-G-10).
        requireMonthOpen(c);
        // The check is re-run: a regular run or another case may have paid the month since capture.
        c.duplicateCheck(duplicateFailure(c), clock.instant());
        cases.save(c); // kept when blocked (RecordedRefusal): the case shows why it is Blocked
        if (!c.duplicateCheckPassed()) {
            throw new RecordedRefusal(
                    GeneralErrorCode.OFFCYCLE_DUPLICATE,
                    new Object[] {c.getDuplicateCheckResult()});
        }
        Long location = employees.locationOf(c.getEmployeeId()).orElse(null);
        SubmittedApproval item =
                approvals.submit(
                        context,
                        new Submission(
                                        ACTION,
                                        SCREEN,
                                        "SUBMIT",
                                        ENTITY,
                                        String.valueOf(c.getId()),
                                        ActionType.CREATE,
                                        location,
                                        "{\"offCycleId\":"
                                                + c.getId()
                                                + ",\"amount\":\""
                                                + c.getAmount().toPlainString()
                                                + "\"}",
                                        Inputs.month(
                                                        c.getSalaryYymm(),
                                                        GeneralErrorCode.OFFCYCLE_INPUT)
                                                .atDay(1),
                                        c.getCaseType()
                                                + " case "
                                                + reference(c)
                                                + ": "
                                                + c.getApprovalReference())
                                .withImpact(c.getAmount()),
                        (OtpProof) null);
        c.submitted(item.id());
        cases.save(c);
        audited(
                context.loginId(),
                c,
                "approval_id",
                null,
                String.valueOf(item.id()),
                "Submitted for approval",
                item.id());
        return view(c, approvalStatuses.of(List.of(item.id())));
    }

    /**
     * The Checker's decision, applied in the decision's transaction ({@link OffCycleEffects}). On
     * approval the duplicate check runs once more; a failure throws, which rolls the approval back
     * ("Block disbursement if the check fails; no role can override the block").
     */
    @Transactional
    public void applyDecision(Long approvalId, boolean approved, String checker) {
        cases.findByApprovalId(approvalId)
                .ifPresent(
                        c -> {
                            if (!approved) {
                                return; // returned or rejected: the case stays as it was, its
                                // status follows the approval
                            }
                            // Both re-checked at approval: a failure rolls the approval back.
                            requireMonthOpen(c);
                            c.duplicateCheck(duplicateFailure(c), clock.instant());
                            c.approved();
                            cases.save(c);
                            audited(
                                    checker,
                                    c,
                                    "excluded_from_yymm",
                                    null,
                                    c.getExcludedFromYymm(),
                                    "Approved; excluded from the regular run of "
                                            + c.getSalaryYymm(),
                                    approvalId);
                        });
    }

    /**
     * Cancels a case with a reason; its documents are cancelled with it. Not while the Checker
     * decides.
     */
    @Transactional
    public CaseView cancel(AccessContext context, Long id, String reason) {
        OffCyclePayment c = authorised(context, id, "SUBMIT");
        if (inQueue(approvalOf(c))) {
            throw new FciException(
                    GeneralErrorCode.OFFCYCLE_STATUS,
                    new Object[] {"PENDING_CHECKER"},
                    List.of(),
                    null);
        }
        String why = Inputs.text(reason, 500, GeneralErrorCode.OFFCYCLE_INPUT);
        c.cancel(why);
        cases.save(c);
        documents.cancelAll(context.loginId(), ENTITY, String.valueOf(c.getId()), why);
        audited(
                context.loginId(),
                c,
                "record_status",
                OffCyclePayment.ACTIVE,
                OffCyclePayment.CANCELLED,
                why,
                null);
        return view(c, statuses(List.of(c)));
    }

    @Transactional(readOnly = true)
    public Paged<CaseView> list(AccessContext context, PageSpec spec) {
        return list(context, null, spec);
    }

    /**
     * The cases in scope, optionally of one case type (I-20 Filter: LATE_JOINER, DEATH_IN_SERVICE,
     * OTHER).
     */
    @Transactional(readOnly = true)
    public Paged<CaseView> list(AccessContext context, String caseType, PageSpec spec) {
        policy.require(context, SCREEN, "VIEW");
        Set<Long> scope = Scopes.of(context);
        Page<OffCyclePayment> rows = cases.findInScopeOfType(scope, caseType, spec.pageable());
        Map<Long, ApprovalStatus> statuses = statuses(rows.getContent());
        return Paged.of(rows).map(c -> view(c, statuses));
    }

    /** Rows OffCycleService#export would write (the size check before streaming, SRS-G-13). */
    @Transactional(readOnly = true)
    public long exportSize(AccessContext context) {
        policy.require(context, SCREEN, "EXPORT");
        Set<Long> scope = Scopes.of(context);
        return exportReader.count(page -> cases.findInScope(scope, page));
    }

    /**
     * The case list as an export (SRS-G-13): every case in scope, uncapped, in the screen's order
     * (newest first). Runs in one read-only snapshot (SERIALIZABLE), batch by batch, and checks the
     * rows written against the rows counted in that snapshot.
     */
    @Transactional(readOnly = true, isolation = Isolation.SERIALIZABLE)
    public void export(AccessContext context, ExportSink sink) throws IOException {
        policy.require(context, SCREEN, "EXPORT");
        Set<Long> scope = Scopes.of(context);
        sink.header(
                "Case reference",
                "EIS",
                "Member",
                "Case type",
                "Salary month",
                "Processing month",
                "Amount",
                "Approval reference",
                "Duplicate check",
                "Status",
                "Excluded from",
                "Invoice status");
        long expected =
                exportReader.readAll(
                        page -> cases.findInScope(scope, page),
                        rows -> {
                            Map<Long, ApprovalStatus> statuses = statuses(rows);
                            for (OffCyclePayment row : rows) {
                                CaseView c = view(row, statuses);
                                sink.row(
                                        c.reference(),
                                        c.eis(),
                                        c.memberName(),
                                        c.caseType(),
                                        c.salaryYymm(),
                                        c.processingYymm(),
                                        c.amount(),
                                        c.approvalReference(),
                                        c.duplicateCheck(),
                                        c.status(),
                                        c.excludedFromYymm(),
                                        c.invoiceStatus());
                            }
                        });
        ExportSink.requireComplete(expected, sink);
    }

    @Transactional(readOnly = true)
    public CaseView get(AccessContext context, Long id) {
        OffCyclePayment c = authorised(context, id, "VIEW");
        return view(c, statuses(List.of(c)));
    }

    /** The approval notes / orders attached to the case (I-20 "Order copy - attach & store"). */
    @Transactional(readOnly = true)
    public List<DocumentStore.AttachmentView> orders(AccessContext context, Long id) {
        OffCyclePayment c = authorised(context, id, "VIEW");
        return documents.active(ENTITY, String.valueOf(c.getId()));
    }

    @Transactional
    public DocumentStore.Content file(
            AccessContext context, String sourceIp, Long id, Long attachmentId) {
        OffCyclePayment c = authorised(context, id, "VIEW");
        return documents.read(
                context.loginId(), sourceIp, ENTITY, String.valueOf(c.getId()), attachmentId);
    }

    // ---------------------------------------------------------------- internals

    /**
     * The duplicate-payment check of SRS-G-10 / BR-CYC-010: the member's salary for the month must
     * not be paid already, nor be paid again:
     *
     * <ul>
     *   <li>a regular run of record (ACTUAL, CURRENT result) already paid the month;
     *   <li>another active off-cycle case covers the month;
     *   <li>an I-23 salary payment for the month exists and was not rejected or cancelled.
     * </ul>
     *
     * The next regular run cannot pay it again once approved: the computation skips the member.
     *
     * @return null when the check passes, else the reason
     */
    private String duplicateFailure(OffCyclePayment c) {
        if (payroll.paidInRegularRun(c.getEmployeeId(), c.getSalaryYymm())) {
            return "salary for " + c.getSalaryYymm() + " already paid in the regular run";
        }
        if (cases.countOtherActive(c.getEmployeeId(), c.getSalaryYymm(), c.getId()) > 0) {
            return "another off-cycle case covers " + c.getSalaryYymm();
        }
        Integer supplementary =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM supplementary_payment WHERE employee_id = ? AND"
                                + " for_period_yymm = ? AND payment_type IN ('SALARY_WAGE',"
                                + " 'SALARY_ARREAR') AND payment_status NOT IN ('REJECTED',"
                                + " 'CANCELLED')",
                        Integer.class,
                        c.getEmployeeId(),
                        c.getSalaryYymm());
        if (RowCounts.of(supplementary) > 0) {
            return "salary for " + c.getSalaryYymm() + " paid through I-23";
        }
        return null;
    }

    private OffCyclePayment authorised(AccessContext context, Long id, String action) {
        OffCyclePayment c =
                cases.findById(id).orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
        policy.requireEmployeeRecord(
                context,
                SCREEN,
                action,
                c.getEmployeeId(),
                employees.locationOf(c.getEmployeeId()).orElse(null));
        return c;
    }

    /**
     * Editable = active and not awaiting or past the Checker's decision. A returned or withdrawn
     * case may be corrected and resubmitted; a rejected one is final (cancel it and raise anew).
     */
    private void requireEditable(OffCyclePayment c) {
        ApprovalStatus s = approvalOf(c);
        if (!OffCyclePayment.ACTIVE.equals(c.getRecordStatus())
                || inQueue(s)
                || s == ApprovalStatus.APPROVED
                || s == ApprovalStatus.REJECTED) {
            throw new FciException(
                    GeneralErrorCode.OFFCYCLE_STATUS,
                    new Object[] {derived(c, s)},
                    List.of(),
                    null);
        }
    }

    /** The status of the case's latest approval item, or null if never submitted. */
    private ApprovalStatus approvalOf(OffCyclePayment c) {
        return c.getApprovalId() == null
                ? null
                : approvalStatuses.of(List.of(c.getApprovalId())).get(c.getApprovalId());
    }

    /**
     * Still in the Checker's queue: neither editable nor cancellable until decided or withdrawn.
     */
    private static boolean inQueue(ApprovalStatus s) {
        return s == ApprovalStatus.PENDING_APPROVAL || s == ApprovalStatus.PENDING_ATTACHMENT;
    }

    /**
     * SRS-G-10, BR-CYC-010: "Where the salary month is already month-end locked, the payment is
     * made through I-23". Checked at capture, at submission and at approval, since the month can
     * lock while a draft waits (gate G3 holds the lock only once the case is submitted).
     */
    private PayrollRecords.Cycle requireMonthOpen(Long location, YearMonth month) {
        PayrollRecords.Cycle cycle = location == null ? null : cycleOf(location, month);
        if (cycle != null && cycle.isLocked()) {
            throw new FciException(
                    GeneralErrorCode.OFFCYCLE_MONTH_LOCKED,
                    new Object[] {Inputs.yymm(month)},
                    List.of(),
                    null);
        }
        return cycle;
    }

    private void requireMonthOpen(OffCyclePayment c) {
        requireMonthOpen(
                employees.locationOf(c.getEmployeeId()).orElse(null),
                Inputs.month(c.getSalaryYymm(), GeneralErrorCode.OFFCYCLE_INPUT));
    }

    private PayrollRecords.Cycle cycleOf(Long location, YearMonth month) {
        return org.cycleUnitOf(location)
                .flatMap(u -> payroll.cycle(u.getId(), Inputs.yymm(month)))
                .orElse(null);
    }

    private Map<Long, ApprovalStatus> statuses(List<OffCyclePayment> rows) {
        return approvalStatuses.of(
                rows.stream()
                        .map(OffCyclePayment::getApprovalId)
                        .filter(Objects::nonNull)
                        .toList());
    }

    /**
     * The SRS screen status: Draft, Pending Checker, Approved, Blocked, Returned, Rejected or
     * Cancelled.
     */
    static String derived(OffCyclePayment c, ApprovalStatus approval) {
        if (OffCyclePayment.CANCELLED.equals(c.getRecordStatus())) {
            return "CANCELLED";
        }
        if (c.isDuplicateCheckRun() && !c.duplicateCheckPassed()) {
            return "BLOCKED";
        }
        if (approval == null) {
            return "DRAFT";
        }
        return switch (approval) {
            case PENDING_APPROVAL, PENDING_ATTACHMENT -> "PENDING_CHECKER";
            case DRAFT, WITHDRAWN -> "DRAFT"; // withdrawn by the Maker: editable again
            case APPROVED -> "APPROVED";
            case RETURNED -> "RETURNED";
            case REJECTED -> "REJECTED";
        };
    }

    private void audited(
            String actor,
            OffCyclePayment c,
            String field,
            String before,
            String after,
            String reason,
            Long approvalId) {
        AuditService.Entry entry =
                AuditService.Entry.change(ENTITY, reference(c), field, before, after, reason)
                        .module(SCREEN);
        audit.record(actor, approvalId == null ? entry : entry.approval(approvalId));
    }

    /** Case Reference (I-20 field table: system-generated, unique). */
    static String reference(OffCyclePayment c) {
        return String.format("OFC-%08d", c.getId());
    }

    private CaseView view(OffCyclePayment c, Map<Long, ApprovalStatus> statuses) {
        EmployeeDirectory.Member m = employees.member(c.getEmployeeId()).orElse(null);
        return new CaseView(
                c.getId(),
                reference(c),
                m == null ? null : m.eis(),
                m == null ? null : m.name(),
                c.getCaseType(),
                c.getSalaryYymm(),
                c.getProcessingYymm(),
                c.getAmount(),
                c.getApprovalReference(),
                c.getDuplicateCheckResult(),
                derived(c, c.getApprovalId() == null ? null : statuses.get(c.getApprovalId())),
                c.getInvoiceStatus(),
                c.getInvoiceNo(),
                c.getInvoiceSentToAppsDate(),
                c.getAppsCancelConfirmedAt(),
                c.getExcludedFromYymm(),
                c.getCancelReason(),
                documents.countActive(ENTITY, String.valueOf(c.getId())),
                c.getApprovalId());
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(api.businessZone()));
    }
}
