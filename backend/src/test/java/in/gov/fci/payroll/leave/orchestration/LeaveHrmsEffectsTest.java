package in.gov.fci.payroll.leave.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.arrear.api.RetroEvents;
import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.employee.api.EmployeeDirectory;
import in.gov.fci.payroll.employee.api.HrmsTaskEffects;
import in.gov.fci.payroll.general.api.ExceptionWorklist;
import in.gov.fci.payroll.leave.business.LeaveErrorCode;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.payroll.api.PayrollRecords;
import in.gov.fci.payroll.platform.error.FciException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * The I-21 leave fields (plan 19 phases 19b / 19e; SRS-LV-10, SRS-LV-13, SRS-LV-16, SRS-LV-34; plan
 * 13 D13-6): a task's value must name a received sanction of the same member with a readable period
 * and days; an approved event settled in a locked month is back-dated and raises the retro event
 * when its pay effect is configured, an unconfigured effect goes to X-05; an approved encashment
 * sanction is recorded and audited.
 */
class LeaveHrmsEffectsTest {

    private static final Instant DECIDED = Instant.parse("2026-10-27T05:00:00Z");
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final LeaveParameters parameters = mock(LeaveParameters.class);
    private final EmployeeDirectory employees = mock(EmployeeDirectory.class);
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final PayrollRecords payroll = mock(PayrollRecords.class);
    private final ExceptionWorklist worklist = mock(ExceptionWorklist.class);
    private final AuditService audit = mock(AuditService.class);
    private final LeaveEncashmentService encashments = mock(LeaveEncashmentService.class);
    private final RetroEvents retro = mock(RetroEvents.class);
    private final LeaveHrmsEffects effects =
            new LeaveHrmsEffects(
                    jdbc,
                    parameters,
                    employees,
                    org,
                    payroll,
                    worklist,
                    audit,
                    JsonMapper.builder().build(),
                    encashments,
                    retro);

    private static String event(String from) {
        return "{\"applicationId\":9,\"leaveType\":\"EL\",\"from\":\""
                + from
                + "\",\"to\":\""
                + from
                + "\",\"days\":\"1\",\"eventType\":\"ADDITION\",\"sanctionRef\":\"SAN/1\"}";
    }

    private void application(long employee) {
        when(jdbc.queryForList(contains("FROM leave_application"), eq(9L)))
                .thenReturn(List.of(Map.of("EMPLOYEE_ID", employee)));
    }

    @Test
    void SRS_LV_13_aValueMustNameAReceivedSanctionOfTheMemberWithAReadablePeriod() {
        assertThat(effects.fields()).containsExactlyInAnyOrder("LEAVE_EVENT", "LEAVE_ENCASHMENT");
        for (String bad :
                new String[] {
                    null,
                    "{",
                    "{\"applicationId\":\"x\"}",
                    "{\"applicationId\":9,\"from\":\"01-10-2026\"}"
                }) {
            assertThatThrownBy(() -> effects.check("LEAVE_EVENT", 7L, bad))
                    .as(String.valueOf(bad))
                    .extracting(e -> ((FciException) e).getErrorCode())
                    .isEqualTo(LeaveErrorCode.EVENT);
        }
        when(jdbc.queryForList(contains("FROM leave_application"), eq(9L))).thenReturn(List.of());
        assertThatThrownBy(() -> effects.check("LEAVE_EVENT", 7L, event("2026-10-01")))
                .isInstanceOf(FciException.class);
        application(8L);
        assertThatThrownBy(() -> effects.check("LEAVE_EVENT", 7L, event("2026-10-01")))
                .as("another member's sanction")
                .isInstanceOf(FciException.class);
        application(7L);
        assertThatCode(() -> effects.check("LEAVE_EVENT", 7L, event("2026-10-01")))
                .doesNotThrowAnyException();
    }

    @Test
    void SRS_LV_34_anEncashmentSanctionHasReadableDaysAndDate() {
        for (String bad :
                new String[] {
                    null,
                    "{",
                    "{\"days\":\"x\",\"encashmentDate\":\"2026-10-01\"}",
                    "{\"days\":\"5\",\"encashmentDate\":\"x\"}"
                }) {
            assertThatThrownBy(() -> effects.check("LEAVE_ENCASHMENT", 7L, bad))
                    .extracting(e -> ((FciException) e).getErrorCode())
                    .isEqualTo(LeaveErrorCode.EVENT);
        }
        String ok =
                "{\"sanctionRef\":\"SAN/E/1\",\"leaveType\":\"EL\",\"days\":\"5\",\"encashmentDate\":\"2026-10-01\"}";
        assertThatCode(() -> effects.check("LEAVE_ENCASHMENT", 7L, ok)).doesNotThrowAnyException();
        when(encashments.recordInService(eq(7L), eq("EL"), any(), any(), eq(55L))).thenReturn(12L);
        effects.apply(
                new HrmsTaskEffects.Task(1L, "HCA-1", 7L, "LEAVE_ENCASHMENT", ok, null, null),
                "ddo@x",
                55L,
                DECIDED);
        verify(encashments)
                .recordInService(7L, "EL", new BigDecimal("5"), LocalDate.of(2026, 10, 1), 55L);
        verify(audit).record(eq("ddo@x"), any());
    }

