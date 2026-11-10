package in.gov.fci.payroll.payroll.business;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.gov.fci.payroll.configuration.api.PayConfiguration;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The provisional staff pay rules (C-02): components from the M-04 rates in force, a missing rate
 * is a configuration gap and never a zero (DDL v4.0 "fail cleanly"), rounding once per component to
 * the rupee (R-01). Test rates are fixtures, not FCI values.
 */
class PayRulesTest {

    private static final Map<Integer, Long> COMPONENTS = Map.of(101, 1L, 111, 2L, 121, 3L, 131, 4L);

    private static PayConfiguration.Snapshot config(boolean ccaSeeded) {
        return new PayConfiguration.Snapshot(
                LocalDate.of(2026, 7, 1),
                Map.of("IDA", new BigDecimal("50"), "CDA", new BigDecimal("45")),
                Map.of(
                        "X",
                        new BigDecimal("30"),
                        "Y",
                        new BigDecimal("20"),
                        PayConfiguration.CDA_HRA + "Y",
                        new BigDecimal("15")),
                ccaSeeded ? Map.of("X", new BigDecimal("1800")) : Map.of(),
                ccaSeeded,
                COMPONENTS,
                Map.of(),
                "hash");
    }

    private static PayRules.Member member(
            BigDecimal basic,
            String pattern,
            String city,
            String status,
            BigDecimal subsistence,
            boolean stopped) {
        return new PayRules.Member(
                7L, basic, pattern, city, status, subsistence, stopped, Set.of("CCA"), null);
    }

    // SC-01-0017: intermediates at two decimals, each line rounded once; SC-16-0003: HRA at the
    // rate of the city class.
    @Test
    void computesBasicDaHraAndCcaWithProvenance() {
        PayRules.Outcome o =
                PayRules.compute(
                        member(new BigDecimal("40001"), "IDA", "X", "IN_SERVICE", null, false),
                        config(true));

        assertThat(o.status()).isEqualTo(PayRules.COMPUTED);
        assertThat(o.lines())
                .extracting(PayRules.Line::payCode)
                .containsExactly(101, 111, 121, 131);
        assertThat(o.lines().get(1).amount())
                .isEqualByComparingTo("20001"); // 20000.50 rounds half up, once
        assertThat(o.lines().get(1).rate()).isEqualByComparingTo("50");
        assertThat(o.lines().get(1).basis()).isEqualByComparingTo("40001");
        assertThat(o.lines().get(2).amount()).isEqualByComparingTo("12000"); // 12000.30
        assertThat(o.lines().get(3).amount()).isEqualByComparingTo("1800");
        assertThat(o.gross()).isEqualByComparingTo("73802");
        assertThat(o.deductions()).isEqualByComparingTo("0");
        assertThat(o.net()).isEqualByComparingTo(o.gross());
        assertThat(o.lines()).allSatisfy(l -> assertThat(l.amount().scale()).isEqualTo(2));
    }

    @Test
    void fndFollowsTheCdaRate() {
        PayRules.Outcome o =
                PayRules.compute(
                        member(new BigDecimal("10000"), "FND", "Y", "IN_SERVICE", null, false),
                        config(true));
        assertThat(o.lines().get(1).amount()).isEqualByComparingTo("4500");
        assertThat(o.lines().get(2).rate()).as("the CDA HRA rule").isEqualByComparingTo("15");
    }

    // SRS-1-3 (US-GAP-41): "Hold HRA rates for CDA-pattern employees under their own rule."
    @Test
    void SRS_1_3_cdaStaffAreComputedUnderTheirOwnHraRuleNeverTheIdaOne() {
        PayRules.Outcome cda =
                PayRules.compute(
                        member(new BigDecimal("10000"), "CDA", "Y", "IN_SERVICE", null, false),
                        config(true));
        assertThat(cda.lines().get(2).payCode()).isEqualTo(PayRules.HRA);
        assertThat(cda.lines().get(2).rate()).isEqualByComparingTo("15");
        assertThat(cda.lines().get(2).amount()).isEqualByComparingTo("1500");
        PayRules.Outcome ida =
                PayRules.compute(
                        member(new BigDecimal("10000"), "IDA", "Y", "IN_SERVICE", null, false),
                        config(true));
        assertThat(ida.lines().get(2).rate()).isEqualByComparingTo("20");
        // Class X has an IDA rate but no CDA one: the CDA member is not computed (R-RULE).
        assertThatThrownBy(
                        () ->
                                PayRules.compute(
                                        member(
                                                new BigDecimal("10000"),
                                                "CDA",
                                                "X",
                                                "IN_SERVICE",
                                                null,
                                                false),
                                        config(true)))
                .isInstanceOf(PayDataException.class)
                .hasMessageContaining("HRA rate not seeded for city class X (CDA pattern)");
    }

