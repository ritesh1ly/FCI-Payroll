package in.gov.fci.payroll.general.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.PayConfiguration;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.employee.api.EmployeeDirectory;
import in.gov.fci.payroll.general.api.SupplementaryPayments;
import in.gov.fci.payroll.general.business.GeneralErrorCode;
import in.gov.fci.payroll.general.business.Inputs;
import in.gov.fci.payroll.general.business.StatutoryDeductions;
import in.gov.fci.payroll.general.business.model.SupplementaryPayment;
import in.gov.fci.payroll.general.persistence.GeneralRepositories;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.payroll.api.PayrollRecords;
import in.gov.fci.payroll.payroll.api.PendingSalary;
import in.gov.fci.payroll.platform.approval.ActionType;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.ApprovalStatuses;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.OtpProof;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.error.RecordedRefusal;
import in.gov.fci.payroll.platform.export.ExportReader;
import in.gov.fci.payroll.platform.export.ExportSink;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.paging.Paged;
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
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * I-23 Supplementary Payment (Post-Lock) (SRS-G-33, SRS-G-34, BR-CYC-010; DDL {@code
 * supplementary_payment}): the only route to pay or recover an amount for a month-end-locked
 * period. A locked result is never reopened.
 *
 * <ol>
 *   <li><b>Record</b>: on the screen only a pending salary ({@code SALARY_WAGE}, computed with the
 *       regular rules by {@link PendingSalary}); every other type comes from its originating module
 *       through {@link SupplementaryPayments} with its computed amount. The period must be locked,
 *       the pay code not excluded, the order attached; the duplicate check runs and is recorded.
 *   <li><b>Submit</b>: duplicate check re-run; the statutory deductions must be known (decision
 *       D-5: not configured yet, so only a RECOVERY passes); routed I23_SUBMIT.
 *   <li><b>Decide</b> ({@link #applyDecision}): the Payroll Checker with OTP
 *       SUPPLEMENTARY_POST_LOCK. On approval the check runs a third time inside the decision - a
 *       duplicate aborts it, no role overrides. Returned -> back to the Maker; rejected -> final.
 *   <li><b>Cancel</b>: with a reason, never deleted; after the invoice is sent only once APPS
 *       confirms (SRS-G-37). Release and the APPS Supplementary Invoice are module 23.
 * </ol>
 */
@Service
@Loggable
public class SupplementaryService implements SupplementaryPayments {

    public static final String SCREEN = "I-23";
    static final String ENTITY = "supplementary_payment";
    static final String ACTION = "I23_SUBMIT";
    private static final Set<String> ENDED = Set.of("SEPARATED", "DISMISSED");

    /** A request as the screen shows it; {@code status} adds Returned and Blocked to the DDL's. */
    public record PaymentView(
            Long id,
            String reference,
            String eis,
            String memberName,
            String paymentType,
            Integer payCode,
            String forPeriodYymm,
            BigDecimal amount,
            LocalDate applicationDate,
            LocalDate sanctionDate,
            String sanctionOrderRef,
            String duplicateCheck,
            String duplicateOf,
            String status,
            String invoiceStatus,
            Long invoiceId,
            Instant invoiceSentAt,
            String appsCancelStatus,
            String cancelReason,
            Long orderAttachmentId,
            String orderReference,
            Long approvalId) {}

    /** I-23 tiles: open requests, pending Checker (OTP), blocked - duplicates. */
    public record Tiles(long open, long pendingChecker, long blockedDuplicates, long rejected) {}

    /** India's business date (the dates of reference lists are India dates). */
    private static final java.time.ZoneId INDIA = java.time.ZoneId.of("Asia/Kolkata");

    private final GeneralRepositories.SupplementaryPayments payments;
    private final EmployeeDirectory employees;
    private final OrgDirectory org;
    private final PayrollRecords payroll;
    private final PendingSalary pending;
    private final PayConfiguration configuration;
    private final StatutoryDeductions deductions;
    private final DocumentStore documents;
    private final MakerChecker approvals;
    private final ApprovalStatuses approvalStatuses;
    private final Authorization policy;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    private final ExportReader exportReader;

    public SupplementaryService(
            ExportReader exportReader,
            GeneralRepositories.SupplementaryPayments payments,
            EmployeeDirectory employees,
            OrgDirectory org,
            PayrollRecords payroll,
            PendingSalary pending,
            PayConfiguration configuration,
            StatutoryDeductions deductions,
            DocumentStore documents,
            MakerChecker approvals,
            ApprovalStatuses approvalStatuses,
            Authorization policy,
            AuditService audit,
            JdbcTemplate jdbc,
            Clock clock) {
        this.exportReader = exportReader;
        this.payments = payments;
        this.employees = employees;
        this.org = org;
        this.payroll = payroll;
        this.pending = pending;
        this.configuration = configuration;
        this.deductions = deductions;
        this.documents = documents;
        this.approvals = approvals;
        this.approvalStatuses = approvalStatuses;
        this.policy = policy;
        this.audit = audit;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * The screen's capture: a pending salary of a locked month. The amount is computed, never
     * typed; other payment types are raised by the module that computes them.
     */
    @Transactional
    public PaymentView capture(
            AccessContext context,
            String eis,
            String paymentType,
            String forPeriodYymm,
            LocalDate applicationDate,
            LocalDate sanctionDate,
            String sanctionOrderRef,
            Order order) {
        if (!"SALARY_WAGE".equals(paymentType)) {
            throw new FciException(
                    GeneralErrorCode.SUPPLEMENTARY_SOURCE,
                    new Object[] {paymentType},
                    List.of(),
                    null);
        }
        EmployeeDirectory.Member member =
                employees
                        .memberByEis(eis)
                        .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
        // Scope and period first: nothing is computed for a member or month the user may not pay.
        policy.requireEmployeeRecord(context, SCREEN, "CREATE", member.id(), member.locationId());
        YearMonth month = Inputs.month(forPeriodYymm, GeneralErrorCode.SUPPLEMENTARY_INPUT);
        lockedCycle(member, month, forPeriodYymm);
        PendingSalary.Result salary = Salaries.payable(pending, member.id(), month);
        // The gross: the statutory deductions are applied on top (D-5), never netted in here.
        Long id =
                record(
                        context,
                        member,
                        new NewPayment(
                                member.id(),
                                forPeriodYymm,
                                paymentType,
                                null,
                                salary.gross(),
                                applicationDate,
                                sanctionDate,
                                sanctionOrderRef,
                                order));
        return view(payments.findById(id).orElseThrow(), Map.of());
    }

    /** {@inheritDoc} Same checks as the screen's capture. */
    @Override
    @Transactional
    public Long raise(AccessContext maker, NewPayment payment) {
        EmployeeDirectory.Member member =
                employees
                        .member(payment.employeeId())
                        .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
        return record(maker, member, payment);
    }

    /** Submits a recorded request to the Payroll Checker (approval with OTP). */
    @Transactional(noRollbackFor = RecordedRefusal.class)
    public PaymentView submit(AccessContext context, Long id) {
        SupplementaryPayment p = authorised(context, id, "SUBMIT");
        if (p.getStatus() != SupplementaryPayment.Status.RECORDED) {
            throw status(p);
        }
        // Re-run: the period may have been paid since the request was recorded.
        duplicateCheck(p);
        payments.save(p); // kept when blocked (RecordedRefusal): the request shows why
        if (!p.duplicateCheckPassed()) {
            throw new RecordedRefusal(GeneralErrorCode.SUPPLEMENTARY_DUPLICATE);
        }
        requireDeductionsKnown(p);
        Long location = employees.locationOf(p.getEmployeeId()).orElse(null);
        SubmittedApproval item =
                approvals.submit(
                        context,
                        new Submission(
                                        ACTION,
                                        SCREEN,
                                        "SUBMIT",
                                        ENTITY,
                                        String.valueOf(p.getId()),
                                        ActionType.CREATE,
                                        location,
                                        "{\"paymentId\":"
                                                + p.getId()
                                                + ",\"amount\":\""
                                                + p.getAmount().toPlainString()
                                                + "\"}",
                                        Inputs.month(
                                                        p.getForPeriodYymm(),
                                                        GeneralErrorCode.SUPPLEMENTARY_INPUT)
                                                .atDay(1),
                                        p.getPaymentType()
                                                + " "
                                                + reference(p)
                                                + " for "
                                                + p.getForPeriodYymm())
                                .withImpact(p.getAmount()),
                        (OtpProof) null);
        p.submit(item.id());
        payments.save(p);
        audited(
                context.loginId(),
                p,
                "payment_status",
                SupplementaryPayment.Status.RECORDED.name(),
                p.getStatus().name(),
                "Submitted for approval",
                item.id());
        return view(p, approvalStatuses.of(List.of(item.id())));
    }

    /**
     * The Checker's decision, inside the decision's transaction ({@link SupplementaryEffects}). On
     * approval the duplicate check runs once more; a duplicate throws and rolls the approval back.
     */
    @Transactional
    public void applyDecision(ApprovalDecided decision) {
        payments.findByApprovalId(decision.approvalId())
                .ifPresent(
                        p -> {
                            String before = p.getStatus().name();
                            if (decision.approved()) {
                                duplicateCheck(p);
                                p.approve(decision.otpChallengeId());
                            } else if (decision.outcome() == ApprovalStatus.RETURNED) {
                                p.returned();
                            } else {
                                p.rejected();
                            }
                            payments.save(p);
                            audited(
                                    decision.checkerLoginId(),
                                    p,
                                    "payment_status",
                                    before,
                                    p.getStatus().name(),
                                    decision.reason() == null
                                            ? "Approved with OTP"
                                            : decision.reason(),
                                    decision.approvalId());
                        });
    }

    /** Cancels a request with a reason; its order is cancelled with it. */
    @Transactional
    public PaymentView cancel(AccessContext context, Long id, String reason) {
        SupplementaryPayment p = authorised(context, id, "SUBMIT");
        String why = Inputs.text(reason, 500, GeneralErrorCode.SUPPLEMENTARY_INPUT);
        String before = p.getStatus().name();
        p.cancel(why, clock.instant());
        payments.save(p);
        documents.cancelAll(context.loginId(), ENTITY, String.valueOf(p.getId()), why);
        audited(context.loginId(), p, "payment_status", before, p.getStatus().name(), why, null);
        return view(p, statuses(List.of(p)));
    }

    @Transactional(readOnly = true)
    public Paged<PaymentView> list(AccessContext context, PageSpec spec) {
        return list(context, null, spec);
    }

    /** The requests in scope, optionally of one payment type (the drawn Filter of I-23). */
    @Transactional(readOnly = true)
    public Paged<PaymentView> list(AccessContext context, String paymentType, PageSpec spec) {
        policy.require(context, SCREEN, "VIEW");
        Page<SupplementaryPayment> rows =
                payments.findInScopeOfType(Scopes.of(context), paymentType, spec.pageable());
        Map<Long, ApprovalStatus> statuses = statuses(rows.getContent());
        return Paged.of(rows).map(p -> view(p, statuses));
    }

    /** Rows SupplementaryService#export would write (the size check before streaming, SRS-G-13). */
    @Transactional(readOnly = true)
    public long exportSize(AccessContext context) {
        policy.require(context, SCREEN, "EXPORT");
        Set<Long> scope = Scopes.of(context);
        return exportReader.count(page -> payments.findInScope(scope, page));
    }

    /**
     * The request list as an export (SRS-G-13): every request in scope, uncapped, newest first.
     * Runs in one read-only snapshot (SERIALIZABLE), batch by batch, and checks the rows written
     * against the rows counted in that snapshot.
     */
    @Transactional(readOnly = true, isolation = Isolation.SERIALIZABLE)
    public void export(AccessContext context, ExportSink sink) throws IOException {
        policy.require(context, SCREEN, "EXPORT");
        Set<Long> scope = Scopes.of(context);
        sink.header(
                "Request reference",
                "EIS",
                "Member",
                "Payment type",
                "Pay code",
                "For period",
                "Amount",
                "Application date",
                "Sanction date",
                "Duplicate check",
                "Duplicate of",
                "Status",
                "Invoice status");
        long expected =
                exportReader.readAll(
                        page -> payments.findInScope(scope, page),
                        rows -> {
                            Map<Long, ApprovalStatus> statuses = statuses(rows);
                            for (SupplementaryPayment row : rows) {
                                PaymentView p = view(row, statuses);
                                sink.row(
                                        p.reference(),
                                        p.eis(),
                                        p.memberName(),
                                        p.paymentType(),
                                        p.payCode(),
                                        p.forPeriodYymm(),
                                        p.amount(),
                                        p.applicationDate(),
                                        p.sanctionDate(),
                                        p.duplicateCheck(),
                                        p.duplicateOf(),
                                        p.status(),
                                        p.invoiceStatus());
                            }
                        });
        ExportSink.requireComplete(expected, sink);
    }

    @Transactional(readOnly = true)
    public Tiles tiles(AccessContext context) {
        policy.require(context, SCREEN, "VIEW");
        Object[] c =
                payments.tileCounts(
                                Scopes.of(context),
                                SupplementaryPayment.Status.RECORDED,
                                SupplementaryPayment.Status.SUBMITTED,
                                SupplementaryPayment.DuplicateCheck.BLOCKED,
                                SupplementaryPayment.Status.REJECTED)
                        .get(0);
        return new Tiles(count(c[0]), count(c[1]), count(c[2]), count(c[3]));
    }

    private static long count(Object value) {
        return ((Number) value).longValue();
    }

    @Transactional(readOnly = true)
    public PaymentView get(AccessContext context, Long id) {
        SupplementaryPayment p = authorised(context, id, "VIEW");
        return view(p, statuses(List.of(p)));
    }

    /** The orders / approval notes attached to the request (I-23 "Order copy - attach & store"). */
    @Transactional(readOnly = true)
    public List<DocumentStore.AttachmentView> orders(AccessContext context, Long id) {
        SupplementaryPayment p = authorised(context, id, "VIEW");
        return documents.active(ENTITY, String.valueOf(p.getId()));
    }

    @Transactional
    public DocumentStore.Content file(
            AccessContext context, String sourceIp, Long id, Long attachmentId) {
        SupplementaryPayment p = authorised(context, id, "VIEW");
        return documents.read(
                context.loginId(), sourceIp, ENTITY, String.valueOf(p.getId()), attachmentId);
    }

    // ---------------------------------------------------------------- internals

    /**
     * Records a request after every check that does not need the row; the order is stored first
     * (provisional key) because {@code chk_supp_locked_cycle} requires it at insert.
     */
    private Long record(AccessContext context, EmployeeDirectory.Member member, NewPayment in) {
        policy.requireEmployeeRecord(context, SCREEN, "CREATE", member.id(), member.locationId());
        if (!SupplementaryPayment.TYPES.contains(in.paymentType())) {
            throw new FciException(GeneralErrorCode.SUPPLEMENTARY_INPUT);
        }
        // SRS-G-14: a separated member's records are read-only, except the payments that exist
        // only because the member left (employer contributions, CPF, court cases, recovery).
        if (ENDED.contains(member.employmentStatus())
                && !SupplementaryPayment.SEPARATED_TYPES.contains(in.paymentType())) {
            throw new FciException(GeneralErrorCode.SUPPLEMENTARY_INPUT);
        }
        YearMonth month = Inputs.month(in.forPeriodYymm(), GeneralErrorCode.SUPPLEMENTARY_INPUT);
        PayConfiguration.Component component =
                component(in.payCode(), context.loginId(), member.id());
        if (in.order() == null) {
            // SRS-20-27: the blocked submission is audited.
            audit.submissionBlocked(
                    context.loginId(), ENTITY, String.valueOf(member.id()), SCREEN, "order copy");
            throw new FciException(GeneralErrorCode.SUPPLEMENTARY_INPUT);
        }
        PayrollRecords.Cycle cycle = lockedCycle(member, month, in.forPeriodYymm());
        String provisional = DocumentStore.PROVISIONAL + UUID.randomUUID();
        DocumentStore.AttachmentView order =
                documents.attach(
                        context.loginId(),
                        new DocumentStore.NewAttachment(
                                ENTITY,
                                provisional,
                                in.order().documentType(),
                                in.order().reference(),
                                in.order().orderDate(),
                                in.order().issuingAuthority(),
                                in.order().fileName(),
                                in.order().content(),
                                false));
        SupplementaryPayment p =
                payments.saveAndFlush(
                        SupplementaryPayment.record(
                                member.id(),
                                cycle.cycleInstanceId(),
                                in.forPeriodYymm(),
                                in.paymentType(),
                                component == null ? null : component.componentId(),
                                in.amount(),
                                in.applicationDate(),
                                in.sanctionDate(),
                                Inputs.optionalText(
                                        in.sanctionOrderRef(),
                                        100,
                                        GeneralErrorCode.SUPPLEMENTARY_INPUT),
                                order.id(),
                                component == null || component.taxable(),
                                false));
        documents.adopt(
                context.loginId(), ENTITY, provisional, order.id(), String.valueOf(p.getId()));
        duplicateCheck(p);
        payments.save(p);
        audited(
                context.loginId(),
                p,
                "payment_status",
                null,
                p.getStatus().name(),
                p.getPaymentType()
                        + " for "
                        + p.getForPeriodYymm()
                        + ", duplicate check "
                        + p.getDuplicateCheck(),
                null);
        return p.getId();
    }

    /**
     * The M-07 pay code, refused when unknown or excluded from I-23 (BR-PEN2-003). SRS-14-3
     * (l.2964, "Block and log any attempt to include an excluded code"; BMAD TR-030): the blocked
     * attempt is audited in its own transaction, so the row stays although the request rolls back.
     */
    private PayConfiguration.Component component(Integer payCode, String actor, Long memberId) {
        if (payCode == null) {
            return null;
        }
        PayConfiguration.Component c =
                configuration
                        .component(payCode)
                        .orElseThrow(() -> new FciException(GeneralErrorCode.SUPPLEMENTARY_INPUT));
        if (c.suppExcluded()) {
            audit.recordIndependently(
                    actor,
                    AuditService.Entry.change(
                                    ENTITY,
                                    String.valueOf(memberId),
                                    "pay_code",
                                    null,
                                    String.valueOf(payCode),
                                    "Blocked: pay code "
                                            + payCode
                                            + " is excluded from supplementary payment (SRS-14-3)")
                            .module(SCREEN));
            throw new FciException(
                    GeneralErrorCode.SUPPLEMENTARY_EXCLUDED_CODE,
                    new Object[] {payCode},
                    List.of(),
                    null);
        }
        if (correctedOnI19(payCode)) {
            throw new FciException(
                    GeneralErrorCode.SUPPLEMENTARY_USE_I19,
                    new Object[] {payCode},
                    List.of(),
                    null);
        }
        return c;
    }

    /**
     * SRS l.786 / BR-CYC-018 (US-GAP-113): "Bulk correction of the PT, LWF, Contributory Scheme and
     * Benevolent Fund heads is made on I-19 Maintain Direct Payroll, not on I-23" - the heads I-19
     * permits are configuration (M-16 list MDP_PERMITTED_COMPONENT, in force today), never a list
     * here.
     */
    private boolean correctedOnI19(int payCode) {
        java.sql.Date today = java.sql.Date.valueOf(LocalDate.ofInstant(clock.instant(), INDIA));
        Integer listed =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM reference_value v JOIN reference_list l ON l.list_id"
                                + " = v.list_id WHERE l.list_code = 'MDP_PERMITTED_COMPONENT' AND"
                                + " v.value_code = ? AND v.effective_from <= ? AND (v.effective_to"
                                + " IS NULL OR v.effective_to >= ?)",
                        Integer.class,
                        String.valueOf(payCode),
                        today,
                        today);
        return listed != null && listed > 0;
    }

    /**
     * BR-CYC-010 duplicate check: an amount already paid or scheduled for the same member,
     * component (or type) and period blocks the request:
     *
     * <ul>
     *   <li>salary types: the regular run of record paid the month, or an active I-20 case covers
     *       it;
     *   <li>any type: another live I-23 request matches (its id is recorded as the duplicate).
     * </ul>
     */
    private void duplicateCheck(SupplementaryPayment p) {
        if (SupplementaryPayment.SALARY_TYPES.contains(p.getPaymentType())) {
            Integer offCycle =
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM off_cycle_payment WHERE employee_id = ? AND"
                                    + " salary_yymm = ? AND record_status = 'ACTIVE'",
                            Integer.class,
                            p.getEmployeeId(),
                            p.getForPeriodYymm());
            if (payroll.paidInRegularRun(p.getEmployeeId(), p.getForPeriodYymm())
                    || (offCycle != null && offCycle > 0)) {
                p.duplicateChecked(true, null);
                return;
            }
        }
        List<SupplementaryPayment> matching =
                payments.findMatching(
                        p.getEmployeeId(),
                        p.getForPeriodYymm(),
                        p.getComponentId(),
                        p.getPaymentType(),
                        p.getId());
        p.duplicateChecked(
                !matching.isEmpty(), matching.isEmpty() ? null : matching.get(0).getId());
    }

    /** D-5: a payment needs its statutory deductions; a recovery has none to apply. */
    private void requireDeductionsKnown(SupplementaryPayment p) {
        if (SupplementaryPayment.RECOVERY.equals(p.getPaymentType())) {
            return;
        }
        deductions
                .due(
                        new StatutoryDeductions.Payment(
                                p.getEmployeeId(),
                                p.getForPeriodYymm(),
                                p.getPaymentType(),
                                p.getComponentId(),
                                p.getAmount(),
                                p.isTaxable(),
                                p.isEpsApplicable()))
                .orElseThrow(() -> new FciException(GeneralErrorCode.SUPPLEMENTARY_STATUTORY));
    }

    private SupplementaryPayment authorised(AccessContext context, Long id, String action) {
        SupplementaryPayment p =
                payments.findById(id)
                        .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
        policy.requireEmployeeRecord(
                context,
                SCREEN,
                action,
                p.getEmployeeId(),
                employees.locationOf(p.getEmployeeId()).orElse(null));
        return p;
    }

    /**
     * BR-CYC-010 / trg_supp_period_locked: the month-end-locked cycle of the period at the member's
     * paying unit; an open period is paid by its regular run.
     */
    private PayrollRecords.Cycle lockedCycle(
            EmployeeDirectory.Member member, YearMonth month, String yymm) {
        PayrollRecords.Cycle cycle =
                org.cycleUnitOf(member.locationId())
                        .flatMap(u -> payroll.cycle(u.getId(), Inputs.yymm(month)))
                        .orElse(null);
        if (cycle == null || !cycle.isLocked()) {
            throw new FciException(
                    GeneralErrorCode.SUPPLEMENTARY_NOT_LOCKED,
                    new Object[] {yymm},
                    List.of(),
                    null);
        }
        return cycle;
    }

    private Map<Long, ApprovalStatus> statuses(List<SupplementaryPayment> rows) {
        return approvalStatuses.of(
                rows.stream()
                        .map(SupplementaryPayment::getApprovalId)
                        .filter(Objects::nonNull)
                        .toList());
    }

    private static FciException status(SupplementaryPayment p) {
        return new FciException(
                GeneralErrorCode.SUPPLEMENTARY_STATUS,
                new Object[] {p.getStatus().name()},
                List.of(),
                null);
    }

    /**
     * The screen status (I-23 field table): Draft, Pending Checker (OTP), Approved, Released,
     * Returned, Rejected, Blocked, Cancelled.
     */
    static String derived(SupplementaryPayment p, ApprovalStatus approval) {
        return switch (p.getStatus()) {
            case RECORDED -> {
                if (p.getDuplicateCheck() == SupplementaryPayment.DuplicateCheck.BLOCKED) {
                    yield "BLOCKED";
                }
                yield approval == ApprovalStatus.RETURNED ? "RETURNED" : "DRAFT";
            }
            case SUBMITTED -> "PENDING_CHECKER";
            default -> p.getStatus().name();
        };
    }

    private void audited(
            String actor,
            SupplementaryPayment p,
            String field,
            String before,
            String after,
            String reason,
            Long approvalId) {
        AuditService.Entry entry =
                AuditService.Entry.change(ENTITY, reference(p), field, before, after, reason)
                        .module(SCREEN);
        audit.record(actor, approvalId == null ? entry : entry.approval(approvalId));
    }

    /** Request Reference (I-23 field table: system-generated, unique). */
    static String reference(SupplementaryPayment p) {
        return String.format("SUP-%08d", p.getId());
    }

    private PaymentView view(SupplementaryPayment p, Map<Long, ApprovalStatus> statuses) {
        EmployeeDirectory.Member m = employees.member(p.getEmployeeId()).orElse(null);
        Integer payCode =
                p.getComponentId() == null
                        ? null
                        : configuration
                                .componentById(p.getComponentId())
                                .map(PayConfiguration.Component::legacyPayCode)
                                .orElse(null);
        return new PaymentView(
                p.getId(),
                reference(p),
                m == null ? null : m.eis(),
                m == null ? null : m.name(),
                p.getPaymentType(),
                payCode,
                p.getForPeriodYymm(),
                p.getAmount(),
                p.getApplicationDate(),
                p.getSanctionDate(),
                p.getSanctionOrderRef(),
                p.getDuplicateCheck().name(),
                p.getDuplicateOfPaymentId() == null
                        ? null
                        : String.format("SUP-%08d", p.getDuplicateOfPaymentId()),
                derived(p, p.getApprovalId() == null ? null : statuses.get(p.getApprovalId())),
                p.getInvoiceStatus(),
                p.getApInvoiceId(),
                p.getInvoiceSentAt(),
                p.getAppsCancelStatus(),
                p.getCancelReason(),
                p.getOrderAttachmentId(),
                documents.active(ENTITY, String.valueOf(p.getId())).stream()
                        .map(DocumentStore.AttachmentView::referenceNumber)
                        .filter(Objects::nonNull)
                        .findFirst()
                        .orElse(p.getSanctionOrderRef()),
                p.getApprovalId());
    }
}