    private void lockedOctober(boolean locked) {
        when(employees.member(7L))
                .thenReturn(
                        Optional.of(
                                new EmployeeDirectory.Member(7L, "104882", "A", 5L, "IN_SERVICE")));
        OrgLocation unit = mock(OrgLocation.class);
        when(unit.getId()).thenReturn(3L);
        when(org.cycleUnitOf(5L)).thenReturn(Optional.of(unit));
        when(payroll.cycle(3L, "202610"))
                .thenReturn(
                        Optional.of(
                                new PayrollRecords.Cycle(
                                        1L, 3L, "202610", locked ? "LOCKED" : "FROZEN")));
    }

    // SC-16-0037: changed leave data from HRMS recomputes the month through the retro event.
    @Test
    void SRS_LV_10_anEventSettledAfterItsMonthLockedIsBackDatedAndRaisesTheRetroEvent() {
        application(7L);
        lockedOctober(true);
        when(parameters.payEffect("EL", LocalDate.of(2026, 10, 5)))
                .thenReturn(Optional.of("FULL_DEDUCTION"));
        effects.apply(
                new HrmsTaskEffects.Task(
                        1L, "HCA-1", 7L, "LEAVE_EVENT", event("2026-10-05"), null, null),
                "ddo@x",
                55L,
                DECIDED);
        verify(jdbc).update(contains("INSERT INTO leave_transaction"), any(Object[].class));
        verify(retro).raise(any());
        verify(worklist, never()).raise(anyString(), any());
    }

    /**
     * SRS-LV-30 (US-19-22 AC2): the audit of an applied leave effect names the sanction reference,
     * the period, the approval and the cycle it took effect in, as the change of the
     * leave_transaction's wage effect by the Checker; a back-dated one names the cycle that settles
     * it.
     */
    @Test
    void SRS_LV_30_theAuditOfAnAppliedEffectRecordsSanctionPeriodApprovalAndCycle() {
        application(7L);
        lockedOctober(false);
        when(parameters.payEffect("EL", LocalDate.of(2026, 10, 5)))
                .thenReturn(Optional.of("FULL_DEDUCTION"));
        effects.apply(
                new HrmsTaskEffects.Task(
                        1L, "HCA-1", 7L, "LEAVE_EVENT", event("2026-10-05"), null, null),
                "ddo@x",
                55L,
                DECIDED);
        ArgumentCaptor<AuditService.Entry> entry = ArgumentCaptor.captor();
        verify(audit).record(eq("ddo@x"), entry.capture());
        AuditService.Entry e = entry.getValue();
        assertThat(e.entityName()).isEqualTo("leave_transaction");
        assertThat(e.entityKey()).isEqualTo("HCA-1");
        assertThat(e.fieldName()).isEqualTo("wage_effect_code");
        assertThat(e.oldValue()).isNull();
        assertThat(e.newValue()).isEqualTo("FULL_DEDUCTION");
        assertThat(e.approvalId()).isEqualTo(55L);
        assertThat(e.moduleCode()).isEqualTo(LeaveIntakeService.MODULE);
        assertThat(e.reason())
                .contains("ADDITION", "EL", "2026-10-05 to 2026-10-05", "sanction SAN/1")
                .contains("applied in")
                .doesNotContain("back-dated");

        // Back-dated: the audit names the open cycle that settles it.
        clearInvocations(audit);
        lockedOctober(true);
        effects.apply(
                new HrmsTaskEffects.Task(
                        2L, "HCA-2", 7L, "LEAVE_EVENT", event("2026-10-05"), null, null),
                "ddo@x",
                56L,
                DECIDED);
        verify(audit).record(eq("ddo@x"), entry.capture());
        assertThat(entry.getValue().reason()).contains("back-dated: settled from");
        assertThat(entry.getValue().approvalId()).isEqualTo(56L);
    }

