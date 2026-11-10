package in.gov.fci.payroll.labour.business;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.gov.fci.payroll.labour.business.NwnpWageRules.Wage;
import java.math.BigDecimal;
import java.math.MathContext;
import org.junit.jupiter.api.Test;

/** NWNP wage formulas (plan 22 phase 22d; SRS-5-6, SRS-5-12, UC C-05): exactly as stated. */
class NwnpWageRulesTest {

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    // SC-22-0005
    @Test
    void SRS_5_6_presentButNoWorkIsPaidAtMgwOverSevenPerHour() {
        assertThat(NwnpWageRules.hourlyRate(d("700"))).isEqualByComparingTo("100");
        assertThat(NwnpWageRules.hourlyRate(d("781")))
                .isEqualByComparingTo(
                        d("781").divide(BigDecimal.valueOf(7), MathContext.DECIMAL64));
        assertThat(NwnpWageRules.presentButNoWorkPay(d("700"), d("2.5")))
                .isEqualByComparingTo("250");
        assertThat(NwnpWageRules.presentButNoWorkPay(d("700"), d("0"))).isEqualByComparingTo("0");
        assertThat(NwnpWageRules.presentButNoWorkPay(d("0"), d("3"))).isEqualByComparingTo("0");
        assertThatThrownBy(() -> NwnpWageRules.presentButNoWorkPay(d("700"), d("-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("hours -1");
        assertThatThrownBy(() -> NwnpWageRules.presentButNoWorkPay(d("700"), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("hours");
        assertThatThrownBy(() -> NwnpWageRules.hourlyRate(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("MGW");
    }

    // SC-22-0021: Continue Work hours earn double OTA.
    @Test
    void SRS_5_6_continueWorkHoursEarnDoubleOta() {
        assertThat(NwnpWageRules.continueWorkOta(d("700"), d("3"))).isEqualByComparingTo("600");
        assertThat(NwnpWageRules.continueWorkOta(d("700"), d("0.5"))).isEqualByComparingTo("100");
        assertThat(NwnpWageRules.continueWorkOta(d("700"), d("0"))).isEqualByComparingTo("0");
        assertThatThrownBy(() -> NwnpWageRules.continueWorkOta(d("700"), d("-0.01")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // SC-22-0018, SC-22-0028, SC-22-0029: the rate is held per its Unit of Measure.
    @Test
    void SRS_5_3_pieceEarningsFollowTheUnitOfMeasure() {
        assertThat(NwnpWageRules.pieceEarnings(d("45.50"), d("1250"), 100))
                .isEqualByComparingTo("568.75");
        assertThat(NwnpWageRules.pieceEarnings(d("110"), d("4"), 1)).isEqualByComparingTo("440");
        assertThat(NwnpWageRules.pieceEarnings(d("45.50"), d("0"), 100)).isEqualByComparingTo("0");
        assertThat(NwnpWageRules.pieceEarnings(d("45.50"), d("1"), 100))
                .isEqualByComparingTo("0.455");
        assertThatThrownBy(() -> NwnpWageRules.pieceEarnings(d("45.50"), d("10"), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("unit size 0");
        assertThatThrownBy(() -> NwnpWageRules.pieceEarnings(d("45.50"), d("10"), -100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NwnpWageRules.pieceEarnings(d("45.50"), d("-1"), 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("output -1");
        assertThatThrownBy(() -> NwnpWageRules.pieceEarnings(null, d("1"), 100))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("piece rate");
    }

    // SC-22-0011, SC-22-0023, SC-22-0030
    @Test
    void SRS_5_12_aWageBelowTheFloorIsRaisedAndFlagged() {
        assertThat(NwnpWageRules.computedWage(d("600"), d("150"), d("781")))
                .isEqualTo(new Wage(d("781"), true));
        assertThat(NwnpWageRules.computedWage(d("600"), d("181"), d("781")))
                .as("equal to the floor: not raised, not flagged")
                .isEqualTo(new Wage(d("781"), false));
        assertThat(NwnpWageRules.computedWage(d("781"), d("568.75"), d("781")))
                .isEqualTo(new Wage(d("1349.75"), false));
        assertThat(NwnpWageRules.computedWage(d("781"), d("0"), d("781")).raisedToFloor())
                .isFalse();
        assertThatThrownBy(() -> NwnpWageRules.computedWage(null, d("1"), d("1")))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("MGW");
        assertThatThrownBy(() -> NwnpWageRules.computedWage(d("1"), null, d("1")))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("piece earnings");
        assertThatThrownBy(() -> NwnpWageRules.computedWage(d("1"), d("1"), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("floor");
    }
}
