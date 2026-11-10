package in.gov.fci.payroll.tax.business;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.tax.business.TaxCalculator.Band;
import in.gov.fci.payroll.tax.business.TaxCalculator.Input;
import in.gov.fci.payroll.tax.business.TaxCalculator.Rates;
import in.gov.fci.payroll.tax.business.TaxCalculator.Result;
import in.gov.fci.payroll.tax.business.model.ItRegimeDetail.Regime;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * SRS-7-1 … SRS-7-4, SRS-7-13, SRS-7-19, SRS-7-26: the tax computation. The bands and rates here
 * are test fixtures only; the system seeds none (OQ-4).
 */
class TaxCalculatorTest {

    private static BigDecimal rs(String v) {
        return new BigDecimal(v);
    }

    private static Band band(String from, String to, String rate) {
        return new Band(rs(from), to == null ? null : rs(to), rs(rate));
    }

    private static final List<Band> SLABS =
            List.of(
                    band("0", "250000", "0"),
                    band("250000", "500000", "5"),
                    band("500000", "1000000", "20"),
                    band("1000000", null, "30"));

    private static final Rates RATES =
            new Rates(
                    SLABS,
                    Optional.of(List.of(band("0", "5000000", "0"), band("5000000", null, "10"))),
                    Optional.of(rs("4")),
                    Optional.of(rs("20")));

    private static Input input(
            String gross, String deductions, boolean pan, String tds, int months) {
        return new Input(Regime.OLD, rs(gross), rs(deductions), pan, rs(tds), months);
    }

    // BR-TAX-004: monthly TDS is the projected annual tax spread over the remaining months.
    @Test
    void SRS_7_3_theAnnualTaxIsSpreadOverTheRemainingMonths() {
        Result r = TaxCalculator.compute(input("1200000", "150000", true, "60000", 6), RATES);
        assertThat(r.taxableIncome()).isEqualByComparingTo("1050000");
        // 0 + 12,500 + 1,00,000 + 15,000 (progressive slabs)
        assertThat(r.slabTax()).isEqualByComparingTo("127500");
        assertThat(r.surcharge()).isEqualByComparingTo("0");
        assertThat(r.cess()).isEqualByComparingTo("5100");
        assertThat(r.annualTax()).isEqualByComparingTo("132600");
        assertThat(r.higherRateApplied()).isFalse();
        // (1,32,600 - 60,000) / 6 = 12,100, a rupee amount (R-02)
        assertThat(r.monthlyTds()).isEqualByComparingTo("12100");
        assertThat(r.monthlyTds().scale()).isZero();
    }

    @Test
    void SRS_7_3_theMonthlyTdsRoundsHalfUpOnceAndAnOverDeductionIsNeverRefunded() {
        // (1,32,600 - 0) / 7 = 18,942.857 -> 18,943
        assertThat(
                        TaxCalculator.compute(input("1200000", "150000", true, "0", 7), RATES)
                                .monthlyTds())
                .isEqualByComparingTo("18943");
        Result over = TaxCalculator.compute(input("1200000", "150000", true, "200000", 3), RATES);
        assertThat(over.monthlyTds()).isEqualByComparingTo("0");
        assertThat(over.annualTax()).isEqualByComparingTo("132600");
    }

    @Test
    void SRS_7_26_theSurchargeIsTheBandOfTheTaxableIncomeOnTheSlabTax() {
        Result r = TaxCalculator.compute(input("6000000", "0", true, "0", 12), RATES);
        // slab tax: 12,500 + 1,00,000 + 15,00,000 = 16,12,500; surcharge 10 %; cess 4 % of both
        assertThat(r.slabTax()).isEqualByComparingTo("1612500");
        assertThat(r.surcharge()).isEqualByComparingTo("161250");
        assertThat(r.cess()).isEqualByComparingTo("70950");
        assertThat(r.annualTax()).isEqualByComparingTo("1844700");
        // A band boundary belongs to the band above (from <= x < to).
        assertThat(TaxCalculator.surchargePercent(rs("5000000"), RATES.surcharge().get()))
                .isEqualByComparingTo("10");
        assertThat(TaxCalculator.surchargePercent(rs("4999999.99"), RATES.surcharge().get()))
                .isEqualByComparingTo("0");
        assertThat(TaxCalculator.surchargePercent(rs("1"), List.of(band("5", null, "10"))))
                .isEqualByComparingTo("0");
    }

    @Test
    void SRS_7_13_withoutAValidPanTheHigherOfTheNormalTaxAndTheNoPanRateApplies() {
        Result low = TaxCalculator.compute(input("1200000", "150000", false, "0", 12), RATES);
        assertThat(low.annualTax()).isEqualByComparingTo("210000");
        assertThat(low.higherRateApplied()).isTrue();
        // Where the normal tax is higher it stays (the PAN rate is a floor, not a discount).
        Rates lowPanRate =
                new Rates(SLABS, RATES.surcharge(), RATES.cessPercent(), Optional.of(rs("1")));
        Result high = TaxCalculator.compute(input("1200000", "150000", false, "0", 12), lowPanRate);
        assertThat(high.annualTax()).isEqualByComparingTo("132600");
        assertThat(high.higherRateApplied()).isFalse();
    }

