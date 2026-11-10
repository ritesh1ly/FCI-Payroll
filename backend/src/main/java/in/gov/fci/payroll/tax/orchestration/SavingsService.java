package in.gov.fci.payroll.tax.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.employee.api.EmployeeDirectory;
import in.gov.fci.payroll.platform.approval.ActionType;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatuses;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import in.gov.fci.payroll.tax.business.DeclarationRules;
import in.gov.fci.payroll.tax.business.SavingsRules;
import in.gov.fci.payroll.tax.business.TaxErrorCode;
import in.gov.fci.payroll.tax.business.model.ExternalIncomeDeclaration;
import in.gov.fci.payroll.tax.business.model.ExternalIncomeDeclaration.Status;
import in.gov.fci.payroll.tax.business.model.ExternalIncomeDeclaration.Type;
import in.gov.fci.payroll.tax.business.model.ItSection;
import in.gov.fci.payroll.tax.business.model.SavingsDeclaration;
import in.gov.fci.payroll.tax.persistence.TaxRepositories;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * I-18 Savings & Tax Rebate Declarations (SRS-7-11, SRS-7-20, SRS-7-25, SRS I-18 field table;
 * BR-TAX-007, BR-TAX-015; plan 08 phase 08d). The Payroll Maker of the member's paying unit records
 * external savings and other income with a mandatory supporting document; a different Payroll
 * Checker approves (route I18_SUBMIT) and only approved entries feed TDS; an approved external
 * savings entry writes its savings row. The transport rebate months of a year are proposed and
 * approved the same way (route I18_REBATE); internal savings are shown as configured. Entries are
 * for the current financial year (Q-08-5). No OTP.
 */
@Service
@Loggable
public class SavingsService {

    public static final String SCREEN = "I-18";
    public static final String ENTRY_ENTITY = "external_income_declaration";
    public static final String REBATE_ENTITY = "savings_declaration";
    public static final String ENTRY_ROUTE = "I18_SUBMIT";
    public static final String REBATE_ROUTE = "I18_REBATE";
    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    /** The supporting document of an entry (SRS-G-30). */
    public record Document(
            String documentTypeCode,
            String referenceNumber,
            LocalDate documentDate,
            String issuingAuthority,
            String fileName,
            byte[] content) {}

    /** A new entry as the Payroll Maker records it. */
    public record NewEntry(
            Type type,
            String sectionCode,
            String incomeHead,
            String description,
            BigDecimal amount,
            BigDecimal tdsOtherSource) {}

    /** One entry as I-18 shows it; the member's identity, never PAN. */
    public record EntryView(
            Long id,
            Long employeeId,
            String eis,
            String name,
            String finYear,
            Type type,
            String sectionCode,
            String incomeHead,
            String description,
            BigDecimal amount,
            BigDecimal tdsOtherSource,
            Status status,
            Long documentId,
            String enteredBy,
            Instant enteredAt) {}

    /** One savings or rebate row. */
    public record SavingsView(
            Long id,
            SavingsDeclaration.Type type,
            String sectionCode,
            BigDecimal annualAmount,
            String payrollYymm,
            BigDecimal monthlyAmount,
            Long componentId) {}

    /** What the Checker decides on: the rebate months of a member's year. */
    public record RebateProposal(
            Long employeeId,
            String finYear,
            Map<String, BigDecimal> months,
            String proposedBy,
            Instant proposedAt) {}

    /**
     * I-18 for one member and year: the entries, the savings rows, what waits, the sections in
     * force.
     */
    public record MemberYear(
            Long employeeId,
            String eis,
            String name,
            String finYear,
            List<EntryView> entries,
            List<SavingsView> savings,
            List<RebateProposal> pendingRebates,
            List<DeclarationService.SectionOption> sections) {}

    private final TaxRepositories.Entries entries;
    private final TaxRepositories.Savings savings;
    private final TaxRepositories.Sections sections;
    private final EmployeeDirectory employees;
    private final DocumentStore store;
    private final MakerChecker approvals;
    private final ApprovalStatuses statuses;
    private final Authorization policy;
    private final AuditService audit;
    private final ObjectMapper json;
    private final Clock clock;

