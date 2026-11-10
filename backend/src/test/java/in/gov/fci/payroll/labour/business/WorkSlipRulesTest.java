package in.gov.fci.payroll.labour.business;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.labour.business.WorkSlipRules.DuplicateKey;
import in.gov.fci.payroll.labour.business.WorkSlipRules.Row;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * Work-slip rules (plan 20 phase 20c; SRS-2-4, SRS-2-5, SRS-2-42, SRS-3-9; D20-9): a row's times,
 * bags and Present But No Work reason, the height band, the Departmental duplicate key, the slip
 * number.
 */
class WorkSlipRulesTest {

    private static Row bags(Long bagType, Integer bags, Integer stack, String start, String end) {
        return new Row(true, false, bagType, bags, stack, start, end, null, null);
    }

    private static Row pbnw(String reason, String text) {
        return new Row(false, true, null, null, null, "09:00", "11:00", reason, text);
    }

    @Test
    void SRS_2_4_aBagBasedRowHasItsBagTypeAndBags() {
        assertThat(WorkSlipRules.rowDefect(bags(1L, 1200, 22, "09:00", "13:00"), false)).isEmpty();
        assertThat(WorkSlipRules.rowDefect(bags(1L, 99_999, null, "09:00", "13:00"), false))
                .isEmpty();
        assertThat(WorkSlipRules.rowDefect(bags(null, 10, null, "09:00", "13:00"), false))
                .contains("BAGS");
        assertThat(WorkSlipRules.rowDefect(bags(1L, null, null, "09:00", "13:00"), false))
                .contains("BAGS");
        assertThat(WorkSlipRules.rowDefect(bags(1L, 0, null, "09:00", "13:00"), false))
                .contains("BAGS");
        assertThat(WorkSlipRules.rowDefect(bags(1L, 100_000, null, "09:00", "13:00"), false))
                .contains("BAGS");
        assertThat(WorkSlipRules.rowDefect(bags(1L, 10, 0, "09:00", "13:00"), false))
                .contains("STACK");
        Row time = new Row(false, false, null, null, null, "09:00", "13:00", null, null);
        assertThat(WorkSlipRules.rowDefect(time, false)).isEmpty();
        assertThat(
                        WorkSlipRules.rowDefect(
                                new Row(false, false, 1L, null, null, "09:00", "13:00", null, null),
                                false))
                .contains("BAGS");
        assertThat(
                        WorkSlipRules.rowDefect(
                                new Row(false, false, null, 5, null, "09:00", "13:00", null, null),
                                false))
                .contains("BAGS");
        assertThat(
                        WorkSlipRules.rowDefect(
                                new Row(false, false, null, null, 5, "09:00", "13:00", null, null),
                                false))
                .contains("BAGS");
    }

    // SC-20-0175: the Continue Work indicator lets the work run past midnight (the double overtime
    // waits for the duty window, PENDING LBR-20c-1).
    @Test
    void SRS_2_4_timesAreHhMmAndTheEndFollowsTheStartUnlessTheWorkContinues() {
        assertThat(WorkSlipRules.rowDefect(bags(1L, 10, null, "9:00", "13:00"), false))
                .contains("TIME");
        assertThat(WorkSlipRules.rowDefect(bags(1L, 10, null, "09:00", "24:00"), false))
                .contains("TIME");
        assertThat(WorkSlipRules.rowDefect(bags(1L, 10, null, null, "13:00"), false))
                .contains("TIME");
        assertThat(WorkSlipRules.rowDefect(bags(1L, 10, null, "13:00", "13:00"), false))
                .contains("TIME_ORDER");
        assertThat(WorkSlipRules.rowDefect(bags(1L, 10, null, "22:00", "02:00"), false))
                .contains("TIME_ORDER");
        assertThat(WorkSlipRules.rowDefect(bags(1L, 10, null, "22:00", "02:00"), true)).isEmpty();
        assertThat(WorkSlipRules.validTime("23:59")).isTrue();
        assertThat(WorkSlipRules.validTime("12:60")).isFalse();
    }