    @Test
    void SRS_7_26_aRateNeededButNotConfiguredIsNamedAndOneNotNeededIsNotAsked() {
        Rates noCess = new Rates(SLABS, RATES.surcharge(), Optional.empty(), RATES.noPanPercent());
        assertThatThrownBy(
                        () -> TaxCalculator.compute(input("1200000", "0", true, "0", 12), noCess))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> {
                            assertThat(e.getErrorCode())
                                    .isEqualTo(TaxErrorCode.RATE_NOT_CONFIGURED);
                            assertThat(e.getMessageArgs()).containsExactly("TAX.CESS");
                        });
        Rates noSurcharge =
                new Rates(SLABS, Optional.empty(), RATES.cessPercent(), RATES.noPanPercent());
        assertThatThrownBy(
                        () ->
                                TaxCalculator.compute(
                                        input("1200000", "0", true, "0", 12), noSurcharge))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> assertThat(e.getMessageArgs()).containsExactly("TAX.SURCHARGE"));
        Rates noPanRate =
                new Rates(SLABS, RATES.surcharge(), RATES.cessPercent(), Optional.empty());
        assertThatThrownBy(
                        () ->
                                TaxCalculator.compute(
                                        input("1200000", "0", false, "0", 12), noPanRate))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> assertThat(e.getMessageArgs()).containsExactly("TAX.NOPAN.RATE"));
        // Below the first taxable band there is no tax: neither surcharge nor cess is read.
        Rates none = new Rates(SLABS, Optional.empty(), Optional.empty(), Optional.empty());
        Result nil = TaxCalculator.compute(input("200000", "0", true, "0", 12), none);
        assertThat(nil.annualTax()).isEqualByComparingTo("0");
        // A valid PAN never reads the no-PAN rate.
        assertThat(
                        TaxCalculator.compute(input("1200000", "0", true, "0", 12), noPanRate)
                                .higherRateApplied())
                .isFalse();
    }

    @Test
    void SRS_7_1_aRegimeAndAtLeastOneMonthAreRequired() {
        assertThatThrownBy(
                        () ->
                                TaxCalculator.compute(
                                        new Input(null, rs("1"), rs("0"), true, rs("0"), 1), RATES))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(TaxErrorCode.REGIME_REQUIRED));
        assertThatThrownBy(() -> TaxCalculator.compute(input("1", "0", true, "0", 0), RATES))
                .isInstanceOf(IllegalArgumentException.class);
        // Deductions above the income leave no negative taxable income.
        assertThat(TaxCalculator.compute(input("100", "500", true, "0", 1), RATES).taxableIncome())
                .isEqualByComparingTo("0");
    }

    @Test
    void SRS_7_4_theMonthlyPtIsTheStateBandOfTheGross() {
        List<Band> pt = List.of(band("10000", "15000", "150"), band("15000", null, "200"));
        assertThat(TaxCalculator.professionalTax(true, rs("20000"), pt))
                .isEqualByComparingTo("200");
        assertThat(TaxCalculator.professionalTax(true, rs("15000"), pt))
                .isEqualByComparingTo("200");
        assertThat(TaxCalculator.professionalTax(true, rs("14999.99"), pt))
                .isEqualByComparingTo("150");
        // Below every band, or PT not applying in the state: nothing.
        assertThat(TaxCalculator.professionalTax(true, rs("5000"), pt)).isEqualByComparingTo("0");
        assertThat(TaxCalculator.professionalTax(false, rs("20000"), pt)).isEqualByComparingTo("0");
        assertThat(
                        TaxCalculator.professionalTax(
                                true, rs("20000"), List.of(band("15000", null, "199.50"))))
                .isEqualByComparingTo("200");
    }

    @Test
    void SRS_7_19_theYearEndDifferenceIsTheActualTaxLessTheTdsDeducted() {
        assertThat(TaxCalculator.yearEndDifference(rs("132600"), rs("132600")))
                .isEqualByComparingTo("0");
        assertThat(TaxCalculator.yearEndDifference(rs("132600"), rs("130000.50")))
                .isEqualByComparingTo("2599.50");
        assertThat(TaxCalculator.yearEndDifference(rs("100"), rs("150")))
                .isEqualByComparingTo("-50");
    }

    @Test
    void SRS_7_1_theProgressiveSlabTaxStopsAtTheIncome() {
        assertThat(TaxCalculator.slabTax(rs("250000"), SLABS)).isEqualByComparingTo("0");
        assertThat(TaxCalculator.slabTax(rs("250001"), SLABS)).isEqualByComparingTo("0.05");
        assertThat(TaxCalculator.slabTax(rs("500000"), SLABS)).isEqualByComparingTo("12500");
        assertThat(TaxCalculator.slabTax(rs("0"), SLABS)).isEqualByComparingTo("0");
    }

    /**
     * SRS-7-1 boundary: an income equal to a band's lower limit has nothing inside that band, so
     * the band adds nothing at all; an income at the first band's start reaches no band and the
     * slab tax is the plain zero.
     */
    @Test
    void SRS_7_1_anIncomeAtABandsLowerLimitTakesNothingFromThatBand() {
        assertThat(TaxCalculator.slabTax(rs("0"), SLABS)).isEqualTo(BigDecimal.ZERO);
        List<Band> fromThreeLakh = List.of(band("300000", null, "5"));
        assertThat(TaxCalculator.slabTax(rs("300000"), fromThreeLakh)).isEqualTo(BigDecimal.ZERO);
        assertThat(TaxCalculator.slabTax(rs("300001"), fromThreeLakh)).isEqualTo(rs("0.05"));
    }

    @Test
    void SRS_7_13_aNoPanTaxEqualToTheSlabTaxIsNotAHigherRate() {
        Rates flat =
                new Rates(
                        List.of(band("0", null, "20")),
                        Optional.of(List.of(band("0", null, "0"))),
                        Optional.of(rs("0")),
                        Optional.of(rs("20")));
        Result r = TaxCalculator.compute(input("100000", "0", false, "0", 12), flat);
        assertThat(r.annualTax()).isEqualByComparingTo("20000");
        assertThat(r.higherRateApplied()).isFalse();
    }

    // US-GAP-192 / US-GAP-187 (SRS-7-3, BR-TAX-004, BR-TAX-005): in the last month of the year
    // (one month remaining) the whole balance still due is the month's TDS, so a shortfall is
    // recovered in the year; the spread never runs over no month at all.
    @Test
    void SRS_7_3_inTheLastMonthTheWholeBalanceIsTheMonthsTdsAndNoMonthLeftIsRefused() {
        // 1,32,600 annual less 60,000 deducted: 72,600 in March.
        assertThat(
                        TaxCalculator.compute(input("1200000", "150000", true, "60000", 1), RATES)
                                .monthlyTds())
                .isEqualByComparingTo("72600");
        // Two months left: half each, rounded once.
        assertThat(
                        TaxCalculator.compute(input("1200000", "150000", true, "60001", 2), RATES)
                                .monthlyTds())
                .isEqualByComparingTo("36300");
        assertThatThrownBy(
                        () ->
                                TaxCalculator.compute(
                                        input("1200000", "150000", true, "0", 0), RATES))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // US-GAP-192 (SRS-7-3): the months left are the months not locked; a year with every month
    // locked, or only March open, has one.
    @Test
    void SRS_7_3_theRemainingMonthsAreTheMonthsNotLockedAndAtLeastOne() {
        assertThat(year(12).remainingMonths()).as("every month locked").isEqualTo(1);
        assertThat(year(11).remainingMonths()).as("only March open").isEqualTo(1);
        assertThat(year(11).allActual()).isFalse();
        assertThat(year(6).remainingMonths()).isEqualTo(6);
        assertThat(year(0).remainingMonths()).isEqualTo(12);
    }

    // US-GAP-188 (FRS V0.5 §14 l.5378: "include TDS from other sources"): TDS deducted elsewhere
    // counts against the year's tax like TDS deducted in payroll. BMAD TR-019: not SRS-7-11 (other
    // income taxable), which TaxComputationServiceTest.SRS_7_11_SRS_7_27_... proves.
    @Test
    void US_GAP_188_tdsFromOtherSourcesReducesWhatIsStillDue() {
        Result payrollOnly =
                TaxCalculator.compute(input("1200000", "150000", true, "60000", 6), RATES);
        Result withOther =
                TaxCalculator.compute(input("1200000", "150000", true, "66000", 6), RATES);
        assertThat(payrollOnly.monthlyTds()).isEqualByComparingTo("12100");
        assertThat(withOther.monthlyTds()).isEqualByComparingTo("11100");
        assertThat(withOther.annualTax()).isEqualByComparingTo(payrollOnly.annualTax());
    }

    /** A year of twelve months, the first {@code locked} of them locked. */
    private static TaxableEarnings.Year year(int locked) {
        List<TaxableEarnings.Month> months = new java.util.ArrayList<>();
        for (int i = 0; i < 12; i++) {
            months.add(
                    new TaxableEarnings.Month(
                            String.valueOf(202604 + i),
                            i < locked,
                            rs("50000"),
                            BigDecimal.ZERO,
                            BigDecimal.ZERO,
                            List.of()));
        }
        return new TaxableEarnings.Year(months);
    }
}
