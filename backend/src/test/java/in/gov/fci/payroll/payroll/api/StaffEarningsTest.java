package in.gov.fci.payroll.payroll.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

/**
 * The staff earnings read by the tax module (plan 16 phase 16e-2; SRS-7-3): a month's lines with
 * their taxable flag (N is not taxable, Y and PARTLY are), the month locked only when every result
 * row is, and the TDS deducted read from the TDS line, signed positive; none when the TDS pay code
 * is not configured.
 */
class StaffEarningsTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);

    private static ResultSet earning(
            String yymm, String locked, int code, String taxable, String amount) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString(1)).thenReturn(yymm);
        when(rs.getString(2)).thenReturn(locked);
        when(rs.getInt(3)).thenReturn(code);
        when(rs.getString(4)).thenReturn("C" + code);
        when(rs.getString(5)).thenReturn(taxable);
        when(rs.getBigDecimal(6)).thenReturn(new BigDecimal(amount));
        return rs;
    }

    private void rows(String sqlStart, List<ResultSet> rows) {
        doAnswer(
                        i -> {
                            RowCallbackHandler h = i.getArgument(1);
                            for (ResultSet rs : rows) {
                                h.processRow(rs);
                            }
                            return null;
                        })
                .when(jdbc)
                .query(startsWith(sqlStart), any(RowCallbackHandler.class), any(Object[].class));
    }

    @Test
    void SRS_7_3_theMonthsCarryTheirTaxableLinesLockAndTdsDeducted() throws Exception {
        rows(
                "SELECT r.payroll_yymm, r.is_locked",
                List.of(
                        earning("202604", "Y", 101, "Y", "30000"),
                        earning("202604", "Y", 141, "N", "500"),
                        earning("202605", "Y", 101, "PARTLY", "30000"),
                        earning("202605", "N", 141, "N", "500"),
                        earning("202605", "N", 908, "N", "2100")));
        ResultSet tdsRow = mock(ResultSet.class);
        when(tdsRow.getString(1)).thenReturn("202604");
        when(tdsRow.getBigDecimal(2)).thenReturn(new BigDecimal("-1250"));
        rows("SELECT r.payroll_yymm, SUM", List.of(tdsRow));
        when(jdbc.queryForList(anyString(), eq(BigDecimal.class), eq("TDS"), any(), any()))
                .thenReturn(List.of(BigDecimal.valueOf(906)));
        when(jdbc.queryForList(
                        anyString(),
                        eq(BigDecimal.class),
                        eq("NPS_EMPLOYER_EARNING"),
                        any(),
                        any()))
                .thenReturn(List.of(BigDecimal.valueOf(908)));

        List<StaffEarnings.Month> months = new StaffEarnings(jdbc).months(7L, "202604", "202605");

        assertThat(months)
                .extracting(StaffEarnings.Month::yymm)
                .containsExactly("202604", "202605");
        assertThat(months.get(0).locked()).isTrue();
        assertThat(months.get(0).taxableGross()).isEqualByComparingTo("30000");
        assertThat(months.get(0).tdsDeducted()).isEqualByComparingTo("1250");
        assertThat(months.get(1).locked()).as("one row not locked").isFalse();
        assertThat(months.get(1).taxableGross())
                .as("PARTLY is taxable")
                .isEqualByComparingTo("30000");
        assertThat(months.get(1).tdsDeducted()).isZero();
        assertThat(months.get(1).employerNps())
                .as("SRS-9-21: the employer NPS earning, apart from the taxable gross")
                .isEqualByComparingTo("2100");
        assertThat(months.get(0).employerNps()).isZero();
    }

    @Test
    void SRS_7_3_noTdsCodeMeansNoTdsAndTheShortMonthHasNone() {
        when(jdbc.queryForList(anyString(), eq(BigDecimal.class), eq("TDS"), any(), any()))
                .thenReturn(List.of());
        assertThat(new StaffEarnings(jdbc).months(7L, "202604", "202605")).isEmpty();
        StaffEarnings.Month m = new StaffEarnings.Month("202604", true, List.of());
        assertThat(m.tdsDeducted()).isZero();
        assertThat(m.taxableGross()).isZero();
    }
}
