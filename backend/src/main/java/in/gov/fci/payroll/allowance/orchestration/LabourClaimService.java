package in.gov.fci.payroll.allowance.orchestration;

import in.gov.fci.payroll.allowance.business.AllowanceErrorCode;
import in.gov.fci.payroll.allowance.business.ClaimRules;
import in.gov.fci.payroll.allowance.business.model.ReimbursementClaim;
import in.gov.fci.payroll.allowance.persistence.AllowanceRepositories;
import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.platform.approval.ActionType;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.OtpProof;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * I-03 labour claims (plan 10 phases 10b; SRS-11-6, SRS-11-9, SRS-11-11, SRS-11-13, SRS-11-14,
 * SRS-11-18; D10-1 … D10-7): the Labour Dealing Hand enters a claim of a labour claim type with its
 * supporting documents; the Payroll Maker validates it (entitlement, cap, overlap) and submits it
 * to the Payroll Checker of the paying unit (route I03_VALIDATE), who approves or rejects it
 * ({@link LabourClaimEffect}). An employee's claim is never entered here (SRS-11-1).
 */
@Service
@Loggable
public class LabourClaimService {

    public static final String SCREEN = "I-03";
    public static final String ENTITY = "reimbursement_claim";

    /** A supporting document as entered. */
    public record Document(
            String documentType,
            String reference,
            LocalDate orderDate,
            String issuingAuthority,
            String fileName,
            byte[] content) {}

    /** A labour claim as the Labour Dealing Hand enters it. */
    public record NewClaim(
            String memberNumber,
            String claimType,
            LocalDate periodFrom,
            LocalDate periodTo,
            BigDecimal claimedAmount,
            List<Document> documents) {

        public NewClaim {
            documents = documents == null ? List.of() : List.copyOf(documents);
        }
    }

    private final AllowanceRepositories.Claims claims;
    private final ClaimCatalogue catalogue;
    private final DocumentStore documents;
    private final MakerChecker approvals;
    private final Authorization policy;
    private final AuditService audit;
    private final SystemParameters settings;

    @SuppressWarnings("java:S107") // one collaborator per concern, as the other claim services
    public LabourClaimService(
            AllowanceRepositories.Claims claims,
            ClaimCatalogue catalogue,
            DocumentStore documents,
            MakerChecker approvals,
            Authorization policy,
            AuditService audit,
            SystemParameters settings) {
        this.claims = claims;
        this.catalogue = catalogue;
        this.documents = documents;
        this.approvals = approvals;
        this.policy = policy;
        this.audit = audit;
        this.settings = settings;
    }

    /**
     * SRS-11-13 / 11-14: the Labour Dealing Hand's entry. A claim overlapping a live claim of the
     * type is recorded OVERLAP_REJECTED (SRS-11-6, DDL comment); a missing required document blocks
     * it, naming the document.
     */
    @Transactional
    public ReimbursementClaim enter(AccessContext context, NewClaim in) {
        ClaimCatalogue.MemberFacts member = catalogue.memberByNumber(in.memberNumber());
        policy.requireEmployeeRecord(
                context, SCREEN, "CREATE", member.employeeId(), member.locationId());
        if (!member.labour()) {
            throw new FciException(AllowanceErrorCode.HRMS_ONLY);
        }
        requireActive(member);
        ClaimRules.Period period = new ClaimRules.Period(in.periodFrom(), in.periodTo());
        ClaimRules.defect(period, in.claimedAmount())
                .ifPresent(
                        why -> {
                            throw invalid(why);
                        });
        LocalDate today = settings.today();
        boolean labourType =
                catalogue.types("LABOUR", today).stream()
                        .anyMatch(t -> t.code().equals(in.claimType()));
        if (!labourType) {
            throw invalid(
                    "the claim type " + in.claimType() + " is not a labour claim type (M-15)");
        }
        ClaimCatalogue.Norm norm = catalogue.norm(in.claimType(), "LABOUR", period.from());
        Set<String> attached = new HashSet<>();
        in.documents().forEach(d -> attached.add(d.documentType()));
        List<String> missing =
                ClaimRules.missingDocuments(
                        in.claimType(), catalogue.requiredDocuments(today), attached);
        if (!missing.isEmpty()) {
            throw new FciException(
                    AllowanceErrorCode.DOCUMENTS,
                    new Object[] {String.join(", ", missing)},
                    List.of(),
                    null);
        }
        ReimbursementClaim claim =
                ReimbursementClaim.labour(
                        member.employeeId(), in.claimType(), period, in.claimedAmount());
        if (catalogue.overlap(member.employeeId(), in.claimType(), period, null).isPresent()) {
            claim.overlapRejected();
        } else {
            // Entered, not yet validated by the Maker: the adjudication is shown, SUBMITTED stays.
            claim.enteredWith(
                    norm.id(),
                    catalogue.adjudicate(
                            norm,
                            member.employeeId(),
                            in.claimType(),
                            period,
                            in.claimedAmount(),
                            null));
        }
        ReimbursementClaim saved = claims.save(claim);
        for (Document d : in.documents()) {
            documents.attach(
                    context.loginId(),
                    new DocumentStore.NewAttachment(
                            ENTITY,
                            String.valueOf(saved.getId()),
                            d.documentType(),
                            d.reference(),
                            d.orderDate(),
                            d.issuingAuthority(),
                            d.fileName(),
                            d.content(),
                            false));
        }
        audited(context.loginId(), saved, null, saved.getStatus(), "Labour claim entered", null);
        return saved;
    }

