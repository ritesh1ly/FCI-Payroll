package in.gov.fci.payroll.penalty.business;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.penalty.business.PenaltyRules.Line;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The penalty / recovery order rules (plan 12 phase 12a; SRS-14-1, SRS-14-15, SRS-G-19). Amounts
 * are fixtures.
 */
class PenaltyRulesTest {

    private static final YearMonth OPEN = YearMonth.of(2026, 11);

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    @Test
    void SRS_14_15_thePenaltyIdIsYearAndFiveDigitSequence() {
        assertThat(PenaltyRules.penaltyId(2026, 41)).isEqualTo("PEN-2026-00041");
        assertThat(PenaltyRules.penaltyId(2027, 12345)).isEqualTo("PEN-2027-12345");
    }

    @Test
    void SRS_14_1_theDeductionIsTheAmountOverTheInstalmentsTheLastTheResidual() {
        List<Line> s = PenaltyRules.schedule(d("10000"), 3, "202611");
        assertThat(s).extracting(Line::payrollYymm).containsExactly("202611", "202612", "202701");
        assertThat(s)
                .extracting(Line::amount)
                .containsExactly(d("3333.00"), d("3333.00"), d("3334.00"));
        assertThat(PenaltyRules.schedule(d("1000.50"), 2, "202611"))
                .extracting(Line::amount)
                .containsExactly(d("500.00"), d("500.50"));
        assertThat(PenaltyRules.schedule(d("750"), 1, "202611"))
                .extracting(Line::amount)
                .containsExactly(d("750.00"));
        assertThat(PenaltyRules.schedule(d("20"), 20, "202611"))
                .hasSize(20)
                .extracting(Line::amount)
                .containsOnly(d("1.00"));
    }

