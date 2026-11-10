package in.gov.fci.payroll.configuration.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * SRS-15-13 / SRS-16-13 and R-01 (rounding-money): a percentage floor is taken to the paisa, half
 * up, before the comparison, so a net pay exactly on the floor is not below it and one paisa under
 * it is; an absolute floor compares as it is; NONE is never breached.
 */
class NetPayFloorsTest {

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    @Test
    void R_01_aPercentageFloorIsTakenToThePaisaHalfUpBeforeTheComparison() {
        // 12.345 % of 1234.56 = 152.4064...: the floor is 152.41.
        NetPayFloors.Floor floor = new NetPayFloors.Floor("PERCENT_OF_GROSS", d("12.345"), true);
        assertThat(floor.breachedBy(d("152.41"), d("1234.56"))).as("on the floor").isFalse();
        assertThat(floor.breachedBy(d("152.40"), d("1234.56"))).as("a paisa under").isTrue();
        // 12.345 % of 1000 = 123.45 exactly; 12.3455 % = 123.455 -> 123.46 half up.
        assertThat(
                        new NetPayFloors.Floor("PERCENT_OF_GROSS", d("12.3455"), true)
                                .breachedBy(d("123.45"), d("1000")))
                .isTrue();
        assertThat(
                        new NetPayFloors.Floor("PERCENT_OF_GROSS", d("12.3455"), true)
                                .breachedBy(d("123.46"), d("1000")))
                .isFalse();
    }

    // BR-EMPR-010: after recoveries the net pay stays at or above the configured floor.
    // SRS-14-9: the net-pay floor is an effective-dated M-14 parameter.
    // SC-12-0009: the net-pay floor is a dated M-14 parameter.
    @Test
    void SRS_16_13_anAbsoluteFloorComparesAsItIsAndNoneIsNeverBreached() {
        NetPayFloors.Floor absolute = new NetPayFloors.Floor("ABSOLUTE_AMOUNT", d("1000"), true);
        assertThat(absolute.breachedBy(d("1000.00"), d("5000"))).isFalse();
        assertThat(absolute.breachedBy(d("999.99"), d("5000"))).isTrue();
        NetPayFloors.Floor none = new NetPayFloors.Floor("NONE", null, true);
        assertThat(none.breachedBy(d("0"), d("5000"))).isFalse();
        assertThat(none.breachedBy(d("-1"), d("5000"))).isFalse();
    }

    /** The one formula the cycle, the arrear recovery and the penalty check share. */
    @Test
    void SRS_16_13_theFloorAmountIsOneFormulaForEveryBasis() {
        assertThat(new NetPayFloors.Floor("ABSOLUTE_AMOUNT", d("1000"), true).amount(d("5000")))
                .isEqualByComparingTo("1000");
        assertThat(new NetPayFloors.Floor("PERCENT_OF_GROSS", d("25"), true).amount(d("30001")))
                .isEqualByComparingTo("7500.25");
        assertThat(new NetPayFloors.Floor("NONE", null, true).amount(d("5000")))
                .isEqualByComparingTo("0");
    }
}
