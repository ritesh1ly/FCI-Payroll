package in.gov.fci.payroll.allowance.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.payroll.api.StaffDeductions;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

/**
 * SRS-11-12 / SRS-11-16 (plan 10 D10-6, D10-11): approved claims are earnings of the run under
 * their pay code; the cafeteria selection in force is paid pro rata, and a line whose month's
 * amount is nothing adds no earning.
 */
class ClaimPaymentsBranchGapsTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ClaimPayments payments = new ClaimPayments(jdbc);

    private static ResultSet claim(long employee, int payCode, String amount) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong(1)).thenReturn(employee);
        when(rs.getInt(2)).thenReturn(payCode);
        when(rs.getBigDecimal(3)).thenReturn(new BigDecimal(amount));
        return rs;
    }

    private static ResultSet election(long employee, int payCode, String amount, LocalDate from)
            throws Exception {
        ResultSet rs = claim(employee, payCode, amount);
        when(rs.getDate(4)).thenReturn(Date.valueOf(from));
        when(rs.getDate(5)).thenReturn(null);
        return rs;
    }

    private static StaffDeductions.Member member(long id, YearMonth month) {
        return new StaffDeductions.Member(
                id, 40L, "IDA", month, List.of(), new BigDecimal("50000"), null);
    }

    @Test
    void SRS_11_16_aZeroSelectionLineAddsNoEarning() throws Exception {
        LocalDate asOf = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        YearMonth month = YearMonth.from(asOf);
        ResultSet approved = claim(7L, 951, "400");
        ResultSet paid = election(7L, 960, "1000", month.atDay(1));
        ResultSet nothing = election(8L, 961, "0", month.atDay(1));
        doAnswer(
                        call -> {
                            ((RowCallbackHandler) call.getArgument(1)).processRow(approved);
                            return null;
                        })
                .when(jdbc)
                .query(contains("reimbursement_claim"), any(RowCallbackHandler.class));
        doAnswer(
                        call -> {
                            RowCallbackHandler h = call.getArgument(1);
                            h.processRow(paid);
                            h.processRow(nothing);
                            return null;
                        })
                .when(jdbc)
                .query(
                        contains("cafeteria_election"),
                        any(RowCallbackHandler.class),
                        any(Object.class),
                        any(Object.class));

        StaffDeductions.Run run = payments.open(asOf);

        assertThat(run.earnings(member(7L, month)))
                .extracting(StaffDeductions.Earning::payCode)
                .containsExactly(951, 960);
        assertThat(run.earnings(member(7L, month)))
                .extracting(StaffDeductions.Earning::amount)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("400"), new BigDecimal("1000"));
        assertThat(run.earnings(member(8L, month))).isEmpty();
        assertThat(run.deductions(member(7L, month))).isEmpty();
        assertThat(run.versions()).isEmpty();
        assertThat(payments.order()).isEqualTo(1);
    }
}