    /**
     * US-GAP-232: the per-period deduction is Recovery Amount / Number of Instalments (SRS l.3024):
     * 1,000 in 3 is 333, 333 and 334, adding up to the amount. Rounding down with the residual on
     * the last month is plan 12 D12-3, put to FCI as CLR-63.
     */
    @Test
    void SRS_14_1_aThousandInThreeInstalmentsAddsUpToTheAmount() {
        List<Line> s = PenaltyRules.schedule(d("1000"), 3, "202611");
        assertThat(s)
                .extracting(Line::amount)
                .containsExactly(d("333.00"), d("333.00"), d("334.00"));
        assertThat(s.stream().map(Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("1000");
        assertThat(PenaltyRules.schedule(d("999"), 3, "202611"))
                .as("an exact division has no residual")
                .extracting(Line::amount)
                .containsOnly(d("333.00"));
    }

    @Test
    void SRS_14_1_badTermsAreNamed() {
        assertThat(PenaltyRules.defect(d("10000"), 3, "202611", OPEN)).isEmpty();
        assertThat(PenaltyRules.defect(d("10000"), 999, "202612", OPEN)).isEmpty();
        assertThat(PenaltyRules.defect(null, 3, "202611", OPEN)).contains("AMOUNT");
        assertThat(PenaltyRules.defect(d("0"), 3, "202611", OPEN)).contains("AMOUNT");
        assertThat(PenaltyRules.defect(d("-5"), 3, "202611", OPEN)).contains("AMOUNT");
        assertThat(PenaltyRules.defect(d("10.005"), 1, "202611", OPEN)).contains("AMOUNT");
        assertThat(PenaltyRules.defect(d("10000"), 0, "202611", OPEN)).contains("INSTALMENTS");
        assertThat(PenaltyRules.defect(d("100000"), 1000, "202611", OPEN)).contains("INSTALMENTS");
        assertThat(PenaltyRules.defect(d("19.99"), 20, "202611", OPEN))
                .as("fewer rupees than instalments")
                .contains("INSTALMENTS");
        assertThat(PenaltyRules.defect(d("20"), 20, "202611", OPEN)).isEmpty();
        assertThat(PenaltyRules.defect(d("10000"), 3, "202610", OPEN))
                .as("before the first open month")
                .contains("START");
        assertThat(PenaltyRules.defect(d("10000"), 3, "202613", OPEN)).contains("START");
        assertThat(PenaltyRules.defect(d("10000"), 3, null, OPEN)).contains("START");
        assertThat(PenaltyRules.defect(d("10000"), 3, "2026-11", OPEN)).contains("START");
    }

    private static List<PenaltyRules.Month> months() {
        return List.of(
                new PenaltyRules.Month("202611", d("1000"), d("1000"), false),
                new PenaltyRules.Month("202612", d("1000"), d("0"), true),
                new PenaltyRules.Month("202701", d("1000"), d("0"), true),
                new PenaltyRules.Month("202702", d("1500"), d("0"), true));
    }

    // SC-12-0023: a penalty cancelled or waived reverses the recovery.
    @Test
    void SRS_14_16_aFullWaiverStopsEveryMonthDueAndReversesWhatWasRecovered() {
        PenaltyRules.Waiver w = PenaltyRules.waiveAmount(months(), null);
        assertThat(w.waive()).containsExactlyInAnyOrder(1, 2, 3);
        assertThat(w.reduce()).isEmpty();
        assertThat(w.reversal()).isEqualByComparingTo("1000");
    }

    @Test
    void SRS_14_16_aPartWaiverEndsTheRecoveryEarlierAndReducesTheMonthItCutsInto() {
        PenaltyRules.Waiver w = PenaltyRules.waiveAmount(months(), d("2000"));
        assertThat(w.waive()).containsExactly(3);
        assertThat(w.reduce())
                .hasValueSatisfying(
                        r -> {
                            assertThat(r.index()).isEqualTo(2);
                            assertThat(r.scheduled()).isEqualByComparingTo("500");
                        });
        assertThat(w.reversal()).isZero();
        PenaltyRules.Waiver exact = PenaltyRules.waiveAmount(months(), d("2500"));
        assertThat(exact.waive()).containsExactly(3, 2);
        assertThat(exact.reduce()).isEmpty();
        PenaltyRules.Waiver beyond = PenaltyRules.waiveAmount(months(), d("4000"));
        assertThat(beyond.waive()).containsExactly(3, 2, 1);
        assertThat(beyond.reversal())
                .as("500 already recovered is reversed")
                .isEqualByComparingTo("500");
    }

    @Test
    void SRS_14_16_aWaiverOfAnOrderNotYetRecoveredReachesItsFirstMonth() {
        List<PenaltyRules.Month> fresh =
                List.of(
                        new PenaltyRules.Month("202612", d("1000"), d("0"), true),
                        new PenaltyRules.Month("202701", d("1000"), d("0"), true));
        PenaltyRules.Waiver whole = PenaltyRules.waiveAmount(fresh, null);
        assertThat(whole.waive()).containsExactly(1, 0);
        assertThat(whole.reversal()).isZero();
        PenaltyRules.Waiver all = PenaltyRules.waiveAmount(fresh, d("2000"));
        assertThat(all.waive()).containsExactly(1, 0);
        assertThat(all.reduce()).isEmpty();
        // 1500 waives the last month and cuts the first to 500.
        PenaltyRules.Waiver part = PenaltyRules.waiveAmount(fresh, d("1500"));
        assertThat(part.waive()).containsExactly(1);
        assertThat(part.reduce())
                .hasValueSatisfying(
                        r -> {
                            assertThat(r.index()).isZero();
                            assertThat(r.scheduled()).isEqualByComparingTo("500");
                        });
    }

    @Test
    void SRS_14_16_aPeriodWaiverStopsItsMonthsAndReversesTheirRecovery() {
        PenaltyRules.Waiver w = PenaltyRules.waivePeriod(months(), "202611", "202612");
        assertThat(w.waive()).containsExactly(1);
        assertThat(w.reversal()).isEqualByComparingTo("1000");
        assertThat(PenaltyRules.waivePeriod(months(), "202701", "202702").waive())
                .containsExactly(2, 3);
        assertThat(PenaltyRules.waivePeriod(months(), "203001", "203012").waive()).isEmpty();
    }

    // SC-13-0089: a reversed penalty event reverses its recovery through the arrear.
    @Test
    void SRS_21_10_theReversingArrearRefundsTheLatestRecoveriesWithinTheWaiver() {
        List<PenaltyRules.Month> recovered =
                List.of(
                        new PenaltyRules.Month("202609", d("1000"), d("1000"), false),
                        new PenaltyRules.Month("202610", d("1000"), d("600"), false),
                        new PenaltyRules.Month("202611", d("1000"), d("1000"), false),
                        new PenaltyRules.Month("202612", d("1000"), d("0"), true));
        assertThat(PenaltyRules.refunds(recovered, d("1300"), null, null))
                .as("the latest recoveries first, in month order")
                .containsExactly(
                        new PenaltyRules.Refund("202610", d("300")),
                        new PenaltyRules.Refund("202611", d("1000")));
        assertThat(PenaltyRules.refunds(recovered, d("2600"), null, null))
                .extracting(PenaltyRules.Refund::amount)
                .containsExactly(d("1000"), d("600"), d("1000"));
        assertThat(PenaltyRules.refunds(recovered, d("1000"), "202609", "202609"))
                .containsExactly(new PenaltyRules.Refund("202609", d("1000")));
        assertThat(PenaltyRules.refunds(recovered, d("1000"), "202612", "202701")).isEmpty();
        assertThat(PenaltyRules.refunds(recovered, BigDecimal.ZERO, null, null)).isEmpty();
    }
}
