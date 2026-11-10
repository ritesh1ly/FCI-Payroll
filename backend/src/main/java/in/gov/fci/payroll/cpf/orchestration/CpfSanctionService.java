package in.gov.fci.payroll.cpf.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.ReferenceData;
import in.gov.fci.payroll.configuration.api.RuleEngine;
import in.gov.fci.payroll.cpf.business.CpfErrorCode;
import in.gov.fci.payroll.cpf.business.LedgerBalances;
import in.gov.fci.payroll.cpf.business.SanctionRules;
import in.gov.fci.payroll.cpf.business.SanctionRules.ChecklistItem;
import in.gov.fci.payroll.cpf.business.SanctionRules.Stage;
import in.gov.fci.payroll.cpf.business.SspGateway;
import in.gov.fci.payroll.cpf.business.WithdrawalLimit;
import in.gov.fci.payroll.cpf.business.model.CpfClaim;
import in.gov.fci.payroll.cpf.business.model.CpfClaimHistory;
import in.gov.fci.payroll.cpf.business.model.CpfSanctionTier;
import in.gov.fci.payroll.cpf.business.model.CpfWithdrawalPurpose;
import in.gov.fci.payroll.cpf.business.model.EmployeePfHeader;
import in.gov.fci.payroll.cpf.business.model.PfSanction;
import in.gov.fci.payroll.cpf.persistence.CpfRepositories;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.employee.api.EmployeeDirectory;
import in.gov.fci.payroll.platform.approval.ActionType;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.ApprovalWithdrawals;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.OtpProof;
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
import in.gov.fci.payroll.platform.selfservice.SelfServiceEvents;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The CPF advance, part-final and final withdrawal through the three-tier chain (SRS-6-4, UC-05;
 * SRS-6-5, SRS-6-14, SRS-6-26, SRS-6-37, SRS-6-38, SRS-6-55, SRS-12-9; BR-CPF-009 to 011, 029 to
 * 032, 039; screens E-01 and I-04; DDL pf_sanction, cpf_sanction_tier, cpf_claim; plan 07 D-14).
 *
 * <ul>
 *   <li>The member raises the request on E-01: a purpose in force, the property details where it
 *       needs them, the amount and every mandatory checklist document (submission blocked naming
 *       the missing ones).
 *   <li>Tier 1, the Unit CPF Administrator (I-04 EDIT), forwards it once the UAN and KYC are on
 *       record and the documents complete, or returns it to the member with the reason.
 *   <li>Tier 2, the Zonal / HQ CPF Division (I-04 SUBMIT), checks the conditions in force on the
 *       request date, works out the ceiling and caps the amount (SRS-6-26), and recommends through
 *       route I04_SUBMIT; or returns it to the unit (a new pass); or rejects it with the reason
 *       (FRS V0.5 §13.4 Step 3), which the member sees and the CPF SSP is sent.
 *   <li>Tier 3, the CPF Sanctioning Authority, decides in the Approvals Inbox with OTP; the
 *       decision writes the tier-3 row, the sanction number and SANCTIONED, or PAYMENT_BLOCKED when
 *       the balance cannot carry it. A rejection ends the request; a return sends it to the unit.
 * </ul>
 *
 * Inputs not available are "not configured" and stop the request at tier 2, never guessed (D-15).
 * Payment release belongs to module 23 (D-13).
 */
@Service
@Loggable
public class CpfSanctionService {

    public static final String SCREEN = "I-04";
    public static final String ESEVA = "E-01";
    public static final String ENTITY = "pf_sanction";
    static final String DOCUMENTS = "pf_sanction_document";
    static final String NUMBER_FORMAT = "CPF_SANCTION_NUMBER_FORMAT";
    static final String MGW_RULE = "CPF.MGW";
    static final String ADVANCE_CAP_RULE = WithdrawalLimit.ADVANCE_CAP;
    static final String MIN_YEARS_RULE = "CPF.CONDITION.PARTFINAL.MIN_YEARS";
    static final String MAX_COUNT_RULE = "CPF.CONDITION.PARTFINAL.MAX_COUNT";
    static final String WINDOW_RULE = "CPF.CONDITION.FINAL90.WINDOW_YEARS";
    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    /**
     * BR-CPF-010, SRS-6-55: a withdrawal that counts against the permitted number - sanctioned, or
     * still able to become one (in the chain, or returned and resubmittable). Counting only the
     * sanctioned ones would let two requests in the chain at once both reach tier 3 and exceed the
     * limit.
     */
    private static final Set<String> LIVE_STAGES =
            names(EnumSet.complementOf(EnumSet.of(Stage.REJECTED, Stage.CANCELLED)));

    /** An advance is active in the chain or sanctioned and not yet paid (Q-07-4). */
    private static final Set<String> ACTIVE_STAGES =
            names(
                    EnumSet.complementOf(
                            EnumSet.of(
                                    Stage.RETURNED, Stage.REJECTED, Stage.PAID, Stage.CANCELLED)));

    /** One document the member attaches, with its particulars (SRS-G-30). */
    public record Document(
            String documentTypeCode,
            String referenceNumber,
            LocalDate documentDate,
            String issuingAuthority,
            String fileName,
            byte[] content) {}

    /** The member's request (E-01 field table). */
    public record Request(
            String purposeCode,
            BigDecimal amount,
            String propertyAddress,
            String propertyDescription) {}

    /**
     * A tier decision: forward / recommend, return with the reason, or - at tier 2 only - reject
     * with the reason (FRS V0.5 §13.4 Step 3 "approves or rejects"; Exception Handling "rejected at
     * Zonal/HQ CPF Division ... with reason captured for the member's visibility in SSP").
     */
    public enum Decision {
        FORWARD,
        RETURN,
        REJECT
    }

    /** DDL {@code cpf_ssp_sync_log.sync_trigger}: a member request's status for the CPF SSP. */
    static final String SSP_REQUEST = "REQUEST";

    public record TierView(
            int pass,
            int tier,
            String tierCode,
            String action,
            String actedBy,
            Instant actedAt,
            String remark,
            BigDecimal ceiling,
            BigDecimal capped) {}

    public record SanctionView(
            Long id,
            String eis,
            String memberName,
            String type,
            String purposeCode,
            String purposeName,
            BigDecimal requested,
            BigDecimal ceiling,
            BigDecimal sanctioned,
            boolean capped,
            String stage,
            String sanctionNumber,
            LocalDate sanctionDate,
            String propertyAddress,
            String propertyDescription,
            Long approvalId,
            String cancelReason,
            List<TierView> tiers,
            List<DocumentStore.AttachmentView> documents,
            // SRS I-04 field table: the UAN the unit verifies, and the Aadhaar as its last four
            // digits (SRS-6-51); both from HRMS, null when not on record.
            String uan,
            String aadhaarMasked,
            // SRS-6-14 (US-GAP-28): the purpose needs the No Caste Dispute Certificate at tier 1.
            boolean noCasteDisputeRequired,
            // SRS I-04 field table "Withdrawal Count" (l.2279; SRS-6-4 tier 2, BR-CPF-010): the
            // member's other withdrawals that count, by type and date - on the detail only (null
            // on a register row).
            List<EarlierWithdrawal> earlierWithdrawals,
            // BR-CPF-029: the name of the purpose's limit rule; null when the purpose has none.
            String limitRule) {}

