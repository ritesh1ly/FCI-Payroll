package in.gov.fci.payroll.tax.business;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The taxable year from the staff results (plan 16 phase 16e-1; SRS-7-3, SRS-7-12). */
class EarningsYearTest {

    private static EarningsYear.Result result(String yymm, boolean locked, String gross) {
        return new EarningsYear.Result(
                yymm,
                locked,
                new BigDecimal(gross),
                List.of(new TaxableEarnings.Line("Basic Pay", new BigDecimal(gross))));
    }

    @Test
    void SRS_7_12_lockedMonthsAreActualAndTheRestIsProjectedFromCurrentPay() {
        TaxableEarnings.Year y =
                EarningsYear.of(
                                "2026-27",
                                List.of(
                                        result("202605", true, "50000"),
                                        result("202604", true, "48000"),
                                        result("202606", false, "52000")))
                        .orElseThrow();
        assertThat(y.months()).hasSize(12);
        assertThat(y.months().get(0).yymm()).isEqualTo("202604");
        assertThat(y.months().get(11).yymm()).isEqualTo("202703");
        assertThat(y.months().get(0).actual()).isTrue();
        assertThat(y.months().get(1).taxableGross()).isEqualByComparingTo("50000");
        assertThat(y.months().get(2).actual()).as("computed, not locked").isFalse();
        assertThat(y.months().get(2).taxableGross()).isEqualByComparingTo("52000");
        assertThat(y.months().get(11).taxableGross())
                .as("projected at current pay")
                .isEqualByComparingTo("52000");
        assertThat(y.gross())
                .isEqualByComparingTo(
                        new BigDecimal("98000")
                                .add(new BigDecimal("52000").multiply(BigDecimal.TEN)));
        assertThat(y.remainingMonths()).isEqualTo(10);
        assertThat(y.tdsDeducted()).isEqualByComparingTo("0");
        assertThat(y.months().get(5).lines())
                .extracting(TaxableEarnings.Line::description)
                .containsExactly("Basic Pay");
    }

    @Test
    void SRS_7_12_monthsBeforeTheFirstResultPaidNothingAndNoResultIsNoYear() {
        TaxableEarnings.Year y =
                EarningsYear.of("2026-27", List.of(result("202609", false, "40000"))).orElseThrow();
        assertThat(y.months().get(4).actual()).isTrue();
        assertThat(y.months().get(4).taxableGross()).isEqualByComparingTo("0");
        assertThat(y.months().get(5).actual()).isFalse();
        assertThat(y.gross()).isEqualByComparingTo("280000");
        assertThat(y.allActual()).isFalse();
        assertThat(EarningsYear.of("2026-27", List.of())).isEmpty();

        TaxableEarnings.Year all =
                EarningsYear.of(
                                "2025-26",
                                java.util.stream.IntStream.range(0, 12)
                                        .mapToObj(
                                                i ->
                                                        result(
                                                                String.format(
                                                                        "%04d%02d",
                                                                        i < 9 ? 2025 : 2026,
                                                                        i < 9 ? i + 4 : i - 8),
                                                                true,
                                                                "1000"))
                                        .toList())
                        .orElseThrow();
        assertThat(all.allActual()).isTrue();
        assertThat(all.remainingMonths()).as("at least one").isEqualTo(1);
    }