    @Test
    void SRS_2_42_presentButNoWorkNeedsItsReason() {
        assertThat(WorkSlipRules.rowDefect(pbnw("RAKE_DELAYED", null), false)).isEmpty();
        assertThat(WorkSlipRules.rowDefect(pbnw("OTHER", "Crane down"), false)).isEmpty();
        assertThat(WorkSlipRules.rowDefect(pbnw(null, null), false)).contains("PBNW_REASON");
        assertThat(WorkSlipRules.rowDefect(pbnw("LATE", null), false)).contains("PBNW_REASON");
        assertThat(WorkSlipRules.rowDefect(pbnw("OTHER", " "), false)).contains("PBNW_REASON");
        assertThat(WorkSlipRules.rowDefect(pbnw("OTHER", null), false)).contains("PBNW_REASON");
        assertThat(
                        WorkSlipRules.rowDefect(
                                new Row(
                                        true,
                                        false,
                                        1L,
                                        10,
                                        null,
                                        "09:00",
                                        "11:00",
                                        "RAKE_DELAYED",
                                        null),
                                false))
                .as("a reason only on Present But No Work")
                .contains("PBNW_REASON");
    }

    @Test
    void SRS_2_5_aStackAbove20IsAbove20NeverUpTo20() {
        assertThat(WorkSlipRules.heightBand(null)).isNull();
        assertThat(WorkSlipRules.heightBand(1)).isEqualTo("UPTO_20");
        assertThat(WorkSlipRules.heightBand(20)).isEqualTo("UPTO_20");
        assertThat(WorkSlipRules.heightBand(21)).isEqualTo("ABOVE_20");
        assertThat(WorkSlipRules.heightBand(30)).isEqualTo("ABOVE_20");
    }

    // SC-20-0174: the duplicate test of the payroll type.
    @Test
    void SRS_3_9_theDepartmentalDuplicateIsOperationNormSlabAndTimeFrame() {
        DuplicateKey a = new DuplicateKey(1, "HANDLING", "ABOVE_20", "09:00", "13:00");
        assertThat(
                        WorkSlipRules.duplicate(
                                a, new DuplicateKey(1, "HANDLING", "ABOVE_20", "09:00", "13:00")))
                .isTrue();
        assertThat(
                        WorkSlipRules.duplicate(
                                a, new DuplicateKey(2, "HANDLING", "ABOVE_20", "09:00", "13:00")))
                .isFalse();
        assertThat(
                        WorkSlipRules.duplicate(
                                a, new DuplicateKey(1, "HEIGHT", "ABOVE_20", "09:00", "13:00")))
                .isFalse();
        assertThat(
                        WorkSlipRules.duplicate(
                                a, new DuplicateKey(1, "HANDLING", "UPTO_20", "09:00", "13:00")))
                .isFalse();
        assertThat(
                        WorkSlipRules.duplicate(
                                a, new DuplicateKey(1, "HANDLING", null, "09:00", "13:00")))
                .isFalse();
        assertThat(
                        WorkSlipRules.duplicate(
                                a, new DuplicateKey(1, "HANDLING", "ABOVE_20", "09:30", "13:00")))
                .isFalse();
        assertThat(
                        WorkSlipRules.duplicate(
                                a, new DuplicateKey(1, "HANDLING", "ABOVE_20", "09:00", "13:30")))
                .isFalse();
    }

    @Test
    void D20_9_theSlipNumberNamesTheGangAndTheDay() {
        assertThat(WorkSlipRules.number(5, LocalDate.of(2026, 8, 1), 2))
                .isEqualTo("WS-5-20260801-2");
        assertThat(WorkSlipRules.number(123_456_789L, LocalDate.of(2026, 8, 1), 99).length())
                .isLessThanOrEqualTo(30);
    }

