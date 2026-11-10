package in.gov.fci.payroll.nps.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.arrear.api.RetroEvents;
import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.employee.api.EmployeeDirectory;
import in.gov.fci.payroll.employee.api.HrmsChanges;
import in.gov.fci.payroll.employee.api.HrmsTaskEffects;
import in.gov.fci.payroll.nps.api.NpsReceipts;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.payroll.api.PayrollRecords;
import in.gov.fci.payroll.payroll.api.StaffDeductions;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Edge cases of the NPS record as received from HRMS (SRS-9-1, SRS-9-1a, SRS-9-21, SRS-9-23;
 * US-GAP-291): a withdrawal previewed with the amounts HRMS left out, a back-dated task without an
 * effective date, a legacy task compared by time whatever type the receipt time is held as, a
 * resend carrying its effective date, a settled month whose deducted amount is not recorded, an
 * employer NPS whose deduction code alone is missing.
 */
class NpsHrmsEffectsBranchGapsTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final PayrollRecords payroll = mock(PayrollRecords.class);
    private final RetroEvents retro = mock(RetroEvents.class);
    private final AuditService audit = mock(AuditService.class);
    private final EmployeeDirectory employees = mock(EmployeeDirectory.class);
    private final JsonMapper json = JsonMapper.builder().build();
    private final NpsHrmsEffects effects =
            new NpsHrmsEffects(
                    jdbc, org, payroll, retro, audit, json, employees, mock(NpsExceptions.class));

    NpsHrmsEffectsBranchGapsTest() {
        when(employees.locationOf(7L)).thenReturn(Optional.of(5L));
        when(employees.eisOf(7L)).thenReturn(Optional.of("870777"));
        OrgLocation unit = mock(OrgLocation.class);
        when(unit.getId()).thenReturn(3L);
        when(org.cycleUnitOf(5L)).thenReturn(Optional.of(unit));
        when(retro.known(any())).thenReturn(42L);
    }

    private void cycle(String status) {
        when(payroll.cycle(3L, "202607"))
                .thenReturn(Optional.of(new PayrollRecords.Cycle(1L, 3L, "202607", status)));
    }

    private void monthly(Object receivedAt, String employee, String employer) {
        Map<String, Object> row = new HashMap<>();
        row.put("NPS_DEDUCTION_ID", 77L);
        row.put("HRMS_NPS_FLAG", "Y");
        row.put("PRAN_REFERENCE", "110012345678");
        row.put("EMPLOYEE_AMOUNT", employee == null ? null : new BigDecimal(employee));
        row.put("EMPLOYER_AMOUNT", employer == null ? null : new BigDecimal(employer));
        row.put("HRMS_EFFECTIVE_FROM", null);
        row.put("MISMATCH_STATUS", "CONSISTENT");
        row.put("APPLY_STATUS", "RECEIVED");
        row.put("HRMS_RECEIVED_AT", receivedAt);
        when(jdbc.queryForList(contains("record_type = 'MONTHLY'"), any(Object[].class)))
                .thenReturn(List.of(row));
    }

    private void taskReceived(List<Timestamp> at) {
        when(jdbc.queryForList(
                        contains("SELECT received_at FROM hrms_change_task"),
                        eq(Timestamp.class),
                        eq(9L)))
                .thenReturn(at);
    }

    private static HrmsTaskEffects.Task legacy(String field, String value) {
        return new HrmsTaskEffects.Task(
                9L, "HCA-9", 7L, field, value, LocalDate.of(2026, 7, 1), null);
    }

    private static final String LEGACY_CHANGE =
            "{\"payrollYymm\":\"202607\",\"npsMember\":true,\"pran\":\"110012345678\","
                    + "\"employeeAmount\":5400,\"employerAmount\":7000}";

    @Test
    void SRS_9_23_aWithdrawalIsPreviewedAsFlagNWithTheAmountsHrmsLeftOutShownAsDashes() {
        monthly(Timestamp.valueOf(LocalDateTime.of(2026, 10, 1, 9, 0)), null, null);
        assertThat(
                        effects.preview(
                                NpsHrmsEffects.CHANGE_FIELD,
                                7L,
                                "{\"payrollYymm\":\"202607\",\"npsMember\":false,"
                                        + "\"employeeAmount\":null,\"employerAmount\":0}"))
                .hasValue("NPS of 202607: flag Y -> N, employee - -> -, employer - -> 0");
    }

    @Test
    void SRS_9_23_aBackDatedTaskWithoutAnEffectiveDateStoresNone() {
        cycle("LOCKED");
        when(jdbc.queryForList(contains("apply_status = 'APPLIED' ORDER BY"), any(Object[].class)))
                .thenReturn(
                        List.of(
                                Map.<String, Object>of(
                                        "RECORD_TYPE",
                                        "MONTHLY",
                                        "DEDUCTED_AMOUNT",
                                        new BigDecimal("5000"))));
        when(jdbc.queryForList(
                        contains("STATUTORY_DEDUCTION"), eq(BigDecimal.class), any(Object[].class)))
                .thenReturn(List.of(BigDecimal.valueOf(905)));
        effects.apply(
                new HrmsTaskEffects.Task(
                        9L, "HCA-9", 7L, NpsHrmsEffects.FIELD, LEGACY_CHANGE, null, "HR-9"),
                "ddo@x",
                55L,
                Instant.parse("2026-10-27T05:00:00Z"));
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("'BACK_DATED', ?"), args.capture());
        // hrms_effective_from is the eighth bound value.
        assertThat(args.getValue()[7]).isNull();
    }

    @Test
    void SRS_9_23_aSettledMonthWithoutADeductedAmountSettledNothing() {
        cycle("LOCKED");
        Map<String, Object> row = new HashMap<>();
        row.put("RECORD_TYPE", "MONTHLY");
        row.put("DEDUCTED_AMOUNT", null);
        when(jdbc.queryForList(contains("apply_status = 'APPLIED' ORDER BY"), any(Object[].class)))
                .thenReturn(List.of(row));
        when(jdbc.queryForList(
                        contains("STATUTORY_DEDUCTION"), eq(BigDecimal.class), any(Object[].class)))
                .thenReturn(List.of(BigDecimal.valueOf(905)));
        effects.apply(
                new HrmsTaskEffects.Task(
                        9L,
                        "HCA-9",
                        7L,
                        NpsHrmsEffects.FIELD,
                        LEGACY_CHANGE,
                        LocalDate.of(2026, 7, 1),
                        "HR-9"),
                "ddo@x",
                55L,
                Instant.parse("2026-10-27T05:00:00Z"));
        verify(retro)
                .known(
                        argThat(
                                k ->
                                        k.lines()
                                                .equals(
                                                        List.of(
                                                                new RetroEvents.Line(
                                                                        "202607",
                                                                        905,
                                                                        new BigDecimal(
                                                                                "-5400"))))));
    }

    /**
     * Wave D review 2 (SRS-9-23): the receipt time of the month's record is compared with the
     * task's whether JDBC hands it over as a LocalDateTime or as text; a task whose receipt time is
     * unknown is refused as stale.
     */
    @Test
    void SRS_9_23_aLegacyTaskIsComparedByTimeWhateverTypeTheReceiptTimeIsHeldAs() {
        HrmsTaskEffects.Task task = legacy(NpsHrmsEffects.CHANGE_FIELD, LEGACY_CHANGE);
        taskReceived(List.of(Timestamp.valueOf(LocalDateTime.of(2026, 10, 1, 10, 0))));

        monthly(LocalDateTime.of(2026, 10, 1, 9, 30), "5000", "7000");
        assertThatCode(() -> effects.beforeApproval(task)).doesNotThrowAnyException();

        monthly("2026-10-01 09:59:59", "5000", "7000");
        assertThatCode(() -> effects.beforeApproval(task)).doesNotThrowAnyException();

        monthly("2026-10-01 10:00:01", "5000", "7000");
        stale(task);

        // The task row is there but its receipt time is not recorded.
        monthly(LocalDateTime.of(2026, 10, 1, 9, 30), "5000", "7000");
        taskReceived(Arrays.asList((Timestamp) null));
        stale(task);
    }

    private void stale(HrmsTaskEffects.Task task) {
        assertThatThrownBy(() -> effects.beforeApproval(task))
                .isInstanceOfSatisfying(
                        FciException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ApiErrorCode.NPS_STALE));
    }

    /**
     * Wave C review (SRS-9-23): a resend is the same record when its effective date is the one
     * waiting too, and an amount HRMS left out on both sides is the same amount.
     */
    @Test
    void SRS_9_23_aResendWithItsEffectiveDateOrWithoutAnAmountIsTheSameRecord() {
        String waiting =
                "{\"payrollYymm\":\"202607\",\"npsMember\":true,\"pran\":\"110012345678\","
                        + "\"employeeAmount\":5400,\"employerAmount\":null,"
                        + "\"effectiveFrom\":\"2026-07-05\"}";
        NpsReceipts.Receipt same =
                new NpsReceipts.Receipt(
                        "870777",
                        "202607",
                        true,
                        "110012345678",
                        new BigDecimal("5400"),
                        null,
                        LocalDate.of(2026, 7, 5));
        assertThat(NpsHrmsEffects.sameRecord(json, waiting, same)).isTrue();
        NpsReceipts.Receipt otherDay =
                new NpsReceipts.Receipt(
                        "870777",
                        "202607",
                        true,
                        "110012345678",
                        new BigDecimal("5400"),
                        null,
                        LocalDate.of(2026, 7, 6));
        assertThat(NpsHrmsEffects.sameRecord(json, waiting, otherDay)).isFalse();
    }

    /** US-GAP-291: a change with its effective date opens its I-21 task from that date. */
    @Test
    void SRS_9_23_aChangeWithAnEffectiveDateOpensItsTaskFromThatDate() {
        HrmsChanges changes = mock(HrmsChanges.class);
        NpsIntakeService intake =
                new NpsIntakeService(
                        jdbc,
                        org,
                        payroll,
                        mock(NpsExceptions.class),
                        audit,
                        changes,
                        json,
                        Clock.fixed(Instant.parse("2026-10-27T05:00:00Z"), ZoneOffset.UTC));
        Map<String, Object> employee = new HashMap<>();
        employee.put("EMPLOYEE_ID", 7L);
        employee.put("LOCATION_ID", null);
        when(jdbc.queryForList(contains("WHERE emp_num = ?"), any(Object[].class)))
                .thenReturn(List.of(employee));
        monthly(Timestamp.from(Instant.parse("2026-10-01T04:00:00Z")), "5000", "7000");
        when(changes.receive(any()))
                .thenReturn(new HrmsChanges.Received(12L, "HCA-12", "P", false));
        NpsReceipts.Received received =
                intake.receive(
                        "it.hrms",
                        new NpsReceipts.Receipt(
                                "870777",
                                "202607",
                                true,
                                "110012345678",
                                new BigDecimal("5600"),
                                new BigDecimal("7000"),
                                LocalDate.of(2026, 7, 5)));
        assertThat(received.heldForTask()).isEqualTo("HCA-12");
        ArgumentCaptor<HrmsChanges.Change> change =
                ArgumentCaptor.forClass(HrmsChanges.Change.class);
        verify(changes).receive(change.capture());
        assertThat(change.getValue().newValue()).contains("\"effectiveFrom\":\"2026-07-05\"");
        assertThat(change.getValue().effectiveDate()).isEqualTo(LocalDate.of(2026, 7, 5));
    }

    /** R-RULE: an employer NPS whose deduction code alone is missing refuses the member. */
    @Test
    void R_RULE_anEmployerNpsWithoutItsDeductionCodeRefusesTheMember() {
        NpsDeduction source = new NpsDeduction(jdbc);
        when(jdbc.queryForList(any(String.class), eq(BigDecimal.class), eq("NPS"), any(), any()))
                .thenReturn(List.of(BigDecimal.valueOf(906)));
        when(jdbc.queryForList(
                        any(String.class), eq(BigDecimal.class), eq("NPS_EMPLOYER"), any(), any()))
                .thenReturn(List.of());
        when(jdbc.queryForList(
                        any(String.class),
                        eq(BigDecimal.class),
                        eq("NPS_EMPLOYER_EARNING"),
                        any(),
                        any()))
                .thenReturn(List.of(BigDecimal.valueOf(908)));
        Map<String, Object> row = new HashMap<>();
        row.put("HRMS_NPS_FLAG", "Y");
        row.put("EMPLOYEE_AMOUNT", new BigDecimal("1500"));
        row.put("EMPLOYER_AMOUNT", new BigDecimal("2100"));
        row.put("MISMATCH_STATUS", "CONSISTENT");
        row.put("SEPARATION_DATE", null);
        when(jdbc.queryForList(any(String.class), eq(7L), eq("202610"))).thenReturn(List.of(row));
        StaffDeductions.Run run = source.open(LocalDate.of(2026, 10, 1));
        assertThat(run.versions()).isEqualTo("codes=906,908,-");
        StaffDeductions.Member member =
                new StaffDeductions.Member(
                        7L,
                        3L,
                        "CDA",
                        YearMonth.of(2026, 10),
                        List.of(new StaffDeductions.Earning(101, new BigDecimal("30000"))),
                        new BigDecimal("30000"));
        assertThatThrownBy(() -> run.contributions(member))
                .isInstanceOf(StaffDeductions.NotComputable.class)
                .hasMessageContaining("Employer NPS pay codes not configured");
    }
}
