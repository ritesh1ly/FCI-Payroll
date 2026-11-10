package in.gov.fci.payroll.allowance.orchestration;

import in.gov.fci.payroll.allowance.persistence.AllowanceRepositories;
import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The Payroll Checker's decision on a labour claim (plan 10 D10-7; SRS-11-13, I-03 use case
 * alternate flows), in the decision's transaction: approved, the claim is APPROVED (paid by the
 * labour engines, modules 20-22, through their port); returned, it goes back to the Maker
 * (SUBMITTED) with the reason; rejected, it is REJECTED and the reason shown on I-03.
 */
@Component
public class LabourClaimEffect {

    private final AllowanceRepositories.Claims claims;
    private final AuditService audit;

    public LabourClaimEffect(AllowanceRepositories.Claims claims, AuditService audit) {
        this.claims = claims;
        this.audit = audit;
    }

    @EventListener
    public void on(ApprovalDecided decision) {
        if (!decision.concerns(LabourClaimService.ENTITY)) {
            return;
        }
        claims.findByApprovalId(decision.approvalId())
                .ifPresent(
                        claim -> {
                            String before = claim.getStatus();
                            if (decision.approved()) {
                                claim.approved();
                            } else if (decision.outcome() == ApprovalStatus.RETURNED) {
                                claim.returned();
                            } else {
                                claim.rejected();
                            }
                            claims.save(claim);
                            audit.record(
                                    decision.checkerLoginId(),
                                    AuditService.Entry.change(
                                                    LabourClaimService.ENTITY,
                                                    String.valueOf(claim.getId()),
                                                    "claim_status",
                                                    before,
                                                    claim.getStatus(),
                                                    decision.reason() == null
                                                            ? "Approved"
                                                            : decision.reason())
                                            .approval(decision.approvalId())
                                            .module(LabourClaimService.SCREEN));
                        });
    }
}