    @SuppressWarnings("java:S107") // one collaborator per concern
    public SavingsService(
            TaxRepositories.Entries entries,
            TaxRepositories.Savings savings,
            TaxRepositories.Sections sections,
            EmployeeDirectory employees,
            DocumentStore store,
            MakerChecker approvals,
            ApprovalStatuses statuses,
            Authorization policy,
            AuditService audit,
            ObjectMapper json,
            Clock clock) {
        this.entries = entries;
        this.savings = savings;
        this.sections = sections;
        this.employees = employees;
        this.store = store;
        this.approvals = approvals;
        this.statuses = statuses;
        this.policy = policy;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- reads

    /** I-18: one page of the entries of a year in the user's offices, by status (null: all). */
    @Transactional(readOnly = true)
    public Paged<EntryView> register(
            AccessContext context, String finYear, String status, PageSpec page) {
        policy.require(context, SCREEN, "VIEW");
        String year = finYear == null ? currentYear() : finYear;
        if (status != null) {
            Status.valueOf(status);
        }
        if (context.dataScope().isEmpty()) {
            return new Paged<>(List.of(), page.page(), page.size(), 0);
        }
        Paged<ExternalIncomeDeclaration> rows =
                Paged.of(entries.inScope(year, context.dataScope(), status, page.pageable()));
        Map<Long, EmployeeDirectory.Member> members =
                employees.members(
                        rows.items().stream()
                                .map(ExternalIncomeDeclaration::getEmployeeId)
                                .toList());
        return rows.map(e -> view(e, members.get(e.getEmployeeId())));
    }

    /** I-18 for a member (by EIS, in the user's offices; 404 outside them) and a year. */
    @Transactional(readOnly = true)
    public MemberYear member(AccessContext context, String eis, String finYear) {
        policy.require(context, SCREEN, "VIEW");
        EmployeeDirectory.Member m = memberInScope(context, eis, "VIEW");
        String year = finYear == null ? currentYear() : finYear;
        return new MemberYear(
                m.id(),
                m.eis(),
                m.name(),
                year,
                entries.findByEmployeeIdAndFinYearOrderByExtIncomeIdDesc(m.id(), year).stream()
                        .map(e -> view(e, m))
                        .toList(),
                savings.findByEmployeeIdAndFinYearOrderBySavingsId(m.id(), year).stream()
                        .map(SavingsService::view)
                        .toList(),
                pendingRebates(m.id(), year),
                sectionsInForce().values().stream()
                        .sorted(java.util.Comparator.comparing(ItSection::getSectionCode))
                        .map(
                                x ->
                                        new DeclarationService.SectionOption(
                                                x.getSectionCode(),
                                                x.getSectionDescription(),
                                                x.getMaxDeductionAmount(),
                                                x.getRegimeApplicability()))
                        .toList());
    }

    // ---------------------------------------------------------------- entries

    /**
     * I-18: the Payroll Maker records an entry for a member of the paying unit with its supporting
     * document (SRS-7-25); saved, not yet sent to the Checker.
     */
    @Transactional
    public EntryView save(AccessContext context, String eis, NewEntry in, Document document) {
        policy.require(context, SCREEN, "CREATE");
        EmployeeDirectory.Member m = memberInScope(context, eis, "CREATE");
        requireNotOwn(context, m.id());
        if (in == null || in.type() == null) {
            throw new FciException(CommonErrorCode.VALIDATION_FAILED);
        }
        if (document == null || document.content() == null) {
            // SRS-20-27: the blocked submission is audited.
            audit.submissionBlocked(
                    context.loginId(),
                    ENTRY_ENTITY,
                    String.valueOf(m.id()),
                    SCREEN,
                    "supporting document");
            throw new FciException(CommonErrorCode.VALIDATION_FAILED);
        }
        String year = currentYear();
        String section = blankToNull(in.sectionCode());
        String head = blankToNull(in.incomeHead());
        SavingsRules.requireKind(
                in.type(),
                section,
                section != null && sectionsInForce().containsKey(section),
                head);
        BigDecimal tds = in.tdsOtherSource() == null ? BigDecimal.ZERO : in.tdsOtherSource();
        SavingsRules.requireAmounts(in.amount(), tds);
        Instant now = clock.instant();
        // The DDL requires the document at insert: stored under a provisional key, then adopted.
        String provisional = DocumentStore.PROVISIONAL + UUID.randomUUID();
        DocumentStore.AttachmentView proof =
                store.attach(
                        context.loginId(),
                        new DocumentStore.NewAttachment(
                                ENTRY_ENTITY,
                                provisional,
                                document.documentTypeCode(),
                                document.referenceNumber(),
                                document.documentDate(),
                                document.issuingAuthority(),
                                document.fileName(),
                                document.content(),
                                true));
        ExternalIncomeDeclaration e =
                entries.save(
                        ExternalIncomeDeclaration.saved(
                                m.id(),
                                year,
                                in.type(),
                                section,
                                head,
                                blankToNull(in.description()),
                                in.amount(),
                                tds,
                                proof.id(),
                                context.loginId(),
                                now));
        store.adopt(context.loginId(), ENTRY_ENTITY, provisional, proof.id(), key(e));
        record(
                context.loginId(),
                e,
                null,
                Status.MAKER_SAVED,
                in.type() + " " + in.amount().toPlainString(),
                null);
        return view(e, m);
    }

    /** I-18: the Payroll Maker sends a saved entry to the Payroll Checker. */
    @Transactional
    public EntryView submit(AccessContext context, Long id) {
        policy.require(context, SCREEN, "SUBMIT");
        ExternalIncomeDeclaration e = locked(id);
        EmployeeDirectory.Member m = requireInScope(context, e, "SUBMIT");
        requireNotOwn(context, e.getEmployeeId());
        requireStatus(e, Status.MAKER_SAVED);
        SubmittedApproval item =
                approvals.submit(
                        context,
                        new Submission(
                                        ENTRY_ROUTE,
                                        SCREEN,
                                        "SUBMIT",
                                        ENTRY_ENTITY,
                                        key(e),
                                        ActionType.CREATE,
                                        m.locationId(),
                                        null,
                                        today(),
                                        e.type() + " " + m.eis() + " " + e.getFinYear())
                                .withImpact(e.getAmount()),
                        null);
        e.submitted(item.id());
        entries.save(e);
        record(
                context.loginId(),
                e,
                Status.MAKER_SAVED,
                Status.PENDING_CHECKER,
                "sent to the Checker",
                item.id());
        return view(e, m);
    }

    /** I-18: a saved entry withdrawn by the Payroll Maker before submission (kept as CANCELLED). */
    @Transactional
    public EntryView cancel(AccessContext context, Long id, String reason) {
        policy.require(context, SCREEN, "EDIT");
        if (reason == null || reason.isBlank()) {
            throw new FciException(CommonErrorCode.VALIDATION_FAILED);
        }
        ExternalIncomeDeclaration e = locked(id);
        EmployeeDirectory.Member m = requireInScope(context, e, "EDIT");
        requireStatus(e, Status.MAKER_SAVED);
        e.cancelled();
        entries.save(e);
        record(context.loginId(), e, Status.MAKER_SAVED, Status.CANCELLED, reason.strip(), null);
        return view(e, m);
    }

    // ---------------------------------------------------------------- transport rebate

    /**
     * I-18: the transport rebate months of a member's current year, to the Payroll Checker (route
     * I18_REBATE). One open proposal per member and year; a month already approved is refused.
     */
    @Transactional
    public Long proposeRebate(
            AccessContext context, String eis, Map<String, BigDecimal> months, String reason) {
        policy.require(context, SCREEN, "SUBMIT");
        EmployeeDirectory.Member m = memberInScope(context, eis, "SUBMIT");
        requireNotOwn(context, m.id());
        if (reason == null || reason.isBlank()) {
            throw new FciException(CommonErrorCode.VALIDATION_FAILED);
        }
        String year = currentYear();
        SavingsRules.requireRebate(year, months, approvedMonths(m.id(), year));
        String rebateKey = m.id() + "|" + year;
        statuses.pending(REBATE_ENTITY).stream()
                .filter(p -> rebateKey.equals(p.entityKey()))
                .findFirst()
                .ifPresent(
                        p -> {
                            throw new FciException(
                                    ApiErrorCode.REFERENCE_CHANGE_PENDING,
                                    new Object[] {"transport rebate " + year, p.approvalId()},
                                    List.of(),
                                    null);
                        });
        RebateProposal proposal =
                new RebateProposal(
                        m.id(), year, new TreeMap<>(months), context.loginId(), clock.instant());
        return approvals
                .submit(
                        context,
                        new Submission(
                                        REBATE_ROUTE,
                                        SCREEN,
                                        "SUBMIT",
                                        REBATE_ENTITY,
                                        rebateKey,
                                        ActionType.CREATE,
                                        m.locationId(),
                                        json.writeValueAsString(proposal),
                                        today(),
                                        reason.strip())
                                .withImpact(
                                        months.values().stream()
                                                .reduce(BigDecimal.ZERO, BigDecimal::add)),
                        null)
                .id();
    }

    // ---------------------------------------------------------------- decisions

    /**
     * The decision of the Payroll Checker: an approved entry counts (and an external savings entry
     * writes its savings row); a rejected one stays REJECTED; approved rebate months are written,
     * checked again against months approved meanwhile.
     */
    @EventListener
    public void on(ApprovalDecided decision) {
        if (!SCREEN.equals(decision.moduleCode())) {
            return;
        }
        if (decision.concerns(ENTRY_ENTITY)) {
            ExternalIncomeDeclaration e = locked(Long.valueOf(decision.entityKey()));
            if (e.status() != Status.PENDING_CHECKER) {
                return;
            }
            if (decision.approved()) {
                e.approved();
                if (e.type() == Type.EXTERNAL_SAVINGS) {
                    savings.save(SavingsDeclaration.external(e, decision.approvalId()));
                }
            } else {
                e.rejected();
            }
            entries.save(e);
            record(
                    decision.checkerLoginId(),
                    e,
                    Status.PENDING_CHECKER,
                    e.status(),
                    decision.reason() == null ? "Payroll Checker" : decision.reason(),
                    decision.approvalId());
        } else if (decision.concerns(REBATE_ENTITY) && decision.approved()) {
            RebateProposal p = json.readValue(decision.payload(), RebateProposal.class);
            SavingsRules.requireRebate(
                    p.finYear(), p.months(), approvedMonths(p.employeeId(), p.finYear()));
            p.months()
                    .forEach(
                            (yymm, amount) ->
                                    savings.save(
                                            SavingsDeclaration.transportRebate(
                                                    p.employeeId(),
                                                    p.finYear(),
                                                    yymm,
                                                    amount,
                                                    decision.approvalId())));
            audit.record(
                    decision.checkerLoginId(),
                    AuditService.Entry.change(
                                    REBATE_ENTITY,
                                    p.employeeId() + "|" + p.finYear(),
                                    "transport_rebate",
                                    null,
                                    p.months().keySet()
                                            + " "
                                            + p.months().values().stream()
                                                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                                                    .toPlainString(),
                                    decision.reason() == null
                                            ? "Payroll Checker"
                                            : decision.reason())
                            .approval(decision.approvalId())
                            .module(SCREEN));
        }
    }

    // ---------------------------------------------------------------- documents

    /** The supporting document of an entry, in the user's offices (the read is logged). */
    @Transactional
    public DocumentStore.Content document(
            AccessContext context, String sourceIp, Long id, Long documentId) {
        policy.require(context, SCREEN, "VIEW");
        ExternalIncomeDeclaration e =
                entries.findById(id).orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
        requireInScope(context, e, "VIEW");
        if (!documentId.equals(e.getAttachmentId())) {
            throw new FciException(CommonErrorCode.NOT_FOUND);
        }
        return store.read(context.loginId(), sourceIp, ENTRY_ENTITY, key(e), documentId);
    }

    // ---------------------------------------------------------------- helpers

    private List<RebateProposal> pendingRebates(Long employeeId, String year) {
        String rebateKey = employeeId + "|" + year;
        return statuses.pending(REBATE_ENTITY).stream()
                .filter(p -> rebateKey.equals(p.entityKey()))
                .map(p -> json.readValue(p.payload(), RebateProposal.class))
                .toList();
    }

    private Set<String> approvedMonths(Long employeeId, String year) {
        return savings.findByEmployeeIdAndFinYearOrderBySavingsId(employeeId, year).stream()
                .filter(s -> s.type() == SavingsDeclaration.Type.TRANSPORT_REBATE)
                .map(SavingsDeclaration::getPayrollYymm)
                .collect(Collectors.toSet());
    }

    private Map<String, ItSection> sectionsInForce() {
        return sections.inForceOn(today()).stream()
                .collect(Collectors.toMap(ItSection::getSectionCode, Function.identity()));
    }

    private void record(
            String actor,
            ExternalIncomeDeclaration e,
            Status before,
            Status after,
            String reason,
            Long approval) {
        audit.record(
                actor,
                AuditService.Entry.change(
                                ENTRY_ENTITY,
                                key(e),
                                "entry_status",
                                before == null ? null : before.name(),
                                after.name(),
                                reason)
                        .approval(approval)
                        .module(SCREEN));
    }

    private EmployeeDirectory.Member memberInScope(
            AccessContext context, String eis, String action) {
        EmployeeDirectory.Member m =
                employees
                        .memberByEis(eis)
                        .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
        policy.requireRecordAt(context, SCREEN, action, m.locationId());
        return m;
    }

    private EmployeeDirectory.Member requireInScope(
            AccessContext context, ExternalIncomeDeclaration e, String action) {
        EmployeeDirectory.Member m =
                employees
                        .member(e.getEmployeeId())
                        .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
        policy.requireRecordAt(context, SCREEN, action, m.locationId());
        return m;
    }

    private static void requireNotOwn(AccessContext context, Long employeeId) {
        if (employeeId.equals(context.employeeId())) {
            throw new FciException(TaxErrorCode.OWN_DECLARATION);
        }
    }

    private static void requireStatus(ExternalIncomeDeclaration e, Status expected) {
        if (e.status() != expected) {
            throw new FciException(
                    TaxErrorCode.DECLARATION_STATE,
                    new Object[] {e.status().name()},
                    List.of(),
                    null);
        }
    }

    private ExternalIncomeDeclaration locked(Long id) {
        return entries.lockById(id).orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(INDIA));
    }

    private String currentYear() {
        return DeclarationRules.finYearOf(today());
    }

    private static String key(ExternalIncomeDeclaration e) {
        return String.valueOf(e.getExtIncomeId());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static EntryView view(ExternalIncomeDeclaration e, EmployeeDirectory.Member m) {
        return new EntryView(
                e.getExtIncomeId(),
                e.getEmployeeId(),
                m == null ? null : m.eis(),
                m == null ? null : m.name(),
                e.getFinYear(),
                e.type(),
                e.getSectionCode(),
                e.getIncomeHead(),
                e.getDescription(),
                e.getAmount(),
                e.getTdsOtherSource(),
                e.status(),
                e.getAttachmentId(),
                e.getEnteredBy(),
                e.getEnteredAt());
    }

    private static SavingsView view(SavingsDeclaration s) {
        return new SavingsView(
                s.getSavingsId(),
                s.type(),
                s.getSectionCode(),
                s.getAnnualAmount(),
                s.getPayrollYymm(),
                s.getMonthlyAmount(),
                s.getComponentId());
    }
}