    /** The I-04 CPF tile: the requests in the chain by type (SRS-6-4). */
    public record PendingSummary(long pending, long advance, long partFinal, long finalPayment) {}

    /**
     * One earlier withdrawal of the member on the withdrawal register (SRS l.2279): sanctioned, or
     * still in the chain (no date yet), never rejected or cancelled (LIVE_STAGES, BR-CPF-010).
     */
    public record EarlierWithdrawal(
            Long id,
            String type,
            String purposeCode,
            String stage,
            String sanctionNumber,
            LocalDate sanctionDate) {}

    /** The tier-3 approval payload: what the Competent Authority sanctions. */
    record Proposal(
            Long sanctionId,
            String eis,
            String type,
            String purposeCode,
            BigDecimal requested,
            BigDecimal ceiling,
            BigDecimal sanctioned,
            boolean capped) {}

    private final Authorization policy;
    private final CpfRepositories.Sanctions sanctions;
    private final CpfRepositories.SanctionTiers tiers;
    private final CpfRepositories.Claims claims;
    private final CpfRepositories.ClaimHistory claimHistory;
    private final CpfRepositories.Purposes purposes;
    private final CpfRepositories.PurposeDocuments checklists;
    private final CpfRepositories.Headers headers;
    private final EmployeeDirectory directory;
    private final CpfMembers members;
    private final CpfZones zones;
    private final RuleEngine engine;
    private final ReferenceData references;
    private final MakerChecker approvals;
    private final ApprovalWithdrawals withdrawals;
    private final DocumentStore documents;
    private final AuditService audit;
    private final tools.jackson.databind.ObjectMapper json;
    private final ApplicationEventPublisher events;
    private final CpfSspSync ssp;
    private final Clock clock;