    @Test
    void SRS_LV_26_aLeaveSpanningALockedAndAnOpenMonthIsBackDatedFromItsFirstDay() {
        // US-GAP-307 (SRS-LV-26 l.4096): 25 October - 3 November with October locked: the locked
        // month's share is settled through the retro event from the leave's first day; the open
        // November's share is reduced in that month's run (LeavePayRules.daysLost counts only the
        // days in the month, SRS_LV_15_aLeaveAcrossMonthsCountsTheDaysInTheMonth...).
        application(7L);
        lockedOctober(true);
        when(parameters.payEffect("EL", LocalDate.of(2026, 10, 25)))
                .thenReturn(Optional.of("FULL_DEDUCTION"));
        String span =
                "{\"applicationId\":9,\"leaveType\":\"EL\",\"from\":\"2026-10-25\",\"to\":"
                        + "\"2026-11-03\",\"days\":\"10\",\"eventType\":\"ADDITION\","
                        + "\"sanctionRef\":\"SAN/1\"}";
        effects.apply(
                new HrmsTaskEffects.Task(1L, "HCA-1", 7L, "LEAVE_EVENT", span, null, null),
                "ddo@x",
                55L,
                DECIDED);
        ArgumentCaptor<Object[]> row = ArgumentCaptor.captor();
        verify(jdbc).update(contains("INSERT INTO leave_transaction"), row.capture());
        assertThat(row.getValue()[8]).as("is_back_dated").isEqualTo("Y");
        ArgumentCaptor<RetroEvents.Raise> raised = ArgumentCaptor.captor();
        verify(retro).raise(raised.capture());
        assertThat(raised.getValue().effectiveFrom()).isEqualTo(LocalDate.of(2026, 10, 25));
        // The same leave approved while October is open is applied, not back-dated.
        clearInvocations(jdbc, retro);
        lockedOctober(false);
        effects.apply(
                new HrmsTaskEffects.Task(2L, "HCA-2", 7L, "LEAVE_EVENT", span, null, null),
                "ddo@x",
                56L,
                DECIDED);
        verify(jdbc).update(contains("INSERT INTO leave_transaction"), row.capture());
        assertThat(row.getValue()[8]).isEqualTo("N");
        verify(retro, never()).raise(any());
    }

    @Test
    void SRS_LV_13_anEventInTheOpenMonthWithoutAPayEffectIsHeldOnX05() {
        application(7L);
        lockedOctober(false);
        when(parameters.payEffect(anyString(), any())).thenReturn(Optional.empty());
        effects.apply(
                new HrmsTaskEffects.Task(
                        1L, "HCA-1", 7L, "LEAVE_EVENT", event("2026-10-05"), null, null),
                "ddo@x",
                55L,
                DECIDED);
        verify(retro, never()).raise(any());
        verify(worklist).raise(eq("ddo@x"), any());
        when(employees.member(anyLong())).thenReturn(Optional.empty());
        effects.apply(
                new HrmsTaskEffects.Task(
                        2L, "HCA-2", 7L, "LEAVE_EVENT", event("2026-09-05"), null, null),
                "ddo@x",
                56L,
                DECIDED);
        verify(retro, never()).raise(any());
    }

    private static String conversion(String from) {
        return "{\"applicationId\":9,\"leaveType\":\"EL\",\"from\":\""
                + from
                + "\",\"to\":\""
                + from
                + "\",\"days\":\"1\",\"eventType\":\"CONVERSION\",\"sanctionRef\":\"SAN/2\","
                + "\"earlierSanctionRef\":\"SAN/1\"}";
    }

    private void earlierLwp(String from) {
        when(jdbc.queryForList(contains("FROM leave_transaction"), eq(7L), eq("SAN/1")))
                .thenReturn(
                        List.of(
                                Map.of(
                                        "LEAVE_TYPE_CODE",
                                        "LWP",
                                        "PERIOD_FROM",
                                        java.sql.Date.valueOf(from),
                                        "PERIOD_TO",
                                        java.sql.Date.valueOf(from),
                                        "DAYS",
                                        BigDecimal.ONE,
                                        "WAGE_EFFECT_CODE",
                                        "FULL_DEDUCTION")));
    }

    @Test
    void SRS_LV_18_aConversionOfASanctionPayrollNeverAppliedIsRefused() {
        application(7L);
        when(jdbc.queryForList(contains("FROM leave_transaction"), eq(7L), eq("SAN/1")))
                .thenReturn(List.of());
        assertThatThrownBy(() -> effects.check("LEAVE_EVENT", 7L, conversion("2026-09-10")))
                .extracting(e -> ((FciException) e).getErrorCode())
                .isEqualTo(LeaveErrorCode.EVENT);
        earlierLwp("2026-09-10");
        assertThatCode(() -> effects.check("LEAVE_EVENT", 7L, conversion("2026-09-10")))
                .doesNotThrowAnyException();
    }