    @Test
    void SRS_2_4_theFirstAndLastBagCountAndTheLowestStackAreAccepted() {
        assertThat(WorkSlipRules.rowDefect(bags(1L, 1, 1, "09:00", "10:00"), false)).isEmpty();
        assertThat(
                        WorkSlipRules.rowDefect(
                                bags(1L, WorkSlipRules.MAX_BAGS, 1, "09:00", "10:00"), false))
                .isEmpty();
        assertThat(
                        WorkSlipRules.rowDefect(
                                bags(1L, WorkSlipRules.MAX_BAGS + 1, 1, "09:00", "10:00"), false))
                .contains("BAGS");
        assertThat(WorkSlipRules.rowDefect(bags(1L, 5, 0, "09:00", "10:00"), false))
                .contains("STACK");
    }

    // BMAD TR-055: the DPS booking-slip key is the gang, date and operation (SRS-2-34 l.4386), so a
    // second slip of the operation is refused whatever its time frame. SRS-4-7 (l.4731) keys on the
    // operation and time frame; the difference is asked of FCI (CLR-142); the stricter SRS-2-34 key
    // is kept as built.
    @Test
    void SRS_2_34_aDpsBookingSlipRepeatsTheOperationWhateverItsTimeFrame() {
        DuplicateKey morning = new DuplicateKey(1, "HANDLING", null, "09:00", "12:00");
        DuplicateKey evening = new DuplicateKey(1, "HANDLING", null, "13:00", "15:00");
        DuplicateKey other = new DuplicateKey(2, "HANDLING", null, "09:00", "12:00");
        assertThat(WorkSlipRules.duplicate("DPS_LABOUR", morning, evening)).isTrue();
        assertThat(WorkSlipRules.duplicate("DPS_LABOUR", morning, other)).isFalse();
        assertThat(WorkSlipRules.duplicate("DEPT_LABOUR", morning, evening)).isFalse();
        assertThat(WorkSlipRules.duplicate("DEPT_LABOUR", morning, morning)).isTrue();
    }

    // SC-21-0007, BMAD TR-055: SRS-4-7 (l.4731) refuses a DPS slip with the same operation and
    // start / end time frame; the norm type and slab are not part of the DPS test.
    @Test
    void SRS_4_7_aDpsSlipWithTheSameOperationAndTimeFrameIsADuplicate() {
        DuplicateKey entered = new DuplicateKey(1, "HANDLING", null, "09:00", "12:00");
        DuplicateKey sameFrame = new DuplicateKey(1, "HANDLING", null, "09:00", "12:00");
        DuplicateKey otherNormAndSlab = new DuplicateKey(1, "HEIGHT", "ABOVE_20", "09:00", "12:00");
        DuplicateKey otherOperation = new DuplicateKey(2, "HANDLING", null, "09:00", "12:00");
        assertThat(WorkSlipRules.duplicate("DPS_LABOUR", sameFrame, entered)).isTrue();
        assertThat(WorkSlipRules.duplicate("DPS_LABOUR", otherNormAndSlab, entered))
                .as("norm type and slab are not part of the DPS duplicate test")
                .isTrue();
        assertThat(WorkSlipRules.duplicate("DPS_LABOUR", otherOperation, entered)).isFalse();
    }

