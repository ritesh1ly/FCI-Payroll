package in.gov.fci.payroll.labour.business;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.labour.business.AttendanceRules.BatchState;
import in.gov.fci.payroll.labour.business.AttendanceRules.Code;
import in.gov.fci.payroll.labour.business.AttendanceRules.MonthCount;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Labour attendance rules (plan 20 phase 20b; SRS-3-1, SRS-2-35, SRS-2-38, SRS-2-29; D20-8): the
 * month's counts from the daily marks, the calendar cap, a whole day, the batch state.
 */
class AttendanceRulesTest {

    private static final Code W = new Code("W", BigDecimal.ONE, false, true);
    private static final Code A = new Code("A", BigDecimal.ZERO, false, true);
    private static final Code H = new Code("H", BigDecimal.ONE, false, true);
    private static final Code S = new Code("S", BigDecimal.ONE, true, true);
    private static final Code F = new Code("F", new BigDecimal("0.5"), true, true);
    private static final Code M = new Code("M", BigDecimal.ZERO, true, false);

    // SC-20-0157: present days by the payroll type's formula.
    @Test
    void SRS_3_1_presentDaysAreWKDHSBAndHalfOfF_medicalExcluded() {
        MonthCount c = AttendanceRules.month(YearMonth.of(2026, 2), List.of(W, W, H, S, F, M, A));
        assertThat(c.calendarDays()).isEqualTo(28);
        assertThat(c.marked()).isEqualTo(7);
        assertThat(c.present()).isEqualByComparingTo("4.5");
        assertThat(c.leave()).as("M and the half of F").isEqualByComparingTo("1.5");
        assertThat(c.absent()).isEqualByComparingTo("1");
        assertThat(AttendanceRules.month(YearMonth.of(2028, 2), List.of()).calendarDays())
                .isEqualTo(29);
    }

    // SC-20-0157: present days never exceed the calendar days.
    @Test
    void SRS_2_38_theMonthNeverCountsMoreDaysThanTheCalendar() {
        YearMonth april = YearMonth.of(2026, 4);
        assertThat(
                        AttendanceRules.withinCalendar(
                                AttendanceRules.month(april, Collections.nCopies(30, W))))
                .isTrue();
        assertThat(
                        AttendanceRules.withinCalendar(
                                AttendanceRules.month(april, Collections.nCopies(31, W))))
                .isFalse();
        assertThat(
                        AttendanceRules.withinCalendar(
                                new MonthCount(
                                        30,
                                        30,
                                        new BigDecimal("31"),
                                        BigDecimal.ZERO,
                                        BigDecimal.ZERO)))
                .isFalse();
        assertThat(
                        AttendanceRules.withinCalendar(
                                new MonthCount(
                                        30,
                                        30,
                                        new BigDecimal("20"),
                                        new BigDecimal("6"),
                                        new BigDecimal("5"))))
                .isFalse();
    }

    @Test
    void SRS_2_35_everyMemberOfTheGangIsMarkedWithACodeOfTheSet() {
        LocalDate day = LocalDate.of(2026, 10, 27);
        LocalDate today = LocalDate.of(2026, 10, 28);
        Set<String> set = Set.of("W", "A");
        assertThat(
                        AttendanceRules.dayDefect(
                                day, today, Set.of(1L, 2L), Map.of(1L, "W", 2L, "A"), set))
                .isEmpty();
        assertThat(
                        AttendanceRules.dayDefect(
                                today.plusDays(1), today, Set.of(1L), Map.of(1L, "W"), set))
                .contains("FUTURE");
        assertThat(AttendanceRules.dayDefect(day, today, Set.of(1L, 2L), Map.of(1L, "W"), set))
                .contains("UNMARKED");
        assertThat(AttendanceRules.dayDefect(day, today, Set.of(1L), Map.of(1L, "W", 3L, "W"), set))
                .contains("NOT_A_MEMBER 3");
        assertThat(AttendanceRules.dayDefect(day, today, Set.of(1L), Map.of(1L, "P"), set))
                .contains("UNKNOWN_CODE P");
        assertThat(AttendanceRules.dayDefect(day, today, Set.of(), Map.of(), set)).isEmpty();
    }

    @Test
    void SRS_2_29_aSubmittedOrApprovedBatchIsNotChanged() {
        assertThat(BatchState.of(null)).isEqualTo(BatchState.OPEN);
        assertThat(BatchState.of("PENDING_APPROVAL")).isEqualTo(BatchState.SUBMITTED);
        assertThat(BatchState.of("PENDING_ATTACHMENT")).isEqualTo(BatchState.SUBMITTED);
        assertThat(BatchState.of("DRAFT")).isEqualTo(BatchState.SUBMITTED);
        assertThat(BatchState.of("APPROVED")).isEqualTo(BatchState.APPROVED);
        assertThat(BatchState.of("RETURNED")).isEqualTo(BatchState.RETURNED);
        assertThat(BatchState.of("REJECTED")).isEqualTo(BatchState.RETURNED);
        assertThat(BatchState.OPEN.editable()).isTrue();
        assertThat(BatchState.RETURNED.editable()).isTrue();
        assertThat(BatchState.SUBMITTED.editable()).isFalse();
        assertThat(BatchState.APPROVED.editable()).isFalse();
    }

