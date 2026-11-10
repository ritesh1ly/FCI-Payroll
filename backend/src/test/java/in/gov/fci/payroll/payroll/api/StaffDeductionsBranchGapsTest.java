package in.gov.fci.payroll.payroll.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * SRS-8-17: the deductions the earlier sources took are what the net-pay floor of a later recovery
 * is measured against; a member handed over without them has taken nothing yet.
 */
class StaffDeductionsBranchGapsTest {

    @Test
    void SRS_8_17_aMemberWithoutTheEarlierDeductionsHasTakenNothing() {
        StaffDeductions.Member none =
                new StaffDeductions.Member(
                        7L, 3L, "IDA", YearMonth.of(2026, 10), List.of(), BigDecimal.TEN, null);
        assertThat(none.deducted()).isEqualByComparingTo("0");
        StaffDeductions.Member some =
                new StaffDeductions.Member(
                        7L,
                        3L,
                        "IDA",
                        YearMonth.of(2026, 10),
                        List.of(),
                        BigDecimal.TEN,
                        new BigDecimal("4"));
        assertThat(some.deducted()).isEqualByComparingTo("4");
    }
}