    // SC-22-0007: an NWNP slip within a time frame already entered is a duplicate.
    @Test
    void SRS_5_8_anNwnpRowWithinATimeFrameAlreadyEnteredRepeatsIt() {
        DuplicateKey day = new DuplicateKey(1, "HANDLING", null, "09:00", "17:00");
        DuplicateKey inside = new DuplicateKey(2, "NONE", null, "10:00", "12:00");
        DuplicateKey across = new DuplicateKey(2, "NONE", null, "16:00", "18:00");
        DuplicateKey same = new DuplicateKey(1, "HANDLING", null, "09:00", "17:00");
        DuplicateKey edge = new DuplicateKey(3, "NONE", null, "09:00", "17:00");
        assertThat(WorkSlipRules.duplicate("NWNP_LABOUR", inside, day)).isTrue();
        assertThat(WorkSlipRules.duplicate("NWNP_LABOUR", day, inside))
                .as("either way: a containing frame, as built (D22-3), CLR-144")
                .isTrue();
        assertThat(WorkSlipRules.duplicate("NWNP_LABOUR", same, day)).isTrue();
        assertThat(WorkSlipRules.duplicate("NWNP_LABOUR", edge, day))
                .as("the same frame is within it")
                .isTrue();
        assertThat(WorkSlipRules.duplicate("NWNP_LABOUR", across, day)).isFalse();
        assertThat(WorkSlipRules.duplicate("DEPT_LABOUR", inside, day)).isFalse();
        // Work past midnight ends the next day.
        DuplicateKey night = new DuplicateKey(1, "HANDLING", null, "22:00", "04:00");
        DuplicateKey late = new DuplicateKey(2, "NONE", null, "23:00", "01:00");
        DuplicateKey morning = new DuplicateKey(2, "NONE", null, "05:00", "06:00");
        assertThat(WorkSlipRules.duplicate("NWNP_LABOUR", late, night)).isTrue();
        assertThat(WorkSlipRules.duplicate("NWNP_LABOUR", morning, night)).isFalse();
    }

    // SC-22-0007: the minutes of a time count, not only its hour.
    @Test
    void SRS_5_8_aFrameEndingMinutesAfterTheEnteredOneIsNotWithinIt() {
        DuplicateKey day = new DuplicateKey(1, "HANDLING", null, "09:00", "17:00");
        DuplicateKey overrun = new DuplicateKey(2, "NONE", null, "16:30", "17:15");
        DuplicateKey early = new DuplicateKey(2, "NONE", null, "08:45", "09:30");
        DuplicateKey inside = new DuplicateKey(2, "NONE", null, "09:15", "16:45");
        DuplicateKey toTheMinute = new DuplicateKey(2, "NONE", null, "09:00", "17:00");
        DuplicateKey oneMinuteLate = new DuplicateKey(2, "NONE", null, "09:01", "17:01");
        assertThat(WorkSlipRules.duplicate("NWNP_LABOUR", overrun, day)).isFalse();
        assertThat(WorkSlipRules.duplicate("NWNP_LABOUR", early, day)).isFalse();
        assertThat(WorkSlipRules.duplicate("NWNP_LABOUR", inside, day)).isTrue();
        assertThat(WorkSlipRules.duplicate("NWNP_LABOUR", toTheMinute, day)).isTrue();
        assertThat(WorkSlipRules.duplicate("NWNP_LABOUR", oneMinuteLate, day)).isFalse();
    }

    // US-GAP-342 (SRS-5-8 l.4852; D22-3): a new frame that contains an entered one, or lies within
    // it, repeats it; a frame that only overlaps or touches it at one end does not. BMAD TR-059:
    // SRS-5-8 states only the "within" direction; the containing frame (D22-3) is kept as built and
    // asked of FCI (CLR-144).
    @Test
    void SRS_5_8_aContainingFrameRepeatsAndAPartialOrTouchingOneDoesNot() {
        DuplicateKey entered = new DuplicateKey(1, "HANDLING", null, "10:00", "12:00");
        DuplicateKey wider = new DuplicateKey(2, "NONE", null, "09:00", "13:00");
        DuplicateKey partial = new DuplicateKey(2, "NONE", null, "11:00", "13:00");
        DuplicateKey touching = new DuplicateKey(2, "NONE", null, "12:00", "14:00");
        DuplicateKey before = new DuplicateKey(2, "NONE", null, "08:00", "10:00");
        assertThat(WorkSlipRules.duplicate("NWNP_LABOUR", wider, entered)).isTrue();
        assertThat(WorkSlipRules.duplicate("NWNP_LABOUR", partial, entered)).isFalse();
        assertThat(WorkSlipRules.duplicate("NWNP_LABOUR", touching, entered)).isFalse();
        assertThat(WorkSlipRules.duplicate("NWNP_LABOUR", before, entered)).isFalse();
    }
}