    @Test
    void D20_8_aBatchIsAGangAndAMonth() {
        assertThat(AttendanceRules.batchKey(5, YearMonth.of(2026, 9))).isEqualTo("5:202609");
        assertThat(AttendanceRules.yymm(YearMonth.of(2027, 1))).isEqualTo("202701");
        LocalDate today = LocalDate.of(2026, 10, 28);
        assertThat(AttendanceRules.lastMarkable(YearMonth.of(2026, 10), today)).isEqualTo(today);
        assertThat(AttendanceRules.lastMarkable(YearMonth.of(2026, 9), today))
                .isEqualTo(LocalDate.of(2026, 9, 30));
    }

    // SC-21-0005, SC-21-0018: the first Sunday of a split week is marked absent.
    @Test
    void SRS_4_5_theFirstSundayOfAWeekBegunInThePreviousMonthIsASplitWeek() {
        // 2026-11-01 is a Sunday: its week began on Monday 2026-10-26.
        assertThat(AttendanceRules.splitWeekDays(LocalDate.of(2026, 11, 1)))
                .containsExactly(
                        LocalDate.of(2026, 10, 26),
                        LocalDate.of(2026, 10, 27),
                        LocalDate.of(2026, 10, 28),
                        LocalDate.of(2026, 10, 29),
                        LocalDate.of(2026, 10, 30),
                        LocalDate.of(2026, 10, 31));
        // 2026-02-01 is a Sunday: its week began on Monday 2026-01-26.
        assertThat(AttendanceRules.splitWeekDays(LocalDate.of(2026, 2, 1))).hasSize(6);
        // A first Sunday on the 6th: the week began on the 31st of the previous month.
        assertThat(AttendanceRules.splitWeekDays(LocalDate.of(2026, 9, 6)))
                .containsExactly(LocalDate.of(2026, 8, 31));
        // A first Sunday on the 7th: the whole week is in the month.
        assertThat(AttendanceRules.splitWeekDays(LocalDate.of(2026, 6, 7))).isEmpty();
        // Not a Sunday, or a later Sunday.
        assertThat(AttendanceRules.splitWeekDays(LocalDate.of(2026, 11, 2))).isEmpty();
        assertThat(AttendanceRules.splitWeekDays(LocalDate.of(2026, 11, 8))).isEmpty();
    }

    @Test
    void SRS_4_5_onlyAnAbsenceOnEveryDayOfTheSplitWeekMarksTheSunday() {
        List<LocalDate> days = AttendanceRules.splitWeekDays(LocalDate.of(2026, 9, 6));
        LocalDate last = LocalDate.of(2026, 8, 31);
        assertThat(AttendanceRules.absentThroughout(days, Map.of(last, "A"))).isTrue();
        assertThat(AttendanceRules.absentThroughout(days, Map.of(last, "W"))).isFalse();
        assertThat(AttendanceRules.absentThroughout(days, Map.of())).as("not marked").isFalse();
        assertThat(AttendanceRules.absentThroughout(List.of(), Map.of())).isFalse();
        List<LocalDate> week = AttendanceRules.splitWeekDays(LocalDate.of(2026, 11, 1));
        Map<LocalDate, String> mostly = new java.util.HashMap<>();
        week.forEach(d -> mostly.put(d, "A"));
        assertThat(AttendanceRules.absentThroughout(week, mostly)).isTrue();
        mostly.put(week.get(3), "M");
        assertThat(AttendanceRules.absentThroughout(week, mostly)).isFalse();
    }

    // US-GAP-332 (SRS-4-5 l.4729, read as D21-6, PENDING DPS-21a-1): a month starting on a Sunday
    // opens with a split week; a labour with no marks in the previous month, or one day not absent,
    // is not marked absent on that Sunday.
    @Test
    void SRS_4_5_aMonthStartingOnSundayAndNoPreviousMonthMarksMarkNothing() {
        java.util.List<LocalDate> week = AttendanceRules.splitWeekDays(LocalDate.of(2026, 11, 1));
        assertThat(AttendanceRules.absentThroughout(week, java.util.Map.of())).isFalse();
        java.util.Map<LocalDate, String> allAbsent = new java.util.HashMap<>();
        week.forEach(d -> allAbsent.put(d, "A"));
        assertThat(AttendanceRules.absentThroughout(week, allAbsent)).isTrue();
        allAbsent.put(week.get(5), "W");
        assertThat(AttendanceRules.absentThroughout(week, allAbsent)).isFalse();
        assertThat(AttendanceRules.absentThroughout(java.util.List.of(), java.util.Map.of()))
                .as("no split week")
                .isFalse();
    }

    // SRS-2-39 (PENDING LBR-20b-1): an approved batch takes no direct change; a change of an
    // approved day is the back-dated correction, which waits for the Headquarters limit.
    @Test
    void SRS_2_39_anApprovedBatchTakesNoDirectChange() {
        assertThat(BatchState.APPROVED.editable()).isFalse();
        assertThat(BatchState.SUBMITTED.editable()).isFalse();
        assertThat(BatchState.RETURNED.editable()).isTrue();
        assertThat(BatchState.OPEN.editable()).isTrue();
    }
}