    @Test
    void SRS_LV_18_lwpConvertedToPaidLeaveCancelsTheEarlierDeductionAndRaisesTheArrear() {
        application(7L);
        lockedOctober(false);
        earlierLwp("2026-09-08");
        when(parameters.payEffect("EL", LocalDate.of(2026, 9, 10)))
                .thenReturn(Optional.of("NO_EFFECT"));
        effects.apply(
                new HrmsTaskEffects.Task(
                        3L, "HCA-3", 7L, "LEAVE_EVENT", conversion("2026-09-10"), null, null),
                "ddo@x",
                57L,
                DECIDED);
        verify(jdbc)
                .update(
                        contains("'CANCELLATION'"),
                        eq(7L),
                        eq(9L),
                        eq("LWP"),
                        eq(java.sql.Date.valueOf("2026-09-08")),
                        eq(java.sql.Date.valueOf("2026-09-08")),
                        eq(BigDecimal.ONE),
                        eq("FULL_DEDUCTION"),
                        eq("Y"),
                        eq("SAN/2"),
                        eq(3L),
                        eq(java.sql.Date.valueOf("2026-09-01")));
        ArgumentCaptor<RetroEvents.Raise> raised = ArgumentCaptor.forClass(RetroEvents.Raise.class);
        verify(retro).raise(raised.capture());
        assertThat(raised.getValue().effectiveFrom())
                .as("settled from the earlier sanction's first day")
                .isEqualTo(LocalDate.of(2026, 9, 8));
        verify(audit, times(2)).record(eq("ddo@x"), any());
    }

    /**
     * SRS-LV-18 (BMAD TR-047): converting paid leave (EL, no pay effect) to LWP after the lock
     * cancels the earlier sanction's transaction, records the LWP deduction back-dated and raises
     * the retrospective event from the earlier first day - the recovery of the overpaid day.
     */
    @Test
    void SRS_LV_18_paidLeaveConvertedToLwpCancelsTheEarlierSanctionAndRaisesTheRecovery() {
        application(7L);
        lockedOctober(false);
        when(jdbc.queryForList(contains("FROM leave_transaction"), eq(7L), eq("SAN/1")))
                .thenReturn(
                        List.of(
                                Map.of(
                                        "LEAVE_TYPE_CODE",
                                        "EL",
                                        "PERIOD_FROM",
                                        java.sql.Date.valueOf("2026-09-08"),
                                        "PERIOD_TO",
                                        java.sql.Date.valueOf("2026-09-08"),
                                        "DAYS",
                                        BigDecimal.ONE,
                                        "WAGE_EFFECT_CODE",
                                        "NO_EFFECT")));
        when(parameters.payEffect("LWP", LocalDate.of(2026, 9, 10)))
                .thenReturn(Optional.of("FULL_DEDUCTION"));
        String toLwp =
                conversion("2026-09-10").replace("\"leaveType\":\"EL\"", "\"leaveType\":\"LWP\"");
        effects.apply(
                new HrmsTaskEffects.Task(3L, "HCA-3", 7L, "LEAVE_EVENT", toLwp, null, null),
                "ddo@x",
                57L,
                DECIDED);
        verify(jdbc)
                .update(
                        contains("'CANCELLATION'"),
                        eq(7L),
                        eq(9L),
                        eq("EL"),
                        eq(java.sql.Date.valueOf("2026-09-08")),
                        eq(java.sql.Date.valueOf("2026-09-08")),
                        eq(BigDecimal.ONE),
                        eq("NO_EFFECT"),
                        eq("Y"),
                        eq("SAN/2"),
                        eq(3L),
                        eq(java.sql.Date.valueOf("2026-09-01")));
        verify(jdbc)
                .update(
                        contains("INSERT INTO leave_transaction"),
                        eq(7L),
                        eq(9L),
                        eq("LWP"),
                        eq(java.sql.Date.valueOf("2026-09-10")),
                        eq(java.sql.Date.valueOf("2026-09-10")),
                        eq(BigDecimal.ONE),
                        eq("FULL_DEDUCTION"),
                        eq("CONVERSION"),
                        eq("Y"),
                        eq("SAN/2"),
                        eq(3L),
                        eq(java.sql.Date.valueOf("2026-09-01")));
        ArgumentCaptor<RetroEvents.Raise> raised = ArgumentCaptor.forClass(RetroEvents.Raise.class);
        verify(retro).raise(raised.capture());
        assertThat(raised.getValue().effectiveFrom()).isEqualTo(LocalDate.of(2026, 9, 8));
        assertThat(raised.getValue().sourceModule()).isEqualTo("LEAVE");
        assertThat(raised.getValue().sourceKey()).isEqualTo("HCA-3");
    }

