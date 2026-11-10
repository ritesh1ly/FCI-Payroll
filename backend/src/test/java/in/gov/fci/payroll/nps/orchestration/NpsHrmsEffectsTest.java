package in.gov.fci.payroll.nps.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.arrear.api.RetroEvents;
import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.employee.api.EmployeeDirectory;
import in.gov.fci.payroll.employee.api.HrmsTaskEffects;
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
 * A back-dated NPS change through I-21 (plan 18 phase 18d; SRS-9-23, SRS-G-45; D18-11): the value
 * is a consistent HRMS record of a locked month; approved, the difference to what the month settled
 * is the stated arrear or recovery on I-08 and the record is stored BACK_DATED, the earlier one
 * superseded; no difference raises nothing; the NPS pay code is never a default.
 */
class NpsHrmsEffectsTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final PayrollRecords payroll = mock(PayrollRecords.class);
    private final RetroEvents retro = mock(RetroEvents.class);
    private final AuditService audit = mock(AuditService.class);
    private final EmployeeDirectory employees = mock(EmployeeDirectory.class);
    private final NpsExceptions exceptions = mock(NpsExceptions.class);
    private final NpsHrmsEffects effects =
            new NpsHrmsEffects(
                    jdbc,
                    org,
                    payroll,
                    retro,
                    audit,
                    JsonMapper.builder().build(),
                    employees,
                    exceptions);

    NpsHrmsEffectsTest() {
        when(employees.locationOf(7L)).thenReturn(Optional.of(5L));
        OrgLocation unit = mock(OrgLocation.class);
        when(unit.getId()).thenReturn(3L);
        when(org.cycleUnitOf(5L)).thenReturn(Optional.of(unit));
        locked("LOCKED");
        when(retro.known(any())).thenReturn(42L);
    }

    private void locked(String status) {
        when(payroll.cycle(3L, "202607"))
                .thenReturn(Optional.of(new PayrollRecords.Cycle(1L, 3L, "202607", status)));
    }

    private static String value(String employee) {
        return "{\"payrollYymm\":\"202607\",\"npsMember\":true,\"pran\":\"110012345678\","
                + "\"employeeAmount\":"
                + employee
                + ",\"employerAmount\":7000}";
    }

    private void refused(String value, String defect) {
        assertThatThrownBy(() -> effects.check(NpsHrmsEffects.FIELD, 7L, value))
                .as(String.valueOf(value))
                .isInstanceOfSatisfying(
                        FciException.class,
                        ex -> assertThat(ex.getMessageArgs()).containsExactly(defect));
    }

    @Test
    void SRS_9_23_theValueIsAConsistentRecordOfALockedMonth() {
        assertThat(effects.fields()).contains(NpsHrmsEffects.FIELD);
        refused(null, "VALUE");
        refused("[1]", "VALUE");
        refused("{", "VALUE");
        refused(value("\"x\""), "AMOUNT");
        refused(value("-1"), "AMOUNT");
        refused(value("5400").replace("202607", "2026-7"), "PERIOD");
        refused(value("null"), "FLAG_WITHOUT_AMOUNT");
        refused(value("5400").replace("110012345678", "PRAN-1"), "PRAN");
        assertThatCode(
                        () ->
                                effects.check(
                                        NpsHrmsEffects.FIELD,
                                        7L,
                                        value("\"5400\"").replace("\"110012345678\"", "null")))
                .as("an amount as text and no PRAN")
                .doesNotThrowAnyException();
    }

    @Test
    void SRS_9_23_aMonthStillOpenTakesTheMonthlyRecord() {
        locked("FROZEN");
        refused(value("5400"), "NOT_LOCKED");
        when(payroll.cycle(3L, "202607")).thenReturn(Optional.empty());
        refused(value("5400"), "NOT_LOCKED");
        locked("LOCKED");
        assertThatCode(() -> effects.check(NpsHrmsEffects.FIELD, 7L, value("5400")))
                .doesNotThrowAnyException();
    }

    private void settled(String deducted) {
        when(jdbc.queryForList(contains("FROM nps_deduction"), any(Object[].class)))
                .thenReturn(
                        deducted == null
                                ? List.of()
                                : List.of(
                                        Map.<String, Object>of(
                                                "RECORD_TYPE",
                                                "MONTHLY",
                                                "DEDUCTED_AMOUNT",
                                                new BigDecimal(deducted))));
    }

    private void code(Integer code) {
        when(jdbc.queryForList(
                        contains("STATUTORY_DEDUCTION"), eq(BigDecimal.class), any(Object[].class)))
                .thenReturn(code == null ? List.of() : List.of(BigDecimal.valueOf(code)));
    }

    private void apply(String value) {
        effects.apply(
                new HrmsTaskEffects.Task(
                        9L,
                        "HCA-9",
                        7L,
                        NpsHrmsEffects.FIELD,
                        value,
                        LocalDate.of(2026, 7, 1),
                        "HR-9"),
                "ddo@x",
                55L,
                Instant.parse("2026-10-27T05:00:00Z"));
    }

    @Test
    void SRS_9_23_moreNpsThanWasDeductedIsRecoveredAndTheRecordStoredBackDated() {
        settled("5000");
        code(905);
        apply(value("5400"));
        ArgumentCaptor<RetroEvents.Known> known = ArgumentCaptor.forClass(RetroEvents.Known.class);
        verify(retro).known(known.capture());
        assertThat(known.getValue().lines())
                .containsExactly(new RetroEvents.Line("202607", 905, new BigDecimal("-400")));
        assertThat(known.getValue().sourceModule()).isEqualTo("NPS_BACKDATED");
        verify(jdbc).update(contains("SET apply_status = 'SUPERSEDED'"), any(Object[].class));
        verify(jdbc).update(contains("'BACK_DATED', ?"), any(Object[].class));
    }

    @Test
    void SRS_9_23_aWithdrawnMembershipRefundsWhatWasDeductedAndNoDifferenceRaisesNothing() {
        settled("5000");
        code(905);
        apply(
                "{\"payrollYymm\":\"202607\",\"npsMember\":false,\"employeeAmount\":null,"
                        + "\"employerAmount\":0}");
        ArgumentCaptor<RetroEvents.Known> known = ArgumentCaptor.forClass(RetroEvents.Known.class);
        verify(retro).known(known.capture());
        assertThat(known.getValue().lines().getFirst().amount()).isEqualByComparingTo("5000");

        settled(null);
        apply("{\"payrollYymm\":\"202607\",\"npsMember\":false,\"employerAmount\":0}");
        verify(retro).known(any());
        verify(audit, atLeastOnce()).record(anyString(), any());
    }

    @Test
    void SRS_9_23_theNpsPayCodeIsNeverADefault() {
        settled("5000");
        code(null);
        assertThatThrownBy(() -> apply(value("5400"))).isInstanceOf(FciException.class);
        verify(retro, never()).known(any());
    }
}