    @Test
    void SRS_1_3_theSnapshotReadsTheHraOfThePatternAndNothingWithoutAClass() {
        PayConfiguration.Snapshot c = config(true);
        assertThat(c.hra("IDA", "X")).contains(new BigDecimal("30"));
        assertThat(c.hra("CDA", "Y")).contains(new BigDecimal("15"));
        assertThat(c.hra("FND", "Y")).contains(new BigDecimal("15"));
        assertThat(c.hra("CDA", "X")).isEmpty();
        assertThat(c.hra(null, "Y"))
                .as("no pattern reads the IDA rule")
                .contains(new BigDecimal("20"));
        assertThat(c.hra("IDA", null)).isEmpty();
        assertThat(c.hra("IDA", "Z")).isEmpty();
    }

    @Test
    void cityClassWithoutCcaAmountPaysNoCcaLine() {
        PayRules.Outcome o =
                PayRules.compute(
                        member(new BigDecimal("10000"), "IDA", "Y", "IN_SERVICE", null, false),
                        config(true));
        assertThat(o.lines()).extracting(PayRules.Line::payCode).containsExactly(101, 111, 121);
    }

    @Test
    void suspendedIsPaidSubsistenceOfBasicAndDaOnItButHraOnFullBasic() {
        PayRules.Outcome o =
                PayRules.compute(
                        member(
                                new BigDecimal("10000"),
                                "IDA",
                                "Y",
                                "SUSPENDED",
                                new BigDecimal("50"),
                                false),
                        config(true));
        assertThat(o.lines().get(0).amount()).isEqualByComparingTo("5000");
        assertThat(o.lines().get(1).amount()).isEqualByComparingTo("2500");
        assertThat(o.lines().get(2).amount()).isEqualByComparingTo("2000");
    }

    // BR-EMPR-011: Salary Stop suppresses the computation (distinct from Withheld).
    @Test
    void salaryStopAndMissingBasicAreSkippedNotErrors() {
        assertThat(
                        PayRules.compute(
                                        member(
                                                BigDecimal.ONE,
                                                "IDA",
                                                "X",
                                                "IN_SERVICE",
                                                null,
                                                true),
                                        config(true))
                                .skipReason())
                .isEqualTo("STOP_PAYMENT");
        assertThat(
                        PayRules.compute(
                                        member(null, "IDA", "X", "IN_SERVICE", null, false),
                                        config(true))
                                .skipReason())
                .isEqualTo("NO_BASIC_PAY");
    }

