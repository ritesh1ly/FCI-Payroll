package in.gov.fci.payroll.payroll.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.payroll.business.CycleKeys;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import java.sql.Timestamp;
import java.time.Clock;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * SRS-15-35 (plan 05 phase 05h): the approved resumption of a payment-release suspension, in the
 * decision's transaction: the suspension is closed with who resumed it (the approving Payroll
 * Checker), when, why and the approval (DDL {@code chk_suspension_resume}), and audited. A
 * suspension resumed meanwhile is left as it is.
 */
@Component
public class ReleaseSuspensionEffect {

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final Clock clock;
    private final ObjectMapper json;

    public ReleaseSuspensionEffect(
            JdbcTemplate jdbc, AuditService audit, Clock clock, ObjectMapper json) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
        this.json = json;
    }

    @EventListener
    public void on(ApprovalDecided decision) {
        if (!decision.approved() || !decision.concerns(ReleaseSuspensionService.ENTITY)) {
            return;
        }
        JsonNode request = json.readTree(decision.payload());
        long id = request.path("suspensionId").asLong();
        String reason = request.path("reason").asString();
        String checker = CycleKeys.truncate(decision.checkerLoginId());
        int resumed =
                jdbc.update(
                        "UPDATE disb_release_suspension SET resumed_by = ?, resumed_at = ?,"
                                + " resume_reason = ?, resume_approval_id = ? WHERE suspension_id ="
                                + " ? AND resumed_at IS NULL",
                        checker,
                        Timestamp.from(clock.instant()),
                        reason,
                        decision.approvalId(),
                        id);
        audit.record(
                decision.checkerLoginId(),
                AuditService.Entry.change(
                                ReleaseSuspensionService.ENTITY,
                                String.valueOf(id),
                                "resumption",
                                "SUSPENDED",
                                resumed == 1 ? "RESUMED" : "ALREADY_RESUMED",
                                reason)
                        .approval(decision.approvalId())
                        .module("O-02"));
    }
}
