package in.gov.fci.payroll.arrear.business;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.arrear.business.WageRevisionRules.Fitment;
import in.gov.fci.payroll.arrear.business.WageRevisionRules.Rounding;
import in.gov.fci.payroll.arrear.business.WageRevisionRules.Rule;
import java.math.BigDecimal;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;

/** Plan 13 phase 13e: the wage revision fitment and its simulation (SRS-8-25 steps 2 and 3). */
class WageRevisionRulesTest {

    private static BigDecimal n(String v) {
        return new BigDecimal(v);
    }

    @Test
    void SRS_8_25_theFitmentRuleGivesTheNewPayWithinItsLimitsAndRounding() {
        assertThat(
                        WageRevisionRules.fit(
                                n("40000"),
                                new Rule(Fitment.FACTOR, n("2.57"), null, null, Rounding.RUPEE)))
                .isEqualByComparingTo("102800");
        assertThat(
                        WageRevisionRules.fit(
                                n("40005"),
                                new Rule(Fitment.PERCENTAGE, n("3"), null, null, Rounding.NEXT_10)))
                .as("41205.15 up to the next 10")
                .isEqualByComparingTo("41210");
        assertThat(
                        WageRevisionRules.fit(
                                n("40000"),
                                new Rule(
                                        Fitment.FIXED_AMOUNT,
                                        n("1001"),
                                        null,
                                        null,
                                        Rounding.NEXT_100)))
                .isEqualByComparingTo("41100");
        assertThat(
                        WageRevisionRules.fit(
                                n("10000"),
                                new Rule(
                                        Fitment.PERCENTAGE,
                                        n("3"),
                                        n("18000"),
                                        n("20000"),
                                        Rounding.RUPEE)))
                .as("held at the minimum")
                .isEqualByComparingTo("18000");
        assertThat(
                        WageRevisionRules.fit(
                                n("90000"),
                                new Rule(
                                        Fitment.PERCENTAGE,
                                        n("3"),
                                        null,
                                        n("91000"),
                                        Rounding.RUPEE)))
                .as("held at the maximum")
                .isEqualByComparingTo("91000");
        assertThat(WageRevisionRules.round(n("100.5"), Rounding.RUPEE)).isEqualByComparingTo("101");
    }

    /**
     * SRS-8-25 step 2 (BMAD TR-032): a maximum that is a multiple of the rounding step holds after
     * rounding - held at the maximum it rounds onto itself, and a pay one step below rounds up to
     * the maximum at most. A maximum that is not a multiple of the step (e.g. 40005 with NEXT_10)
     * is left unpinned: whether rounding may cross the maximum is FCI's answer (CLR-122).
     */
    @Test
    void SRS_8_25_aMaximumOnTheRoundingStepHoldsAfterRounding() {
        assertThat(
                        WageRevisionRules.fit(
                                n("40000"),
                                new Rule(
                                        Fitment.FIXED_AMOUNT,
                                        n("100"),
                                        null,
                                        n("40010"),
                                        Rounding.NEXT_10)))
                .as("held at the maximum, rounding lands on it")
                .isEqualByComparingTo("40010");
        assertThat(
                        WageRevisionRules.fit(
                                n("40000"),
                                new Rule(
                                        Fitment.FIXED_AMOUNT,
                                        n("1"),
                                        null,
                                        n("40010"),
                                        Rounding.NEXT_10)))
                .as("40001 rounds to 40010, the maximum itself")
                .isEqualByComparingTo("40010")
                .isLessThanOrEqualTo(n("40010"));
        assertThat(
                        WageRevisionRules.fit(
                                n("40000"),
                                new Rule(
                                        Fitment.FIXED_AMOUNT,
                                        n("0"),
                                        null,
                                        n("40100"),
                                        Rounding.NEXT_100)))
                .as("already on the step, below the maximum")
                .isEqualByComparingTo("40000");
    }

    @Test
    void SRS_8_25_anIncompleteRuleIsRefused() {
        assertThat(WageRevisionRules.defect(new Rule(null, n("1"), null, null, Rounding.RUPEE)))
                .isPresent();
        assertThat(
                        WageRevisionRules.defect(
                                new Rule(Fitment.FACTOR, null, null, null, Rounding.RUPEE)))
                .isPresent();
        assertThat(WageRevisionRules.defect(new Rule(Fitment.FACTOR, n("1"), null, null, null)))
                .isPresent();
        assertThat(
                        WageRevisionRules.defect(
                                new Rule(Fitment.FACTOR, n("0"), null, null, Rounding.RUPEE)))
                .hasValueSatisfying(r -> assertThat(r).contains("positive"));
        assertThat(
                        WageRevisionRules.defect(
                                new Rule(Fitment.FACTOR, n("1"), n("5"), n("4"), Rounding.RUPEE)))
                .hasValueSatisfying(r -> assertThat(r).contains("minimum"));
        assertThat(
                        WageRevisionRules.defect(
                                new Rule(Fitment.FACTOR, n("1"), n("4"), n("5"), Rounding.RUPEE)))
                .isEmpty();
    }

    @Test
    void SRS_8_25_theEstimateIsTheBasicDifferenceForTheMonthsAlreadyPaid() {
        YearMonth now = YearMonth.of(2026, 10);
        assertThat(WageRevisionRules.arrearMonths(YearMonth.of(2026, 7), now)).isEqualTo(3);
        assertThat(WageRevisionRules.arrearMonths(YearMonth.of(2026, 12), now))
                .as("future")
                .isZero();
        assertThat(WageRevisionRules.estimate(n("40000"), n("41210"), 3))
                .isEqualByComparingTo("3630");
    }

    @Test
    void SRS_8_25_theBoundariesOfTheEstimateAndTheRules() {
        YearMonth now = YearMonth.of(2026, 10);
        assertThat(WageRevisionRules.arrearMonths(now, now))
                .as("this month: none paid yet")
                .isZero();
        assertThat(WageRevisionRules.arrearMonths(now.minusMonths(1), now)).isEqualTo(1);
        assertThat(WageRevisionRules.estimate(n("40000"), n("40000"), 5)).isEqualByComparingTo("0");
        assertThat(
                        WageRevisionRules.fit(
                                n("40000"),
                                new Rule(
                                        Fitment.FIXED_AMOUNT,
                                        n("10"),
                                        n("40010"),
                                        n("40010"),
                                        Rounding.NEXT_10)))
                .as("minimum equal to maximum")
                .isEqualByComparingTo("40010");
        assertThat(WageRevisionRules.round(n("100"), Rounding.NEXT_100))
                .as("already a multiple")
                .isEqualByComparingTo("100");
        assertThat(WageRevisionRules.round(n("101"), Rounding.NEXT_100))
                .isEqualByComparingTo("200");
        assertThat(
                        WageRevisionRules.defect(
                                new Rule(Fitment.FACTOR, n("1"), n("5"), n("5"), Rounding.RUPEE)))
                .isEmpty();
    }

    @Test
    void SRS_8_25_aRuleWithOnlyAMinimumOrOnlyAMaximumIsComplete() {
        assertThat(
                        WageRevisionRules.defect(
                                new Rule(Fitment.FACTOR, n("1"), n("5"), null, Rounding.RUPEE)))
                .isEmpty();
        assertThat(
                        WageRevisionRules.defect(
                                new Rule(Fitment.FACTOR, n("1"), null, n("5"), Rounding.RUPEE)))
                .isEmpty();
        assertThat(
                        WageRevisionRules.defect(
                                new Rule(Fitment.FACTOR, n("1"), null, null, Rounding.RUPEE)))
                .isEmpty();
    }
}
