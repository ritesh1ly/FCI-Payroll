package in.gov.fci.payroll.cpf.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.cpf.business.PurposeRules;
import in.gov.fci.payroll.cpf.business.PurposeRules.Category;
import in.gov.fci.payroll.cpf.business.model.CpfPurposeDocument;
import in.gov.fci.payroll.cpf.business.model.CpfWithdrawalPurpose;
import in.gov.fci.payroll.cpf.persistence.CpfRepositories;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.platform.approval.ActionType;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatuses;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.OtpProof;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * CPF withdrawal purposes and their document checklists, maintained on M-19 at Headquarters
 * (SRS-6-5, SRS-6-14, SRS-6-39; BR-CPF-028, 032, 037, 039; plan 07 D-5): the Rule Administrator
 * proposes a new version of a purpose with its checklist and the Rule Approver approves it (route
 * M19_CREATE). A version is effective-dated: the one in force ends the day before its successor
 * starts. The SRS-named purposes are seeded unconfirmed with an empty checklist until FCI supplies
 * them (Q-074).
 */
@Service
@Loggable
public class WithdrawalPurposeService {

    public static final String SCREEN = "M-19";
    public static final String ENTITY = "cpf_withdrawal_purpose";
    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    /** A checklist document as shown and proposed. */
    public record DocumentView(String documentType, boolean mandatory, String tier) {}

    /** One version of a purpose with its checklist. */
    public record PurposeView(
            Long id,
            String code,
            String name,
            String category,
            String limitRuleKey,
            Integer maxOccurrences,
            boolean noCasteDispute,
            boolean propertyDetails,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            boolean confirmed,
            boolean inForce,
            List<DocumentView> documents) {}

    /** What the Rule Approver decides on: the new version as proposed. */
    public record Proposal(
            String code,
            String name,
            String category,
            String limitRuleKey,
            Integer maxOccurrences,
            boolean noCasteDispute,
            boolean propertyDetails,
            LocalDate effectiveFrom,
            boolean confirmed,
            List<DocumentView> documents,
            String proposedBy,
            Instant proposedAt) {}

    /** A proposal awaiting the Rule Approver. */
    public record Pending(Long approvalId, Proposal proposal) {}

    /** The purposes (every version) and the proposals awaiting a decision. */
    public record Purposes(List<PurposeView> purposes, List<Pending> pending) {}

    private final CpfRepositories.Purposes purposes;
    private final CpfRepositories.PurposeDocuments documents;
    private final DocumentStore store;
    private final MakerChecker approvals;
    private final ApprovalStatuses statuses;
    private final Authorization policy;
    private final AuditService audit;
    private final ObjectMapper json;
    private final Clock clock;

    @SuppressWarnings("java:S107") // one collaborator per concern
    public WithdrawalPurposeService(
            CpfRepositories.Purposes purposes,
            CpfRepositories.PurposeDocuments documents,
            DocumentStore store,
            MakerChecker approvals,
            ApprovalStatuses statuses,
            Authorization policy,
            AuditService audit,
            ObjectMapper json,
            Clock clock) {
        this.purposes = purposes;
        this.documents = documents;
        this.store = store;
        this.approvals = approvals;
        this.statuses = statuses;
        this.policy = policy;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    /**
     * M-19 purposes card: every version with its checklist, and the proposals awaiting decision.
     */
    @Transactional(readOnly = true)
    public Purposes list(AccessContext context) {
        policy.require(context, SCREEN, "VIEW");
        LocalDate today = LocalDate.now(clock.withZone(INDIA));
        List<PurposeView> views =
                purposes.findAllByOrderByPurposeCodeAscEffectiveFromDesc().stream()
                        .map(p -> view(p, today))
                        .toList();
        List<Pending> pending =
                statuses.pending(ENTITY).stream()
                        .map(
                                p ->
                                        new Pending(
                                                p.approvalId(),
                                                json.readValue(p.payload(), Proposal.class)))
                        .toList();
        return new Purposes(views, pending);
    }

    /**
     * M-19: a new version of a purpose (new or existing code) with its checklist, validated and
     * sent to the Rule Approver. Headquarters only.
     */
    @Transactional
    public Pending propose(AccessContext context, Proposal request, String reason, OtpProof otp) {
        policy.require(context, SCREEN, "CREATE");
        if (!policy.isAtHeadquarters(context)) {
            throw new FciException(ApiErrorCode.HQ_ONLY);
        }
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new FciException(CommonErrorCode.VALIDATION_FAILED);
        }
        Proposal p = validated(request, context.loginId());
        SubmittedApproval item =
                approvals.submit(
                        context,
                        new Submission(
                                SCREEN,
                                "CREATE",
                                ENTITY,
                                p.code(),
                                ActionType.CREATE,
                                context.locationId(),
                                json.writeValueAsString(p),
                                p.effectiveFrom(),
                                reason),
                        otp);
        return new Pending(item.id(), p);
    }

