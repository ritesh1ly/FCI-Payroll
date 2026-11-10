package in.gov.fci.payroll.settlement.orchestration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.employee.api.EmployeeEvents;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.support.KeyHolder;

/**
 * O-05 case opening (plan 11; SRS-10-8, SRS-10-14; UC-06): a separation opens one case with every
 * clearance raised, its type as HRMS sent it (the I-21 SEPARATION value, else the event type); the
 * same separation again opens nothing.
 */
class SettlementCaseOpenerTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final SettlementCaseOpener opener =
            new SettlementCaseOpener(
                    jdbc,
                    mock(SettlementClearances.class),
                    mock(AuditService.class),
                    Clock.fixed(Instant.parse("2026-10-27T05:00:00Z"), ZoneOffset.UTC));

    private static EmployeeEvents.EmployeeSeparated separated(String reason) {
        return new EmployeeEvents.EmployeeSeparated(
                7L, 3L, "superannuation", LocalDate.of(2026, 9, 30), reason, "ddo@x");
    }

    private void keyed() {
        doAnswer(
                        call -> {
                            ((KeyHolder) call.getArgument(1))
                                    .getKeyList()
                                    .add(Map.of("settlement_case_id", 5L));
                            return 1;
                        })
                .when(jdbc)
                .update(any(PreparedStatementCreator.class), any(KeyHolder.class));
    }

    @Test
    void SRS_10_8_theSameSeparationAgainOpensNothing() {
        when(jdbc.queryForObject(
                        contains("FROM settlement_case WHERE employee_id"),
                        eq(Long.class),
                        any(Object[].class)))
                .thenReturn(1L);
        opener.on(separated("Retired"));
        verify(jdbc, never()).update(any(PreparedStatementCreator.class), any(KeyHolder.class));
    }

    @Test
    void SRS_10_14_BR_FNF_013_aSeparationOpensOneCaseWithEveryClearanceAndItsTypeAsHrmsSentIt() {
        when(jdbc.queryForObject(
                        contains("FROM settlement_case WHERE employee_id"),
                        eq(Long.class),
                        any(Object[].class)))
                .thenReturn(null);
        keyed();
        when(jdbc.queryForList(
                        contains("FROM employee_lifecycle_event"),
                        eq(String.class),
                        any(Object[].class)))
                .thenReturn(List.of(" voluntary_retirement_scheme_option "));
        opener.on(separated("x".repeat(60)));
        verify(jdbc)
                .update(
                        contains("INSERT INTO final_settlement"),
                        eq(5L),
                        eq(7L),
                        eq("VOLUNTARY_RETIREMENT"),
                        any(),
                        eq("HRMS"));
        when(jdbc.queryForList(
                        contains("FROM employee_lifecycle_event"),
                        eq(String.class),
                        any(Object[].class)))
                .thenReturn(List.of(" "));
        opener.on(separated(null));
        verify(jdbc)
                .update(
                        contains("INSERT INTO final_settlement"),
                        eq(5L),
                        eq(7L),
                        eq("SUPERANNUATION"),
                        any(),
                        eq("HRMS"));
        when(jdbc.queryForList(
                        contains("FROM employee_lifecycle_event"),
                        eq(String.class),
                        any(Object[].class)))
                .thenReturn(List.of());
        opener.on(separated("Retired"));
        verify(jdbc, org.mockito.Mockito.atLeast(3))
                .update(contains("INSERT INTO settlement_clearance"), any(Object[].class));
        verify(jdbc, never()).update(anyString(), eq("never"));
    }

    // SRS-INT-27: no labour HRMS interface; labour data is entered in Payroll.
    @Test
    void SRS_10_14_aLabourSeparationIsEnteredInPayrollNotSentByHrms() {
        keyed();
        when(jdbc.queryForObject(contains("emp_lbr_flag"), eq(String.class), any(Object[].class)))
                .thenReturn("Y");
        opener.on(
                new EmployeeEvents.EmployeeSeparated(
                        7L, null, "RETIREMENT", LocalDate.of(2026, 9, 30), "Retired", "hr@x"));
        verify(jdbc)
                .update(
                        contains("INSERT INTO final_settlement"),
                        eq(5L),
                        eq(7L),
                        eq("RETIREMENT"),
                        any(),
                        eq("ENT"));
    }
}
