package in.gov.fci.payroll.leave.business;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.leave.business.LeavePayRules.Leave;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Plan 19 phase 19d: the month's leave pay reduction (SRS-LV-15, SRS-LV-27, SRS-LV-22). */
class LeavePayRulesTest {

    private static final YearMonth OCT = YearMonth.of(2026, 10);

    private static Leave leave(String from, String to, String days, String effect, boolean cancel) {
        return new Leave(
                LocalDate.parse(from), LocalDate.parse(to), new BigDecimal(days), effect, cancel);
    }

    @Test
    void SRS_LV_27_lwpCountsInFullHalfPayAtHalfAndOtherEffectsNot() {
        assertThat(
                        LeavePayRules.daysLost(
                                List.of(
                                        leave(
                                                "2026-10-05",
                                                "2026-10-07",
                                                "3",
                                                "FULL_DEDUCTION",
                                                false),
                                        leave("2026-10-12", "2026-10-13", "2", "HALF_PAY", false),
                                        leave("2026-10-20", "2026-10-21", "2", "NO_EFFECT", false)),
                                OCT))
                .isEqualByComparingTo("4");
    }

    @Test
    void SRS_LV_15_aLeaveAcrossMonthsCountsTheDaysInTheMonthAndACancellationGivesThemBack() {
        Leave across = leave("2026-09-28", "2026-10-03", "6", "FULL_DEDUCTION", false);
        assertThat(LeavePayRules.daysLost(List.of(across), OCT)).isEqualByComparingTo("3");
        assertThat(LeavePayRules.daysLost(List.of(across), YearMonth.of(2026, 9)))
                .isEqualByComparingTo("3");
        assertThat(LeavePayRules.daysLost(List.of(across), YearMonth.of(2026, 11))).isZero();
        Leave halfDays = leave("2026-10-01", "2026-10-04", "2", "FULL_DEDUCTION", false);
        assertThat(LeavePayRules.daysLost(List.of(halfDays), OCT)).isEqualByComparingTo("2");
        Leave partHalf = leave("2026-09-29", "2026-10-02", "2", "FULL_DEDUCTION", false);
        assertThat(LeavePayRules.daysLost(List.of(partHalf), OCT)).isEqualByComparingTo("1");
        Leave cancelled = leave("2026-10-05", "2026-10-07", "3", "FULL_DEDUCTION", true);
        assertThat(
                        LeavePayRules.daysLost(
                                List.of(
                                        leave(
                                                "2026-10-05",
                                                "2026-10-07",
                                                "3",
                                                "FULL_DEDUCTION",
                                                false),
                                        cancelled),
                                OCT))
                .isZero();
        assertThat(LeavePayRules.daysLost(List.of(cancelled), OCT)).as("never below zero").isZero();
    }

    /**
     * SRS-LV-15 boundary: days sanctioned equal to the span are whole days, counted as the overlap
     * itself; one day fewer than the span is the proportion of the overlap, to two places.
     */
    @Test
    void SRS_LV_15_daysEqualToTheSpanCountWholeDaysAndFewerAProportion() {
        Leave whole = leave("2026-09-28", "2026-10-03", "6", "FULL_DEDUCTION", false);
        assertThat(LeavePayRules.daysInMonth(whole, OCT)).isEqualTo(BigDecimal.valueOf(3));
        Leave oneShort = leave("2026-09-28", "2026-10-03", "5", "FULL_DEDUCTION", false);
        assertThat(LeavePayRules.daysInMonth(oneShort, OCT)).isEqualTo(new BigDecimal("2.50"));
    }