    // SC-16-0088: a member with missing master data is not computed and the reason is kept; the
    // others are.
    @Test
    void everyConfigurationOrDataGapRefusesToCompute() {
        PayConfiguration.Snapshot ok = config(true);
        assertThatThrownBy(
                        () ->
                                PayRules.compute(
                                        member(
                                                BigDecimal.ZERO,
                                                "IDA",
                                                "X",
                                                "IN_SERVICE",
                                                null,
                                                false),
                                        ok))
                .isInstanceOf(PayDataException.class)
                .hasMessageContaining("positive");
        assertThatThrownBy(
                        () ->
                                PayRules.compute(
                                        member(
                                                BigDecimal.TEN,
                                                "IDA",
                                                "X",
                                                "SUSPENDED",
                                                null,
                                                false),
                                        ok))
                .isInstanceOf(PayDataException.class)
                .hasMessageContaining("subsistence");
        assertThatThrownBy(
                        () ->
                                PayRules.compute(
                                        member(
                                                BigDecimal.TEN,
                                                null,
                                                "X",
                                                "IN_SERVICE",
                                                null,
                                                false),
                                        ok))
                .isInstanceOf(PayDataException.class)
                .hasMessageContaining("pay pattern");
        assertThatThrownBy(
                        () ->
                                PayRules.compute(
                                        member(
                                                BigDecimal.TEN,
                                                "XYZ",
                                                "X",
                                                "IN_SERVICE",
                                                null,
                                                false),
                                        ok))
                .isInstanceOf(PayDataException.class)
                .hasMessageContaining("DA rate not seeded");
        assertThatThrownBy(
                        () ->
                                PayRules.compute(
                                        member(
                                                BigDecimal.TEN,
                                                "IDA",
                                                null,
                                                "IN_SERVICE",
                                                null,
                                                false),
                                        ok))
                .isInstanceOf(PayDataException.class)
                .hasMessageContaining("city class");
        assertThatThrownBy(
                        () ->
                                PayRules.compute(
                                        member(
                                                BigDecimal.TEN,
                                                "IDA",
                                                "Z",
                                                "IN_SERVICE",
                                                null,
                                                false),
                                        ok))
                .isInstanceOf(PayDataException.class)
                .hasMessageContaining("HRA rate not seeded");
        assertThatThrownBy(
                        () ->
                                PayRules.compute(
                                        member(
                                                BigDecimal.TEN,
                                                "IDA",
                                                "X",
                                                "IN_SERVICE",
                                                null,
                                                false),
                                        config(false)))
                .isInstanceOf(PayDataException.class)
                .hasMessageContaining("CCA rates not seeded");
        PayConfiguration.Snapshot noComponent =
                new PayConfiguration.Snapshot(
                        ok.asOf(),
                        ok.daPercent(),
                        ok.hraPercent(),
                        ok.ccaAmount(),
                        true,
                        Map.of(101, 1L),
                        Map.of(),
                        "h");
        assertThatThrownBy(
                        () ->
                                PayRules.compute(
                                        member(
                                                BigDecimal.TEN,
                                                "IDA",
                                                "X",
                                                "IN_SERVICE",
                                                null,
                                                false),
                                        noComponent))
                .isInstanceOf(PayDataException.class)
                .hasMessageContaining("Pay code 111");
    }

    /**
     * R-02 (rounding-money): a component is rounded once, from the full working precision, never
     * twice. 2468.99 x 50 % = 1234.495: rounded once it is 1234; rounded to the paisa first it
     * would read 1234.50 and then 1235.
     */
    // SRS-G-17: two-decimal working precision, each component rounded half-up once, gross the sum.
    @Test
    void R_02_aComponentIsRoundedOnceFromTheWorkingPrecisionNeverTwice() {
        PayRules.Outcome o =
                PayRules.compute(
                        member(new BigDecimal("2468.99"), "IDA", "X", "IN_SERVICE", null, false),
                        config(true));
        assertThat(o.status()).isEqualTo(PayRules.COMPUTED);
        // DA 50 % of 2468.99 = 1234.495 -> 1234: from the working precision (R-01), never from a
        // basic already rounded to 2469 (which would give 1235).
        assertThat(o.lines().get(1).amount()).as("DA, rounded once").isEqualByComparingTo("1234");
        // HRA X 30 %: 740.697 -> 741 (half-up, once); CCA X 1800; every component incl. the basic
        // is a rounded rupee line (R-02) and the gross foots them exactly (R-03).
        assertThat(o.lines().get(0).amount()).isEqualByComparingTo("2469");
        assertThat(o.lines().get(2).amount()).isEqualByComparingTo("741");
        assertThat(o.gross()).isEqualByComparingTo("6244");
    }

    /** SRS-1-4: a CCA rate of zero for the city class pays no CCA line. */
    @Test
    void SRS_1_4_aZeroCcaPaysNoCcaLine() {
        PayConfiguration.Snapshot zero =
                new PayConfiguration.Snapshot(
                        LocalDate.of(2026, 7, 1),
                        Map.of("IDA", new BigDecimal("50")),
                        Map.of("X", new BigDecimal("30")),
                        Map.of("X", BigDecimal.ZERO),
                        true,
                        COMPONENTS,
                        Map.of(),
                        "hash");
        PayRules.Outcome o =
                PayRules.compute(
                        member(new BigDecimal("30000"), "IDA", "X", "ACTIVE", null, false), zero);
        assertThat(o.lines()).extracting(PayRules.Line::payCode).doesNotContain(PayRules.CCA);
    }
}
