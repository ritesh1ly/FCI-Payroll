package in.gov.fci.payroll.nps.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.employee.api.HrmsChanges;
import in.gov.fci.payroll.nps.api.NpsReceipts;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.payroll.api.PayrollRecords;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wave C review (SRS-9-23, SRS-G-45; US-GAP-291): the newest HRMS record of a month stands. A new
 * record of the member and month closes every NPS_CHANGE task of that month still waiting as
 * SUPERSEDED through I-21 - a second change (A to B, then A to C), a PRAN change or a change back
 * applied at once, a mismatch or its correction - and a resend of the change already waiting opens
 * no second task. A task of another month is left as it is.
 */
class NpsIntakeSupersedeTest {

    private static final String YYMM = "202607";

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final HrmsChanges changes = mock(HrmsChanges.class);
    private final NpsExceptions exceptions = mock(NpsExceptions.class);
    private final JsonMapper json = JsonMapper.builder().build();
    private final NpsIntakeService intake =
            new NpsIntakeService(
                    jdbc,
                    mock(OrgDirectory.class),
                    mock(PayrollRecords.class),
                    exceptions,
                    mock(AuditService.class),
                    changes,
                    json,
                    Clock.fixed(Instant.parse("2026-10-27T05:00:00Z"), ZoneOffset.UTC));

    NpsIntakeSupersedeTest() {
        Map<String, Object> employee = new HashMap<>();
        employee.put("EMPLOYEE_ID", 7L);
        employee.put("LOCATION_ID", null);
        when(jdbc.queryForList(contains("WHERE emp_num = ?"), any(Object[].class)))
                .thenReturn(List.of(employee));
        when(changes.receive(any()))
                .thenReturn(new HrmsChanges.Received(12L, "HCA-12", "P", false));
    }

    /** The month's record: A = flag Y, employee 5000, employer 7000. */
    private void recordA(String mismatch) {
        Map<String, Object> row = new HashMap<>();
        row.put("NPS_DEDUCTION_ID", 77L);
        row.put("HRMS_NPS_FLAG", "Y");
        row.put("PRAN_REFERENCE", "110012345678");
        row.put("EMPLOYEE_AMOUNT", new BigDecimal("5000"));
        row.put("EMPLOYER_AMOUNT", new BigDecimal("7000"));
        row.put("HRMS_EFFECTIVE_FROM", null);
        row.put("MISMATCH_STATUS", mismatch);
        row.put("APPLY_STATUS", "RECEIVED");
        row.put("HRMS_RECEIVED_AT", Timestamp.from(Instant.parse("2026-10-01T04:00:00Z")));
        when(jdbc.queryForList(contains("record_type = 'MONTHLY'"), any(Object[].class)))
                .thenReturn(List.of(row));
    }

    /** T1 (A to B, employee 5400) waits; so does a task of another month. */
    private void waiting() {
        when(changes.open(7L, NpsHrmsEffects.CHANGE_FIELD))
                .thenReturn(
                        List.of(
                                new HrmsChanges.Open(9L, "HCA-9", "PENDING_CHECKER", value(YYMM)),
                                new HrmsChanges.Open(
                                        10L, "HCA-10", "PENDING_ORDER_DETAILS", value("202608"))));
    }

    private static String value(String yymm) {
        return "{\"payrollYymm\":\""
                + yymm
                + "\",\"npsMember\":true,\"pran\":\"110012345678\",\"employeeAmount\":5400,"
                + "\"employerAmount\":7000,\"effectiveFrom\":null,\"basis\":\"77@x\"}";
    }

    private NpsReceipts.Received receive(boolean flag, String pran, String employee) {
        return intake.receive(
                "it.hrms",
                new NpsReceipts.Receipt(
                        "870777",
                        YYMM,
                        flag,
                        pran,
                        employee == null ? null : new BigDecimal(employee),
                        new BigDecimal("7000"),
                        null));
    }

    @Test
    void SRS_9_23_aSecondChangeOfTheMonthSupersedesTheOneWaiting() {
        recordA("CONSISTENT");
        waiting();
        NpsReceipts.Received r = receive(true, "110012345678", "5600");
        assertThat(r.heldForTask()).isEqualTo("HCA-12");
        verify(changes).supersede(eq(9L), eq(12L), anyString(), contains("HCA-12"));
        verify(changes, never()).supersede(eq(10L), any(), anyString(), anyString());
        ArgumentCaptor<HrmsChanges.Change> change =
                ArgumentCaptor.forClass(HrmsChanges.Change.class);
        verify(changes).receive(change.capture());
        // Finding 5: no effective date sent, none carried; the task's date is the month's first.
        assertThat(change.getValue().newValue())
                .contains("\"effectiveFrom\":null")
                .contains("\"basis\":\"77@2026-10-01T04:00:00Z\"");
        assertThat(change.getValue().effectiveDate()).hasToString("2026-07-01");
    }

    @Test
    void SRS_9_23_aResendOfTheChangeWaitingOpensNoSecondTask() {
        recordA("CONSISTENT");
        waiting();
        NpsReceipts.Received r = receive(true, "110012345678", "5400.00");
        assertThat(r.heldForTask()).isEqualTo("HCA-9");
        assertThat(r.replaced()).isFalse();
        verify(changes, never()).receive(any());
        verify(changes, never()).supersede(anyLong(), any(), anyString(), anyString());
    }

    @Test
    void SRS_9_23_aPranChangeOrAChangeBackAppliedAtOnceSupersedesTheOneWaiting() {
        recordA("CONSISTENT");
        waiting();
        NpsReceipts.Received pran = receive(true, "110012345679", "5000");
        assertThat(pran.heldForTask()).isNull();
        assertThat(pran.replaced()).isTrue();
        verify(changes).supersede(eq(9L), isNull(), anyString(), contains("applied at once"));
        verify(changes, never()).supersede(eq(10L), any(), anyString(), anyString());
        verify(changes, never()).receive(any());
    }

    @Test
    void SRS_9_1a_aMismatchOrItsCorrectionSupersedesTheOneWaiting() {
        recordA("CONSISTENT");
        waiting();
        assertThat(receive(true, "110012345678", null).mismatchStatus())
                .isEqualTo("FLAG_WITHOUT_AMOUNT");
        verify(changes).supersede(eq(9L), isNull(), anyString(), anyString());

        recordA("FLAG_WITHOUT_AMOUNT");
        assertThat(receive(true, "110012345678", "5400").mismatchStatus())
                .isEqualTo("RESOLVED_IN_HRMS");
        verify(changes, never()).receive(any());
    }

    @Test
    void SRS_9_1_theFirstRecordOfTheMonthLooksForNoTask() {
        when(jdbc.queryForList(contains("record_type = 'MONTHLY'"), any(Object[].class)))
                .thenReturn(List.of());
        when(jdbc.update(
                        any(org.springframework.jdbc.core.PreparedStatementCreator.class),
                        any(org.springframework.jdbc.support.KeyHolder.class)))
                .thenAnswer(
                        i -> {
                            org.springframework.jdbc.support.KeyHolder key = i.getArgument(1);
                            key.getKeyList().add(Map.of("nps_deduction_id", 1L));
                            return 1;
                        });
        receive(true, "110012345678", "5000");
        verify(changes, never()).open(anyLong(), anyString());
    }
}