    @Test
    void SRS_LV_15_theDivisorIsConfiguredOrTheCalendarDaysAndNeverDefaulted() {
        assertThat(LeavePayRules.divisor(Optional.empty(), Optional.of("CALENDAR_DAYS"), OCT))
                .contains(BigDecimal.valueOf(31));
        assertThat(LeavePayRules.divisor(Optional.of(new BigDecimal("30")), Optional.empty(), OCT))
                .contains(new BigDecimal("30"));
        assertThat(LeavePayRules.divisor(Optional.of(BigDecimal.ZERO), Optional.empty(), OCT))
                .isEmpty();
        assertThat(LeavePayRules.divisor(Optional.empty(), Optional.empty(), OCT)).isEmpty();
        assertThat(LeavePayRules.divisor(Optional.empty(), Optional.of("OTHER"), OCT)).isEmpty();
    }

    @Test
    void SRS_LV_15_theReductionIsAmountOverDivisorTimesDaysRoundedOnceAndAtMostTheAmount() {
        assertThat(
                        LeavePayRules.reduction(
                                new BigDecimal("56100"),
                                BigDecimal.valueOf(31),
                                new BigDecimal("3")))
                .isEqualByComparingTo("5429");
        assertThat(
                        LeavePayRules.reduction(
                                new BigDecimal("1000"),
                                BigDecimal.valueOf(30),
                                new BigDecimal("31")))
                .isEqualByComparingTo("1000");
    }

    @Test
    void SRS_LV_22_theDaysLostNeverExceedTheDaysOfTheMonth() {
        assertThat(LeavePayRules.withinMonth(new BigDecimal("31"), OCT)).isTrue();
        assertThat(LeavePayRules.withinMonth(new BigDecimal("31.5"), OCT)).isFalse();
        // BMAD TR-049: the month's own days - February, a leap February, a 30-day month.
        java.time.YearMonth feb = java.time.YearMonth.of(2027, 2);
        java.time.YearMonth leapFeb = java.time.YearMonth.of(2028, 2);
        java.time.YearMonth sep = java.time.YearMonth.of(2026, 9);
        assertThat(LeavePayRules.withinMonth(new BigDecimal("28"), feb)).isTrue();
        assertThat(LeavePayRules.withinMonth(new BigDecimal("28.5"), feb)).isFalse();
        assertThat(LeavePayRules.withinMonth(new BigDecimal("29"), feb)).isFalse();
        assertThat(LeavePayRules.withinMonth(new BigDecimal("29"), leapFeb)).isTrue();
        assertThat(LeavePayRules.withinMonth(new BigDecimal("29.5"), leapFeb)).isFalse();
        assertThat(LeavePayRules.withinMonth(new BigDecimal("30"), sep)).isTrue();
        assertThat(LeavePayRules.withinMonth(new BigDecimal("31"), sep)).isFalse();
    }

    @Test
    void SRS_LV_22_theDaysOnTheRollsRunFromJoiningToSeparationBothCounted() {
        java.time.YearMonth sep = java.time.YearMonth.of(2026, 9);
        assertThat(LeavePayRules.daysOnRolls(LocalDate.of(2010, 1, 1), null, sep)).isEqualTo(30);
        assertThat(LeavePayRules.daysOnRolls(LocalDate.of(2026, 9, 20), null, sep)).isEqualTo(11);
        assertThat(
                        LeavePayRules.daysOnRolls(
                                LocalDate.of(2026, 9, 5), LocalDate.of(2026, 9, 5), sep))
                .as("joined and left the same day")
                .isEqualTo(1);
        assertThat(LeavePayRules.daysOnRolls(LocalDate.of(2026, 10, 1), null, sep))
                .as("joins after the month")
                .isZero();
        assertThat(
                        LeavePayRules.daysOnRolls(
                                LocalDate.of(2010, 1, 1), LocalDate.of(2026, 8, 31), sep))
                .as("left before the month")
                .isZero();
        // No joining date received: on the rolls from the first day of the month.
        assertThat(LeavePayRules.daysOnRolls(null, LocalDate.of(2026, 9, 10), sep)).isEqualTo(10);
        assertThat(LeavePayRules.daysOnRolls(null, null, sep)).isEqualTo(30);
        // Joined on the first or left on the last day: the whole month.
        assertThat(
                        LeavePayRules.daysOnRolls(
                                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), sep))
                .isEqualTo(30);
    }
}
