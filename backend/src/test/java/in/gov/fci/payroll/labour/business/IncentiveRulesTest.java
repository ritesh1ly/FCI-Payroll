package in.gov.fci.payroll.labour.business;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.labour.business.IncentiveRules.Band;
import in.gov.fci.payroll.labour.business.IncentiveRules.Pay;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Departmental gang incentive (plan 20 phase 20d; SRS-3-3 to SRS-3-8; D20-11): BPL, BEMSL, ODW, PBW
 * and TPBW by the SRS formulas, the progressive slab bands, the three norms added, the Sardar and
 * Mandal share. The figures are worked by hand below each assertion.
 */
class IncentiveRulesTest {

    private static final List<Band> BAND_1 =
            List.of(
                    new Band(1, 30L, new BigDecimal("8")),
                    new Band(31, 60L, new BigDecimal("15")),
                    new Band(61, 90L, new BigDecimal("35")),
                    new Band(91, null, new BigDecimal("50")));

    @Test
    void SRS_3_4_bagsPerLabourAndTheExcessOverTheMinimum() {
        // BPL = (TB x MLN) / (MLSW x TPL) = (12000 x 10) / (8 x 10) = 1500
        BigDecimal bpl = IncentiveRules.bagsPerLabour(12_000, 10, new BigDecimal("8"), 10);
        assertThat(bpl).isEqualByComparingTo("1500");
        // BEMSL = BPL - MLN = 1500 - 10
        assertThat(IncentiveRules.excessBags(bpl, 10))
                .hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("1490"));
        // (1000 x 10) / (3 x 7) = 476.1905 at four decimals
        assertThat(IncentiveRules.bagsPerLabour(1000, 10, new BigDecimal("3"), 7))
                .isEqualByComparingTo("476.1905");
        // BMAD TR-054: SRS-3-4 states no outcome for a zero divisor (no labour present, or a zero
        // minimum slab weight); the as-built zero (no incentive) is kept until FCI answers
        // (CLR-141).
        assertThat(IncentiveRules.bagsPerLabour(1000, 10, BigDecimal.ONE, 0))
                .as("no labour present, as built (CLR-141)")
                .isEqualByComparingTo("0");
        assertThat(IncentiveRules.bagsPerLabour(1000, 10, BigDecimal.ZERO, 5))
                .isEqualByComparingTo("0");
    }

    @Test
    void SRS_3_3_noIncentiveWhenTheOutputDoesNotExceedTheMinimum() {
        assertThat(IncentiveRules.excessBags(new BigDecimal("10"), 10)).isEmpty();
        assertThat(IncentiveRules.excessBags(new BigDecimal("9.9999"), 10)).isEmpty();
        assertThat(IncentiveRules.excessBags(new BigDecimal("10.0001"), 10)).isPresent();
    }

    // BMAD TR-050 REJECTED: FRS R-01 (l.1062) carries every intermediate at two decimals (paise);
    // the rupee rounding of R-02 is applied once where the component is written to the payroll
    // result, which C-06 (parked, LBR-20d-2) will do. So ODW, the HRA + DA term and PBW are paise.
    @Test
    void SRS_3_5_theDailyAndPerBagWage() {
        // ODW = 21500 / 26 = 826.923... -> 826.92
        assertThat(IncentiveRules.ordinaryDailyWage(new BigDecimal("21500")))
                .isEqualByComparingTo("826.92");
        // PBW = ((HRA + DA) x 12 / 365 + ODW) / MLN = ((2000 + 9000) x 12 / 365 + 826.92) / 10
        //     = (361.64 + 826.92) / 10 = 118.856 -> 118.86
        Pay pay = new Pay(new BigDecimal("21500"), new BigDecimal("9000"), new BigDecimal("2000"));
        assertThat(IncentiveRules.perBagWage(pay, 10)).isEqualByComparingTo("118.86");
    }

    @Test
    void SRS_3_6_eachStepIsPaidAtItsOwnBandRate() {
        BigDecimal pbw = new BigDecimal("10.00");
        // 20 excess bags, all in 1-30 at 8%: 20 x 10.80 = 216.00
        assertThat(IncentiveRules.slabIncentive(new BigDecimal("20"), pbw, BAND_1))
                .isEqualByComparingTo("216.00");
        // 100 bags: 30 x 10.80 + 30 x 11.50 + 30 x 13.50 + 10 x 15.00 = 324 + 345 + 405 + 150 =
        // 1224.00
        assertThat(IncentiveRules.slabIncentive(new BigDecimal("100"), pbw, BAND_1))
                .isEqualByComparingTo("1224.00");
        // 30.5 bags: 30 x 10.80 + 0.5 x 11.50 = 324 + 5.75
        assertThat(IncentiveRules.slabIncentive(new BigDecimal("30.5"), pbw, BAND_1))
                .isEqualByComparingTo("329.75");
        assertThat(IncentiveRules.slabIncentive(new BigDecimal("0"), pbw, BAND_1))
                .isEqualByComparingTo("0.00");
        assertThat(
                        IncentiveRules.slabIncentive(
                                new BigDecimal("45"),
                                pbw,
                                List.of(
                                        BAND_1.get(2),
                                        BAND_1.get(0),
                                        BAND_1.get(1),
                                        BAND_1.get(3))))
                .as("bands in any order")
                .isEqualByComparingTo("496.50");
    }

    // FRS R-01 / R-02: the grand total stays at paise (no rupee rounding before the write point).
    @Test
    void SRS_3_7_theThreeNormsAddUp() {
        assertThat(
                        IncentiveRules.grandTotal(
                                new BigDecimal("1224.00"),
                                new BigDecimal("80.505"),
                                BigDecimal.ZERO))
                .isEqualByComparingTo("1304.51");
    }

    @Test
    void SRS_3_8_theSardarAndMandalTakeTheGangMaximumOnlyWhenPresent() {
        List<BigDecimal> labour =
                List.of(new BigDecimal("900"), new BigDecimal("1224.40"), new BigDecimal("1100"));
        assertThat(IncentiveRules.leaderShare(labour, true)).isEqualByComparingTo("1224.40");
        assertThat(IncentiveRules.leaderShare(labour, false)).isEqualByComparingTo("0");
        assertThat(IncentiveRules.leaderShare(List.of(), true)).isEqualByComparingTo("0");
    }

    // SC-20-0024: band tables by bag weight (66 kg and above).
    // SC-01-0191: the bag weight band comes from the master and chooses the slab table.
    @Test
    void SRS_3_6_theBagWeightChoosesTheSlabTable() {
        BigDecimal boundary = new BigDecimal("66");
        assertThat(IncentiveRules.slabTable(new BigDecimal("50"), boundary)).isEqualTo("BAND_1");
        assertThat(IncentiveRules.slabTable(new BigDecimal("66"), boundary)).isEqualTo("BAND_2");
        assertThat(IncentiveRules.slabTable(new BigDecimal("75"), boundary)).isEqualTo("BAND_2");
        // BMAD TR-053: SRS-3-6 gives no default for a missing bag weight; the as-built Band 1 is
        // kept (C-06 is not computed, LBR-20d-2) until FCI answers (CLR-140).
        assertThat(IncentiveRules.slabTable(null, boundary))
                .as("missing bag weight, as built (CLR-140)")
                .isEqualTo("BAND_1");
    }

    // US-GAP-313 (SRS-3-6 l.4233): Band 1 in 30-bag steps (1-30, 31-60, 61-90, above 90 at 8 / 15 /
    // 35 / 50 %) and Band 2 in 20-bag steps; each boundary bag is paid at its own band.
    @Test
    void SRS_3_6_theStepBoundaries30_60_90And20_40_60AreInclusive() {
        BigDecimal pbw = new BigDecimal("10.00");
        assertThat(IncentiveRules.slabIncentive(new BigDecimal("30"), pbw, BAND_1))
                .isEqualByComparingTo("324.00");
        assertThat(IncentiveRules.slabIncentive(new BigDecimal("31"), pbw, BAND_1))
                .isEqualByComparingTo("335.50");
        assertThat(IncentiveRules.slabIncentive(new BigDecimal("60"), pbw, BAND_1))
                .isEqualByComparingTo("669.00");
        assertThat(IncentiveRules.slabIncentive(new BigDecimal("90"), pbw, BAND_1))
                .isEqualByComparingTo("1074.00");
        assertThat(IncentiveRules.slabIncentive(new BigDecimal("91"), pbw, BAND_1))
                .isEqualByComparingTo("1089.00");
        List<Band> band2 =
                List.of(
                        new Band(1, 20L, new BigDecimal("8")),
                        new Band(21, 40L, new BigDecimal("15")),
                        new Band(41, 60L, new BigDecimal("35")),
                        new Band(61, null, new BigDecimal("50")));
        // 20 x 10.80 = 216; 21: + 11.50; 40: 216 + 230 = 446; 60: 446 + 270 = 716; 61: + 15.
        assertThat(IncentiveRules.slabIncentive(new BigDecimal("20"), pbw, band2))
                .isEqualByComparingTo("216.00");
        assertThat(IncentiveRules.slabIncentive(new BigDecimal("21"), pbw, band2))
                .isEqualByComparingTo("227.50");
        assertThat(IncentiveRules.slabIncentive(new BigDecimal("40"), pbw, band2))
                .isEqualByComparingTo("446.00");
        assertThat(IncentiveRules.slabIncentive(new BigDecimal("60"), pbw, band2))
                .isEqualByComparingTo("716.00");
        assertThat(IncentiveRules.slabIncentive(new BigDecimal("61"), pbw, band2))
                .isEqualByComparingTo("731.00");
        BigDecimal boundary = new BigDecimal("66");
        assertThat(IncentiveRules.slabTable(new BigDecimal("65.99"), boundary)).isEqualTo("BAND_1");
        assertThat(IncentiveRules.slabTable(new BigDecimal("66.00"), boundary)).isEqualTo("BAND_2");
    }

    // US-GAP-314 (SRS-3-8 l.4235): the Sardar and the Mandal are left out of the gang's
    // calculation;
    // they take the highest labour incentive only when present, never a share of their own.
    @Test
    void SRS_3_8_anAbsentLeaderTakesNothingAndOneLabourIsItsOwnMaximum() {
        List<BigDecimal> one = List.of(new BigDecimal("812.25"));
        assertThat(IncentiveRules.leaderShare(one, true)).isEqualByComparingTo("812.25");
        assertThat(IncentiveRules.leaderShare(one, false)).isEqualByComparingTo("0");
        List<BigDecimal> tied = List.of(new BigDecimal("500"), new BigDecimal("500.00"));
        assertThat(IncentiveRules.leaderShare(tied, true)).isEqualByComparingTo("500");
    }
}