    @SuppressWarnings("java:S107") // the collaborators of the three-tier chain
    public CpfSanctionService(
            Authorization policy,
            CpfRepositories.Sanctions sanctions,
            CpfRepositories.SanctionTiers tiers,
            CpfRepositories.Claims claims,
            CpfRepositories.ClaimHistory claimHistory,
            CpfRepositories.Purposes purposes,
            CpfRepositories.PurposeDocuments checklists,
            CpfRepositories.Headers headers,
            EmployeeDirectory directory,
            CpfMembers members,
            CpfZones zones,
            RuleEngine engine,
            ReferenceData references,
            MakerChecker approvals,
            DocumentStore documents,
            AuditService audit,
            tools.jackson.databind.ObjectMapper json,
            ApplicationEventPublisher events,
            CpfSspSync ssp,
            Clock clock,
            ApprovalWithdrawals withdrawals) {
        this.withdrawals = withdrawals;
        this.policy = policy;
        this.sanctions = sanctions;
        this.tiers = tiers;
        this.claims = claims;
        this.claimHistory = claimHistory;
        this.purposes = purposes;
        this.checklists = checklists;
        this.headers = headers;
        this.directory = directory;
        this.members = members;
        this.zones = zones;
        this.engine = engine;
        this.references = references;
        this.approvals = approvals;
        this.documents = documents;
        this.audit = audit;
        this.json = json;
        this.events = events;
        this.ssp = ssp;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- the member (E-01)

    /**
     * SRS-6-4 step 1, SRS-6-14, BR-CPF-039: the member raises a request. Checked here: a purpose in
     * force today, the amount, the property details the purpose needs, every mandatory document and
     * the member conditions known at once (one active advance, the part-final count, the years of
     * membership, the retirement window). The ceiling is tier 2's.
     */
    @Transactional
    public SanctionView request(AccessContext context, Request request, List<Document> attached) {
        Long employeeId = context.employeeId();
        if (employeeId == null || request == null) {
            throw new FciException(
                    ApiErrorCode.PERMISSION_DENIED,
                    new Object[] {ESEVA, "CREATE"},
                    List.of(),
                    null);
        }
        EmployeeDirectory.Member member =
                directory
                        .member(employeeId)
                        .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
        policy.requireEmployeeRecord(context, ESEVA, "CREATE", employeeId, member.locationId());
        members.require(employeeId, context.loginId());
        LocalDate today = today();
        CpfWithdrawalPurpose purpose = purposeInForce(request.purposeCode(), today);
        SanctionRules.requireAmount(request.amount());
        SanctionRules.requireProperty(
                purpose.isRequiresPropertyDetails(),
                request.propertyAddress(),
                request.propertyDescription());
        List<Document> files = attached == null ? List.of() : attached;
        requireDocuments(purpose, files);
        String type = purpose.getWithdrawalCategory();
        requireMemberConditions(employeeId, type, purpose, today);
        Instant now = clock.instant();
        Long zone = zones.of(member.locationId()).id();
        PfSanction sanction =
                sanctions.save(
                        PfSanction.requested(
                                employeeId, type, purpose.getPurposeId(), request.amount(), zone));
        sanctions.flush();
        CpfClaim claim =
                claims.save(
                        CpfClaim.eseva(
                                employeeId,
                                type,
                                purpose.getPurposeId(),
                                sanction.getSanctionId(),
                                request.amount(),
                                trim(request.propertyAddress()),
                                trim(request.propertyDescription()),
                                now));
        claimHistory.save(
                CpfClaimHistory.of(
                        claim.getClaimId(),
                        null,
                        CpfClaim.Status.SUBMITTED.name(),
                        Stage.UNIT_VERIFICATION.name(),
                        context.loginId(),
                        now,
                        "Raised in eSeva"));
        for (Document d : files) {
            documents.attach(
                    context.loginId(),
                    new DocumentStore.NewAttachment(
                            DOCUMENTS,
                            String.valueOf(sanction.getSanctionId()),
                            d.documentTypeCode(),
                            d.referenceNumber(),
                            d.documentDate(),
                            d.issuingAuthority(),
                            d.fileName(),
                            d.content(),
                            true));
        }
        audit.record(
                context.loginId(),
                AuditService.Entry.change(
                                ENTITY,
                                String.valueOf(sanction.getSanctionId()),
                                "workflow_stage",
                                null,
                                Stage.UNIT_VERIFICATION.name(),
                                "CPF " + type + " request raised in eSeva")
                        .module(ESEVA));
        // SRS-17-17 / SRS-17-18 (plan 14 phase 14a): the request in the eSeva Submission Log.
        events.publishEvent(
                new SelfServiceEvents.Submitted(
                        employeeId,
                        logType(type),
                        ENTITY,
                        sanction.getSanctionId(),
                        null,
                        null,
                        null,
                        "CPF " + type + " " + purpose.getPurposeCode() + " of " + request.amount(),
                        null,
                        context.loginId()));
        return view(sanction, member);
    }

    /** One purpose the member may choose, with its checklist (E-01 form; SRS-6-14, BR-CPF-039). */
    public record PurposeOption(
            String code,
            String name,
            String category,
            boolean propertyDetails,
            List<ChecklistItem> documents) {}

    /**
     * E-01: the purposes in force today, each with its checklist (a bounded configuration list).
     */
    @Transactional(readOnly = true)
    public List<PurposeOption> purposes(AccessContext context) {
        if (context.employeeId() == null) {
            throw new FciException(
                    ApiErrorCode.PERMISSION_DENIED, new Object[] {ESEVA, "SELF"}, List.of(), null);
        }
        LocalDate today = today();
        return purposes.findAllByOrderByPurposeCodeAscEffectiveFromDesc().stream()
                .filter(p -> p.inForceOn(today))
                .map(
                        p ->
                                new PurposeOption(
                                        p.getPurposeCode(),
                                        p.getPurposeName(),
                                        p.getWithdrawalCategory(),
                                        p.isRequiresPropertyDetails(),
                                        checklist(p)))
                .toList();
    }

    /** E-01: the member's own requests, newest first (bounded: a member's lifetime requests). */
    @Transactional(readOnly = true)
    public List<SanctionView> mine(AccessContext context) {
        Long employeeId = context.employeeId();
        if (employeeId == null) {
            throw new FciException(
                    ApiErrorCode.PERMISSION_DENIED, new Object[] {ESEVA, "SELF"}, List.of(), null);
        }
        EmployeeDirectory.Member member =
                directory
                        .member(employeeId)
                        .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
        policy.requireEmployeeRecord(context, ESEVA, "SELF", employeeId, member.locationId());
        return sanctions.findByEmployeeIdOrderBySanctionIdDesc(employeeId).stream()
                .map(s -> view(s, member))
                .toList();
    }

    /**
     * UC-05: the member withdraws the own request before the sanction (a status, never a delete).
     */
    @Transactional
    public SanctionView withdraw(AccessContext context, Long id, String reason) {
        PfSanction s = ownLocked(context, id);
        if (!s.stage().withdrawable()) {
            throw stage(s);
        }
        String why = requireReason(reason);
        Instant now = clock.instant();
        String before = s.stage().name();
        s.cancelled(why, now);
        claimStatus(s, CpfClaim.Status.WITHDRAWN, context.loginId(), now, why);
        auditStage(context, s, before, ESEVA, "Withdrawn by the member: " + why);
        // Wave D review 9: a request withdrawn at tier 3 leaves the Competent Authority's inbox.
        withdrawals.withdraw(
                ENTITY, String.valueOf(s.getSanctionId()), context.loginId(), "Withdrawn: " + why);
        return view(s, member(s));
    }

    /**
     * UC-05 alternate flow: the member resubmits a request returned to them, with the corrected
     * amount and property details; it starts a new pass at tier 1.
     */
    @Transactional
    public SanctionView resubmit(AccessContext context, Long id, Request request) {
        PfSanction s = ownLocked(context, id);
        if (s.stage() != Stage.RETURNED || request == null) {
            throw stage(s);
        }
        CpfWithdrawalPurpose purpose = purposes.findById(s.getPurposeId()).orElseThrow();
        SanctionRules.requireAmount(request.amount());
        SanctionRules.requireProperty(
                purpose.isRequiresPropertyDetails(),
                request.propertyAddress(),
                request.propertyDescription());
        Instant now = clock.instant();
        s.resubmitted(request.amount());
        CpfClaim claim = claims.findBySanctionId(s.getSanctionId()).orElseThrow();
        claim.resubmitted(
                request.amount(),
                trim(request.propertyAddress()),
                trim(request.propertyDescription()));
        claimStatus(s, CpfClaim.Status.SUBMITTED, context.loginId(), now, "Resubmitted");
        auditStage(context, s, Stage.RETURNED.name(), ESEVA, "Resubmitted by the member");
        return view(s, member(s));
    }

    // ---------------------------------------------------------------- the register (I-04)

    /** SRS-6-55, SRS-NF-3: the withdrawal register of the user's scope, one page, newest first. */
    @Transactional(readOnly = true)
    public Paged<SanctionView> register(AccessContext context, String stage, PageSpec spec) {
        policy.require(context, SCREEN, "VIEW");
        if (stage != null && !stage.isBlank()) {
            Stage.valueOf(stage);
        }
        if (context.dataScope().isEmpty()) {
            return new Paged<>(List.of(), spec.page(), spec.size(), 0);
        }
        Paged<PfSanction> page =
                Paged.of(
                        sanctions.inScope(
                                context.dataScope(),
                                stage == null || stage.isBlank() ? null : stage,
                                spec.pageable()));
        return new Paged<>(views(page.items()), page.page(), page.size(), page.total());
    }

    /** The requests of the user's scope waiting at a tier, by type (SRS-6-4; API-07-12). */
    @Transactional(readOnly = true)
    public PendingSummary pending(AccessContext context) {
        policy.require(context, SCREEN, "VIEW");
        if (context.dataScope().isEmpty()) {
            return new PendingSummary(0, 0, 0, 0);
        }
        Map<String, Long> byType = new HashMap<>();
        for (Object[] row : sanctions.pendingByType(context.dataScope())) {
            byType.put((String) row[0], ((Number) row[1]).longValue());
        }
        long advance = byType.getOrDefault("ADVANCE", 0L);
        long partFinal = byType.getOrDefault("PART_FINAL", 0L);
        long finalPayment = byType.getOrDefault("FINAL", 0L);
        return new PendingSummary(
                advance + partFinal + finalPayment, advance, partFinal, finalPayment);
    }

    /** One request with its tiers and documents, for I-04. */
    @Transactional(readOnly = true)
    public SanctionView get(AccessContext context, Long id) {
        policy.require(context, SCREEN, "VIEW");
        PfSanction s =
                sanctions
                        .findById(id)
                        .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
        EmployeeDirectory.Member member = member(s);
        requireInScope(context, member);
        SanctionView v = view(s, member);
        return withEarlier(v, earlier(s));
    }

    /** SRS l.2279: the member's other withdrawals that count, newest first (BR-CPF-010). */
    private List<EarlierWithdrawal> earlier(PfSanction self) {
        Map<Long, String> codes = new HashMap<>();
        List<EarlierWithdrawal> out = new ArrayList<>();
        for (PfSanction s : sanctions.findByEmployeeIdOrderBySanctionIdDesc(self.getEmployeeId())) {
            if (s.getSanctionId().equals(self.getSanctionId())
                    || !LIVE_STAGES.contains(s.stage().name())) {
                continue;
            }
            String code =
                    s.getPurposeId() == null
                            ? null
                            : codes.computeIfAbsent(
                                    s.getPurposeId(),
                                    id ->
                                            purposes.findById(id)
                                                    .map(CpfWithdrawalPurpose::getPurposeCode)
                                                    .orElse(null));
            out.add(
                    new EarlierWithdrawal(
                            s.getSanctionId(),
                            s.getSanctionType(),
                            code,
                            s.stage().name(),
                            s.getSystemSanctionNumber(),
                            s.getSanctionDate()));
        }
        return out;
    }

    private static SanctionView withEarlier(SanctionView v, List<EarlierWithdrawal> earlier) {
        return new SanctionView(
                v.id(),
                v.eis(),
                v.memberName(),
                v.type(),
                v.purposeCode(),
                v.purposeName(),
                v.requested(),
                v.ceiling(),
                v.sanctioned(),
                v.capped(),
                v.stage(),
                v.sanctionNumber(),
                v.sanctionDate(),
                v.propertyAddress(),
                v.propertyDescription(),
                v.approvalId(),
                v.cancelReason(),
                v.tiers(),
                v.documents(),
                v.uan(),
                v.aadhaarMasked(),
                v.noCasteDisputeRequired(),
                earlier,
                v.limitRule());
    }

    /** A document of a request, to the member or to the tiers in scope (the read is logged). */
    @Transactional
    public DocumentStore.Content document(
            AccessContext context, String sourceIp, Long id, Long attachmentId) {
        PfSanction s =
                sanctions
                        .findById(id)
                        .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
        if (!s.getEmployeeId().equals(context.employeeId())) {
            // Another member's request does not exist for a caller without I-04 (security review
            // 2026-11-04, W-5: a 403 here told an eSeva member that the id was real).
            if (!policy.can(context, SCREEN, "VIEW")) {
                policy.recordForeignAttempt(context, ESEVA, "SELF", "pf_sanction", id);
                throw new FciException(CommonErrorCode.NOT_FOUND);
            }
            requireInScope(context, member(s));
        }
        return documents.read(
                context.loginId(),
                sourceIp,
                DOCUMENTS,
                String.valueOf(s.getSanctionId()),
                attachmentId);
    }

    // ---------------------------------------------------------------- tier 1 (unit)

    /** SRS-6-14: the document type of the No Caste Dispute Certificate (V067_1). */
    public static final String NO_CASTE_DISPUTE = "NO_CASTE_DISPUTE";

    /**
     * SRS-6-14 (US-GAP-28): at tier 1 the Unit CPF Administrator attaches the No Caste Dispute
     * Certificate to the request where its purpose requires it; no other document is attached here
     * (the member attaches the checklist documents on E-01).
     */
    @Transactional
    public SanctionView attachAtUnit(AccessContext context, Long id, Document d) {
        policy.require(context, SCREEN, "EDIT");
        PfSanction s = locked(id);
        EmployeeDirectory.Member member = member(s);
        policy.requireRecordAt(context, SCREEN, "EDIT", member.locationId());
        if (s.stage() != Stage.UNIT_VERIFICATION) {
            throw stage(s);
        }
        CpfWithdrawalPurpose purpose = purposes.findById(s.getPurposeId()).orElseThrow();
        if (!NO_CASTE_DISPUTE.equals(d.documentTypeCode()) || !purpose.isRequiresNoCasteDispute()) {
            throw new FciException(
                    CpfErrorCode.DOCUMENTS_MISSING,
                    new Object[] {"only the " + NO_CASTE_DISPUTE + " certificate, where required"},
                    List.of(),
                    null);
        }
        documents.attach(
                context.loginId(),
                new DocumentStore.NewAttachment(
                        DOCUMENTS,
                        String.valueOf(s.getSanctionId()),
                        d.documentTypeCode(),
                        d.referenceNumber(),
                        d.documentDate(),
                        d.issuingAuthority(),
                        d.fileName(),
                        d.content(),
                        true));
        auditStage(
                context,
                s,
                Stage.UNIT_VERIFICATION.name(),
                SCREEN,
                "No Caste Dispute Certificate attached at the unit");
        return view(s, member);
    }

    /**
     * SRS-6-4 tier 1: the Unit CPF Administrator forwards the request once the UAN and KYC are on
     * record (from HRMS) and the mandatory documents are attached, or returns it to the member.
     */
    @Transactional
    public SanctionView verify(AccessContext context, Long id, Decision decision, String remark) {
        policy.require(context, SCREEN, "EDIT");
        PfSanction s = locked(id);
        EmployeeDirectory.Member member = member(s);
        policy.requireRecordAt(context, SCREEN, "EDIT", member.locationId());
        if (s.stage() != Stage.UNIT_VERIFICATION) {
            throw stage(s);
        }
        if (decision == Decision.REJECT) {
            // FRS V0.5 §13.4 Step 2: the unit returns a request it cannot forward; rejection is
            // tier 2's (Step 3).
            throw new FciException(CpfErrorCode.TIER1_REJECT);
        }
        int pass = currentPass(s);
        requireNewActor(s, pass, context.loginId());
        Instant now = clock.instant();
        if (decision == Decision.RETURN) {
            String why = requireReason(remark);
            tiers.save(
                    CpfSanctionTier.acted(
                            s.getSanctionId(),
                            pass,
                            1,
                            "RETURNED",
                            context.loginId(),
                            context.locationId(),
                            now,
                            why));
            s.returnedToMember();
            claimStatus(s, CpfClaim.Status.RETURNED, context.loginId(), now, why);
            auditStage(
                    context,
                    s,
                    Stage.UNIT_VERIFICATION.name(),
                    SCREEN,
                    "Returned to the member: " + why);
            // SRS use case alternate flow (l.2260, US-GAP-165): the reason is visible in the SSP.
            toSsp(s);
            return view(s, member);
        }
        EmployeeDirectory.CpfEligibility kyc =
                directory.cpfEligibility(s.getEmployeeId()).orElseThrow();
        if (!kyc.uanOnRecord() || !kyc.kycOnRecord()) {
            throw new FciException(
                    CpfErrorCode.ELIGIBILITY_DATA,
                    new Object[] {!kyc.uanOnRecord() ? "uan_number" : "aadhaar_number"},
                    List.of(),
                    null);
        }
        CpfWithdrawalPurpose purpose = purposes.findById(s.getPurposeId()).orElseThrow();
        List<String> attached =
                documents.active(DOCUMENTS, String.valueOf(s.getSanctionId())).stream()
                        .map(DocumentStore.AttachmentView::documentTypeCode)
                        .toList();
        List<String> missing =
                new java.util.ArrayList<>(
                        SanctionRules.missingDocuments(checklist(purpose), attached));
        // SRS-6-14 (US-GAP-28): the purpose's No Caste Dispute flag needs the certificate too.
        if (purpose.isRequiresNoCasteDispute()
                && !attached.contains(NO_CASTE_DISPUTE)
                && !missing.contains(NO_CASTE_DISPUTE)) {
            missing.add(NO_CASTE_DISPUTE);
        }
        if (!missing.isEmpty()) {
            throw new FciException(
                    CpfErrorCode.DOCUMENTS_MISSING,
                    new Object[] {String.join(", ", missing)},
                    List.of(),
                    null);
        }
        tiers.save(
                CpfSanctionTier.acted(
                                s.getSanctionId(),
                                pass,
                                1,
                                "FORWARDED",
                                context.loginId(),
                                context.locationId(),
                                now,
                                trim(remark))
                        .verified(true, true));
        s.forwarded();
        claimStatus(
                s, CpfClaim.Status.UNDER_REVIEW, context.loginId(), now, "Verified at the unit");
        auditStage(
                context,
                s,
                Stage.UNIT_VERIFICATION.name(),
                SCREEN,
                "Forwarded to the CPF Division");
        return view(s, member);
    }

    // ---------------------------------------------------------------- tier 2 (CPF Division)

    /**
     * SRS-6-4 tier 2, SRS-6-26: the CPF Division checks the conditions in force on the request
     * date, works out the ceiling, caps the amount and recommends the sanction to the Competent
     * Authority (route I04_SUBMIT, OTP at tier 3); or returns the request to the unit.
     */
    @Transactional
    public SanctionView recommend(
            AccessContext context, Long id, Decision decision, String remark, OtpProof otp) {
        policy.require(context, SCREEN, "SUBMIT");
        PfSanction s = locked(id);
        EmployeeDirectory.Member member = member(s);
        policy.requireRecordAt(context, SCREEN, "SUBMIT", member.locationId());
        if (s.stage() != Stage.ZONAL_HQ_VALIDATION) {
            throw stage(s);
        }
        int pass = currentPass(s);
        requireNewActor(s, pass, context.loginId());
        Instant now = clock.instant();
        if (decision == Decision.RETURN) {
            String why = requireReason(remark);
            tiers.save(
                    CpfSanctionTier.acted(
                            s.getSanctionId(),
                            pass,
                            2,
                            "RETURNED",
                            context.loginId(),
                            context.locationId(),
                            now,
                            why));
            s.backToUnit();
            claimStatus(s, CpfClaim.Status.DOCS_PENDING, context.loginId(), now, why);
            auditStage(
                    context,
                    s,
                    Stage.ZONAL_HQ_VALIDATION.name(),
                    SCREEN,
                    "Returned to the unit: " + why);
            return view(s, member);
        }
        if (decision == Decision.REJECT) {
            // FRS V0.5 §13.4 Step 3 and Exception Handling (US-GAP-164): a request that fails the
            // eligibility, purpose or limit validation is rejected with the reason, which the
            // member sees on E-01 and which is queued for the CPF SSP. It ends the request.
            String why = requireReason(remark);
            tiers.save(
                    CpfSanctionTier.acted(
                            s.getSanctionId(),
                            pass,
                            2,
                            "REJECTED",
                            context.loginId(),
                            context.locationId(),
                            now,
                            why));
            s.rejected();
            claimStatus(s, CpfClaim.Status.REJECTED, context.loginId(), now, why);
            auditStage(
                    context,
                    s,
                    Stage.ZONAL_HQ_VALIDATION.name(),
                    SCREEN,
                    "Rejected by the CPF Division: " + why);
            toSsp(s);
            return view(s, member);
        }
        CpfWithdrawalPurpose purpose = purposes.findById(s.getPurposeId()).orElseThrow();
        if ("FINAL".equals(s.getSanctionType())) {
            // SRS-6-48 / BR-CPF-014: a final payment carries the interest to the sanction date,
            // whose method is not configured (Q-072, D-4); 07f-3 completes it.
            throw new FciException(CpfErrorCode.FINAL_INTEREST_NOT_CONFIGURED);
        }
        LocalDate requestDate = requestDate(s);
        requireMemberConditions(
                s.getEmployeeId(), s.getSanctionType(), purpose, requestDate, s.getSanctionId());
        BigDecimal ceiling = ceiling(s, purpose, requestDate);
        SanctionRules.Capped capped = SanctionRules.cap(s.getRequestedAmount(), ceiling);
        // The number is issued at tier 3: refuse now, not after the Competent Authority's OTP.
        numberFormat();
        SubmittedApproval item =
                approvals.submit(
                        context,
                        new Submission(
                                        SCREEN,
                                        "SUBMIT",
                                        ENTITY,
                                        String.valueOf(s.getSanctionId()),
                                        ActionType.CREATE,
                                        member.locationId(),
                                        json.writeValueAsString(
                                                new Proposal(
                                                        s.getSanctionId(),
                                                        member.eis(),
                                                        s.getSanctionType(),
                                                        purpose.getPurposeCode(),
                                                        s.getRequestedAmount(),
                                                        ceiling,
                                                        capped.sanctioned(),
                                                        capped.wasCapped())),
                                        requestDate,
                                        trim(remark))
                                .withImpact(capped.sanctioned()),
                        otp);
        tiers.save(
                CpfSanctionTier.acted(
                                s.getSanctionId(),
                                pass,
                                2,
                                "RECOMMENDED",
                                context.loginId(),
                                context.locationId(),
                                now,
                                trim(remark))
                        .validated(ceiling, capped.sanctioned(), item.id()));
        s.validated(ceiling, capped.sanctioned(), capped.wasCapped(), null, item.id());
        auditStage(
                context,
                s,
                Stage.ZONAL_HQ_VALIDATION.name(),
                SCREEN,
                "Recommended at " + capped.sanctioned() + (capped.wasCapped() ? " (capped)" : ""));
        return view(s, member);
    }

    // ---------------------------------------------------------------- tier 3 (Competent Authority)

    /**
     * SRS-6-4 tier 3: the Competent Authority's decision in the Approvals Inbox (with OTP).
     * Approved: the tier-3 row, the sanction number and SANCTIONED - or PAYMENT_BLOCKED when the
     * ledger balance cannot carry the amount (UC-05). Rejected: the request ends. Returned: back to
     * the unit for a new pass (SRS-12-9).
     */
    @EventListener
    public void on(ApprovalDecided decision) {
        if (!decision.concerns(ENTITY) || !SCREEN.equals(decision.moduleCode())) {
            return;
        }
        PfSanction s = sanctions.findByApprovalId(decision.approvalId()).orElse(null);
        if (s == null || s.stage() != Stage.COMPETENT_AUTHORITY) {
            return;
        }
        int pass = currentPass(s);
        requireNewActor(s, pass, decision.checkerLoginId());
        Instant now = decision.decidedAt();
        String before = s.stage().name();
        if (decision.outcome() == ApprovalStatus.APPROVED) {
            EmployeeDirectory.Member member = member(s);
            LocalDate day = LocalDate.ofInstant(now, INDIA);
            tiers.save(
                    CpfSanctionTier.acted(
                                    s.getSanctionId(),
                                    pass,
                                    3,
                                    "SANCTIONED",
                                    decision.checkerLoginId(),
                                    member.locationId(),
                                    now,
                                    decision.reason())
                            .decided(decision.approvalId(), decision.otpChallengeId()));
            boolean blocked =
                    SanctionRules.paymentBlocked(
                            balance(s.getEmployeeId()), s.getSanctionedAmount());
            s.sanctioned(number(s, day), day, member.locationId(), blocked);
            claimStatus(
                    s,
                    CpfClaim.Status.APPROVED,
                    decision.checkerLoginId(),
                    now,
                    s.getSystemSanctionNumber());
        } else if (decision.outcome() == ApprovalStatus.REJECTED) {
            tiers.save(
                    CpfSanctionTier.acted(
                                    s.getSanctionId(),
                                    pass,
                                    3,
                                    "REJECTED",
                                    decision.checkerLoginId(),
                                    null,
                                    now,
                                    decision.reason())
                            .decided(decision.approvalId(), decision.otpChallengeId()));
            s.rejected();
            claimStatus(
                    s, CpfClaim.Status.REJECTED, decision.checkerLoginId(), now, decision.reason());
        } else {
            tiers.save(
                    CpfSanctionTier.acted(
                                    s.getSanctionId(),
                                    pass,
                                    3,
                                    "RETURNED",
                                    decision.checkerLoginId(),
                                    null,
                                    now,
                                    decision.reason())
                            .decided(decision.approvalId(), decision.otpChallengeId()));
            s.backToUnit();
            claimStatus(
                    s,
                    CpfClaim.Status.DOCS_PENDING,
                    decision.checkerLoginId(),
                    now,
                    decision.reason());
        }
        audit.record(
                decision.checkerLoginId(),
                AuditService.Entry.change(
                                ENTITY,
                                String.valueOf(s.getSanctionId()),
                                "workflow_stage",
                                before,
                                s.stage().name(),
                                decision.reason() == null ? "Decided" : decision.reason())
                        .module(SCREEN)
                        .approval(decision.approvalId()));
        tierFacts(
                decision.checkerLoginId(),
                s,
                SCREEN,
                decision.reason() == null ? "Decided" : decision.reason(),
                decision.approvalId());
    }

    // ---------------------------------------------------------------- rules

    /** The member's withdrawals that count (LIVE_STAGES) and match, never the request itself. */
    private static long live(List<PfSanction> theirs, Long self, Predicate<PfSanction> which) {
        return theirs.stream()
                .filter(s -> !s.getSanctionId().equals(self))
                .filter(s -> LIVE_STAGES.contains(s.stage().name()))
                .filter(which)
                .count();
    }

    /** BR-CPF-009 to 011, BR-CPF-030: the member conditions of the request type on {@code day}. */
    private void requireMemberConditions(
            Long employeeId, String type, CpfWithdrawalPurpose purpose, LocalDate day) {
        requireMemberConditions(employeeId, type, purpose, day, null);
    }

    private void requireMemberConditions(
            Long employeeId, String type, CpfWithdrawalPurpose purpose, LocalDate day, Long self) {
        // One member's requests are checked one after the other (two tabs, a double click).
        directory.lockMember(employeeId);
        List<PfSanction> theirs = sanctions.findByEmployeeIdOrderBySanctionIdDesc(employeeId);
        EmployeeDirectory.CpfEligibility data =
                directory
                        .cpfEligibility(employeeId)
                        .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
        boolean ninety =
                WithdrawalLimit.BEFORE_RETIREMENT
                        .ruleKey()
                        .equals(purpose.getSanctionLimitRuleKey());
        switch (type) {
            case "ADVANCE" ->
                    SanctionRules.requireNoActiveAdvance(
                            theirs.stream()
                                    .anyMatch(
                                            s ->
                                                    !s.getSanctionId().equals(self)
                                                            && "ADVANCE".equals(s.getSanctionType())
                                                            && ACTIVE_STAGES.contains(
                                                                    s.stage().name())));
            case "PART_FINAL" -> {
                if (ninety) {
                    // OPEN-QUESTION: Q-07-7 - SRS-6-5 names the 90 % withdrawal beside the
                    // part-final
                    // rules but does not say whether it also needs the 5 years and counts against
                    // the 6; built: only its own window (BR-CPF-011).
                    SanctionRules.requireRetirementWindow(
                            data.retirementDate(), day, rule(WINDOW_RULE, day));
                } else {
                    SanctionRules.requireMembership(
                            data.epsJoinDate(), day, rule(MIN_YEARS_RULE, day));
                    SanctionRules.requireCount(
                            live(theirs, self, s -> "PART_FINAL".equals(s.getSanctionType())),
                            rule(MAX_COUNT_RULE, day));
                }
            }
            default -> {
                // FINAL: the final settlement's conditions are 07f-3's (final payment).
            }
        }
        Integer most = purpose.getMaxOccurrences();
        if (most != null
                && live(theirs, self, s -> purpose.getPurposeId().equals(s.getPurposeId()))
                        >= most) {
            throw new FciException(
                    CpfErrorCode.WITHDRAWAL_COUNT, new Object[] {most}, List.of(), null);
        }
    }

    /**
     * SRS-6-2, SRS-6-24, D-15: the ceiling of the purpose's limit on the request date; an advance
     * is also held to its cap. Refused "not configured" when an input is missing (the MGW basis,
     * the pay, a limit without a version) - never guessed.
     */
    private BigDecimal ceiling(PfSanction s, CpfWithdrawalPurpose purpose, LocalDate day) {
        WithdrawalLimit limit =
                WithdrawalLimit.ofRuleKey(purpose.getSanctionLimitRuleKey())
                        .orElseThrow(() -> notConfigured(purpose.getSanctionLimitRuleKey()));
        LedgerBalances balance = balance(s.getEmployeeId());
        BigDecimal multiplier = optionalRule(limit.ruleKey(), day);
        BigDecimal mgw =
                limit.basis() == WithdrawalLimit.Basis.MGW_MULTIPLE
                        ? optionalRule(MGW_RULE, day)
                        : null;
        // SRS-6-24: the pay (Basic + DA + DP + NPA + Stagnation + Grade Pay) comes from the payroll
        // engines, which do not compute it yet (D-15): the renovation limit is not configured.
        BigDecimal ceiling =
                SanctionRules.ceiling(limit, multiplier, mgw, balance, null)
                        .orElseThrow(
                                () ->
                                        notConfigured(
                                                multiplier == null
                                                        ? limit.ruleKey()
                                                        : limit.basis()
                                                                        == WithdrawalLimit.Basis
                                                                                .MGW_MULTIPLE
                                                                ? MGW_RULE
                                                                : "pay (SRS-6-24)"));
        if ("ADVANCE".equals(s.getSanctionType())) {
            BigDecimal fraction = optionalRule(ADVANCE_CAP_RULE, day);
            if (fraction == null) {
                throw notConfigured(ADVANCE_CAP_RULE);
            }
            ceiling = ceiling.min(SanctionRules.advanceCap(fraction, balance));
        }
        return ceiling;
    }

    /**
     * SRS-6-38 / BR-CPF-031: month + year + zone + running sequence.
     *
     * <p>OPEN-QUESTION: Q-07-6 - the SRS does not say when the running sequence restarts (each
     * month, each year, never) nor whether it is per zone or per sanctioning office; built as one
     * continuous sequence per zone, the literal reading, serialised on the zone's row lock.
     */
    private String number(PfSanction s, LocalDate day) {
        NumberFormat format = numberFormat();
        CpfZones.Zone zone = zones.of(member(s).locationId());
        zones.lock(s.getSanctionZoneId());
        long sequence = sanctions.numberedInZone(s.getSanctionZoneId()) + 1;
        return SanctionRules.sanctionNumber(
                day, zone.code(), sequence, format.separator(), format.width());
    }

    record NumberFormat(String separator, int width) {}

    /** D-12: the M-16 values of the number format; "not configured" until FCI supplies them. */
    private NumberFormat numberFormat() {
        List<ReferenceData.Value> values =
                references
                        .list(NUMBER_FORMAT)
                        .map(ReferenceData.ListView::values)
                        .orElse(List.of())
                        .stream()
                        .filter(ReferenceData.Value::active)
                        .toList();
        Optional<ReferenceData.Value> separator =
                values.stream().filter(v -> "SEPARATOR".equals(v.code())).findFirst();
        Optional<ReferenceData.Value> width =
                values.stream()
                        .filter(v -> "SEQUENCE_WIDTH".equals(v.code()) && v.numeric() != null)
                        .findFirst();
        if (separator.isEmpty() || width.isEmpty()) {
            throw new FciException(CpfErrorCode.SANCTION_NUMBER_NOT_CONFIGURED);
        }
        return new NumberFormat(separator.get().label(), width.get().numeric().intValue());
    }

    private List<ChecklistItem> checklist(CpfWithdrawalPurpose purpose) {
        return checklists.findByPurposeIdOrderByPurposeDocId(purpose.getPurposeId()).stream()
                .map(d -> new ChecklistItem(d.getDocumentTypeCode(), d.isMandatory()))
                .toList();
    }

    private void requireDocuments(CpfWithdrawalPurpose purpose, List<Document> files) {
        // The type of each file (PDF / JPG / PNG by content) is the document store's (CF-13).
        SanctionRules.requireDocumentCount(files.size());
        List<String> missing =
                SanctionRules.missingDocuments(
                        checklist(purpose),
                        files.stream().map(Document::documentTypeCode).toList());
        if (!missing.isEmpty()) {
            throw new FciException(
                    CpfErrorCode.DOCUMENTS_MISSING,
                    new Object[] {String.join(", ", missing)},
                    List.of(),
                    null);
        }
    }

    private CpfWithdrawalPurpose purposeInForce(String code, LocalDate day) {
        return purposes.findByPurposeCodeOrderByEffectiveFromDesc(code == null ? "" : code).stream()
                .filter(p -> p.inForceOn(day))
                .findFirst()
                .orElseThrow(() -> new FciException(CpfErrorCode.PURPOSE_NOT_OFFERED));
    }

    /** The latest ledger year's closing balance (zero when the member has no ledger yet). */
    private LedgerBalances balance(Long employeeId) {
        return headers
                .findByEmployeeIdAndFundTypeOrderByFinYearDescPfHeaderIdDesc(
                        employeeId, CpfLedgerService.FUND)
                .stream()
                .findFirst()
                .map(EmployeePfHeader::closing)
                .orElse(new LedgerBalances(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
    }

    private BigDecimal rule(String key, LocalDate day) {
        BigDecimal value = optionalRule(key, day);
        if (value == null) {
            throw notConfigured(key);
        }
        return value;
    }

    private BigDecimal optionalRule(String key, LocalDate day) {
        try {
            return engine.resolve(key, day, RuleEngine.Context.unscoped())
                    .map(RuleEngine.Resolved::scalar)
                    .orElse(null);
        } catch (FciException notConfigured) {
            return null;
        }
    }

    private static FciException notConfigured(String what) {
        return new FciException(
                CpfErrorCode.LIMIT_NOT_CONFIGURED, new Object[] {what}, List.of(), null);
    }

    // ---------------------------------------------------------------- helpers

    private PfSanction locked(Long id) {
        return sanctions
                .lockById(id)
                .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
    }

    /** The member's own request, locked; another member's is simply not found. */
    private PfSanction ownLocked(AccessContext context, Long id) {
        PfSanction s = locked(id);
        if (context.employeeId() == null || !s.getEmployeeId().equals(context.employeeId())) {
            policy.recordForeignAttempt(context, ESEVA, "CREATE", "pf_sanction", id);
            throw new FciException(CommonErrorCode.NOT_FOUND);
        }
        policy.requireEmployeeRecord(
                context, ESEVA, "CREATE", s.getEmployeeId(), member(s).locationId());
        return s;
    }

    private void requireInScope(AccessContext context, EmployeeDirectory.Member member) {
        if (!context.dataScope().contains(member.locationId())) {
            throw new FciException(CommonErrorCode.NOT_FOUND);
        }
    }

    private EmployeeDirectory.Member member(PfSanction s) {
        return directory.member(s.getEmployeeId()).orElseThrow();
    }

    /**
     * SRS-6-4, SRS-G-1 (uk_cpf_tier_actor): one user acts at most once per pass, so the three tiers
     * are three different people.
     */
    private void requireNewActor(PfSanction s, int pass, String actor) {
        boolean acted =
                tiers.findBySanctionIdOrderByTierActionIdAsc(s.getSanctionId()).stream()
                        .anyMatch(t -> t.getPassNo() == pass && actor.equals(t.getActedBy()));
        if (acted) {
            throw new FciException(CpfErrorCode.TIER_SAME_USER);
        }
    }

    /** The pass of the current round: one after the last RETURNED tier action (SRS-12-9). */
    private int currentPass(PfSanction s) {
        List<CpfSanctionTier> rows =
                tiers.findBySanctionIdOrderByTierActionIdAsc(s.getSanctionId());
        int pass = 1;
        for (CpfSanctionTier t : rows) {
            pass =
                    Math.max(
                            pass,
                            "RETURNED".equals(t.getTierAction())
                                    ? t.getPassNo() + 1
                                    : t.getPassNo());
        }
        return pass;
    }

    private LocalDate requestDate(PfSanction s) {
        return claims.findBySanctionId(s.getSanctionId())
                .map(c -> LocalDate.ofInstant(c.getSubmittedAt(), INDIA))
                .orElse(today());
    }

    /** DDL {@code withdrawal_category} as the eSeva log's submission type (SRS-17-17). */
    static SelfServiceEvents.Type logType(String category) {
        return switch (category) {
            case "ADVANCE" -> SelfServiceEvents.Type.CPF_ADVANCE_REQUEST;
            case "FINAL" -> SelfServiceEvents.Type.CPF_FINAL_PAYMENT_REQUEST;
            default -> SelfServiceEvents.Type.CPF_WITHDRAWAL_REQUEST;
        };
    }

    /** The member's claim status in the eSeva log: sanctioned or paid, refused or withdrawn. */
    static SelfServiceEvents.Status logStatus(CpfClaim.Status status) {
        return switch (status) {
            case APPROVED, PAID -> SelfServiceEvents.Status.ACTIONED;
            case REJECTED, WITHDRAWN -> SelfServiceEvents.Status.REJECTED;
            default -> SelfServiceEvents.Status.ROUTED;
        };
    }

    private void claimStatus(
            PfSanction s, CpfClaim.Status next, String by, Instant at, String remark) {
        events.publishEvent(
                new SelfServiceEvents.StatusChanged(
                        ENTITY, s.getSanctionId(), logStatus(next), by));
        claims.findBySanctionId(s.getSanctionId())
                .ifPresent(
                        c -> {
                            String from = c.status().name();
                            c.status(next, at);
                            claims.save(c);
                            claimHistory.save(
                                    CpfClaimHistory.of(
                                            c.getClaimId(),
                                            from,
                                            next.name(),
                                            s.stage().name(),
                                            by,
                                            at,
                                            remark == null
                                                    ? null
                                                    : remark.length() <= 1000
                                                            ? remark
                                                            : remark.substring(0, 1000)));
                        });
    }

    private void auditStage(
            AccessContext context, PfSanction s, String before, String screen, String why) {
        audit.record(
                context.loginId(),
                AuditService.Entry.change(
                                ENTITY,
                                String.valueOf(s.getSanctionId()),
                                "workflow_stage",
                                before,
                                s.stage().name(),
                                why)
                        .module(screen));
        tierFacts(context.loginId(), s, screen, why, null);
    }

    /**
     * FRS V0.5 §13 Audit Process (US-GAP-167): every action of the chain - each tier's decision, a
     * sanction, rejection or cancellation - records, beside the stage change, the purpose category
     * and the supporting documents on the request when the authority acted (the authority is the
     * audit row's actor, the reason its reason).
     */
    private void tierFacts(String actor, PfSanction s, String screen, String why, Long approval) {
        String key = String.valueOf(s.getSanctionId());
        String category =
                s.getPurposeId() == null
                        ? null
                        : purposes.findById(s.getPurposeId())
                                .map(CpfWithdrawalPurpose::getWithdrawalCategory)
                                .orElse(null);
        List<String> verified =
                documents.active(DOCUMENTS, key).stream()
                        .map(DocumentStore.AttachmentView::documentTypeCode)
                        .sorted()
                        .toList();
        audit.recordAll(
                actor,
                List.of(
                        AuditService.Entry.change(
                                        ENTITY, key, "purpose_category", null, category, why)
                                .module(screen)
                                .approval(approval),
                        AuditService.Entry.change(
                                        ENTITY,
                                        key,
                                        "documents_verified",
                                        null,
                                        verified.isEmpty() ? "none" : String.join(",", verified),
                                        why)
                                .module(screen)
                                .approval(approval)));
    }

    /**
     * SRS l.2260, FRS V0.5 §13 Exception Handling (US-GAP-164, US-GAP-165): a request returned to
     * the member or rejected is queued for the CPF SSP with its stage and reason (logged SKIPPED
     * with the reason until the portal interface of module 23 exists, API-07-5).
     */
    private void toSsp(PfSanction s) {
        ssp.queue(
                new SspGateway.Change(
                        s.getEmployeeId(), SSP_REQUEST, List.of("workflow_stage", "reason")));
    }

    private static FciException stage(PfSanction s) {
        return new FciException(
                CpfErrorCode.SANCTION_STAGE, new Object[] {s.stage().name()}, List.of(), null);
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new FciException(ApiErrorCode.REASON_REQUIRED);
        }
        return reason.strip();
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), INDIA);
    }