    /**
     * The Rule Approver's decision: the new version is written (checked again: another version may
     * have been approved meanwhile) with its checklist, and the version in force ends the day
     * before.
     */
    @EventListener
    public void on(ApprovalDecided decision) {
        if (!decision.concerns(ENTITY)
                || !SCREEN.equals(decision.moduleCode())
                || !decision.approved()) {
            return;
        }
        Proposal p = json.readValue(decision.payload(), Proposal.class);
        List<CpfWithdrawalPurpose> versions =
                purposes.findByPurposeCodeOrderByEffectiveFromDesc(p.code());
        PurposeRules.requireAfter(
                p.effectiveFrom(),
                versions.stream().findFirst().map(CpfWithdrawalPurpose::getEffectiveFrom));
        versions.stream()
                .filter(
                        v ->
                                v.getEffectiveTo() == null
                                        || !v.getEffectiveTo().isBefore(p.effectiveFrom()))
                .forEach(
                        v -> {
                            v.endBefore(p.effectiveFrom());
                            purposes.save(v);
                        });
        CpfWithdrawalPurpose saved =
                purposes.save(
                        CpfWithdrawalPurpose.approved(
                                p.code(),
                                p.name(),
                                p.category(),
                                p.limitRuleKey(),
                                p.maxOccurrences(),
                                p.noCasteDispute(),
                                p.propertyDetails(),
                                p.effectiveFrom(),
                                p.confirmed()));
        purposes.flush();
        p.documents()
                .forEach(
                        d ->
                                documents.save(
                                        CpfPurposeDocument.of(
                                                saved.getPurposeId(),
                                                d.documentType(),
                                                d.mandatory(),
                                                d.tier())));
        audit.record(
                decision.checkerLoginId(),
                AuditService.Entry.change(
                                ENTITY,
                                p.code(),
                                "version",
                                versions.isEmpty()
                                        ? null
                                        : versions.get(0).getEffectiveFrom().toString(),
                                p.effectiveFrom()
                                        + " "
                                        + p.name()
                                        + "; checklist "
                                        + p.documents().size()
                                        + "; confirmed "
                                        + (p.confirmed() ? "Y" : "N"),
                                decision.reason() == null
                                        ? "M-19 purpose version"
                                        : decision.reason())
                        .approval(decision.approvalId())
                        .module(SCREEN)
                        .effective(p.effectiveFrom()));
    }

    /** The rules of PurposeRules, the text stripped, the proposer and time stamped. */
    private Proposal validated(Proposal r, String actor) {
        PurposeRules.requireCode(r.code());
        Category category = category(r.category());
        PurposeRules.requireLimitKey(r.limitRuleKey(), category);
        PurposeRules.requireQuota(r.maxOccurrences());
        List<DocumentView> docs = r.documents() == null ? List.of() : r.documents();
        Set<String> types =
                store.types().stream()
                        .map(DocumentStore.TypeView::code)
                        .collect(Collectors.toSet());
        List<PurposeRules.Document> checked = new ArrayList<>();
        for (DocumentView d : docs) {
            checked.add(
                    new PurposeRules.Document(
                            d == null ? null : d.documentType(),
                            d != null && d.mandatory(),
                            tier(d)));
        }
        PurposeRules.requireDocuments(checked, types);
        PurposeRules.requireAfter(
                r.effectiveFrom(),
                purposes.findByPurposeCodeOrderByEffectiveFromDesc(r.code()).stream()
                        .findFirst()
                        .map(CpfWithdrawalPurpose::getEffectiveFrom));
        return new Proposal(
                r.code(),
                r.name().strip(),
                category.name(),
                r.limitRuleKey(),
                r.maxOccurrences(),
                r.noCasteDispute(),
                r.propertyDetails(),
                r.effectiveFrom(),
                r.confirmed(),
                checked.stream()
                        .map(
                                d ->
                                        new DocumentView(
                                                d.documentType(), d.mandatory(), d.tier().name()))
                        .toList(),
                actor,
                clock.instant());
    }

    private static Category category(String code) {
        if (code == null) {
            throw new FciException(CommonErrorCode.VALIDATION_FAILED);
        }
        try {
            return Category.valueOf(code);
        } catch (IllegalArgumentException e) {
            throw new FciException(CommonErrorCode.VALIDATION_FAILED);
        }
    }

    private static PurposeRules.Tier tier(DocumentView d) {
        if (d == null || d.tier() == null) {
            return PurposeRules.Tier.UNIT_CPF_ADMIN;
        }
        try {
            return PurposeRules.Tier.valueOf(d.tier());
        } catch (IllegalArgumentException e) {
            throw new FciException(CommonErrorCode.VALIDATION_FAILED);
        }
    }

    private PurposeView view(CpfWithdrawalPurpose p, LocalDate today) {
        return new PurposeView(
                p.getPurposeId(),
                p.getPurposeCode(),
                p.getPurposeName(),
                p.getWithdrawalCategory(),
                p.getSanctionLimitRuleKey(),
                p.getMaxOccurrences(),
                p.isRequiresNoCasteDispute(),
                p.isRequiresPropertyDetails(),
                p.getEffectiveFrom(),
                p.getEffectiveTo(),
                p.isConfirmed(),
                p.inForceOn(today),
                documents.findByPurposeIdOrderByPurposeDocId(p.getPurposeId()).stream()
                        .map(
                                d ->
                                        new DocumentView(
                                                d.getDocumentTypeCode(),
                                                d.isMandatory(),
                                                d.getVerificationTier()))
                        .toList());
    }
}
