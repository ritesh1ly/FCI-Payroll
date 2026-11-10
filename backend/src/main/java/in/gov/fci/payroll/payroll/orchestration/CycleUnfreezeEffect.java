package in.gov.fci.payroll.payroll.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.payroll.business.CycleCorrectionRules;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import java.util.List;
import java.util.Map;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * SRS-15-31 / BR-CYC-017: the approved un-freeze of a payroll cycle (route {@code C02_UNFREEZE},
 * plan 05 D-8), in the decision's transaction. The last level decided with the OTP {@code
 * CYCLE_UNFREEZE_STUCK_RELEASE} (SRS-G-21).
 *
 * <p>The state is checked again at the decision: never after lock (SRS-15-10), never during a run
 * (the UNFREEZE lease is taken for the transaction, SRS-15-28); a refusal fails the decision, which
 * stays to be decided again. On approval: a {@code cycle_unfreeze} row (from status, requester,
 * checker = the level-2 decider, never absent nor the authoriser, BMAD TR-008; authoriser,
 * approval, OTP challenge, invalidated run); the run of record and its results SUPERSEDED (never
 * deleted); the cycle INITIATED with its freeze and verification cleared, so the next computation
 * needs a new freeze ({@code trg_cycle_status_forward_only} admits the regression for the un-freeze
 * row just written). No consolidated parent run exists yet, so {@code parent_instance_invalidated}
 * stays empty.
 */
@Component
public class CycleUnfreezeEffect {

    private final JdbcTemplate jdbc;
    private final CycleLeases leases;
    private final AuditService audit;
    private final ObjectMapper json;

    public CycleUnfreezeEffect(
            JdbcTemplate jdbc, CycleLeases leases, AuditService audit, ObjectMapper json) {
        this.jdbc = jdbc;
        this.leases = leases;
        this.audit = audit;
        this.json = json;
    }

    @EventListener
    public void on(ApprovalDecided decision) {
        if (!decision.approved() || !decision.concerns(CycleCorrections.UNFREEZE_ENTITY)) {
            return;
        }
        if (decision.otpChallengeId() == null) {
            throw new IllegalStateException(
                    "Route C02_UNFREEZE must ask the OTP at the deciding level (X-01)");
        }
        JsonNode request = json.readTree(decision.payload());
        long cycleId = request.path("cycleInstanceId").asLong();
        String reason = request.path("reason").asString();
        Map<String, Object> cycle =
                jdbc.queryForMap(
                        "SELECT cycle_status, current_run_id, org_unit_id, payroll_yymm FROM"
                                + " payroll_cycle_instance WHERE cycle_instance_id = ?",
                        cycleId);
        String status = (String) cycle.get("CYCLE_STATUS");
        if (!CycleCorrectionRules.unfreezable(status)) {
            throw new FciException(
                    ApiErrorCode.CYCLE_STATE, new Object[] {status}, List.of(), null);
        }
        String checker = truncate(decision.checkerLoginId());
        leases.acquire(cycleId, CycleLeases.Action.UNFREEZE, checker, null);
        String requestedBy =
                jdbc.queryForObject(
                        "SELECT submitted_by FROM approval_queue WHERE approval_id = ?",
                        String.class,
                        decision.approvalId());
        // The Payroll Checker who checked it at level 2.
        String checkedBy =
                jdbc
                        .queryForList(
                                "SELECT decided_by FROM approval_decision WHERE approval_id = ? AND"
                                        + " level_no = 2 AND decision = 'APPROVE' ORDER BY"
                                        + " decided_at DESC",
                                String.class,
                                decision.approvalId())
                        .stream()
                        .findFirst()
                        .orElse(null);
        // SRS-15-31 / SRS-15-10 (BMAD TR-008): the Maker requests, the Payroll Checker checks, the
        // configured authority approves with the OTP - three people. A route without the Checker's
        // check (a two-level route, whose level-2 decider is the authority itself) is refused at
        // the
        // decision; the item stays to be decided once the route is corrected on A-08.
        if (checkedBy == null || checkedBy.equals(decision.checkerLoginId())) {
            throw new FciException(ApiErrorCode.CYCLE_UNFREEZE_UNCHECKED);
        }
        Long run =
                cycle.get("CURRENT_RUN_ID") == null
                        ? null
                        : ((Number) cycle.get("CURRENT_RUN_ID")).longValue();
        // executed_at from the database clock: the status trigger compares it with its own clock.
        jdbc.update(
                "INSERT INTO cycle_unfreeze (cycle_instance_id, from_status, reason, requested_by,"
                        + " checked_by, authorised_by, approval_id, otp_challenge_id,"
                        + " invalidated_run_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                cycleId,
                status,
                reason,
                requestedBy,
                checkedBy,
                checker,
                decision.approvalId(),
                decision.otpChallengeId(),
                run);
        if (run != null) {
            jdbc.update(
                    "UPDATE computation_run SET run_status = 'SUPERSEDED', row_version ="
                            + " row_version + 1 WHERE run_id = ? AND run_status = 'COMPLETE'",
                    run);
            jdbc.update(
                    "UPDATE payroll_result SET result_status = 'SUPERSEDED' WHERE run_id = ? AND"
                            + " result_status = 'CURRENT' AND is_locked = 'N'",
                    run);
        }
        int moved =
                jdbc.update(
                        "UPDATE payroll_cycle_instance SET cycle_status = 'INITIATED', frozen_by ="
                                + " NULL, frozen_at = NULL, freeze_otp_challenge_id = NULL,"
                                + " verified_by = NULL, verified_at = NULL, current_run_id = NULL,"
                                + " row_version = row_version + 1 WHERE cycle_instance_id = ? AND"
                                + " cycle_status = ?",
                        cycleId,
                        status);
        if (moved != 1) {
            throw new FciException(
                    ApiErrorCode.CYCLE_STATE, new Object[] {status}, List.of(), null);
        }
        audit.record(
                decision.checkerLoginId(),
                AuditService.Entry.change(
                                CycleService.ENTITY,
                                request.path("unit").asString()
                                        + "|"
                                        + request.path("period").asString(),
                                "cycle_status",
                                status,
                                "INITIATED",
                                "Un-freeze approved (approval "
                                        + decision.approvalId()
                                        + "): "
                                        + reason)
                        .approval(decision.approvalId())
                        .module("C-02"));
        leases.release(cycleId, checker);
    }

    private static String truncate(String login) {
        return login.length() <= 50 ? login : login.substring(0, 50);
    }
}