    private static Set<String> names(Set<Stage> stages) {
        return stages.stream().map(Stage::name).collect(Collectors.toUnmodifiableSet());
    }

    /**
     * The views of a register page with one lookup per kind (members, KYC, purposes, claims, tiers,
     * documents), not six per row (deep profile 2026-10-27: a page of 20 cost about 120 round
     * trips).
     */
    private List<SanctionView> views(List<PfSanction> rows) {
        List<Long> ids = rows.stream().map(PfSanction::getSanctionId).toList();
        List<Long> employees = rows.stream().map(PfSanction::getEmployeeId).distinct().toList();
        Map<Long, EmployeeDirectory.Member> people = directory.members(employees);
        Map<Long, EmployeeDirectory.CpfKyc> kycs = directory.cpfKycs(employees);
        Map<Long, CpfWithdrawalPurpose> purposeById = new HashMap<>();
        rows.stream()
                .map(PfSanction::getPurposeId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .forEach(id -> purposes.findById(id).ifPresent(p -> purposeById.put(id, p)));
        Map<Long, CpfClaim> claimBySanction = new HashMap<>();
        Map<Long, List<CpfSanctionTier>> tiersBySanction = new HashMap<>();
        Map<String, List<DocumentStore.AttachmentView>> docs = Map.of();
        if (!ids.isEmpty()) {
            claims.findBySanctionIdIn(ids).forEach(c -> claimBySanction.put(c.getSanctionId(), c));
            tiers.findBySanctionIdInOrderByTierActionIdAsc(ids)
                    .forEach(
                            t ->
                                    tiersBySanction
                                            .computeIfAbsent(
                                                    t.getSanctionId(), k -> new ArrayList<>())
                                            .add(t));
            docs = documents.activeByKey(DOCUMENTS, ids.stream().map(String::valueOf).toList());
        }
        List<SanctionView> out = new ArrayList<>();
        Map<String, String> ruleNames = new HashMap<>();
        for (PfSanction s : rows) {
            CpfWithdrawalPurpose purpose =
                    s.getPurposeId() == null ? null : purposeById.get(s.getPurposeId());
            out.add(
                    view(
                            s,
                            people.containsKey(s.getEmployeeId())
                                    ? people.get(s.getEmployeeId())
                                    : member(s),
                            purpose,
                            claimBySanction.get(s.getSanctionId()),
                            kycs.get(s.getEmployeeId()),
                            tiersBySanction.getOrDefault(s.getSanctionId(), List.of()),
                            docs.getOrDefault(String.valueOf(s.getSanctionId()), List.of()),
                            limitRule(purpose, ruleNames)));
        }
        return out;
    }

    private SanctionView view(PfSanction s, EmployeeDirectory.Member member) {
        CpfWithdrawalPurpose purpose =
                s.getPurposeId() == null ? null : purposes.findById(s.getPurposeId()).orElse(null);
        return view(
                s,
                member,
                purpose,
                claims.findBySanctionId(s.getSanctionId()).orElse(null),
                directory.cpfKyc(s.getEmployeeId()).orElse(null),
                tiers.findBySanctionIdOrderByTierActionIdAsc(s.getSanctionId()),
                documents.active(DOCUMENTS, String.valueOf(s.getSanctionId())),
                limitRule(purpose, new HashMap<>()));
    }

    /** BR-CPF-029: the limit rule's name; the key when the catalogue has no name for it. */
    private String limitRule(CpfWithdrawalPurpose purpose, Map<String, String> names) {
        String key = purpose == null ? null : purpose.getSanctionLimitRuleKey();
        if (key == null) {
            return null;
        }
        return names.computeIfAbsent(
                key,
                k -> {
                    String name = purposes.ruleName(k);
                    return name == null ? k : name;
                });
    }

    private SanctionView view(
            PfSanction s,
            EmployeeDirectory.Member member,
            CpfWithdrawalPurpose purpose,
            CpfClaim claim,
            EmployeeDirectory.CpfKyc kyc,
            List<CpfSanctionTier> tierRows,
            List<DocumentStore.AttachmentView> attached,
            String limitRule) {
        List<TierView> tierViews = new ArrayList<>();
        for (CpfSanctionTier t : tierRows) {
            tierViews.add(
                    new TierView(
                            t.getPassNo(),
                            t.getTierNo(),
                            t.getTierCode(),
                            t.getTierAction(),
                            t.getActedBy(),
                            t.getActedAt(),
                            t.getRemark(),
                            t.getCeilingAmount(),
                            t.getCappedAmount()));
        }
        tierViews.sort(Comparator.comparing(TierView::pass).thenComparing(TierView::tier));
        return new SanctionView(
                s.getSanctionId(),
                member.eis(),
                member.name(),
                s.getSanctionType(),
                purpose == null ? null : purpose.getPurposeCode(),
                purpose == null ? null : purpose.getPurposeName(),
                s.getRequestedAmount(),
                s.getComputedCeilingAmount(),
                s.getSanctionedAmount(),
                s.isWasCapped(),
                s.stage().name(),
                s.getSystemSanctionNumber(),
                s.getSanctionDate(),
                claim == null ? null : claim.getPropertyAddress(),
                claim == null ? null : claim.getPropertyDescription(),
                s.getApprovalId(),
                s.getCancelReason(),
                tierViews,
                attached,
                kyc == null ? null : kyc.uan(),
                kyc == null ? null : kyc.aadhaarMasked(),
                purpose != null && purpose.isRequiresNoCasteDispute(),
                null,
                limitRule);
    }
}
