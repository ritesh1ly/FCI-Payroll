package in.gov.fci.payroll.allowance.orchestration;

import in.gov.fci.payroll.allowance.business.AllowanceErrorCode;
import in.gov.fci.payroll.allowance.business.model.ReimbursementClaim;
import in.gov.fci.payroll.allowance.persistence.AllowanceRepositories;
import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.platform.approval.ActionType;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
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
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The Payroll Maker's maintenance of an employee medical claim on I-03 (plan 10 D10-9, D10-10): the
 * taxable flag confirmed before the medical invoice (SRS-11-19) and the IOGA received from another
 * District Office (SRS-11-21), the latter approved by the Payroll Checker (route I03_EDIT).
 */
@Service
@Loggable
public class ClaimMaintenance {

    static final String IOGA = "reimbursement_claim_ioga";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Set<String> PAID_OR_APPROVED =
            Set.of(
                    ReimbursementClaim.Status.APPROVED.name(),
                    ReimbursementClaim.Status.PAID.name());

    private final AllowanceRepositories.Claims claims;
    private final ClaimQueryService queries;
    private final ClaimCatalogue catalogue;
    private final MakerChecker approvals;
    private final Authorization policy;
    private final AuditService audit;
    private final ObjectMapper json;
    private final Clock clock;

    @SuppressWarnings("java:S107") // one collaborator per concern, as the other claim services
    public ClaimMaintenance(
            AllowanceRepositories.Claims claims,
            ClaimQueryService queries,
            ClaimCatalogue catalogue,
            MakerChecker approvals,
            Authorization policy,
            AuditService audit,
            ObjectMapper json,
            Clock clock) {
        this.claims = claims;
        this.queries = queries;
        this.catalogue = catalogue;
        this.approvals = approvals;
        this.policy = policy;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    /** SRS-11-19: the Maker confirms the taxable flag of an approved medical claim. */
    @Transactional
    public ClaimQueryService.ClaimRow confirmTaxable(
            AccessContext context, long id, boolean taxable) {
        ReimbursementClaim claim = medicalClaim(context, id);
        String before = claim.getTaxable() == null ? null : claim.getTaxable() ? "Y" : "N";
        claim.taxableConfirmed(taxable, clock.instant());
        claims.save(claim);
        claims.flush(); // the row is read back through JDBC below
        audit.record(
                context.loginId(),
                AuditService.Entry.change(
                                LabourClaimService.ENTITY,
                                String.valueOf(id),
                                "taxable_flag",
                                before,
                                taxable ? "Y" : "N",
                                "Taxable flag confirmed before the medical invoice (SRS-11-19)")
                        .module(LabourClaimService.SCREEN));
        return queries.one(context, id);
    }

    /** SRS-11-21: the IOGA of a medical claim, for the Payroll Checker's approval. */
    @Transactional
    public SubmittedApproval recordIoga(
            AccessContext context,
            long id,
            String number,
            LocalDate date,
            BigDecimal amount,
            String originatingOffice,
            String reason) {
        ReimbursementClaim claim = medicalClaim(context, id);
        if (amount == null || amount.signum() <= 0 || amount.stripTrailingZeros().scale() > 2) {
            throw new FciException(
                    AllowanceErrorCode.CLAIM,
                    new Object[] {"the IOGA amount is positive, at most two decimals"},
                    List.of(),
                    null);
        }
        if (date == null || date.isAfter(LocalDate.now(clock.withZone(IST)))) {
            throw new FciException(
                    AllowanceErrorCode.CLAIM,
                    new Object[] {"the IOGA date is required and not in the future"},
                    List.of(),
                    null);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("claimId", id);
        payload.put("number", number);
        payload.put("date", date.toString());
        payload.put("amount", amount.toPlainString());
        // DBO-10-1: no column for the originating office; it is kept in the payload and the audit.
        payload.put("originatingOffice", originatingOffice);
        Long location = catalogue.member(claim.getEmployeeId()).locationId();
        return approvals.submit(
                context,
                new Submission(
                        LabourClaimService.SCREEN,
                        "EDIT",
                        IOGA,
                        String.valueOf(id),
                        ActionType.UPDATE,
                        location,
                        json.writeValueAsString(payload),
                        date,
                        reason == null || reason.isBlank()
                                ? "IOGA " + number + " from " + originatingOffice
                                : reason.strip()),
                (OtpProof) null);
    }

    /** The Checker's decision on an IOGA record, in the decision's transaction. */
    @EventListener
    public void on(ApprovalDecided decision) {
        if (!decision.concerns(IOGA) || !decision.approved()) {
            return;
        }
        JsonNode p = json.readTree(decision.payload());
        ReimbursementClaim claim = claims.findById(p.path("claimId").asLong()).orElseThrow();
        String before = claim.getIogaNumber();
        claim.ioga(
                p.path("number").asString(),
                LocalDate.parse(p.path("date").asString()),
                new BigDecimal(p.path("amount").asString()));
        claims.save(claim);
        audit.record(
                decision.checkerLoginId(),
                AuditService.Entry.change(
                                LabourClaimService.ENTITY,
                                String.valueOf(claim.getId()),
                                "ioga_number",
                                before,
                                claim.getIogaNumber(),
                                "IOGA from " + p.path("originatingOffice").asString() + " approved")
                        .approval(decision.approvalId())
                        .module(LabourClaimService.SCREEN));
    }

    /** An approved or paid employee medical claim in scope, with the Maker's EDIT right. */
    private ReimbursementClaim medicalClaim(AccessContext context, long id) {
        queries.one(context, id);
        ReimbursementClaim claim =
                claims.findById(id).orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
        ClaimCatalogue.MemberFacts member = catalogue.member(claim.getEmployeeId());
        policy.requireEmployeeRecord(
                context,
                LabourClaimService.SCREEN,
                "EDIT",
                member.employeeId(),
                member.locationId());
        if (!"EMPLOYEE".equals(claim.getMemberType())
                || !EmployeeClaimEffects.medical(claim.getClaimType())
                || !PAID_OR_APPROVED.contains(claim.getStatus())) {
            throw new FciException(
                    AllowanceErrorCode.STATE, new Object[] {claim.getStatus()}, List.of(), null);
        }
        return claim;
    }
}
