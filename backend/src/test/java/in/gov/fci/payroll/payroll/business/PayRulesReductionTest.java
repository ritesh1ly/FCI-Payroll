package in.gov.fci.payroll.payroll.business;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Plan 19 phase 19d (SRS-LV-15, SRS-LV-27, D19-4): a reduction of an earning line before the
 * deductions, gross and net following, the line keeping its amount before as its basis.
 */
class PayRulesReductionTest {

    private static PayRules.Outcome computed() {
        return new PayRules.Outcome(
                PayRules.COMPUTED,
                null,
                List.of(
                        new PayRules.Line(
                                101, 1, new BigDecimal("31000"), null, new BigDecimal("31000")),
                        new PayRules.Line(
                                111, 2, new BigDecimal("15500"), new BigDecimal("50"), null)),
                new BigDecimal("46500"),
                BigDecimal.ZERO,
                new BigDecimal("46500"));
    }

    @Test
    void SRS_LV_15_theReducedLineIsPaidLessItsReductionAndGrossFollows() {
        PayRules.Outcome out =
                PayRules.reduced(
                        computed(),
                        List.of(
                                new PayRules.Reduction(101, new BigDecimal("3000")),
                                new PayRules.Reduction(111, new BigDecimal("1499.5"))));
        assertThat(out.lines().get(0).amount()).isEqualByComparingTo("28000");
        assertThat(out.lines().get(0).basis()).isEqualByComparingTo("31000");
        assertThat(out.lines().get(1).amount())
                .as("rounded once, half up")
                .isEqualByComparingTo("14000");
        assertThat(out.gross()).isEqualByComparingTo("42000");
        assertThat(out.net()).isEqualByComparingTo("42000");
        assertThat(PayRules.reductionOf(101, List.of(new PayRules.Reduction(101, BigDecimal.ONE))))
                .isEqualByComparingTo("1");
        assertThat(PayRules.reductionOf(131, List.of())).isZero();
    }

    @Test
    void SRS_LV_15_noReductionOrASkippedOutcomeIsUnchanged() {
        PayRules.Outcome c = computed();
        assertThat(PayRules.reduced(c, List.of())).isSameAs(c);
        PayRules.Outcome skipped =
                new PayRules.Outcome(PayRules.SKIPPED, "STOP_PAYMENT", List.of(), null, null, null);
        assertThat(PayRules.reduced(skipped, List.of(new PayRules.Reduction(101, BigDecimal.TEN))))
                .isSameAs(skipped);
        PayRules.Outcome capped =
                PayRules.reduced(c, List.of(new PayRules.Reduction(101, new BigDecimal("40000"))));
        assertThat(capped.lines().get(0).amount()).as("never below zero").isZero();
    }

    /**
     * SRS-LV-15 edge case: a zero line of the reduced pay code (beside its positive line) has
     * nothing to reduce; it is kept exactly as computed, without a basis, and only the positive
     * line carries the reduction.
     */
    @Test
    void SRS_LV_15_aZeroLineOfTheReducedPayCodeIsKeptAsComputed() {
        PayRules.Line zero = new PayRules.Line(101, 3, BigDecimal.ZERO, null, null);
        PayRules.Outcome withZero =
                new PayRules.Outcome(
                        PayRules.COMPUTED,
                        null,
                        List.of(
                                new PayRules.Line(
                                        101,
                                        1,
                                        new BigDecimal("31000"),
                                        null,
                                        new BigDecimal("31000")),
                                zero),
                        new BigDecimal("31000"),
                        BigDecimal.ZERO,
                        new BigDecimal("31000"));
        PayRules.Outcome out =
                PayRules.reduced(
                        withZero, List.of(new PayRules.Reduction(101, new BigDecimal("1000"))));
        assertThat(out.lines().get(0).amount()).isEqualByComparingTo("30000");
        assertThat(out.lines().get(1)).isSameAs(zero);
        assertThat(out.gross()).isEqualByComparingTo("30000");
    }

    @Test
    void SRS_LV_15_aReductionThatIsNotPositiveOrHasNoEarningRefusesTheMember() {
        assertThatThrownBy(
                        () ->
                                PayRules.reduced(
                                        computed(),
                                        List.of(new PayRules.Reduction(101, BigDecimal.ZERO))))
                .isInstanceOf(PayDataException.class);
        assertThatThrownBy(
                        () ->
                                PayRules.reduced(
                                        computed(), List.of(new PayRules.Reduction(101, null))))
                .isInstanceOf(PayDataException.class);
        assertThatThrownBy(
                        () ->
                                PayRules.reduced(
                                        computed(),
                                        List.of(new PayRules.Reduction(131, BigDecimal.TEN))))
                .isInstanceOf(PayDataException.class)
                .hasMessageContaining("no earning");
    }
}