    @Test
    void SRS_7_3_eachEarlierMonthKeepsItsOwnWrittenEarnings() {
        TaxableEarnings.Year written =
                EarningsYear.of(
                                "2026-27",
                                List.of(
                                        new EarningsYear.Result(
                                                "202604",
                                                true,
                                                new BigDecimal("40000"),
                                                List.of(),
                                                new BigDecimal("3000")),
                                        new EarningsYear.Result(
                                                "202605",
                                                true,
                                                new BigDecimal("41000"),
                                                List.of(),
                                                new BigDecimal("2900"))))
                        .orElseThrow();
        TaxableEarnings.Year y =
                EarningsYear.withMonth(
                        java.util.Optional.of(written),
                        "2026-27",
                        "202606",
                        new BigDecimal("45000"),
                        List.of());
        assertThat(y.months().get(0).yymm()).isEqualTo("202604");
        assertThat(y.months().get(0).taxableGross()).isEqualByComparingTo("40000");
        assertThat(y.months().get(1).yymm()).isEqualTo("202605");
        assertThat(y.months().get(1).taxableGross())
                .as("May's own earnings, not April's")
                .isEqualByComparingTo("41000");
        assertThat(y.months().get(2).taxableGross()).isEqualByComparingTo("45000");
        assertThat(y.tdsDeducted()).isEqualByComparingTo("5900");
    }

    @Test
    void SRS_7_3_theRunsMonthReplacesTheWrittenOneAndProjectsTheRestAtIt() {
        TaxableEarnings.Year written =
                EarningsYear.of(
                                "2026-27",
                                List.of(
                                        new EarningsYear.Result(
                                                "202604",
                                                true,
                                                new BigDecimal("40000"),
                                                List.of(),
                                                new BigDecimal("3000")),
                                        new EarningsYear.Result(
                                                "202605",
                                                false,
                                                new BigDecimal("41000"),
                                                List.of(),
                                                new BigDecimal("2900"))))
                        .orElseThrow();
        assertThat(written.months().get(0).tdsDeducted())
                .as("locked: deducted")
                .isEqualByComparingTo("3000");
        assertThat(written.months().get(1).tdsDeducted())
                .as("not locked: nothing yet")
                .isEqualByComparingTo("0");

        TaxableEarnings.Year y =
                EarningsYear.withMonth(
                        java.util.Optional.of(written),
                        "2026-27",
                        "202605",
                        new BigDecimal("45000"),
                        List.of(new TaxableEarnings.Line("Basic Pay", new BigDecimal("45000"))));
        assertThat(y.months().get(0).actual()).isTrue();
        assertThat(y.months().get(0).taxableGross()).isEqualByComparingTo("40000");
        assertThat(y.months().get(1).actual()).isFalse();
        assertThat(y.months().get(1).taxableGross())
                .as("the run's month")
                .isEqualByComparingTo("45000");
        assertThat(y.months().get(11).taxableGross())
                .as("projected at it")
                .isEqualByComparingTo("45000");
        assertThat(y.tdsDeducted()).isEqualByComparingTo("3000");
        assertThat(y.remainingMonths()).isEqualTo(11);

        TaxableEarnings.Year fresh =
                EarningsYear.withMonth(
                        java.util.Optional.empty(),
                        "2026-27",
                        "202609",
                        new BigDecimal("30000"),
                        List.of());
        assertThat(fresh.months().get(4).actual())
                .as("before the run's month: nothing paid")
                .isTrue();
        assertThat(fresh.months().get(4).taxableGross()).isEqualByComparingTo("0");
        assertThat(fresh.gross()).isEqualByComparingTo("210000");

        TaxableEarnings.Year nps =
                EarningsYear.withMonth(
                        java.util.Optional.empty(),
                        "2026-27",
                        "202609",
                        new BigDecimal("30000"),
                        new BigDecimal("2100"),
                        List.of());
        assertThat(nps.employerNps())
                .as("SRS-9-21: the month's employer NPS, projected on the later months")
                .isEqualByComparingTo("14700");
        assertThat(nps.gross()).isEqualByComparingTo("210000");
        assertThat(
                        EarningsYear.of(
                                        "2026-27",
                                        List.of(
                                                new EarningsYear.Result(
                                                        "202604",
                                                        true,
                                                        new BigDecimal("30000"),
                                                        List.of(),
                                                        BigDecimal.ZERO,
                                                        new BigDecimal("2100"))))
                                .orElseThrow()
                                .employerNps())
                .as("locked and projected months alike")
                .isEqualByComparingTo("25200");
    }
}
