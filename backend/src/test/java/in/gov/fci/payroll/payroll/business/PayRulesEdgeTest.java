package in.gov.fci.payroll.payroll.business;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.gov.fci.payroll.configuration.api.PayConfiguration;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The staff pay rules' edges (SRS-1-6, SRS-DM-6, SRS-LV-15, SRS-9-21): a member without allowance
 * flags holds none; a reduction of a pay code paid nothing this month refuses the member and a zero
 * line of a reduced code is kept as it is; one-time earnings, contributions and deductions leave a
 * skipped outcome alone; a contribution or deduction without an amount refuses the member.
 */
class PayRulesEdgeTest {

    private static final PayConfiguration.Snapshot CONFIG =
            new PayConfiguration.Snapshot(
                    LocalDate.of(2026, 7, 1),
                    Map.of("IDA", new BigDecimal("50")),
                    Map.of("X", new BigDecimal("30")),
                    Map.of(),
                    false,
                    Map.of(101, 1L, 121, 3L, 912, 9L, 913, 10L),
                    Map.of(),
                    "hash");

    private static PayRules.Outcome computed(PayRules.Line... lines) {
        BigDecimal gross =
                List.of(lines).stream()
                        .map(PayRules.Line::amount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new PayRules.Outcome(
                PayRules.COMPUTED, null, List.of(lines), gross, BigDecimal.ZERO, gross);
    }

    private static PayRules.Line line(int payCode, String amount) {
        return new PayRules.Line(payCode, 1L, new BigDecimal(amount), null, null);
    }

    @Test
    void SRS_1_6_aMemberWithoutAllowanceFlagsHoldsNone() {
        PayRules.Member m =
                new PayRules.Member(
                        7L, BigDecimal.TEN, "IDA", "X", "IN_SERVICE", null, false, null, null);
        assertThat(m.allowanceFlags()).isEmpty();
    }

    @Test
    void SRS_LV_15_aReductionOfACodePaidNothingRefusesAndAZeroLineIsKept() {
        assertThatThrownBy(
                        () ->
                                PayRules.reduced(
                                        computed(line(101, "31000"), line(121, "0")),
                                        List.of(new PayRules.Reduction(121, BigDecimal.TEN))))
                .isInstanceOf(PayDataException.class)
                .hasMessageContaining("no earning");
        PayRules.Outcome out =
                PayRules.reduced(
                        computed(line(101, "0"), line(101, "31000")),
                        List.of(new PayRules.Reduction(101, new BigDecimal("1000"))));
        assertThat(out.lines())
                .extracting(PayRules.Line::amount)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("0"), new BigDecimal("30000"));
    }

    @Test
    void SRS_9_21_aSkippedOutcomeTakesNoEarningContributionOrDeduction() {
        PayRules.Outcome skipped =
                new PayRules.Outcome(PayRules.SKIPPED, "STOP_PAYMENT", List.of(), null, null, null);
        assertThat(
                        PayRules.withDeductions(
                                skipped,
                                List.of(new PayRules.Earning(912, BigDecimal.TEN)),
                                List.of(),
                                List.of(),
                                CONFIG))
                .isSameAs(skipped);
    }

    @Test
    void SRS_1_6_aContributionOrDeductionWithoutAnAmountRefusesTheMember() {
        assertThatThrownBy(
                        () ->
                                PayRules.withDeductions(
                                        computed(line(101, "31000")),
                                        List.of(),
                                        List.of(new PayRules.Contribution(912, 913, null)),
                                        List.of(),
                                        CONFIG))
                .isInstanceOf(PayDataException.class)
                .hasMessageContaining("Contribution 912");
        assertThatThrownBy(
                        () ->
                                PayRules.withDeductions(
                                        computed(line(101, "31000")),
                                        List.of(),
                                        List.of(),
                                        List.of(new PayRules.Deduction(913, null, null, null)),
                                        CONFIG))
                .isInstanceOf(PayDataException.class)
                .hasMessageContaining("Deduction 913");
    }
}
