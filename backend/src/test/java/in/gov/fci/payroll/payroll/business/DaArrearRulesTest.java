package in.gov.fci.payroll.payroll.business;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * DA arrear on a retrospective rate (R-MONEY: BigDecimal, rounded once to the rupee half-up, kept
 * at two decimals): the arrear is the recomputed DA less the DA paid; no difference, no arrear
 * line.
 */
class DaArrearRulesTest {

    @Test
    void theArrearIsTheRecomputedDaLessThePaidDa() {
        DaArrearRules.Differential d =
                DaArrearRules.differential(
                                new BigDecimal("25000.00"),
                                new BigDecimal("50000"),
                                new BigDecimal("53"))
                        .orElseThrow();
        assertThat(d.original()).isEqualByComparingTo("25000.00");
        assertThat(d.recomputed()).isEqualByComparingTo("26500.00");
        assertThat(d.recomputed().scale()).isEqualTo(2);
        assertThat(d.arrear()).isEqualByComparingTo("1500.00");
    }

    @Test
    void theRecomputedDaIsRoundedOnceToTheRupeeHalfUp() {
        // 33333 x 50 % = 16666.50 -> 16667; 33331 x 50 % = 16665.50 -> 16666; 1 x 0.49 % rounds to
        // 0.
        assertThat(
                        DaArrearRules.differential(
                                        BigDecimal.ZERO,
                                        new BigDecimal("33333"),
                                        new BigDecimal("50"))
                                .orElseThrow()
                                .recomputed())
                .isEqualByComparingTo("16667");
        assertThat(
                        DaArrearRules.differential(
                                        BigDecimal.ZERO,
                                        new BigDecimal("33331"),
                                        new BigDecimal("50"))
                                .orElseThrow()
                                .recomputed())
                .isEqualByComparingTo("16666");
        assertThat(
                        DaArrearRules.differential(
                                BigDecimal.ZERO, new BigDecimal("100"), new BigDecimal("0.49")))
                .isEmpty();
        assertThat(
                        DaArrearRules.differential(
                                        BigDecimal.ZERO,
                                        new BigDecimal("100"),
                                        new BigDecimal("0.5"))
                                .orElseThrow()
                                .recomputed())
                .isEqualByComparingTo("1");
    }

    @Test
    void noDifferenceMeansNoArrearAndAReductionIsANegativeArrear() {
        assertThat(
                        DaArrearRules.differential(
                                new BigDecimal("26500"),
                                new BigDecimal("50000"),
                                new BigDecimal("53")))
                .isEmpty();
        assertThat(
                        DaArrearRules.differential(
                                        new BigDecimal("27000"),
                                        new BigDecimal("50000"),
                                        new BigDecimal("53"))
                                .orElseThrow()
                                .arrear())
                .isEqualByComparingTo("-500");
    }

    @Test
    void aLineWithoutItsBasisOrRateCannotBeRecomputed() {
        assertThatThrownBy(() -> DaArrearRules.differential(null, BigDecimal.ONE, BigDecimal.ONE))
                .isInstanceOf(PayDataException.class);
        assertThatThrownBy(() -> DaArrearRules.differential(BigDecimal.ONE, null, BigDecimal.ONE))
                .isInstanceOf(PayDataException.class);
        assertThatThrownBy(() -> DaArrearRules.differential(BigDecimal.ONE, BigDecimal.ONE, null))
                .isInstanceOf(PayDataException.class);
    }

    /** R-02: the recomputed DA is rounded once from four decimals, never from two. */
    @Test
    void R_02_theRecomputedDaIsRoundedOnceFromTheWorkingPrecision() {
        // 100 x 1234.495 % = 1234.4950: once -> 1234; via the paisa it would be 1234.50 -> 1235.
        DaArrearRules.Differential d =
                DaArrearRules.differential(
                                new BigDecimal("1000.00"),
                                new BigDecimal("100.00"),
                                new BigDecimal("1234.495"))
                        .orElseThrow();
        assertThat(d.recomputed()).isEqualByComparingTo("1234.00");
        assertThat(d.arrear()).isEqualByComparingTo("234.00");
        // Exactly half a rupee goes up; just under it goes down.
        assertThat(
                        DaArrearRules.differential(
                                        new BigDecimal("0"),
                                        new BigDecimal("1000"),
                                        new BigDecimal("12.345"))
                                .orElseThrow()
                                .recomputed())
                .isEqualByComparingTo("123.00");
        assertThat(
                        DaArrearRules.differential(
                                        new BigDecimal("0"),
                                        new BigDecimal("1000"),
                                        new BigDecimal("12.35"))
                                .orElseThrow()
                                .recomputed())
                .isEqualByComparingTo("124.00");
    }
}
