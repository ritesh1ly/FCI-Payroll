package in.gov.fci.payroll.allowance.orchestration;

import in.gov.fci.payroll.allowance.business.model.ReimbursementClaim;
import in.gov.fci.payroll.allowance.persistence.AllowanceRepositories;
import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.payroll.api.CycleLocked;
import java.util.List;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * SRS-11-12 (plan 10 D10-6): when a staff cycle is locked, every approved employee claim the locked
 * run paid becomes PAID, in the lock's transaction: the member's CURRENT Actual result of the cycle
 * was computed after the claim's approval and carries the claim type's pay code. A claim the run
 * did not pay (approved later, or held for its pay code) stays APPROVED for the next open cycle.
 */
@Component
public class ClaimLockEffect {

    private final JdbcTemplate jdbc;
    private final AllowanceRepositories.Claims claims;
    private final AuditService audit;

    public ClaimLockEffect(
            JdbcTemplate jdbc, AllowanceRepositories.Claims claims, AuditService audit) {
        this.jdbc = jdbc;
        this.claims = claims;
        this.audit = audit;
    }

    @EventListener
    public void on(CycleLocked locked) {
        List<Long> paid =
                jdbc.queryForList(
                        "SELECT c.claim_id FROM reimbursement_claim c JOIN approval_queue a ON"
                                + " a.approval_id = c.approval_id JOIN payroll_result r ON"
                                + " r.employee_id = c.employee_id AND r.cycle_instance_id = ? AND"
                                + " r.run_type = 'ACTUAL' AND r.result_status = 'CURRENT' JOIN"
                                + " reference_list l ON l.list_code = 'CLAIM_TYPE' JOIN"
                                + " reference_value v ON v.list_id = l.list_id AND v.value_code ="
                                + " c.claim_type WHERE c.claim_status = 'APPROVED' AND"
                                + " c.member_type = 'EMPLOYEE' AND a.decided_at <= r.computed_at"
                                + " AND EXISTS (SELECT 1 FROM payroll_result_component rc JOIN"
                                + " pay_component p ON p.component_id = rc.component_id WHERE"
                                + " rc.payroll_result_id = r.payroll_result_id AND"
                                + " p.legacy_pay_code = v.numeric_attribute) ORDER BY c.claim_id",
                        Long.class,
                        locked.cycleInstanceId());
        for (Long id : paid) {
            ReimbursementClaim claim = claims.findById(id).orElseThrow();
            String before = claim.getStatus();
            claim.paid();
            claims.save(claim);
            audit.record(
                    locked.lockedBy(),
                    AuditService.Entry.change(
                                    LabourClaimService.ENTITY,
                                    String.valueOf(id),
                                    "claim_status",
                                    before,
                                    claim.getStatus(),
                                    "Paid in the payroll of " + locked.payrollYymm() + " (locked)")
                            .module(LabourClaimService.SCREEN));
        }
    }
}
