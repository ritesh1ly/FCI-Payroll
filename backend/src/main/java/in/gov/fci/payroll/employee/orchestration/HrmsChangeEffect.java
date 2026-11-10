package in.gov.fci.payroll.employee.orchestration;

import in.gov.fci.payroll.arrear.api.RetroEvents;
import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalDeciding;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The final decision on an I-21 task or on a bulk order of tasks (plan 16 phases 16a / 16b;
 * SRS-1-28, SRS-1-29, SRS-1-23, SRS-1-61, SRS-1-67), in the decision's transaction.
 *
 * <ul>
 *   <li>Approved: the value is applied from its effective date ({@link HrmsChangeApplier}); the
 *       task records the Checker (level 2) and, on the HQ route, the HQ Establishment approver
 *       (level 3) and the OTP challenge. An approved order applies every task linked to it, all or
 *       none.
 *   <li>Rejected: the old value stays; the task is returned to HRMS with the reason (the interface
 *       carries it there, module 23).
 *   <li>Returned: back to the Maker with the reason; a returned order is open again.
 * </ul>
 */
@Component
public class HrmsChangeEffect {

    private static final String SUPERSEDED = "SUPERSEDED";

    private final JdbcTemplate jdbc;
    private final HrmsChangeApplier applier;
    private final HrmsEventApplier events;
    private final AuditService audit;
    private final RetroEvents retro;
    private final Clock clock;

    public HrmsChangeEffect(
            JdbcTemplate jdbc,
            HrmsChangeApplier applier,
            HrmsEventApplier events,
            AuditService audit,
            RetroEvents retro,
            Clock clock) {
        this.jdbc = jdbc;
        this.applier = applier;
        this.events = events;
        this.audit = audit;
        this.retro = retro;
        this.clock = clock;
    }

    @EventListener
    public void on(ApprovalDecided decision) {
        if (decision.concerns(HrmsChangeService.ENTITY)) {
            decide(Long.parseLong(decision.entityKey()), decision);
        } else if (decision.concerns(HrmsOrderService.ENTITY)) {
            long orderId = Long.parseLong(decision.entityKey());
            for (Long taskId :
                    jdbc.queryForList(
                            "SELECT hrms_change_task_id FROM hrms_change_task WHERE task_order_id ="
                                    + " ? AND task_status = 'PENDING_CHECKER' ORDER BY"
                                    + " hrms_change_task_id",
                            Long.class,
                            orderId)) {
                decide(taskId, decision);
            }
            String status =
                    decision.approved()
                            ? "APPROVED"
                            : decision.outcome() == ApprovalStatus.RETURNED ? "OPEN" : "REJECTED";
            jdbc.update(
                    "UPDATE hrms_change_task_order SET order_status = ?, approved_by = ?,"
                        + " approved_at = ?, otp_challenge_id = ?, row_version = row_version + 1"
                        + " WHERE task_order_id = ?",
                    status,
                    decision.approved() ? decider(decision.approvalId(), 2) : null,
                    decision.approved() ? Timestamp.from(decision.decidedAt()) : null,
                    decision.approved() ? decision.otpChallengeId() : null,
                    orderId);
        }
    }

    /**
     * Wave C review (SRS-9-23, SRS-1-28): a task a newer HRMS record superseded while it was with
     * the Checker is never approved (HRC-409-SUPERSEDED, before any OTP is asked); the Checker
     * rejects or returns it, which leaves it SUPERSEDED.
     */
    @EventListener
    public void on(ApprovalDeciding deciding) {
        if (deciding.concerns(HrmsOrderService.ENTITY)) {
            // Wave D review 2: each task the order would apply passes its owner's precondition.
            for (Long taskId :
                    jdbc.queryForList(
                            "SELECT hrms_change_task_id FROM hrms_change_task WHERE task_order_id ="
                                    + " ? AND task_status = 'PENDING_CHECKER' ORDER BY"
                                    + " hrms_change_task_id",
                            Long.class,
                            Long.parseLong(deciding.entityKey()))) {
                beforeApproval(taskId);
            }
            return;
        }
        if (!deciding.concerns(HrmsChangeService.ENTITY)) {
            return;
        }
        long id = Long.parseLong(deciding.entityKey());
        List<Map<String, Object>> task =
                jdbc.queryForList(
                        "SELECT task_no, task_status FROM hrms_change_task WHERE"
                                + " hrms_change_task_id = ?",
                        id);
        if (task.isEmpty()) {
            return;
        }
        if (SUPERSEDED.equals(task.getFirst().get("TASK_STATUS"))) {
            throw superseded((String) task.getFirst().get("TASK_NO"));
        }
        beforeApproval(id);
    }