    @Test
    void SRS_LV_17_theCheckerSeesTheCappedDaysAndTheAmountBeforeApproving() {
        String sanction =
                "{\"sanctionRef\":\"SAN/E/2\",\"leaveType\":\"EL\",\"days\":\"8\",\"encashmentDate\":\"2026-10-01\"}";
        when(encashments.valueInService(7L, "EL", new BigDecimal("8"), LocalDate.of(2026, 10, 1)))
                .thenReturn(
                        new LeaveEncashmentService.InService(
                                new in.gov.fci.payroll.leave.business.EncashmentRules.Days(
                                        new BigDecimal("5"), true),
                                new BigDecimal("60000"),
                                new BigDecimal("30"),
                                new BigDecimal("10000.00"),
                                9L,
                                "60000 / 30 x 5 days (capped from 8)"));
        assertThat(effects.preview("LEAVE_ENCASHMENT", 7L, sanction))
                .contains("5 days (capped), amount 10000.00: 60000 / 30 x 5 days (capped from 8)");
        // A limit not configured is said, never guessed.
        when(encashments.valueInService(8L, "EL", new BigDecimal("8"), LocalDate.of(2026, 10, 1)))
                .thenThrow(
                        new FciException(
                                LeaveErrorCode.NOT_CONFIGURED,
                                new Object[] {"In-service encashment per year"},
                                List.of(),
                                null));
        assertThat(effects.preview("LEAVE_ENCASHMENT", 8L, sanction).orElseThrow())
                .startsWith("Not computable");
        // A leave event shows no preview.
        assertThat(effects.preview("LEAVE_EVENT", 7L, event("2026-10-01"))).isEmpty();
    }

    /** SRS-LV-17 (branch gap): days within the limit are shown without "(capped)". */
    @Test
    void SRS_LV_17_daysWithinTheLimitAreShownUncapped() {
        String sanction =
                "{\"sanctionRef\":\"SAN/E/3\",\"leaveType\":\"EL\",\"days\":\"5\",\"encashmentDate\":\"2026-10-01\"}";
        when(encashments.valueInService(7L, "EL", new BigDecimal("5"), LocalDate.of(2026, 10, 1)))
                .thenReturn(
                        new LeaveEncashmentService.InService(
                                new in.gov.fci.payroll.leave.business.EncashmentRules.Days(
                                        new BigDecimal("5"), false),
                                new BigDecimal("60000"),
                                new BigDecimal("30"),
                                new BigDecimal("10000.00"),
                                9L,
                                "60000 / 30 x 5 days"));
        assertThat(effects.preview("LEAVE_ENCASHMENT", 7L, sanction))
                .contains("5 days, amount 10000.00: 60000 / 30 x 5 days");
    }

    /**
     * SRS-LV-18 (branch gap): converting a sanction of the open month cancels the earlier
     * transaction not back-dated, and nothing is raised for a locked month.
     */
    @Test
    void SRS_LV_18_aConversionWithinTheOpenMonthIsNotBackDated() {
        application(7L);
        lockedOctober(false);
        earlierLwp("2026-10-08");
        when(parameters.payEffect("EL", LocalDate.of(2026, 10, 10)))
                .thenReturn(Optional.of("NO_EFFECT"));
        effects.apply(
                new HrmsTaskEffects.Task(
                        4L, "HCA-4", 7L, "LEAVE_EVENT", conversion("2026-10-10"), null, null),
                "ddo@x",
                58L,
                DECIDED);
        verify(jdbc)
                .update(
                        contains("'CANCELLATION'"),
                        eq(7L),
                        eq(9L),
                        eq("LWP"),
                        eq(java.sql.Date.valueOf("2026-10-08")),
                        eq(java.sql.Date.valueOf("2026-10-08")),
                        eq(BigDecimal.ONE),
                        eq("FULL_DEDUCTION"),
                        eq("N"),
                        eq("SAN/2"),
                        eq(4L),
                        eq(java.sql.Date.valueOf("2026-10-01")));
        verify(retro, never()).raise(any());
    }
}