    /**
     * SRS-11-13 (D10-7): the Payroll Maker validates the claim (entitlement, cap, overlap,
     * frequency) and submits it to the Payroll Checker.
     */
    @Transactional
    public SubmittedApproval validate(AccessContext context, long id, String reason) {
        ReimbursementClaim claim =
                claims.findById(id).orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
        ClaimCatalogue.MemberFacts member = catalogue.member(claim.getEmployeeId());
        policy.requireEmployeeRecord(
                context, SCREEN, "VALIDATE", member.employeeId(), member.locationId());
        if (!"LABOUR".equals(claim.getMemberType())
                || !ReimbursementClaim.Status.SUBMITTED.name().equals(claim.getStatus())) {
            throw new FciException(
                    AllowanceErrorCode.STATE, new Object[] {claim.getStatus()}, List.of(), null);
        }
        requireActive(member);
        ClaimRules.Period period = claim.period();
        catalogue.requireNoOverlap(claim.getEmployeeId(), claim.getClaimType(), period, id);
        ClaimCatalogue.Norm norm = catalogue.norm(claim.getClaimType(), "LABOUR", period.from());
        String before = claim.getStatus();
        claim.validated(
                norm.id(),
                catalogue.adjudicate(
                        norm,
                        claim.getEmployeeId(),
                        claim.getClaimType(),
                        period,
                        claim.getClaimedAmount(),
                        id));
        SubmittedApproval item =
                approvals.submit(
                        context,
                        new Submission(
                                        SCREEN,
                                        "VALIDATE",
                                        ENTITY,
                                        String.valueOf(id),
                                        ActionType.UPDATE,
                                        member.locationId(),
                                        "{\"claimId\":"
                                                + id
                                                + ",\"payable\":\""
                                                + claim.getPayableAmount().toPlainString()
                                                + "\",\"capped\":"
                                                + claim.isCapped()
                                                + "}",
                                        period.from(),
                                        reason(reason, claim))
                                .withImpact(claim.getPayableAmount()),
                        (OtpProof) null);
        claim.submittedFor(item.id());
        claims.save(claim);
        audited(
                context.loginId(),
                claim,
                before,
                claim.getStatus(),
                "Validated and submitted",
                item.id());
        return item;
    }

    /**
     * I-03 Employee / Labour Number (SRS l.2796, US-GAP-216; OD-62): "Active member within the
     * user's scope" - a member not in service (separated, retired, absconding, suspended) has no
     * labour claim entered or validated.
     */
    static void requireActive(ClaimCatalogue.MemberFacts member) {
        if (!ClaimRules.active(member.employmentStatus())) {
            throw new FciException(
                    AllowanceErrorCode.INACTIVE,
                    new Object[] {member.eis(), String.valueOf(member.employmentStatus())},
                    List.of(),
                    null);
        }
    }

    private static String reason(String reason, ReimbursementClaim claim) {
        String r = reason == null || reason.isBlank() ? null : reason.strip();
        String summary =
                claim.getClaimType()
                        + " "
                        + claim.getPeriodFrom()
                        + " to "
                        + claim.getPeriodTo()
                        + (claim.isCapped() ? " capped at " + claim.getPayableAmount() : "");
        return r == null ? summary : (r + " - " + summary);
    }

    /**
     * SRS-11-14, SRS-G-30 (I-03 order-copy card, A-41): the Payroll Maker adds a supporting
     * document to a labour claim not yet validated. An employee claim is received from HRMS and
     * read-only (SRS-11-1); a claim past validation stands as the Checker sees it.
     */
    @Transactional
    public DocumentStore.AttachmentView attach(AccessContext context, long id, Document d) {
        ReimbursementClaim claim =
                claims.findById(id).orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
        ClaimCatalogue.MemberFacts member = catalogue.member(claim.getEmployeeId());
        policy.requireEmployeeRecord(
                context, SCREEN, "EDIT", member.employeeId(), member.locationId());
        if (!"LABOUR".equals(claim.getMemberType())) {
            throw new FciException(AllowanceErrorCode.HRMS_ONLY);
        }
        if (!ReimbursementClaim.Status.SUBMITTED.name().equals(claim.getStatus())) {
            throw invalid("a supporting document is added before the claim is validated");
        }
        DocumentStore.AttachmentView view =
                documents.attach(
                        context.loginId(),
                        new DocumentStore.NewAttachment(
                                ENTITY,
                                String.valueOf(id),
                                d.documentType(),
                                d.reference(),
                                d.orderDate(),
                                d.issuingAuthority(),
                                d.fileName(),
                                d.content(),
                                false));
        audit.record(
                context.loginId(),
                AuditService.Entry.change(
                                ENTITY,
                                String.valueOf(id),
                                "document",
                                null,
                                d.documentType(),
                                "Supporting document added on I-03")
                        .module(SCREEN));
        return view;
    }

    private void audited(
            String actor,
            ReimbursementClaim claim,
            String before,
            String after,
            String reason,
            Long approvalId) {
        AuditService.Entry entry =
                AuditService.Entry.change(
                                ENTITY,
                                String.valueOf(claim.getId()),
                                "claim_status",
                                before,
                                after,
                                reason)
                        .module(SCREEN);
        audit.record(actor, approvalId == null ? entry : entry.approval(approvalId));
    }

    private static FciException invalid(String why) {
        return new FciException(AllowanceErrorCode.CLAIM, new Object[] {why}, List.of(), null);
    }
}
