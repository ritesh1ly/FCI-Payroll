package in.gov.fci.payroll.nps.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.payroll.api.StaffDeductions;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The NPS of the staff payroll as received (plan 18 phase 18b; SRS-9-1, SRS-9-1a, SRS-9-18,
 * SRS-9-21; D18-3, D18-4, D18-9): the employee NPS deducted as received, the employer NPS a
 * contribution; nothing without a record, for a mismatch, for a member not flagged or separated
 * before the month; unset pay codes refuse a member with an amount. Codes and amounts are fixtures.
 */
class NpsDeductionTest {

    private static final LocalDate OCT = LocalDate.of(2026, 10, 1);

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final NpsDeduction source = new NpsDeduction(jdbc);

    private void codes(Integer employee, Integer employerDeduction, Integer employerEarning) {
        code("NPS", employee);
        code("NPS_EMPLOYER", employerDeduction);
        code("NPS_EMPLOYER_EARNING", employerEarning);
    }

    private void code(String value, Integer code) {
        when(jdbc.queryForList(anyString(), eq(BigDecimal.class), eq(value), any(), any()))
                .thenReturn(code == null ? List.of() : List.of(BigDecimal.valueOf(code)));
    }

    private void record(
            String flag, String employee, String employer, String status, LocalDate sep) {
        Map<String, Object> row = new HashMap<>();
        row.put("HRMS_NPS_FLAG", flag);
        row.put("EMPLOYEE_AMOUNT", employee == null ? null : new BigDecimal(employee));
        row.put("EMPLOYER_AMOUNT", employer == null ? null : new BigDecimal(employer));
        row.put("MISMATCH_STATUS", status);
        row.put("SEPARATION_DATE", sep == null ? null : Date.valueOf(sep));
        when(jdbc.queryForList(anyString(), eq(7L), eq("202610"))).thenReturn(List.of(row));
    }

    private static StaffDeductions.Member member() {
        return new StaffDeductions.Member(
                7L,
                3L,
                "CDA",
                YearMonth.of(2026, 10),
                List.of(new StaffDeductions.Earning(101, new BigDecimal("30000"))),
                new BigDecimal("30000"));
    }

    @Test
    void SRS_9_21_theEmployeeNpsIsDeductedAndTheEmployerNpsIsAContribution() {
        // BR-PEN-002 / BR-PEN-004: the contributions are taken as HRMS computed them; the employer
        // share is a contribution that leaves net pay unchanged.
        codes(906, 907, 908);
        record("Y", "1500", "2100", "CONSISTENT", null);
        StaffDeductions.Run run = source.open(OCT);
        assertThat(source.order()).isEqualTo(20);
        assertThat(run.versions()).isEqualTo("codes=906,908,907");
        assertThat(run.contributions(member()))
                .containsExactly(
                        new StaffDeductions.Contribution(908, 907, new BigDecimal("2100")));
        assertThat(run.deductions(member()))
                .containsExactly(new StaffDeductions.Line(906, new BigDecimal("1500"), null, null));
        verify(jdbc, times(1)).queryForList(anyString(), eq(7L), eq("202610"));
    }

    @Test
    void SRS_9_1a_nothingIsTakenWithoutARecordForAMismatchOrWhenNotFlagged() {
        codes(906, 907, 908);
        when(jdbc.queryForList(anyString(), eq(7L), eq("202610"))).thenReturn(List.of());
        assertThat(source.open(OCT).deductions(member())).as("D18-9: no record").isEmpty();
        assertThat(source.open(OCT).contributions(member())).isEmpty();
        for (String[] r :
                new String[][] {
                    {"Y", null, "2100", "FLAG_WITHOUT_AMOUNT"},
                    {"N", "1500", null, "AMOUNT_WITHOUT_FLAG"},
                    {"N", null, null, "CONSISTENT"},
                    {"Y", "0", "0", "RESOLVED_IN_HRMS"}
                }) {
            record(r[0], r[1], r[2], r[3], null);
            StaffDeductions.Run run = source.open(OCT);
            assertThat(run.contributions(member())).as(r[3]).isEmpty();
            assertThat(run.deductions(member())).as(r[3]).isEmpty();
        }
        record("Y", "1500", "2100", "RESOLVED_IN_HRMS", null);
        assertThat(source.open(OCT).deductions(member())).hasSize(1);
    }

    @Test
    void SRS_9_18_aMemberSeparatedBeforeTheMonthHasNoNps() {
        codes(906, 907, 908);
        record("Y", "1500", "2100", "CONSISTENT", LocalDate.of(2026, 9, 30));
        StaffDeductions.Run run = source.open(OCT);
        assertThat(run.contributions(member())).isEmpty();
        assertThat(run.deductions(member())).isEmpty();
        record("Y", "1500", "2100", "CONSISTENT", LocalDate.of(2026, 10, 15));
        assertThat(source.open(OCT).deductions(member()))
                .as("the month of separation, as received")
                .hasSize(1);
    }

    @Test
    void R_RULE_unsetPayCodesRefuseAMemberWithAnAmount() {
        codes(null, null, null);
        record("Y", "1500", "2100", "CONSISTENT", null);
        StaffDeductions.Run run = source.open(OCT);
        assertThat(run.versions()).isEqualTo("codes=-,-,-");
        assertThatThrownBy(() -> run.contributions(member()))
                .isInstanceOf(StaffDeductions.NotComputable.class)
                .hasMessageContaining("Employer NPS pay codes not configured");
        assertThatThrownBy(() -> run.deductions(member()))
                .isInstanceOf(StaffDeductions.NotComputable.class)
                .hasMessageContaining("NPS pay code not configured");
        codes(906, 907, null);
        assertThatThrownBy(() -> source.open(OCT).contributions(member()))
                .isInstanceOf(StaffDeductions.NotComputable.class);
        record("Y", "1500", null, "CONSISTENT", null);
        assertThat(source.open(OCT).contributions(member()))
                .as("no employer amount: no codes needed")
                .isEmpty();
    }

    // US-GAP-296 (SRS-1-64, SRS-9-1): Payroll never computes NPS, so a suspended member on
    // subsistence allowance has the month's NPS exactly as HRMS sent it, nothing derived from pay.
    @Test
    void SRS_1_64_aSuspendedMembersNpsIsTheAmountHrmsSentForTheMonth() {
        codes(906, 907, 908);
        record("Y", "750", "1050", "CONSISTENT", null);
        StaffDeductions.Member subsistence =
                new StaffDeductions.Member(
                        7L,
                        3L,
                        "CDA",
                        YearMonth.of(2026, 10),
                        List.of(new StaffDeductions.Earning(150, new BigDecimal("15000"))),
                        new BigDecimal("15000"));
        StaffDeductions.Run run = source.open(OCT);
        assertThat(run.deductions(subsistence))
                .containsExactly(new StaffDeductions.Line(906, new BigDecimal("750"), null, null));
        assertThat(run.contributions(subsistence))
                .containsExactly(
                        new StaffDeductions.Contribution(908, 907, new BigDecimal("1050")));
    }
}
