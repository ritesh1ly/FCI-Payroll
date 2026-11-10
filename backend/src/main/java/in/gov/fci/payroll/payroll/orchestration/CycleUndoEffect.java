package in.gov.fci.payroll.payroll.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.payroll.api.UndoableTransaction;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.CriticalLog;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * SRS-15-3 / SRS-15-10, BR-CYC-006 (plan 05 phase 05e): the authorised undo, in the decision's
 * transaction. The Administrator decided with the OTP {@code UNDO_TRANSACTION} (SRS-G-21).
 *
 * <p>Checked again at the decision: still not posted to EBS (an undo is never made once posted),
 * still undoable by its module, and no other action holding the cycle (the UNDO lease is taken for
 * the transaction, SRS-15-28); a refusal fails the decision, which stays to be decided. Then the
 * owning module undoes it and the register gets an UNDO row: transaction, period, stage at undo,
 * reason, requester, the Checker who verified it (level 2), the authoriser, the approval and the
 * OTP challenge (DDL {@code chk_undo_identity}, {@code chk_undo_otp}).
 */
@Component
public class CycleUndoEffect {

    private final JdbcTemplate jdbc;
    private final CycleUndo undo;
    private final CycleLeases leases;
    private final AuditService audit;
    private final ObjectMapper json;

    public CycleUndoEffect(
            JdbcTemplate jdbc,
            CycleUndo undo,
            CycleLeases leases,
            AuditService audit,
            ObjectMapper json) {
        this.jdbc = jdbc;
        this.undo = undo;
        this.leases = leases;
        this.audit = audit;
        this.json = json;
    }

    @EventListener
    public void on(ApprovalDecided decision) {
        if (!decision.approved() || !decision.concerns(CycleUndo.UNDO_ENTITY)) {
            return;
        }
        if (decision.otpChallengeId() == null) {
            throw new IllegalStateException(
                    "Route C02_UNDO must ask the OTP at the deciding level (X-01)");
        }
        JsonNode request = json.readTree(decision.payload());
        long cycleId = request.path("cycleInstanceId").asLong();
        String entity = request.path("entity").asString();
        String key = request.path("key").asString();
        String reason = request.path("reason").asString();
        // OD-130: logged at the decision; a refusal below rolls back but the attempt stays visible.
        CriticalLog.log(
                CriticalLog.Event.UNDO_APPLIED,
                CriticalLog.fields("cycle", cycleId, "entity", entity, "key", key));
        UndoableTransaction kind =
                undo.kind(entity).orElseThrow(() -> new FciException(ApiErrorCode.CYCLE_UNDO_TYPE));
        UndoableTransaction.Target target =
                kind.find(key)
                        .filter(t -> t.cycleInstanceId() == cycleId)
                        .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
        String posted =
                jdbc.queryForObject(
                        "SELECT posted_to_ebs_flag FROM payroll_cycle_instance WHERE"
                                + " cycle_instance_id = ?",
                        String.class,
                        cycleId);
        if ("Y".equals(posted)) {
            throw new FciException(ApiErrorCode.CYCLE_POSTED);
        }
        String authoriser = truncate(decision.checkerLoginId());
        leases.acquire(cycleId, CycleLeases.Action.UNDO, authoriser, null);
        kind.undo(key, reason, decision.checkerLoginId(), decision.approvalId());
        String requestedBy =
                jdbc.queryForObject(
                        "SELECT submitted_by FROM approval_queue WHERE approval_id = ?",
                        String.class,
                        decision.approvalId());
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
        jdbc.update(
                "INSERT INTO undo_reversal_register (cycle_instance_id, register_type,"
                        + " transaction_entity, transaction_key, payroll_yymm, stage_at_undo,"
                        + " reason, requested_by, checked_by, authorised_by, approval_id,"
                        + " otp_challenge_id) VALUES (?, 'UNDO', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                cycleId,
                entity,
                key,
                request.path("period").asString().replace("-", ""),
                target.stage(),
                reason,
                requestedBy,
                checkedBy,
                authoriser,
                decision.approvalId(),
                decision.otpChallengeId());
        audit.record(
                decision.checkerLoginId(),
                AuditService.Entry.change(
                                CycleUndo.UNDO_ENTITY,
                                entity + ":" + key,
                                "undo",
                                target.stage(),
                                "UNDONE",
                                reason)
                        .approval(decision.approvalId())
                        .module("C-02"));
        leases.release(cycleId, authoriser);
    }

    private static String truncate(String login) {
        return login.length() <= 50 ? login : login.substring(0, 50);
    }
}
