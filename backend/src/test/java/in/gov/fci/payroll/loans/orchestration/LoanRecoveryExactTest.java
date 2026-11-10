package in.gov.fci.payroll.loans.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.payroll.api.StaffDeductions;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

/** SRS-G-19, R-07: a loan's final instalment reaches payroll as scheduled, with its paise. */
class LoanRecoveryExactTest {

    private static void answers(JdbcTemplate jdbc, String sql, String code, String value)
            throws Exception {
        doAnswer(
                        i -> {
                            ResultSet rs = mock(ResultSet.class);
                            when(rs.getString(1)).thenReturn(code);
                            when(rs.getBigDecimal(2)).thenReturn(new BigDecimal(value));
                            ((RowCallbackHandler) i.getArgument(1)).processRow(rs);
                            return null;
                        })
                .when(jdbc)
                .query(startsWith(sql), any(RowCallbackHandler.class), any(Object[].class));
    }

    @Test
    void SRS_G_19_aFinalInstalmentIsDeductedAsTheLedgerPostsIt() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        answers(jdbc, "SELECT v.value_code", "FESTIVAL_ADVANCE", "910");
        answers(jdbc, "SELECT t.loan_type_code", "FESTIVAL_ADVANCE", "334.50");
        StaffDeductions.Run run = new LoanRecoveryDeduction(jdbc).open(LocalDate.of(2026, 10, 1));

        assertThat(
                        run.deductions(
                                new StaffDeductions.Member(
                                        7L,
                                        3L,
                                        "IDA",
                                        YearMonth.of(2026, 10),
                                        List.of(),
                                        BigDecimal.TEN)))
                .containsExactly(
                        new StaffDeductions.Line(910, new BigDecimal("334.50"), null, null, true));
    }
}
