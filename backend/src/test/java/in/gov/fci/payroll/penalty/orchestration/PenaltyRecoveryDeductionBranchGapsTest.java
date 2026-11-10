package in.gov.fci.payroll.penalty.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
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

/**
 * Penalty recoveries in the staff payroll (SRS-14-1, SRS-1-6; D12-6, R-RULE): each nature due in
 * the month is recovered under its recovery head (M-16 PENALTY_RECOVERY); a nature due whose head
 * is not set refuses the member instead of recovering under a guessed code.
 */
class PenaltyRecoveryDeductionBranchGapsTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);

    private static ResultSet row(String nature, String amount) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString(1)).thenReturn(nature);
        when(rs.getBigDecimal(2)).thenReturn(new BigDecimal(amount));
        return rs;
    }

    private void heads(ResultSet... rows) {
        doAnswer(
                        call -> {
                            RowCallbackHandler each = call.getArgument(1);
                            for (ResultSet r : rows) {
                                each.processRow(r);
                            }
                            return null;
                        })
                .when(jdbc)
                .query(
                        contains("FROM reference_value"),
                        any(RowCallbackHandler.class),
                        any(),
                        any());
    }

    private void due(ResultSet... rows) {
        doAnswer(
                        call -> {
                            RowCallbackHandler each = call.getArgument(1);
                            for (ResultSet r : rows) {
                                each.processRow(r);
                            }
                            return null;
                        })
                .when(jdbc)
                .query(
                        contains("FROM penalty_order o"),
                        any(RowCallbackHandler.class),
                        any(),
                        any(),
                        any());
    }

    private static StaffDeductions.Member member() {
        return new StaffDeductions.Member(
                7L,
                5L,
                "IDA",
                YearMonth.of(2026, 6),
                List.of(),
                new BigDecimal("60000"),
                BigDecimal.ZERO);
    }

    @Test
    void D12_6_aNatureDueIsRecoveredUnderItsRecoveryHead() throws Exception {
        heads(row("RECOVERY", "401"));
        due(row("RECOVERY", "600"));
        StaffDeductions.Run run = new PenaltyRecoveryDeduction(jdbc).open(LocalDate.of(2026, 6, 1));
        assertThat(run.versions()).isEqualTo("penalty={RECOVERY=401}");
        assertThat(run.deductions(member()))
                .containsExactly(
                        new StaffDeductions.Line(401, new BigDecimal("600"), null, null, true));
    }

    @Test
    void D12_6_aNatureDueWithoutItsRecoveryHeadRefusesTheMember() throws Exception {
        heads();
        due(row("RECOVERY", "600"));
        StaffDeductions.Run run = new PenaltyRecoveryDeduction(jdbc).open(LocalDate.of(2026, 6, 1));
        assertThatThrownBy(() -> run.deductions(member()))
                .isInstanceOf(StaffDeductions.NotComputable.class)
                .hasMessageContaining("Penalty recovery head of RECOVERY not configured");
    }
}
