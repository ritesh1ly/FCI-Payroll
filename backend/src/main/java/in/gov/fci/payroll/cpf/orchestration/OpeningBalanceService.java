package in.gov.fci.payroll.cpf.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.audit.api.FieldChanges;
import in.gov.fci.payroll.configuration.api.ReferenceData;
import in.gov.fci.payroll.cpf.business.CorrectionRules;
import in.gov.fci.payroll.cpf.business.CpfErrorCode;
import in.gov.fci.payroll.cpf.business.InterestRateRules.FinYear;
import in.gov.fci.payroll.cpf.business.LedgerBalances;
import in.gov.fci.payroll.cpf.business.SspGateway;
import in.gov.fci.payroll.cpf.business.model.EmployeePfHeader;
import in.gov.fci.payroll.cpf.business.model.OpeningBalanceCorrection;
import in.gov.fci.payroll.cpf.business.model.OpeningBalanceCorrection.Status;
import in.gov.fci.payroll.cpf.persistence.CpfRepositories;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.employee.api.EmployeeDirectory;
import in.gov.fci.payroll.general.api.ExceptionWorklist;
import in.gov.fci.payroll.platform.approval.ActionType;
import in.gov.fci.payroll.platform.approval.ApprovalAdvanced;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.OtpProof;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import in.gov.fci.payroll.platform.security.SecureTokens;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * I-22 PF / CPF Opening Balance Correction (SRS-6-43, SRS-6-44, SRS-6-33, BR-CPF-025 … 027; FRS
 * §6.13.8; DDL {@code opening_balance_correction}): the only way to change an established opening
 * balance. Manager (CPF) at a Zonal Office or Headquarters raises it with a permitted reason,
 * evidence, an interest effective date and remarks; AGM (CPF) checks it with OTP; the Headquarters
 * CPF approver approves every correction with OTP, no value threshold (route I22_CREATE, three
 * levels; the engine keeps the three users different).
 *
 * <p>The correction is kept as one row per changed part beside its old amount (never an overwrite).
 * On final approval the ledger moves by the difference ({@link CpfLedgerService#applyCorrection}),
 * the interest effect is settled (SRS-6-33), the corrected balance is queued for the CPF SSP and
 * the register shows it APPLIED. A correction that lowers a part of a member with a withdrawal
 * sanctioned since the as-at date is blocked for the CPF Division's review instead of being
 * submitted (SRS-6-44).
 */
@Service
@Loggable
public class OpeningBalanceService {

    public static final String SCREEN = "I-22";
    public static final String ENTITY = "opening_balance_correction";
    static final String REASONS = "OB_CORRECTION_REASON";
    static final String INTEREST_PENDING = "CPF_INTEREST_RECOMPUTE";
    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    /** The member and opening balance the Manager (CPF) corrects. */
    public record Current(
            String eis,
            String name,
            String cpfCode,
            String finYear,
            LedgerBalances opening,
            boolean settled,
            boolean pending,
            // SRS I-22 field table (l.2234): the member's UAN from HRMS, read-only; null if none.
            String uan) {}

    /** The evidence, through the standard order-copy control (SRS-G-30). */
    public record Evidence(
            String documentTypeCode,
            String referenceNumber,
            LocalDate orderDate,
            String issuingAuthority,
            String fileName,
            byte[] content) {}

    /** A correction as the Manager (CPF) enters it (a part left null is not corrected). */
    public record Request(
            String eis,
            LocalDate asAtDate,
            BigDecimal employee,
            BigDecimal employer,
            BigDecimal vpf,
            String reasonCode,
            LocalDate interestEffectiveDate,
            String remarks) {}

    /** One part of a correction as the register shows it. */
    public record PartView(
            String part, BigDecimal oldAmount, BigDecimal newAmount, BigDecimal difference) {}

    /** A correction as the register shows it (the Opening Balance Correction Register, A.4.6). */
    public record CorrectionView(
            String ref,
            String eis,
            String finYear,
            LocalDate asAtDate,
            List<PartView> parts,
            String reasonCode,
            String remarks,
            LocalDate interestEffectiveDate,
            BigDecimal interestEffect,
            String status,
            String statusReason,
            String raisedBy,
            Instant raisedAt,
            String checker,
            String hqApprover,
            Instant appliedAt,
            Long approvalId,
            Long evidenceId) {}

    /** The proposal as its approval item carries it (what the AGM and HQ approver decide on). */
    public record Proposal(
            String ref, String eis, String finYear, LocalDate asAtDate, List<PartView> parts) {}

    private final CpfRepositories.Corrections corrections;
    private final CpfRepositories.Headers headers;
    private final CpfLedgerService ledger;
    private final CpfMembers members;
    private final SanctionRegister sanctions;
    private final CpfSspSync ssp;
    private final ExceptionWorklist exceptions;
    private final EmployeeDirectory directory;
    private final ReferenceData references;
    private final DocumentStore documents;
    private final MakerChecker approvals;
    private final Authorization policy;
    private final AuditService audit;
    private final ObjectMapper json;
    private final Clock clock;

    @SuppressWarnings("java:S107") // one collaborator per concern
    public OpeningBalanceService(
            CpfRepositories.Corrections corrections,
            CpfRepositories.Headers headers,
            CpfLedgerService ledger,
            CpfMembers members,
            SanctionRegister sanctions,
            CpfSspSync ssp,
            ExceptionWorklist exceptions,
            EmployeeDirectory directory,
            ReferenceData references,
            DocumentStore documents,
            MakerChecker approvals,
            Authorization policy,
            AuditService audit,
            ObjectMapper json,
            Clock clock) {
        this.corrections = corrections;
        this.headers = headers;
        this.ledger = ledger;
        this.members = members;
        this.sanctions = sanctions;
        this.ssp = ssp;
        this.exceptions = exceptions;
        this.directory = directory;
        this.references = references;
        this.documents = documents;
        this.approvals = approvals;
        this.policy = policy;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- reading

    /**
     * SRS I-22 field table: the member (in the user's scope) and the current opening balance per
     * part of the year of {@code asAt} - the established opening plus the corrections already
     * applied.
     */
    @Transactional(readOnly = true)
    public Current current(AccessContext context, String eis, LocalDate asAt) {
        return current(context, eis, null, null, asAt);
    }

    /**
     * SRS I-22 field table "Member (CPF Code / UAN)" (l.2234): the member is found by exactly one
     * of EIS, CPF code or UAN; none or more than one is a validation error, a value no member (or
     * more than one) holds answers as not found, and so does a member outside the user's scope.
     */
    @Transactional(readOnly = true)
    public Current current(
            AccessContext context, String eis, String cpfCode, String uan, LocalDate asAt) {
        policy.require(context, SCREEN, "VIEW");
        long given =
                java.util.stream.Stream.of(eis, cpfCode, uan)
                        .filter(v -> v != null && !v.isBlank())
                        .count();
        if (given != 1) {
            throw new FciException(CommonErrorCode.VALIDATION_FAILED);
        }
        EmployeeDirectory.Member member =
                eis != null && !eis.isBlank()
                        ? memberInScope(context, eis, "VIEW")
                        : inScope(
                                context,
                                directory
                                        .memberByCpfCodeOrUan(cpfCode, uan)
                                        .orElseThrow(
                                                () -> new FciException(CommonErrorCode.NOT_FOUND)),
                                "VIEW");
        CpfMembers.Member cpf = members.require(member.id(), context.loginId());
        FinYear year = FinYear.containing(requireDate(asAt));
        return new Current(
                member.eis(),
                member.name(),
                cpf.cpfCode(),
                year.code(),
                opening(member.id(), year),
                settled(member.id()),
                open(member.id(), year.code()),
                directory.cpfKyc(member.id()).map(EmployeeDirectory.CpfKyc::uan).orElse(null));
    }

    /** The Opening Balance Correction Register: the corrections of members in the user's scope. */
    @Transactional(readOnly = true)
    public List<CorrectionView> register(AccessContext context) {
        policy.require(context, SCREEN, "VIEW");
        if (context.dataScope().isEmpty()) {
            return List.of();
        }
        // Deep profile 2026-10-27: the scope is filtered in the database, not by loading every
        // member id of the scope.
        Map<String, List<OpeningBalanceCorrection>> byRef = new LinkedHashMap<>();
        for (OpeningBalanceCorrection c : corrections.inScope(context.dataScope())) {
            byRef.computeIfAbsent(c.getCorrectionRef(), k -> new ArrayList<>()).add(c);
        }
        List<CorrectionView> out = new ArrayList<>();
        byRef.values().forEach(rows -> out.add(view(rows)));
        return out;
    }

    /**
     * SRS-6-43: the evidence of a correction, for the AGM (CPF) and the HQ approver who verify it
     * against the differential (the access is logged by the document store). A correction outside
     * the reader's scope answers as not found.
     */
    @Transactional
    public DocumentStore.Content evidence(
            AccessContext context, String sourceIp, String ref, Long attachmentId) {
        policy.require(context, SCREEN, "VIEW");
        List<OpeningBalanceCorrection> rows =
                corrections.findByCorrectionRefOrderByCorrectionId(ref);
        if (rows.isEmpty()
                || !directory
                        .locationOf(rows.get(0).getEmployeeId())
                        .map(location -> context.dataScope().contains(location))
                        .orElse(false)) {
            throw new FciException(CommonErrorCode.NOT_FOUND);
        }
        return documents.read(context.loginId(), sourceIp, ENTITY, ref, attachmentId);
    }

    // ---------------------------------------------------------------- raising

    /**
     * SRS-6-43 / SRS-6-44: validates the correction (a ledger to correct, not finally settled, no
     * other correction open, a permitted reason, at least one part changed, none negative,
     * evidence), writes one row per changed part and either submits it (AGM (CPF), then the HQ CPF
     * approver, both with OTP) or blocks it for the CPF Division when it lowers a part of a member
     * with a withdrawal sanctioned since the as-at date.
     */
    @Transactional
    public CorrectionView raise(
            AccessContext context, Request request, Evidence evidence, OtpProof otp) {
        policy.require(context, SCREEN, "CREATE");
        if (request == null) {
            throw new FciException(CommonErrorCode.VALIDATION_FAILED);
        }
        EmployeeDirectory.Member member = memberInScope(context, request.eis(), "CREATE");
        members.require(member.id(), context.loginId());
        FinYear year = FinYear.containing(requireDate(request.asAtDate()));
        requireDate(request.interestEffectiveDate());
        if (!references.contains(REASONS, request.reasonCode())) {
            throw new FciException(CpfErrorCode.REASON_CODE);
        }
        if (settled(member.id())) {
            throw new FciException(CpfErrorCode.FINALLY_SETTLED);
        }
        if (open(member.id(), year.code())) {
            throw new FciException(CpfErrorCode.CORRECTION_PENDING);
        }
        List<CorrectionRules.Change> changes =
                CorrectionRules.changes(
                        opening(member.id(), year),
                        request.employee(),
                        request.employer(),
                        request.vpf());
        if (evidence == null || evidence.content() == null || evidence.content().length == 0) {
            throw new FciException(CpfErrorCode.EVIDENCE_REQUIRED);
        }
        boolean blocked =
                CorrectionRules.blockedBySanction(
                        sanctions.sanctionedSince(member.id(), request.asAtDate()), changes);
        // OPEN-QUESTION: Q-07-2 - SRS-6-44 routes a blocked correction to the CPF Division "for
        // review" but names no outcome (release to AGM (CPF), or reject); it stays BLOCKED, open,
        // until FCI says.
        Status status = blocked ? Status.BLOCKED_CPF_DIVISION : Status.PENDING_AGM_CPF;
        String blockReason =
                blocked
                        ? "Lowers a part of a member with a withdrawal sanctioned since the as-at"
                                + " date: routed to the CPF Division for review (SRS-6-44)"
                        : null;
        // The rows take a provisional reference until the first one has its number.
        String provisional = "TMP-" + SecureTokens.randomToken(16).substring(0, 12);
        Instant now = clock.instant();
        List<OpeningBalanceCorrection> rows = new ArrayList<>();
        for (CorrectionRules.Change c : changes) {
            rows.add(
                    corrections.save(
                            OpeningBalanceCorrection.raise(
                                    provisional,
                                    member.id(),
                                    year.code(),
                                    request.asAtDate(),
                                    c.part().name(),
                                    c.oldAmount(),
                                    c.newAmount(),
                                    request.reasonCode(),
                                    request.remarks(),
                                    request.interestEffectiveDate(),
                                    null,
                                    status,
                                    blockReason,
                                    context.loginId(),
                                    now)));
        }
        corrections.flush();
        String ref =
                CorrectionRules.reference(
                        LocalDate.ofInstant(now, INDIA).getYear(), rows.get(0).getCorrectionId());
        DocumentStore.AttachmentView attached =
                documents.attach(
                        context.loginId(),
                        new DocumentStore.NewAttachment(
                                ENTITY,
                                ref,
                                evidence.documentTypeCode(),
                                evidence.referenceNumber(),
                                evidence.orderDate(),
                                evidence.issuingAuthority(),
                                evidence.fileName(),
                                evidence.content(),
                                true));
        for (OpeningBalanceCorrection row : rows) {
            row.reference(ref);
            row.evidence(attached.id());
        }
        if (!blocked) {
            List<PartView> parts = rows.stream().map(OpeningBalanceService::part).toList();
            SubmittedApproval item =
                    approvals.submit(
                            context,
                            new Submission(
                                            SCREEN,
                                            "CREATE",
                                            ENTITY,
                                            ref,
                                            ActionType.CREATE,
                                            context.locationId(),
                                            json.writeValueAsString(
                                                    new Proposal(
                                                            ref,
                                                            member.eis(),
                                                            year.code(),
                                                            request.asAtDate(),
                                                            parts)),
                                            request.interestEffectiveDate(),
                                            request.remarks())
                                    .withImpact(
                                            parts.stream()
                                                    .map(p -> p.difference().abs())
                                                    .reduce(BigDecimal.ZERO, BigDecimal::add)),
                            otp);
            rows.forEach(r -> r.submitted(item.id()));
        }
        rows.forEach(corrections::save);
        return view(rows);
    }

    // ---------------------------------------------------------------- the decisions

    /** Tier 2: the AGM (CPF) approved with OTP; the correction goes to the HQ CPF approver. */
    @EventListener
    public void on(ApprovalAdvanced advanced) {
        if (!advanced.concerns(ENTITY) || !SCREEN.equals(advanced.moduleCode())) {
            return;
        }
        for (OpeningBalanceCorrection row :
                corrections.findByApprovalIdOrderByCorrectionId(advanced.approvalId())) {
            row.checked(advanced.approvedBy(), advanced.otpChallengeId(), advanced.decidedAt());
            corrections.save(row);
        }
    }

    /**
     * Tier 3 or a refusal: an approved correction is applied (checked again: the member may have
     * been finally settled meanwhile, and the ledger refuses a negative part); a returned or
     * rejected one applies nothing and keeps its reason.
     */
    @EventListener
    public void on(ApprovalDecided decision) {
        if (!decision.concerns(ENTITY) || !SCREEN.equals(decision.moduleCode())) {
            return;
        }
        List<OpeningBalanceCorrection> rows =
                corrections.findByApprovalIdOrderByCorrectionId(decision.approvalId());
        if (rows.isEmpty()) {
            return;
        }
        if (!decision.approved()) {
            Status closed =
                    decision.outcome() == ApprovalStatus.RETURNED
                            ? Status.RETURNED
                            : Status.REJECTED;
            rows.forEach(
                    r -> {
                        r.closed(closed, decision.reason());
                        corrections.save(r);
                    });
            return;
        }
        apply(decision, rows);
    }

    private void apply(ApprovalDecided decision, List<OpeningBalanceCorrection> rows) {
        OpeningBalanceCorrection first = rows.get(0);
        if (settled(first.getEmployeeId())) {
            throw new FciException(CpfErrorCode.FINALLY_SETTLED);
        }
        Instant now = clock.instant();
        String why = "I-22 " + first.getCorrectionRef() + ": " + first.getReasonCode();
        LedgerBalances difference =
                CorrectionRules.difference(
                        rows.stream()
                                .map(
                                        r ->
                                                new CorrectionRules.Change(
                                                        LedgerBalances.Part.valueOf(
                                                                r.getBalanceComponent()),
                                                        r.getOldAmount(),
                                                        r.getNewAmount()))
                                .toList());
        ledger.applyCorrection(
                first.getEmployeeId(),
                first.getFinYear(),
                first.getAsAtDate(),
                difference,
                decision.checkerLoginId(),
                why);
        BigDecimal effect = interestEffect(decision, first);
        List<AuditService.Entry> entries = new ArrayList<>();
        for (OpeningBalanceCorrection r : rows) {
            r.approved(decision.checkerLoginId(), decision.otpChallengeId(), decision.decidedAt());
            r.applied(effect, now);
            corrections.save(r);
            entries.addAll(
                    FieldChanges.between(
                            ENTITY,
                            first.getCorrectionRef(),
                            Map.of(openingField(r), plain(r.getOldAmount())),
                            Map.of(openingField(r), plain(r.getNewAmount())),
                            why));
        }
        audit.recordAll(
                decision.checkerLoginId(),
                entries.stream()
                        .map(
                                e ->
                                        e.approval(decision.approvalId())
                                                .module(SCREEN)
                                                .effective(first.getInterestEffectiveDate()))
                        .toList());
        ssp.queue(
                new SspGateway.Change(
                        first.getEmployeeId(),
                        "OB_CORRECTION",
                        rows.stream().map(OpeningBalanceService::openingField).toList()));
    }

    /**
     * SRS-6-33: interest credited since the effective date must be recomputed; the method is not in
     * any source (Q-072), so a correction of a member with interest already credited is raised on
     * X-05 and its effect left open (null). With none credited the effect is exactly zero.
     */
    private BigDecimal interestEffect(ApprovalDecided decision, OpeningBalanceCorrection first) {
        if (ledger.interestCreditedFrom(first.getEmployeeId(), first.getInterestEffectiveDate())
                        .signum()
                == 0) {
            return BigDecimal.ZERO;
        }
        exceptions.raise(
                decision.checkerLoginId(),
                new ExceptionWorklist.NewEntry(
                        null,
                        first.getEmployeeId(),
                        CpfMembers.MODULE,
                        INTEREST_PENDING,
                        "WARNING",
                        null,
                        null,
                        ENTITY,
                        "interest_effect_amount",
                        "CALC",
                        "C-12",
                        "Interest credited since "
                                + first.getInterestEffectiveDate()
                                + " must be recomputed for correction "
                                + first.getCorrectionRef()
                                + " (SRS-6-33); the method is open (Q-072)."));
        return null;
    }

    // ---------------------------------------------------------------- helpers

    private EmployeeDirectory.Member memberInScope(
            AccessContext context, String eis, String action) {
        EmployeeDirectory.Member member =
                directory
                        .memberByEis(eis)
                        .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
        return inScope(context, member, action);
    }

    private EmployeeDirectory.Member inScope(
            AccessContext context, EmployeeDirectory.Member member, String action) {
        // Out of scope answers as not found: no enumeration of members.
        if (!policy.canAt(context, SCREEN, action, member.locationId())) {
            throw new FciException(CommonErrorCode.NOT_FOUND);
        }
        return member;
    }

    private static LocalDate requireDate(LocalDate day) {
        if (day == null) {
            throw new FciException(CommonErrorCode.VALIDATION_FAILED);
        }
        return day;
    }

    /** The established opening of the year's ledger plus the corrections already applied. */
    private LedgerBalances opening(Long employeeId, FinYear year) {
        List<Long> ids = headers.idsOf(employeeId, year.code(), CpfLedgerService.FUND);
        if (ids.isEmpty()) {
            throw new FciException(CpfErrorCode.NO_LEDGER);
        }
        EmployeePfHeader header = headers.findById(ids.get(0)).orElseThrow();
        LedgerBalances opening = header.opening();
        for (OpeningBalanceCorrection c :
                corrections.findByEmployeeIdAndFinYearOrderByCorrectionId(
                        employeeId, year.code())) {
            if (Status.APPLIED.name().equals(c.getCorrectionStatus())) {
                BigDecimal d = c.getNewAmount().subtract(c.getOldAmount());
                opening =
                        new LedgerBalances(
                                opening.employee().add(part(c, "EMPLOYEE", d)),
                                opening.employer().add(part(c, "EMPLOYER", d)),
                                opening.vpf().add(part(c, "VPF", d)));
            }
        }
        return opening;
    }

    private static BigDecimal part(OpeningBalanceCorrection c, String name, BigDecimal d) {
        return name.equals(c.getBalanceComponent()) ? d : BigDecimal.ZERO;
    }

    /** SRS-6-44: finally settled - a sanctioned final payment, or a ledger already marked so. */
    private boolean settled(Long employeeId) {
        return sanctions.finallySettled(employeeId)
                || headers
                        .findByEmployeeIdAndFundTypeOrderByFinYearDescPfHeaderIdDesc(
                                employeeId, CpfLedgerService.FUND)
                        .stream()
                        .anyMatch(
                                h ->
                                        EmployeePfHeader.MemberStatus.FINALLY_SETTLED
                                                .name()
                                                .equals(h.getMemberStatus()));
    }

    /** A correction of the member's year awaiting a decision or the CPF Division's review. */
    private boolean open(Long employeeId, String finYear) {
        return corrections
                .findByEmployeeIdAndFinYearOrderByCorrectionId(employeeId, finYear)
                .stream()
                .map(OpeningBalanceCorrection::getCorrectionStatus)
                .anyMatch(
                        s ->
                                Status.PENDING_AGM_CPF.name().equals(s)
                                        || Status.PENDING_HQ_CPF.name().equals(s)
                                        || Status.BLOCKED_CPF_DIVISION.name().equals(s));
    }

    static String openingField(OpeningBalanceCorrection r) {
        return "opening_balance_" + r.getBalanceComponent().toLowerCase(java.util.Locale.ROOT);
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    private static PartView part(OpeningBalanceCorrection r) {
        return new PartView(
                r.getBalanceComponent(),
                r.getOldAmount(),
                r.getNewAmount(),
                r.getNewAmount().subtract(r.getOldAmount()));
    }

    private CorrectionView view(List<OpeningBalanceCorrection> rows) {
        OpeningBalanceCorrection f = rows.get(0);
        return new CorrectionView(
                f.getCorrectionRef(),
                directory.eisOf(f.getEmployeeId()).orElse(null),
                f.getFinYear(),
                f.getAsAtDate(),
                rows.stream().map(OpeningBalanceService::part).toList(),
                f.getReasonCode(),
                f.getReason(),
                f.getInterestEffectiveDate(),
                f.getInterestEffectAmount(),
                f.getCorrectionStatus(),
                f.getStatusReason(),
                f.getRaisedBy(),
                f.getRaisedAt(),
                f.getCheckerUser(),
                f.getHqApproverUser(),
                f.getAppliedAt(),
                f.getApprovalId(),
                f.getEvidenceAttachmentId());
    }
}
