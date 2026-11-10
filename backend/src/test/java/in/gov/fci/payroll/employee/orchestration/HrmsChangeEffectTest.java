package in.gov.fci.payroll.employee.orchestration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.arrear.api.RetroEvents;
import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import java.sql.Date;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The I-21 Checker's decision on a task (plan 16; SRS-1-28, SRS-1-30; plan 13 phase 13b, SRS-21-11,
 * SRS-8-13): approved, the value is applied and a change effective before this month raises the one
 * retro event (a current or undated one raises none); returned, it goes back to the Maker with a
 * default reason; rejected, it goes back to HRMS with one.
 */
class HrmsChangeEffectTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final HrmsChangeApplier applier = mock(HrmsChangeApplier.class);
    private final RetroEvents retro = mock(RetroEvents.class);
    private final HrmsChangeEffect effect =
            new HrmsChangeEffect(
                    jdbc,
                    applier,
                    mock(HrmsEventApplier.class),
                    mock(AuditService.class),
                    retro,
                    Clock.fixed(Instant.parse("2026-10-27T05:00:00Z"), ZoneOffset.UTC));

    private void task(LocalDate effective) {
        Map<String, Object> t = new HashMap<>();
        t.put("TASK_NO", "HCA-2026-0009");
        t.put("TASK_STATUS", "PENDING_CHECKER");
        t.put("EMPLOYEE_ID", 7L);
        t.put("PAY_EFFECT_FIELD_ID", 3L);
        t.put("NEW_VALUE", "X");
        t.put("EFFECTIVE_DATE", effective == null ? null : Date.valueOf(effective));
        t.put("RETIREMENT_DATE_PAST", "N");
        when(jdbc.queryForMap(contains("FROM hrms_change_task WHERE"), eq(9L))).thenReturn(t);
        when(jdbc.queryForMap(contains("FROM hrms_pay_effect_field"), any(Object[].class)))
                .thenReturn(Map.of("FIELD_CODE", "DEPARTMENT"));
        when(applier.effectFor("DEPARTMENT")).thenReturn(Optional.empty());
        when(jdbc.queryForList(
                        contains("FROM approval_decision"), eq(String.class), any(Object[].class)))
                .thenReturn(List.of());
        when(jdbc.update(contains("UPDATE hrms_change_task SET"), any(Object[].class)))
                .thenReturn(1);
    }

    private static ApprovalDecided decided(ApprovalStatus outcome, String reason) {
        return new ApprovalDecided(
                4L,
                "I-21",
                HrmsChangeService.ENTITY,
                "9",
                outcome,
                reason,
                null,
                "ddo@x",
                Instant.now());
    }

    @Test
    void SRS_21_11_anApprovedChangeBeforeThisMonthRaisesTheRetroEventACurrentOneNone() {
        task(LocalDate.of(2026, 8, 1));
        effect.on(decided(ApprovalStatus.APPROVED, null));
        ArgumentCaptor<RetroEvents.Raise> raised = ArgumentCaptor.forClass(RetroEvents.Raise.class);
        verify(retro).raise(raised.capture());
        org.assertj.core.api.Assertions.assertThat(raised.getValue().sourceModule())
                .isEqualTo("HRMS_CHANGE");
        task(LocalDate.of(2026, 10, 1));
        effect.on(decided(ApprovalStatus.APPROVED, " "));
        task(null);
        effect.on(decided(ApprovalStatus.APPROVED, null));
        verify(retro).raise(any());
    }

    // SC-16-0159: a rejection needs a reason, keeps the old value and returns to HRMS.
    @Test
    void SRS_1_30_aReturnedTaskGoesBackToTheMakerAndARejectedOneToHrmsWithAReason() {
        task(null);
        effect.on(decided(ApprovalStatus.RETURNED, null));
        verify(jdbc)
                .update(
                        contains("task_status = 'RETURNED_TO_MAKER'"),
                        eq("Returned to the Maker"),
                        eq(9L),
                        eq("PENDING_CHECKER"));
        effect.on(decided(ApprovalStatus.REJECTED, null));
        verify(jdbc)
                .update(
                        contains("task_status = 'REJECTED_RETURNED_TO_HRMS'"),
                        eq("Rejected"),
                        any(),
                        any(),
                        any(),
                        eq(9L),
                        eq("PENDING_CHECKER"));
        effect.on(decided(ApprovalStatus.REJECTED, "Wrong order"));
        verify(jdbc)
                .update(
                        contains("task_status = 'REJECTED_RETURNED_TO_HRMS'"),
                        eq("Wrong order"),
                        any(),
                        any(),
                        any(),
                        eq(9L),
                        eq("PENDING_CHECKER"));
        verify(retro, never()).raise(any());
        verify(applier, never()).apply(any(), any(Long.class), anyString(), anyString(), any());
    }

    private static in.gov.fci.payroll.platform.approval.ApprovalDeciding deciding(String entity) {
        return new in.gov.fci.payroll.platform.approval.ApprovalDeciding(
                4L, "I-21", entity, "9", null, "ddo@x");
    }

    private void status(String status) {
        when(jdbc.queryForList(contains("SELECT task_no, task_status"), any(Object[].class)))
                .thenReturn(
                        status == null
                                ? List.of()
                                : List.of(Map.of("TASK_NO", "HCA-9", "TASK_STATUS", status)));
        owned("DEPARTMENT");
    }

    private void owned(String fieldCode) {
        Map<String, Object> row = new HashMap<>();
        row.put("TASK_NO", "HCA-9");
        row.put("EMPLOYEE_ID", 7L);
        row.put("NEW_VALUE", "{}");
        row.put("EFFECTIVE_DATE", Date.valueOf(LocalDate.of(2026, 7, 1)));
        row.put("HRMS_REQUEST_ID", "REQ-1");
        row.put("FIELD_CODE", fieldCode);
        when(jdbc.queryForMap(contains("JOIN hrms_pay_effect_field"), eq(9L))).thenReturn(row);
    }

    // Wave C review (SRS-9-23): a task a newer HRMS record superseded is never approved.
    @Test
    void SRS_1_28_aSupersededTaskIsRefusedApprovalBeforeAnyOtp() {
        status("SUPERSEDED");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> effect.on(deciding(HrmsChangeService.ENTITY)))
                .isInstanceOfSatisfying(
                        in.gov.fci.payroll.platform.error.FciException.class,
                        e -> {
                            org.assertj.core.api.Assertions.assertThat(e.getErrorCode())
                                    .isEqualTo(
                                            in.gov.fci.payroll.platform.error.ApiErrorCode
                                                    .I21_SUPERSEDED);
                            org.assertj.core.api.Assertions.assertThat(e.getMessageArgs())
                                    .containsExactly("HCA-9");
                        });
        status("PENDING_CHECKER");
        effect.on(deciding(HrmsChangeService.ENTITY));
        status(null);
        effect.on(deciding(HrmsChangeService.ENTITY));
        effect.on(deciding("another_entity"));
    }

    // Wave C review: a rejection, a return (or an approval) of a superseded task changes nothing.
    @Test
    void SRS_1_28_aDecisionOnASupersededTaskChangesNothing() {
        task(null);
        Map<String, Object> t = new HashMap<>();
        t.put("TASK_NO", "HCA-2026-0009");
        t.put("TASK_STATUS", "SUPERSEDED");
        t.put("EMPLOYEE_ID", 7L);
        t.put("PAY_EFFECT_FIELD_ID", 3L);
        when(jdbc.queryForMap(contains("FROM hrms_change_task WHERE"), eq(9L))).thenReturn(t);
        effect.on(decided(ApprovalStatus.REJECTED, "Superseded"));
        effect.on(decided(ApprovalStatus.RETURNED, "Superseded"));
        // Wave D review 1: an approval that reaches a superseded task (a race past the Deciding
        // check) is refused, so the whole decision rolls back.
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> effect.on(decided(ApprovalStatus.APPROVED, null)))
                .isInstanceOfSatisfying(
                        in.gov.fci.payroll.platform.error.FciException.class,
                        e ->
                                org.assertj.core.api.Assertions.assertThat(e.getErrorCode())
                                        .isEqualTo(
                                                in.gov.fci.payroll.platform.error.ApiErrorCode
                                                        .I21_SUPERSEDED));
        verify(jdbc, never()).update(contains("UPDATE hrms_change_task SET"), any(Object[].class));
        verify(applier, never()).apply(any(), any(Long.class), anyString(), anyString(), any());
        verify(retro, never()).raise(any());
    }

    // Wave D review 1: the task row is locked for the decision; a status changed under it (no row
    // updated) refuses the decision, whatever its outcome.
    @Test
    void SRS_1_28_aTaskChangedUnderTheDecisionIsRefusedWhateverTheOutcome() {
        task(null);
        when(jdbc.update(contains("UPDATE hrms_change_task SET"), any(Object[].class)))
                .thenReturn(0);
        for (ApprovalStatus outcome :
                new ApprovalStatus[] {
                    ApprovalStatus.APPROVED, ApprovalStatus.REJECTED, ApprovalStatus.RETURNED
                }) {
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> effect.on(decided(outcome, "why")))
                    .as(outcome.name())
                    .isInstanceOfSatisfying(
                            in.gov.fci.payroll.platform.error.FciException.class,
                            e ->
                                    org.assertj.core.api.Assertions.assertThat(e.getMessageArgs())
                                            .containsExactly("HCA-2026-0009"));
        }
        verify(jdbc, org.mockito.Mockito.atLeastOnce()).queryForMap(contains("FOR UPDATE"), eq(9L));
    }

    /**
     * Wave D review 2 (SRS-G-21): the owning module's precondition is checked when the Checker is
     * about to approve, before any OTP - for one task, and for every task waiting in an order; its
     * refusal stops the approval; a field no module owns has none.
     */
    @Test
    void SRS_G_21_theOwnersPreconditionRefusesTheApprovalBeforeTheOtp() {
        in.gov.fci.payroll.employee.api.HrmsTaskEffects owner =
                mock(in.gov.fci.payroll.employee.api.HrmsTaskEffects.class);
        status("PENDING_CHECKER");
        owned("NPS_CHANGE");
        when(applier.effectFor("NPS_CHANGE")).thenReturn(Optional.of(owner));
        org.mockito.Mockito.doThrow(
                        new in.gov.fci.payroll.platform.error.FciException(
                                in.gov.fci.payroll.platform.error.ApiErrorCode.NPS_STALE,
                                new Object[] {"202607"},
                                List.of(),
                                null))
                .when(owner)
                .beforeApproval(any());
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> effect.on(deciding(HrmsChangeService.ENTITY)))
                .isInstanceOf(in.gov.fci.payroll.platform.error.FciException.class);
        ArgumentCaptor<in.gov.fci.payroll.employee.api.HrmsTaskEffects.Task> task =
                ArgumentCaptor.forClass(in.gov.fci.payroll.employee.api.HrmsTaskEffects.Task.class);
        verify(owner).beforeApproval(task.capture());
        org.assertj.core.api.Assertions.assertThat(task.getValue().taskId()).isEqualTo(9L);
        org.assertj.core.api.Assertions.assertThat(task.getValue().employeeId()).isEqualTo(7L);
        org.assertj.core.api.Assertions.assertThat(task.getValue().effectiveDate())
                .isEqualTo(LocalDate.of(2026, 7, 1));
        // An order: each of its waiting tasks is checked.
        when(jdbc.queryForList(
                        contains("WHERE task_order_id"), eq(Long.class), any(Object[].class)))
                .thenReturn(List.of(9L));
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> effect.on(deciding(HrmsOrderService.ENTITY)))
                .isInstanceOf(in.gov.fci.payroll.platform.error.FciException.class);
        verify(owner, org.mockito.Mockito.times(2)).beforeApproval(any());
        // A field no module owns has no precondition.
        owned("DEPARTMENT");
        effect.on(deciding(HrmsChangeService.ENTITY));
        effect.on(deciding(HrmsOrderService.ENTITY));
        verify(owner, org.mockito.Mockito.times(2)).beforeApproval(any());
    }
}
