package in.gov.fci.payroll.arrear.business;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.arrear.business.ArrearRules.Differential;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Plan 13 phase 13a: the payment of approved arrears (SRS-8-3, SRS-8-17, SRS-8-21). */
class ArrearRulesTest {

    private static Differential d(int code, String amount) {
        return new Differential(code, new BigDecimal(amount));
    }

    private static BigDecimal n(String v) {
        return new BigDecimal(v);
    }

    @Test
    void SRS_8_21_positiveComponentTotalsArePaidAndNegativeOnesRecovered() {
        ArrearRules.Split split =
                ArrearRules.split(
                        List.of(
                                d(111, "1200"),
                                d(111, "-200"),
                                d(121, "-300"),
                                d(131, "50"),
                                d(131, "-50")));
        assertThat(split.earnings()).containsOnlyKeys(111);
        assertThat(split.earnings().get(111)).isEqualByComparingTo("1000");
        assertThat(split.recovery()).isEqualByComparingTo("300");
        assertThat(ArrearRules.split(List.of()).recovery()).isEqualByComparingTo("0");
    }

    @Test
    void SRS_8_21_aComponentWhoseDifferentialsCancelIsNeitherPaidNorRecovered() {
        // 50.00 paid and 50.00 recovered on one pay code net to 0.00: nothing is added to the
        // recovery, which stays exactly zero (no scale carried over from the cancelled amounts).
        ArrearRules.Split split = ArrearRules.split(List.of(d(131, "50.00"), d(131, "-50.00")));
        assertThat(split.earnings()).isEmpty();
        assertThat(split.recovery()).isEqualTo(BigDecimal.ZERO);
        // One paisa either side of zero is paid or recovered.
        assertThat(ArrearRules.split(List.of(d(131, "0.01"))).earnings().get(131))
                .isEqualByComparingTo("0.01");
        assertThat(ArrearRules.split(List.of(d(131, "-0.01"))).recovery())
                .isEqualByComparingTo("0.01");
    }

    // SC-13-0056: a later revision pays only the difference over what was paid, never an arrear
    // twice.
    @Test
    void SRS_8_13_eachComponentIsDueLessPaidAndAComponentOnOneSideCountsAsZeroOnTheOther() {
        List<ArrearRules.Recomputed> d =
                ArrearRules.differentials(
                        java.util.Map.of(101, n("60000"), 111, n("33000"), 121, n("500")),
                        java.util.Map.of(101, n("62400"), 111, n("33000"), 131, n("300")));
        assertThat(d).extracting(ArrearRules.Recomputed::payCode).containsExactly(101, 121, 131);
        assertThat(d.get(0).arrear()).isEqualByComparingTo("2400");
        assertThat(d.get(1).arrear()).as("paid, no longer due").isEqualByComparingTo("-500");
        assertThat(d.get(1).recomputed()).isEqualByComparingTo("0");
        assertThat(d.get(2).original()).as("due, never paid").isEqualByComparingTo("0");
        assertThat(ArrearRules.differentials(java.util.Map.of(), java.util.Map.of())).isEmpty();
    }

    @Test
    void SRS_8_17_theRecoveryKeepsTheNetPayAtTheFloor() {
        BigDecimal gross = n("30000");
        assertThat(ArrearRules.recoverable(n("500"), gross, n("10000"), n("5000")))
                .as("room enough: in full")
                .isEqualByComparingTo("500");
        assertThat(ArrearRules.recoverable(n("9000"), gross, n("20000"), n("5000")))
                .as("only down to the floor")
                .isEqualByComparingTo("5000");
        assertThat(ArrearRules.recoverable(n("9000"), gross, n("28000"), n("5000")))
                .as("already below the floor: nothing")
                .isEqualByComparingTo("0");
    }

    /**
     * SRS-LV-19 / BR-LEAVE-011 (US-19-13 AC1, AC2): a leave recovery is taken in the next open
     * cycle only down to the net-pay floor; what is left is the outstanding recovery carried
     * forward.
     */
    @Test
    void SRS_LV_19_aLeaveRecoveryIsTakenWithinTheFloorAndTheRestCarried() {
        BigDecimal due = n("4000");
        BigDecimal taken = ArrearRules.recoverable(due, n("30000"), n("23000"), n("5000"));
        assertThat(taken).isEqualByComparingTo("2000");
        assertThat(due.subtract(taken)).as("carried forward").isEqualByComparingTo("2000");
        assertThat(ArrearRules.recoverable(due, n("30000"), n("21000"), n("5000")))
                .as("room exactly the due: nothing carried")
                .isEqualByComparingTo("4000");
        assertThat(ArrearRules.recoverable(due, n("30000"), n("21001"), n("5000")))
                .as("one rupee short: one rupee carried")
                .isEqualByComparingTo("3999");
    }

    @Test
    void SRS_8_17_theRecoveryAtExactlyTheFloorAndAZeroDifferentialAreBoundaries() {
        assertThat(ArrearRules.recoverable(n("100"), n("1000"), n("900"), n("0")))
                .as("room exactly the due")
                .isEqualByComparingTo("100");
        assertThat(ArrearRules.recoverable(n("100"), n("1000"), n("500"), n("500")))
                .as("no room at all")
                .isEqualByComparingTo("0");
        assertThat(ArrearRules.split(List.of(d(111, "0"))).earnings()).isEmpty();
        assertThat(ArrearRules.split(List.of(d(111, "0"))).recovery()).isEqualByComparingTo("0");
        assertThat(
                        ArrearRules.differentials(
                                java.util.Map.of(101, n("5")), java.util.Map.of(101, n("5"))))
                .as("equal: no differential")
                .isEmpty();
        assertThat(new ArrearRules.Recomputed(101, n("5"), n("3")).arrear())
                .isEqualByComparingTo("-2");
    }

    // SC-13-0005 / SC-13-0025: the effective span within the back-date limit, the month before it
    // beyond.
    @Test
    void SRS_8_6_anEventBeforeTheBackDateLimitIsBeyondTheWindow() {
        YearMonth current = YearMonth.of(2026, 10);
        assertThat(ArrearRules.beyondWindow(LocalDate.of(2021, 10, 1), current, 60))
                .as("the oldest month the limit reaches")
                .isFalse();
        assertThat(ArrearRules.beyondWindow(LocalDate.of(2021, 9, 30), current, 60)).isTrue();
        assertThat(ArrearRules.beyondWindow(LocalDate.of(2026, 10, 15), current, 0)).isFalse();
        assertThat(ArrearRules.beyondWindow(LocalDate.of(2026, 9, 30), current, 0)).isTrue();
    }
}
