package in.gov.fci.payroll.payroll.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import java.math.BigDecimal;
import java.util.Map;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * I-19 decisions (plan 05 phase 05g; SRS-15-19, SRS-G-1, SRS-G-33, SRS-G-37), in the decision's
 * transaction.
 *
 * <ul>
 *   <li>Approved: a capture or a corrected returned entry becomes APPROVED; an amendment of an
 *       approved entry cancels it and records a new APPROVED entry with the new amount (the
 *       original is never edited in place); a cancellation makes the entry CANCELLED.
 *   <li>Rejected: an entry not yet approved is CANCELLED (a rejection is final, SRS-G-33); a
 *       rejected amendment or cancellation of an approved entry leaves it as it was.
 *   <li>Returned: nothing changes; the Maker corrects or cancels the entry.
 * </ul>
 *
 * Every change is audited with its old and new value.
 */
@Component
public class DirectPayrollEffect {

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final ObjectMapper json;

    public DirectPayrollEffect(JdbcTemplate jdbc, AuditService audit, ObjectMapper json) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.json = json;
    }

    @EventListener
    public void on(ApprovalDecided decision) {
        if (!decision.concerns(DirectPayrollService.ENTITY)
                || decision.outcome() == ApprovalStatus.RETURNED) {
            return;
        }
        JsonNode request = json.readTree(decision.payload());
        long mdpId = request.path("mdpId").asLong();
        String op = request.path("op").asString();
        String reason = request.path("reason").asString();
        Map<String, Object> entry =
                jdbc.queryForMap("SELECT * FROM mdp_transaction WHERE mdp_id = ?", mdpId);
        String status = (String) entry.get("MDP_STATUS");
        boolean approvedBefore = "APPROVED".equals(status);
        if (!decision.approved()) {
            if (decision.outcome() == ApprovalStatus.REJECTED && !approvedBefore) {
                move(decision, mdpId, status, "CANCELLED", reason);
            }
            return;
        }
        switch (op) {
            case "CANCEL" -> move(decision, mdpId, status, "CANCELLED", reason);
            case "AMEND" -> {
                if (approvedBefore) {
                    amendApproved(
                            decision,
                            entry,
                            new BigDecimal(request.path("amount").asString()),
                            reason);
                } else {
                    move(decision, mdpId, status, "APPROVED", reason);
                }
            }
            default -> move(decision, mdpId, status, "APPROVED", reason);
        }
    }

    /** SRS-G-37: the approved entry is cancelled and the amended one recorded as a new entry. */
    private void amendApproved(
            ApprovalDecided decision,
            Map<String, Object> original,
            BigDecimal amount,
            String reason) {
        long mdpId = ((Number) original.get("MDP_ID")).longValue();
        move(decision, mdpId, "APPROVED", "CANCELLED", "Amended: " + reason);
        String submittedBy =
                jdbc.queryForObject(
                        "SELECT submitted_by FROM approval_queue WHERE approval_id = ?",
                        String.class,
                        decision.approvalId());
        jdbc.update(
                "INSERT INTO mdp_transaction (cycle_instance_id, employee_id, component_id, amount,"
                        + " mdp_status, reason, approval_id, entered_by) VALUES (?, ?, ?, ?,"
                        + " 'APPROVED', ?, ?, ?)",
                original.get("CYCLE_INSTANCE_ID"),
                original.get("EMPLOYEE_ID"),
                original.get("COMPONENT_ID"),
                amount,
                "Amends MDP " + mdpId + ": " + reason,
                decision.approvalId(),
                submittedBy);
        audit.record(
                decision.checkerLoginId(),
                AuditService.Entry.change(
                                DirectPayrollService.ENTITY,
                                String.valueOf(mdpId),
                                "amount",
                                ((BigDecimal) original.get("AMOUNT")).toPlainString(),
                                amount.toPlainString(),
                                reason)
                        .approval(decision.approvalId())
                        .module("I-19"));
    }

    private void move(ApprovalDecided decision, long mdpId, String from, String to, String reason) {
        jdbc.update(
                "UPDATE mdp_transaction SET mdp_status = ? WHERE mdp_id = ? AND mdp_status = ?",
                to,
                mdpId,
                from);
        audit.record(
                decision.checkerLoginId(),
                AuditService.Entry.change(
                                DirectPayrollService.ENTITY,
                                String.valueOf(mdpId),
                                "mdp_status",
                                from,
                                to,
                                reason)
                        .approval(decision.approvalId())
                        .module("I-19"));
    }
}