    /**
     * Wave D review 2: another module's precondition (a stale NPS record) refuses the approval
     * here, before the OTP, not after it in the decision.
     */
    private void beforeApproval(long id) {
        Map<String, Object> row =
                jdbc.queryForMap(
                        "SELECT t.task_no, t.employee_id, t.new_value, t.effective_date,"
                                + " t.hrms_request_id, f.field_code FROM hrms_change_task t JOIN"
                                + " hrms_pay_effect_field f ON f.pay_effect_field_id ="
                                + " t.pay_effect_field_id WHERE t.hrms_change_task_id = ?",
                        id);
        String code = (String) row.get("FIELD_CODE");
        applier.effectFor(code)
                .ifPresent(
                        owner ->
                                owner.beforeApproval(
                                        new in.gov.fci.payroll.employee.api.HrmsTaskEffects.Task(
                                                id,
                                                (String) row.get("TASK_NO"),
                                                ((Number) row.get("EMPLOYEE_ID")).longValue(),
                                                code,
                                                (String) row.get("NEW_VALUE"),
                                                HrmsChangeService.date(row.get("EFFECTIVE_DATE")),
                                                (String) row.get("HRMS_REQUEST_ID"))));
    }

    /**
     * One task's outcome: applied and approved, rejected, or returned to the Maker.
     *
     * <p>Wave D review 1 (SRS-1-28, SRS-9-23): the task row is locked for the decision, and every
     * status change is guarded on the status read, so an HRMS record that supersedes the task
     * concurrently either waits for this decision or wins it: an approval of a task found
     * superseded (or changed under it) is refused with HRC-409-SUPERSEDED and the whole decision
     * rolls back, nothing applied.
     */
    private void decide(long id, ApprovalDecided decision) {
        Map<String, Object> task =
                jdbc.queryForMap(
                        "SELECT * FROM hrms_change_task WHERE hrms_change_task_id = ? FOR UPDATE",
                        id);
        String taskNo = (String) task.get("TASK_NO");
        String before = (String) task.get("TASK_STATUS");
        Timestamp at = Timestamp.from(decision.decidedAt());
        String checker = decider(decision.approvalId(), 2);
        String reason =
                decision.reason() == null || decision.reason().isBlank() ? null : decision.reason();
        if (SUPERSEDED.equals(before) && decision.approved()) {
            throw superseded(taskNo);
        }
        if (SUPERSEDED.equals(before)) {
            // Wave C review: a superseded task stays superseded; the decision is audited only.
            audit.record(
                    decision.checkerLoginId(),
                    AuditService.Entry.change(
                                    HrmsChangeService.ENTITY,
                                    taskNo,
                                    "task_status",
                                    before,
                                    before,
                                    "Decision "
                                            + decision.outcome().name()
                                            + " on a superseded task: no effect")
                            .approval(decision.approvalId())
                            .module(HrmsChangeService.SCREEN));
            return;
        }
        String after;
        if (decision.outcome() == ApprovalStatus.RETURNED) {
            after = "RETURNED_TO_MAKER";
            guarded(
                    jdbc.update(
                            "UPDATE hrms_change_task SET task_status = 'RETURNED_TO_MAKER',"
                                    + " return_reason = ?, row_version = row_version + 1 WHERE"
                                    + " hrms_change_task_id = ? AND task_status = ?",
                            reason == null ? "Returned to the Maker" : reason,
                            id,
                            before),
                    taskNo);
        } else if (decision.approved()) {
            after = "APPROVED";
            long employeeId = ((Number) task.get("EMPLOYEE_ID")).longValue();
            Map<String, Object> field =
                    jdbc.queryForMap(
                            "SELECT * FROM hrms_pay_effect_field WHERE pay_effect_field_id = ?",
                            task.get("PAY_EFFECT_FIELD_ID"));
            String code = (String) field.get("FIELD_CODE");
            java.util.Optional<in.gov.fci.payroll.employee.api.HrmsTaskEffects> owner =
                    applier.effectFor(code);
            if (HrmsEventApplier.handles(code)) {
                // Plan 16 phase 16f-1 (SRS-1-63): the event takes effect as an approved I-17 event.
                events.apply(task, code, decision);
            } else if (owner.isPresent()) {
                // Plan 09 phase 09c (SRS-12-19): another module's field, applied by that module.
                Object effective = task.get("EFFECTIVE_DATE");
                owner.get()
                        .apply(
                                new in.gov.fci.payroll.employee.api.HrmsTaskEffects.Task(
                                        id,
                                        taskNo,
                                        employeeId,
                                        code,
                                        (String) task.get("NEW_VALUE"),
                                        HrmsChangeService.date(effective),
                                        (String) task.get("HRMS_REQUEST_ID")),
                                decision.checkerLoginId(),
                                decision.approvalId(),
                                decision.decidedAt());
            } else {
                applier.apply(
                        field,
                        employeeId,
                        (String) task.get("NEW_VALUE"),
                        decision.checkerLoginId(),
                        decision.approvalId());
                LocalDate effective = HrmsChangeService.date(task.get("EFFECTIVE_DATE"));
                LocalDate month = LocalDate.now(clock.withZone(ZoneId.of("Asia/Kolkata")));
                if (effective != null && effective.isBefore(month.withDayOfMonth(1))) {
                    // SRS-21-11 / SRS-8-13 (plan 13 phase 13b): an approved pay-effect change
                    // with an earlier effective date is settled by the one retrospective
                    // mechanism; the locked months are never changed.
                    retro.raise(
                            new RetroEvents.Raise(
                                    employeeId,
                                    effective,
                                    "OTHER",
                                    "HRMS_CHANGE",
                                    HrmsChangeService.ENTITY,
                                    taskNo,
                                    "Back-dated HRMS change " + code + " from " + effective,
                                    decision.checkerLoginId()));
                }
            }
            String hq =
                    "Y".equals(task.get("RETIREMENT_DATE_PAST"))
                            ? decider(decision.approvalId(), 3)
                            : null;
            guarded(
                    jdbc.update(
                            "UPDATE hrms_change_task SET task_status = 'APPROVED', checker_user ="
                                    + " ?, checker_decided_at = ?, hq_estab_user = ?,"
                                    + " hq_estab_decided_at = ?, otp_challenge_id = ?, applied_at ="
                                    + " ?, row_version = row_version + 1 WHERE hrms_change_task_id"
                                    + " = ? AND task_status = ?",
                            checker,
                            at,
                            hq,
                            hq == null ? null : at,
                            decision.otpChallengeId(),
                            at,
                            id,
                            before),
                    taskNo);
        } else {
            after = "REJECTED_RETURNED_TO_HRMS";
            guarded(
                    jdbc.update(
                            "UPDATE hrms_change_task SET task_status = 'REJECTED_RETURNED_TO_HRMS',"
                                    + " rejection_reason = ?, checker_user = ?, checker_decided_at"
                                    + " = ?, returned_to_hrms_at = ?, row_version = row_version + 1"
                                    + " WHERE hrms_change_task_id = ? AND task_status = ?",
                            reason == null ? "Rejected" : reason,
                            checker,
                            at,
                            at,
                            id,
                            before),
                    taskNo);
        }
        audit.record(
                decision.checkerLoginId(),
                AuditService.Entry.change(
                                HrmsChangeService.ENTITY,
                                taskNo,
                                "task_status",
                                before,
                                after,
                                reason == null ? after : reason)
                        .approval(decision.approvalId())
                        .module(HrmsChangeService.SCREEN));
    }

    /** Wave D review 1: no row changed means the task moved under the decision: refused. */
    private static void guarded(int updated, String taskNo) {
        if (updated == 0) {
            throw superseded(taskNo);
        }
    }

    private static FciException superseded(String taskNo) {
        return new FciException(
                ApiErrorCode.I21_SUPERSEDED, new Object[] {taskNo}, List.of(), null);
    }

    /** The user who decided the level (the latest decision of it). */
    private String decider(Long approvalId, int level) {
        List<String> by =
                jdbc.queryForList(
                        "SELECT decided_by FROM approval_decision WHERE approval_id = ? AND"
                                + " level_no = ? ORDER BY decision_id DESC",
                        String.class,
                        approvalId,
                        level);
        return by.isEmpty() ? null : by.get(0);
    }
}
