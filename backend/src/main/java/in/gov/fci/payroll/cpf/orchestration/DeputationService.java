package in.gov.fci.payroll.cpf.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.cpf.business.CpfErrorCode;
import in.gov.fci.payroll.cpf.business.DeputationRules;
import in.gov.fci.payroll.cpf.business.LedgerBalances;
import in.gov.fci.payroll.cpf.business.model.BorrowingOrganisation;
import in.gov.fci.payroll.cpf.business.model.CpfDeputationRecovery;
import in.gov.fci.payroll.cpf.business.model.CpfDeputationRecovery.EntryType;
import in.gov.fci.payroll.cpf.business.model.CpfDeputationRecovery.Status;
import in.gov.fci.payroll.cpf.business.model.EmployeePfDetail.PaymentType;
import in.gov.fci.payroll.cpf.business.model.PfSanction;
import in.gov.fci.payroll.cpf.persistence.CpfRepositories;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.employee.api.EmployeeDirectory;
import in.gov.fci.payroll.platform.approval.ActionType;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.OtpProof;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.persistence.InLists;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * I-09 CPF Adjustment / Recovery Adjustment for deputed members (plan 07 phase 07g; SRS-6-27 …
 * 6-31, BR-CPF-016, BR-CPF-021 … 024; DDL {@code cpf_deputation_recovery}, {@code
 * borrowing_organisation}). CPF stays with FCI throughout a deputation: the Manager (CPF) at a
 * Zonal Office or Headquarters records what the borrowing organisation remits, with the remittance
 * evidence, and the AGM (CPF) approves it (route I09_CREATE; no OTP, not a gated action):
 *
 * <ul>
 *   <li>an advance instalment against the sanctioned advance it repays: the outstanding (sanctioned
 *       − approved instalments) falls only on approval, and never below zero (BR-CPF-022: the
 *       excess is recorded apart as a contribution receipt or a refund item, never netted);
 *   <li>a monthly contribution receipt with its employee, employer and VPF parts, credited to the
 *       member's ledger against the month it relates to on approval (BR-CPF-023, a
 *       DEPUTATION_RECEIPT row through {@link CpfLedgerService#post});
 *   <li>an excess refund item, recorded on the Checker's decision.
 * </ul>
 *
 * The deputation itself (from, to) is HRMS's, read from the member's approved DEPUTATION_OUT event,
 * never keyed here. The credit memo to Oracle EBS / APPS (SRS-6-27) waits for module 23 ({@code
 * credit_memo_flag} stays N; PENDING 07g-apps). A month of the deputation without an approved
 * receipt is listed for the CPF Control Register (SRS-6-30).
 */
@Service
@Loggable
public class DeputationService {

    public static final String SCREEN = "I-09";
    public static final String ENTITY = "cpf_deputation_recovery";
    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    private static final Pattern ORG_CODE = Pattern.compile("^[A-Z0-9_-]{1,30}$");
    private static final Pattern PRINTABLE = Pattern.compile("^[\\x20-\\x7E]*$");

    /** Multi-line text of a text area (contact detail, a reason): line breaks kept (R-ASCII). */
    private static final Pattern PRINTABLE_TEXT = Pattern.compile("^[\\x20-\\x7E\\t\\r\\n]*$");

    private static final Set<String> ADVANCE_STAGES = Set.of("SANCTIONED", "PAID");

    /** A borrowing organisation as I-09 lists it. */
    public record Organisation(Long id, String code, String name, String contact, boolean active) {}

    /** What the Manager (CPF) keys; parts not used by the type are zero. */
    public record Entry(
            String eis,
            Long borrowingOrgId,
            EntryType type,
            Long sanctionId,
            String payrollYymm,
            BigDecimal instalment,
            BigDecimal employee,
            BigDecimal employer,
            BigDecimal vpf) {}

    /** The remittance evidence, through the standard order-copy control (SRS-G-30). */
    public record Evidence(
            String documentTypeCode,
            String referenceNumber,
            LocalDate orderDate,
            String issuingAuthority,
            String fileName,
            byte[] content) {}

    /** I-09 Remittance Evidence: "PDF / JPG / PNG, up to 5 files". */
    public static final int MAX_EVIDENCE_FILES = 5;

    /** An active evidence file of an entry, as the register lists it (never the bytes). */
    public record EvidenceFile(Long id, String fileName, String uploadedBy, Instant uploadedAt) {}

    /** A sanctioned advance of the member with what is still outstanding (SRS-6-28). */
    public record Advance(
            Long sanctionId,
            String sanctionNumber,
            BigDecimal sanctioned,
            BigDecimal outstanding) {}

    /** The deputed member I-09 works on. */
    public record Member(
            String eis,
            String name,
            LocalDate deputationFrom,
            LocalDate deputationTo,
            List<Advance> advances,
            List<String> monthsWithoutReceipt,
            List<EntryView> entries) {}

    /** An entry as the register shows it. */
    public record EntryView(
            Long id,
            String eis,
            String organisation,
            String type,
            Long sanctionId,
            String payrollYymm,
            BigDecimal instalment,
            BigDecimal employee,
            BigDecimal employer,
            BigDecimal vpf,
            BigDecimal outstandingBefore,
            BigDecimal balanceAfter,
            String status,
            Long approvalId,
            Long evidenceId,
            List<EvidenceFile> evidence,
            // SRS l.2167: the sanctioned advance an instalment repays, by its number.
            String sanctionNumber,
            // SRS-G-2 / I-09 alternate flows (l.2147): the AGM (CPF)'s reason of a rejection or a
            // return to the Maker, from the approval item.
            String decisionReason) {}

    /** The Checker's reason of each rejected or returned item (SRS-G-2). */
    private static final String DECISION_REASONS =
            "SELECT approval_id, CASE WHEN status = 'RETURNED' THEN return_reason ELSE"
                    + " decision_reason END FROM approval_queue WHERE status IN ('REJECTED',"
                    + " 'RETURNED') AND approval_id IN (:ids)";

    /** What a register row shows besides the entry: advance numbers and decision reasons. */
    private record Details(Map<Long, String> sanctionNumbers, Map<Long, String> reasons) {
        static final Details NONE = new Details(Map.of(), Map.of());
    }

    private record Deputation(LocalDate from, LocalDate to) {}

    private final CpfRepositories.BorrowingOrgs orgs;
    private final CpfRepositories.DeputationEntries entries;
    private final CpfRepositories.Sanctions sanctions;
    private final CpfLedgerService ledger;
    private final CpfMembers members;
    private final EmployeeDirectory directory;
    private final DocumentStore documents;
    private final MakerChecker approvals;
    private final Authorization policy;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;

    @SuppressWarnings("java:S107") // one collaborator per concern
    public DeputationService(
            CpfRepositories.BorrowingOrgs orgs,
            CpfRepositories.DeputationEntries entries,
            CpfRepositories.Sanctions sanctions,
            CpfLedgerService ledger,
            CpfMembers members,
            EmployeeDirectory directory,
            DocumentStore documents,
            MakerChecker approvals,
            Authorization policy,
            AuditService audit,
            JdbcTemplate jdbc,
            ObjectMapper json,
            Clock clock) {
        this.orgs = orgs;
        this.entries = entries;
        this.sanctions = sanctions;
        this.ledger = ledger;
        this.members = members;
        this.directory = directory;
        this.documents = documents;
        this.approvals = approvals;
        this.policy = policy;
        this.audit = audit;
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- borrowing organisations

    /** The borrowing organisations, by name. */
    @Transactional(readOnly = true)
    public List<Organisation> organisations(AccessContext context) {
        policy.require(context, SCREEN, "VIEW");
        return orgs.findAllByOrderByOrgName().stream().map(DeputationService::view).toList();
    }

    /**
     * A borrowing organisation added by the Manager (CPF) when a member is deputed to one not yet
     * listed (plan 07 D-20: master data with no amount, audited, no approval).
     */
    @Transactional
    public Organisation addOrganisation(
            AccessContext context, String code, String name, String contact) {
        policy.require(context, SCREEN, "CREATE");
        String c = code == null ? "" : code.strip();
        String n = name == null ? "" : name.strip();
        String k = contact == null || contact.isBlank() ? null : contact.strip();
        if (!ORG_CODE.matcher(c).matches()) {
            throw org("the code is 1 to 30 capital letters, digits, '-' or '_'");
        }
        if (n.isEmpty() || n.length() > 250 || !PRINTABLE.matcher(n).matches()) {
            throw org("the name is required, printable, at most 250 characters");
        }
        if (k != null && (k.length() > 500 || !PRINTABLE_TEXT.matcher(k).matches())) {
            throw org("the contact is printable, at most 500 characters");
        }
        if (orgs.findByOrgCode(c).isPresent()) {
            throw org(c + " already exists");
        }
        BorrowingOrganisation saved = orgs.save(BorrowingOrganisation.of(c, n, k));
        audit.record(
                context.loginId(),
                AuditService.Entry.change(
                                "borrowing_organisation",
                                String.valueOf(saved.getId()),
                                "org_code",
                                null,
                                c,
                                "I-09 borrowing organisation added: " + n)
                        .module(SCREEN));
        return view(saved);
    }

    // ---------------------------------------------------------------- reading

    /**
     * The deputed member (in the user's scope): the deputation from HRMS, the sanctioned advances
     * with what is outstanding, the months without an approved receipt and the entries.
     */
    @Transactional(readOnly = true)
    public Member member(AccessContext context, String eis) {
        policy.require(context, SCREEN, "VIEW");
        EmployeeDirectory.Member m = memberInScope(context, eis, "VIEW");
        Deputation d = deputation(m.id());
        List<CpfDeputationRecovery> rows =
                entries.findByEmployeeIdOrderByPayrollYymmDescIdDesc(m.id());
        Set<String> received = new HashSet<>();
        rows.stream()
                .filter(r -> r.type() == EntryType.CONTRIBUTION_RECEIPT)
                .filter(r -> r.status() == Status.APPROVED)
                .forEach(r -> received.add(r.getPayrollYymm()));
        Map<Long, String> names = organisationNames();
        Map<String, List<DocumentStore.AttachmentView>> files = files(rows);
        Details details = details(rows);
        return new Member(
                m.eis(),
                m.name(),
                d.from(),
                d.to(),
                advances(m.id(), rows),
                DeputationRules.monthsWithoutReceipt(
                        d.from(), d.to(), YearMonth.from(today()), received),
                rows.stream().map(r -> view(r, m.eis(), names, files, details)).toList());
    }

    /** The I-09 register: the entries of members in the user's scope, newest first. */
    @Transactional(readOnly = true)
    public List<EntryView> register(AccessContext context) {
        policy.require(context, SCREEN, "VIEW");
        if (context.dataScope().isEmpty()) {
            return List.of();
        }
        Map<Long, String> names = organisationNames();
        // Deep profile 2026-10-27: the scope is filtered in the database and the members are read
        // once for the list, not every member id of the zone loaded and one lookup per member.
        List<CpfDeputationRecovery> rows = entries.inScope(context.dataScope());
        Map<Long, EmployeeDirectory.Member> people =
                directory.members(
                        rows.stream()
                                .map(CpfDeputationRecovery::getEmployeeId)
                                .distinct()
                                .toList());
        Map<String, List<DocumentStore.AttachmentView>> files = files(rows);
        Details details = details(rows);
        return rows.stream()
                .map(
                        r -> {
                            EmployeeDirectory.Member m = people.get(r.getEmployeeId());
                            return view(r, m == null ? "" : m.eis(), names, files, details);
                        })
                .toList();
    }

    /** The remittance evidence of an entry in the reader's scope (the access is logged). */
    @Transactional
    public DocumentStore.Content evidence(
            AccessContext context, String sourceIp, Long entryId, Long attachmentId) {
        policy.require(context, SCREEN, "VIEW");
        CpfDeputationRecovery r =
                entries.findById(entryId)
                        .filter(e -> inScope(context, e.getEmployeeId()))
                        .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
        return documents.read(
                context.loginId(), sourceIp, ENTITY, String.valueOf(r.getId()), attachmentId);
    }

    // ---------------------------------------------------------------- recording

    /**
     * SRS-6-27 … 6-30: records an entry and submits it to the AGM (CPF). A month already recorded
     * for the organisation and type is refused unless its entry was returned or rejected: that row
     * is corrected and submitted again (the DDL key allows one row per month).
     */
    @Transactional
    public EntryView record(AccessContext context, Entry e, Evidence evidence, OtpProof otp) {
        return recordWithFiles(context, e, evidence == null ? List.of() : List.of(evidence), otp);
    }

    /**
     * {@link #record(AccessContext, Entry, Evidence, OtpProof)} with every evidence file of the
     * entry (I-09 Remittance Evidence: "PDF / JPG / PNG, up to 5 files"; a corrected entry keeps
     * the files it already has, which count towards the five).
     */
    @Transactional
    public EntryView recordWithFiles(
            AccessContext context, Entry e, List<Evidence> evidence, OtpProof otp) {
        policy.require(context, SCREEN, "CREATE");
        if (e == null || e.type() == null) {
            throw new FciException(CommonErrorCode.VALIDATION_FAILED);
        }
        EmployeeDirectory.Member m = memberInScope(context, e.eis(), "CREATE");
        members.require(m.id(), context.loginId());
        Deputation d = deputation(m.id());
        BorrowingOrganisation org =
                orgs.findById(e.borrowingOrgId())
                        .filter(BorrowingOrganisation::isActive)
                        .orElseThrow(() -> org("choose an active borrowing organisation"));
        BigDecimal instalment = zeroIfNull(e.instalment());
        BigDecimal employee = zeroIfNull(e.employee());
        BigDecimal employer = zeroIfNull(e.employer());
        BigDecimal vpf = zeroIfNull(e.vpf());
        DeputationRules.defect(
                        e.type(),
                        instalment,
                        employee,
                        employer,
                        vpf,
                        e.payrollYymm(),
                        d.from(),
                        d.to())
                .ifPresent(
                        why ->
                                throwEntry(
                                        "AMOUNT".equals(why)
                                                ? "the amounts are not valid for the entry type"
                                                : "the month is not a month of the deputation"));
        Long sanctionId = null;
        if (e.type() == EntryType.RECOVERY_INSTALMENT) {
            PfSanction advance = advanceOf(m.id(), e.sanctionId());
            sanctionId = advance.getSanctionId();
            BigDecimal outstanding = outstanding(advance, null);
            if (DeputationRules.after(outstanding, instalment).isEmpty()) {
                throw over(outstanding);
            }
        } else {
            employee = e.type() == EntryType.CONTRIBUTION_RECEIPT ? employee : BigDecimal.ZERO;
            employer = e.type() == EntryType.CONTRIBUTION_RECEIPT ? employer : BigDecimal.ZERO;
            vpf = e.type() == EntryType.CONTRIBUTION_RECEIPT ? vpf : BigDecimal.ZERO;
            instalment = e.type() == EntryType.EXCESS_REFUND ? instalment : BigDecimal.ZERO;
        }
        if (missing(evidence)) {
            // SRS-20-27: the blocked submission is audited.
            audit.submissionBlocked(
                    context.loginId(),
                    ENTITY,
                    String.valueOf(m.id()),
                    SCREEN,
                    "remittance evidence");
            throw new FciException(CpfErrorCode.REMITTANCE_EVIDENCE);
        }
        CpfDeputationRecovery row = existing(m.id(), org.getId(), e, sanctionId);
        if (row == null) {
            row =
                    entries.save(
                            CpfDeputationRecovery.keyed(
                                    m.id(),
                                    org.getId(),
                                    e.type(),
                                    sanctionId,
                                    d.from(),
                                    d.to(),
                                    e.payrollYymm(),
                                    instalment,
                                    employee,
                                    employer,
                                    vpf));
        } else {
            row.corrected(instalment, employee, employer, vpf);
        }
        requireRoom(active(row).size(), evidence.size());
        List<DocumentStore.AttachmentView> attached = attach(context, row, evidence);
        if (row.getEvidenceAttachmentId() == null) {
            row.evidence(attached.getFirst().id());
        }
        BigDecimal impact =
                e.type() == EntryType.CONTRIBUTION_RECEIPT
                        ? employee.add(employer).add(vpf)
                        : instalment;
        Map<Long, String> names = Map.of(org.getId(), org.getOrgName());
        EntryView proposal = view(row, m.eis(), names, Map.of(), details(List.of(row)));
        SubmittedApproval item =
                approvals.submit(
                        context,
                        new Submission(
                                        SCREEN,
                                        "CREATE",
                                        ENTITY,
                                        String.valueOf(row.getId()),
                                        ActionType.CREATE,
                                        context.locationId(),
                                        json.writeValueAsString(proposal),
                                        DeputationRules.month(e.payrollYymm())
                                                .orElseThrow()
                                                .atDay(1),
                                        "I-09 "
                                                + e.type().name()
                                                + " "
                                                + m.eis()
                                                + " "
                                                + e.payrollYymm())
                                .withImpact(impact),
                        otp);
        row.submitted(item.id());
        CpfDeputationRecovery saved = entries.save(row);
        return view(
                saved,
                m.eis(),
                names,
                Map.of(String.valueOf(saved.getId()), active(saved)),
                details(List.of(saved)));
    }

    // ---------------------------------------------------------------- evidence after submission

    /**
     * I-09 Remittance Evidence, SRS-G-31: after submission files are only added, up to five active
     * files per entry, by the Manager (CPF) of the member's office. Each file is audited by the
     * document store.
     */
    @Transactional
    public List<EvidenceFile> addEvidence(
            AccessContext context, Long entryId, List<Evidence> evidence) {
        policy.require(context, SCREEN, "CREATE");
        CpfDeputationRecovery row = ownEntry(context, entryId);
        if (missing(evidence)) {
            throw new FciException(CpfErrorCode.REMITTANCE_EVIDENCE);
        }
        requireRoom(active(row).size(), evidence.size());
        attach(context, row, evidence);
        return evidenceOf(active(row));
    }

    /**
     * I-09 Remittance Evidence: "a wrong file is cancelled, never deleted" (SRS-G-31,
     * BR-AUDIT-009), with the reason. The evidence stays mandatory, so the last active file is
     * never cancelled: add the right file first. An entry that pointed at the cancelled file points
     * at the oldest file still active.
     */
    @Transactional
    public List<EvidenceFile> cancelEvidence(
            AccessContext context, Long entryId, Long attachmentId, String reason) {
        policy.require(context, SCREEN, "CREATE");
        CpfDeputationRecovery row = ownEntry(context, entryId);
        String why = reason == null ? "" : reason.strip();
        if (why.isEmpty() || why.length() > 500 || !PRINTABLE_TEXT.matcher(why).matches()) {
            throw new FciException(CommonErrorCode.VALIDATION_FAILED);
        }
        List<DocumentStore.AttachmentView> before = active(row);
        if (before.stream().noneMatch(a -> a.id().equals(attachmentId))) {
            throw new FciException(CommonErrorCode.NOT_FOUND);
        }
        if (before.size() == 1) {
            throw new FciException(CpfErrorCode.REMITTANCE_EVIDENCE);
        }
        documents.cancel(context.loginId(), ENTITY, String.valueOf(row.getId()), attachmentId, why);
        List<DocumentStore.AttachmentView> after = active(row);
        if (attachmentId.equals(row.getEvidenceAttachmentId())) {
            row.evidence(after.getFirst().id());
            entries.save(row);
        }
        return evidenceOf(after);
    }

    private CpfDeputationRecovery ownEntry(AccessContext context, Long entryId) {
        return entries.findById(entryId)
                .filter(e -> inScope(context, e.getEmployeeId()))
                .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
    }

    private static boolean missing(List<Evidence> evidence) {
        return evidence == null
                || evidence.isEmpty()
                || evidence.stream()
                        .anyMatch(f -> f == null || f.content() == null || f.content().length == 0);
    }

    private static void requireRoom(int active, int adding) {
        if (active + adding > MAX_EVIDENCE_FILES) {
            throw new FciException(
                    CpfErrorCode.EVIDENCE_FILES,
                    new Object[] {MAX_EVIDENCE_FILES},
                    List.of(),
                    null);
        }
    }

    private List<DocumentStore.AttachmentView> attach(
            AccessContext context, CpfDeputationRecovery row, List<Evidence> evidence) {
        return evidence.stream()
                .map(
                        f ->
                                documents.attach(
                                        context.loginId(),
                                        new DocumentStore.NewAttachment(
                                                ENTITY,
                                                String.valueOf(row.getId()),
                                                f.documentTypeCode(),
                                                f.referenceNumber(),
                                                f.orderDate(),
                                                f.issuingAuthority(),
                                                f.fileName(),
                                                f.content(),
                                                true)))
                .toList();
    }

    private List<DocumentStore.AttachmentView> active(CpfDeputationRecovery row) {
        return documents.active(ENTITY, String.valueOf(row.getId()));
    }

    private Map<String, List<DocumentStore.AttachmentView>> files(
            List<CpfDeputationRecovery> rows) {
        return rows.isEmpty()
                ? Map.of()
                : documents.activeByKey(
                        ENTITY, rows.stream().map(r -> String.valueOf(r.getId())).toList());
    }

    private static List<EvidenceFile> evidenceOf(List<DocumentStore.AttachmentView> attachments) {
        return attachments.stream()
                .map(a -> new EvidenceFile(a.id(), a.fileName(), a.uploadedBy(), a.uploadedAt()))
                .toList();
    }

    // ---------------------------------------------------------------- the decision

    /**
     * The AGM (CPF)'s decision: an approved instalment reduces the advance (checked again: an
     * instalment approved meanwhile may have left less outstanding); an approved receipt is
     * credited to the ledger against its month, parts apart; a returned entry goes back to the
     * Maker, a rejected one stays rejected. Audited with the approval.
     */
    @EventListener
    public void on(ApprovalDecided decision) {
        if (!decision.concerns(ENTITY) || !SCREEN.equals(decision.moduleCode())) {
            return;
        }
        for (CpfDeputationRecovery row : entries.findByApprovalId(decision.approvalId())) {
            if (row.status() != Status.PENDING_CHECKER) {
                continue;
            }
            if (!decision.approved()) {
                if (decision.outcome() == ApprovalStatus.RETURNED) {
                    row.returned();
                } else {
                    row.rejected();
                }
            } else {
                apply(decision, row);
            }
            entries.save(row);
            audit.record(
                    decision.checkerLoginId(),
                    AuditService.Entry.change(
                                    ENTITY,
                                    String.valueOf(row.getId()),
                                    "entry_status",
                                    Status.PENDING_CHECKER.name(),
                                    row.status().name(),
                                    decision.reason() == null ? "I-09 decision" : decision.reason())
                            .approval(decision.approvalId())
                            .module(SCREEN));
        }
    }

    private void apply(ApprovalDecided decision, CpfDeputationRecovery row) {
        switch (row.type()) {
            case RECOVERY_INSTALMENT -> {
                PfSanction advance =
                        sanctions
                                .findById(row.getSanctionId())
                                .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
                BigDecimal outstanding = outstanding(advance, row.getId());
                BigDecimal after =
                        DeputationRules.after(outstanding, row.getInstalmentRemitted())
                                .orElseThrow(() -> over(outstanding));
                row.approved(outstanding, after);
            }
            case CONTRIBUTION_RECEIPT -> {
                ledger.post(
                        new CpfLedgerService.Posting(
                                row.getEmployeeId(),
                                DeputationRules.month(row.getPayrollYymm()).orElseThrow().atDay(1),
                                PaymentType.DEPUTATION_RECEIPT,
                                new LedgerBalances(
                                        row.getContributionEmployee(),
                                        row.getContributionEmployer(),
                                        row.getContributionVpf()),
                                null,
                                null,
                                null,
                                null,
                                decision.checkerLoginId(),
                                "I-09 deputation contribution receipt " + row.getPayrollYymm()));
                row.approved(null, null);
            }
            case EXCESS_REFUND -> row.approved(null, null);
        }
    }

    // ---------------------------------------------------------------- helpers

    /** The member's approved deputation from HRMS (DEPUTATION_OUT, ended by DEPUTATION_IN). */
    private Deputation deputation(Long employeeId) {
        List<Deputation> out =
                jdbc.query(
                        "SELECT e.event_date, e.end_date, (SELECT MIN(i.event_date) FROM"
                                + " employee_lifecycle_event i WHERE i.employee_id = e.employee_id"
                                + " AND i.event_type = 'DEPUTATION_IN' AND i.event_date >"
                                + " e.event_date) FROM employee_lifecycle_event e WHERE"
                                + " e.employee_id = ? AND e.event_type = 'DEPUTATION_OUT' ORDER BY"
                                + " e.event_date DESC FETCH FIRST 1 ROWS ONLY",
                        (rs, n) -> {
                            LocalDate from = rs.getDate(1).toLocalDate();
                            java.sql.Date end = rs.getDate(2);
                            java.sql.Date back = rs.getDate(3);
                            // Ended by the order's end date, else the day before the return.
                            LocalDate to =
                                    end != null
                                            ? end.toLocalDate()
                                            : back == null ? null : back.toLocalDate().minusDays(1);
                            return new Deputation(from, to);
                        },
                        employeeId);
        if (out.isEmpty()) {
            throw new FciException(CpfErrorCode.NOT_DEPUTED);
        }
        return out.get(0);
    }

    /** BR-CPF-021: a sanctioned (or paid) CPF advance of the member. */
    private PfSanction advanceOf(Long employeeId, Long sanctionId) {
        return Optional.ofNullable(sanctionId)
                .flatMap(sanctions::findById)
                .filter(s -> Objects.equals(s.getEmployeeId(), employeeId))
                .filter(s -> "ADVANCE".equals(s.getSanctionType()))
                .filter(s -> ADVANCE_STAGES.contains(s.stage().name()))
                .filter(s -> s.getSanctionedAmount() != null)
                .orElseThrow(() -> new FciException(CpfErrorCode.DEPUTATION_ADVANCE));
    }

    /** SRS-6-28: sanctioned − approved instalments (other than {@code except}). */
    private BigDecimal outstanding(PfSanction advance, Long except) {
        return DeputationRules.outstanding(
                advance.getSanctionedAmount(),
                entries
                        .findByEmployeeIdOrderByPayrollYymmDescIdDesc(advance.getEmployeeId())
                        .stream()
                        .filter(r -> r.type() == EntryType.RECOVERY_INSTALMENT)
                        .filter(r -> r.status() == Status.APPROVED)
                        .filter(r -> Objects.equals(r.getSanctionId(), advance.getSanctionId()))
                        .filter(r -> !Objects.equals(r.getId(), except))
                        .map(CpfDeputationRecovery::getInstalmentRemitted)
                        .toList());
    }

    private List<Advance> advances(Long employeeId, List<CpfDeputationRecovery> rows) {
        return sanctions.findByEmployeeIdOrderBySanctionIdDesc(employeeId).stream()
                .filter(s -> "ADVANCE".equals(s.getSanctionType()))
                .filter(s -> ADVANCE_STAGES.contains(s.stage().name()))
                .filter(s -> s.getSanctionedAmount() != null)
                .map(
                        s ->
                                new Advance(
                                        s.getSanctionId(),
                                        s.getSystemSanctionNumber(),
                                        s.getSanctionedAmount(),
                                        DeputationRules.outstanding(
                                                s.getSanctionedAmount(),
                                                rows.stream()
                                                        .filter(
                                                                r ->
                                                                        r.type()
                                                                                == EntryType
                                                                                        .RECOVERY_INSTALMENT)
                                                        .filter(r -> r.status() == Status.APPROVED)
                                                        .filter(
                                                                r ->
                                                                        Objects.equals(
                                                                                r.getSanctionId(),
                                                                                s.getSanctionId()))
                                                        .map(
                                                                CpfDeputationRecovery
                                                                        ::getInstalmentRemitted)
                                                        .toList())))
                .toList();
    }

    /**
     * Whether the member's office is in the reader's scope (one lookup, not the scope's members).
     */
    private boolean inScope(AccessContext context, Long employeeId) {
        return directory
                .locationOf(employeeId)
                .map(location -> context.dataScope().contains(location))
                .orElse(false);
    }

    /** The month's row for the key, when it may be corrected; refused when it is live. */
    private CpfDeputationRecovery existing(Long employeeId, Long orgId, Entry e, Long sanctionId) {
        for (CpfDeputationRecovery r :
                entries.findByEmployeeIdOrderByPayrollYymmDescIdDesc(employeeId)) {
            if (Objects.equals(r.getBorrowingOrgId(), orgId)
                    && r.getPayrollYymm().equals(e.payrollYymm())
                    && r.type() == e.type()
                    && Objects.equals(r.getSanctionId(), sanctionId)) {
                if (r.status() == Status.MAKER_SAVED || r.status() == Status.REJECTED) {
                    return r;
                }
                throw new FciException(CpfErrorCode.DEPUTATION_DUPLICATE);
            }
        }
        return null;
    }

    private EmployeeDirectory.Member memberInScope(
            AccessContext context, String eis, String action) {
        EmployeeDirectory.Member member =
                directory
                        .memberByEis(eis)
                        .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
        // Out of scope answers as not found: no enumeration of members.
        if (!policy.canAt(context, SCREEN, action, member.locationId())) {
            throw new FciException(CommonErrorCode.NOT_FOUND);
        }
        return member;
    }

    private Map<Long, String> organisationNames() {
        Map<Long, String> names = new HashMap<>();
        orgs.findAllByOrderByOrgName().forEach(o -> names.put(o.getId(), o.getOrgName()));
        return names;
    }

    private static Organisation view(BorrowingOrganisation o) {
        return new Organisation(
                o.getId(), o.getOrgCode(), o.getOrgName(), o.getContactDetail(), o.isActive());
    }

    /**
     * The advance numbers of the rows' instalments (one query) and the decision reasons of their
     * rejected or returned items (approval ids in chunks of at most 1,000, Oracle F1).
     */
    private Details details(List<CpfDeputationRecovery> rows) {
        List<Long> sanctionIds =
                rows.stream()
                        .map(CpfDeputationRecovery::getSanctionId)
                        .filter(Objects::nonNull)
                        .distinct()
                        .toList();
        Map<Long, String> numbers = new HashMap<>();
        if (!sanctionIds.isEmpty()) {
            sanctions
                    .findBySanctionIdIn(sanctionIds)
                    .forEach(
                            s ->
                                    numbers.put(
                                            s.getSanctionId(),
                                            s.getSystemSanctionNumber() == null
                                                    ? String.valueOf(s.getSanctionId())
                                                    : s.getSystemSanctionNumber()));
        }
        // A rejected entry, or one returned to the Maker (MAKER_SAVED with its approval item).
        List<Long> decided =
                rows.stream()
                        .filter(
                                r ->
                                        r.getApprovalId() != null
                                                && (r.status() == Status.REJECTED
                                                        || r.status() == Status.MAKER_SAVED))
                        .map(CpfDeputationRecovery::getApprovalId)
                        .distinct()
                        .toList();
        Map<Long, String> reasons = new HashMap<>();
        // A constant statement (no SQL built from text); each list at most InLists.MAX values.
        org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate named =
                new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc);
        for (int i = 0; i < decided.size(); i += InLists.MAX) {
            List<Long> chunk = decided.subList(i, Math.min(decided.size(), i + InLists.MAX));
            named.query(
                    DECISION_REASONS,
                    java.util.Map.of("ids", chunk),
                    rs -> {
                        reasons.put(rs.getLong(1), rs.getString(2));
                    });
        }
        return new Details(numbers, reasons);
    }

    private static EntryView view(
            CpfDeputationRecovery r,
            String eis,
            Map<Long, String> names,
            Map<String, List<DocumentStore.AttachmentView>> files,
            Details details) {
        return new EntryView(
                r.getId(),
                eis,
                names.getOrDefault(r.getBorrowingOrgId(), ""),
                r.type().name(),
                r.getSanctionId(),
                r.getPayrollYymm(),
                r.getInstalmentRemitted(),
                r.getContributionEmployee(),
                r.getContributionEmployer(),
                r.getContributionVpf(),
                r.getAdvanceOutstanding(),
                r.getBalanceAfter(),
                r.status().name(),
                r.getApprovalId(),
                r.getEvidenceAttachmentId(),
                evidenceOf(files.getOrDefault(String.valueOf(r.getId()), List.of())),
                r.getSanctionId() == null ? null : details.sanctionNumbers().get(r.getSanctionId()),
                r.getApprovalId() == null ? null : details.reasons().get(r.getApprovalId()));
    }

    private static BigDecimal zeroIfNull(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static FciException org(String why) {
        return new FciException(CpfErrorCode.BORROWING_ORG, new Object[] {why}, List.of(), null);
    }

    private static FciException over(BigDecimal outstanding) {
        return new FciException(
                CpfErrorCode.OVER_REMITTANCE,
                new Object[] {outstanding.toPlainString()},
                List.of(),
                null);
    }

    private static void throwEntry(String why) {
        throw new FciException(CpfErrorCode.DEPUTATION_ENTRY, new Object[] {why}, List.of(), null);
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(INDIA));
    }
}
