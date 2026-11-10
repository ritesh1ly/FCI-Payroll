package in.gov.fci.payroll.payroll.business;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.payroll.business.CalendarRules.Defect;
import in.gov.fci.payroll.payroll.business.CalendarRules.Period;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * SRS M-10 field table and alternate flow (plan 05 phase 05f): mandatory fields, the month in the
 * year, targets within the period and in order, windows after the lock target, and no overlap with
 * an adjacent period.
 */
class CalendarRulesTest {

    private static final YearMonth APRIL = YearMonth.of(2027, 4);

    /** A valid April 2027: freeze 20th, lock 28th, windows from the 28th. */
    private static Period april() {
        return period(APRIL, 1, 30, 20, 28, 28, 30, 28, 30);
    }

    private static Period period(
            YearMonth m,
            int start,
            int end,
            int freeze,
            int lock,
            int supFrom,
            int supTo,
            int mdpFrom,
            int mdpTo) {
        return new Period(
                m,
                m.atDay(start),
                m.atDay(end),
                m.atDay(freeze),
                null,
                null,
                m.atDay(lock),
                null,
                m.atDay(supFrom),
                m.atDay(supTo),
                m.atDay(mdpFrom),
                m.atDay(mdpTo));
    }

    private static Optional<Defect> check(Period p) {
        return CalendarRules.defect("2027-28", List.of(p), List.of());
    }

    @Test
    void SRS_15_33_aCompletePeriodInItsYearIsAccepted() {
        assertThat(check(april())).isEmpty();
        // Lock on the freeze date and windows opening on the lock date are allowed.
        assertThat(check(period(APRIL, 1, 30, 25, 25, 25, 25, 25, 25))).isEmpty();
    }

    @Test
    void SRS_15_33_eachMandatoryFieldIsNeeded() {
        Period p = april();
        List<Period> missing =
                List.of(
                        new Period(
                                null,
                                p.start(),
                                p.end(),
                                p.freezeTarget(),
                                null,
                                null,
                                p.lockTarget(),
                                null,
                                p.supplementaryFrom(),
                                p.supplementaryTo(),
                                p.mdpFrom(),
                                p.mdpTo()),
                        new Period(
                                APRIL,
                                null,
                                p.end(),
                                p.freezeTarget(),
                                null,
                                null,
                                p.lockTarget(),
                                null,
                                p.supplementaryFrom(),
                                p.supplementaryTo(),
                                p.mdpFrom(),
                                p.mdpTo()),
                        new Period(
                                APRIL,
                                p.start(),
                                null,
                                p.freezeTarget(),
                                null,
                                null,
                                p.lockTarget(),
                                null,
                                p.supplementaryFrom(),
                                p.supplementaryTo(),
                                p.mdpFrom(),
                                p.mdpTo()),
                        new Period(
                                APRIL,
                                p.start(),
                                p.end(),
                                null,
                                null,
                                null,
                                p.lockTarget(),
                                null,
                                p.supplementaryFrom(),
                                p.supplementaryTo(),
                                p.mdpFrom(),
                                p.mdpTo()),
                        new Period(
                                APRIL,
                                p.start(),
                                p.end(),
                                p.freezeTarget(),
                                null,
                                null,
                                null,
                                null,
                                p.supplementaryFrom(),
                                p.supplementaryTo(),
                                p.mdpFrom(),
                                p.mdpTo()),
                        new Period(
                                APRIL,
                                p.start(),
                                p.end(),
                                p.freezeTarget(),
                                null,
                                null,
                                p.lockTarget(),
                                null,
                                null,
                                p.supplementaryTo(),
                                p.mdpFrom(),
                                p.mdpTo()),
                        new Period(
                                APRIL,
                                p.start(),
                                p.end(),
                                p.freezeTarget(),
                                null,
                                null,
                                p.lockTarget(),
                                null,
                                p.supplementaryFrom(),
                                null,
                                p.mdpFrom(),
                                p.mdpTo()),
                        new Period(
                                APRIL,
                                p.start(),
                                p.end(),
                                p.freezeTarget(),
                                null,
                                null,
                                p.lockTarget(),
                                null,
                                p.supplementaryFrom(),
                                p.supplementaryTo(),
                                null,
                                p.mdpTo()),
                        new Period(
                                APRIL,
                                p.start(),
                                p.end(),
                                p.freezeTarget(),
                                null,
                                null,
                                p.lockTarget(),
                                null,
                                p.supplementaryFrom(),
                                p.supplementaryTo(),
                                p.mdpFrom(),
                                null));
        for (Period each : missing) {
            assertThat(check(each)).map(Defect::code).contains("MISSING");
        }
    }

    @Test
    void SRS_15_33_theTargetsLieWithinThePeriodInOrder() {
        Period reversed =
                new Period(
                        APRIL,
                        APRIL.atDay(30),
                        APRIL.atDay(1),
                        APRIL.atDay(20),
                        null,
                        null,
                        APRIL.atDay(28),
                        null,
                        APRIL.atDay(28),
                        APRIL.atDay(30),
                        APRIL.atDay(28),
                        APRIL.atDay(30));
        assertThat(check(reversed)).map(Defect::code).contains("END_BEFORE_START");
        Period p = april();
        assertThat(check(withFreeze(p, LocalDate.of(2027, 3, 31))))
                .map(Defect::code)
                .contains("FREEZE_OUTSIDE");
        assertThat(check(withFreeze(p, LocalDate.of(2027, 5, 1))))
                .map(Defect::code)
                .contains("FREEZE_OUTSIDE");
        assertThat(check(withLock(p, LocalDate.of(2027, 5, 2))))
                .map(Defect::code)
                .contains("LOCK_OUTSIDE");
        assertThat(check(withLock(p, LocalDate.of(2027, 3, 30))))
                .map(Defect::code)
                .contains("LOCK_OUTSIDE");
        assertThat(check(period(APRIL, 1, 30, 25, 24, 28, 30, 28, 30)))
                .map(Defect::code)
                .contains("LOCK_BEFORE_FREEZE");
    }

    @Test
    void SRS_15_2_theWindowsOpenOnlyAfterTheLockAndRunForward() {
        assertThat(check(period(APRIL, 1, 30, 20, 28, 27, 30, 28, 30)))
                .map(Defect::code)
                .contains("SUPPLEMENTARY_BEFORE_LOCK");
        assertThat(check(period(APRIL, 1, 30, 20, 28, 29, 28, 28, 30)))
                .map(Defect::code)
                .contains("SUPPLEMENTARY_REVERSED");
        assertThat(check(period(APRIL, 1, 30, 20, 28, 28, 30, 27, 30)))
                .map(Defect::code)
                .contains("MDP_BEFORE_LOCK");
        assertThat(check(period(APRIL, 1, 30, 20, 28, 28, 30, 30, 29)))
                .map(Defect::code)
                .contains("MDP_REVERSED");
    }

    @Test
    void SRS_15_33_aMonthOfAnotherYearOrTwiceIsRefused() {
        assertThat(CalendarRules.defect("2026-27", List.of(april()), List.of()))
                .map(Defect::code)
                .contains("NOT_IN_YEAR");
        assertThat(CalendarRules.defect("2027-28", List.of(april(), april()), List.of()))
                .map(Defect::code)
                .contains("TWICE");
    }

    // SC-05-0055: a period overlapping an adjacent one is rejected.
    @Test
    void SRS_15_33_aPeriodMustNotOverlapAnAdjacentOne() {
        YearMonth may = YearMonth.of(2027, 5);
        Period mayOverlapping = period(may, 1, 31, 20, 28, 28, 31, 28, 31);
        Period earlyMay =
                new Period(
                        may,
                        LocalDate.of(2027, 4, 30),
                        may.atDay(31),
                        may.atDay(20),
                        null,
                        null,
                        may.atDay(28),
                        null,
                        may.atDay(28),
                        may.atDay(31),
                        may.atDay(28),
                        may.atDay(31));
        // With an approved April that the proposal does not replace: May may not start in it.
        assertThat(CalendarRules.defect("2027-28", List.of(earlyMay), List.of(april())))
                .contains(new Defect(may, "OVERLAP"));
        // Within the proposal itself, the later period is named.
        assertThat(CalendarRules.defect("2027-28", List.of(april(), earlyMay), List.of()))
                .contains(new Defect(may, "OVERLAP"));
        // A proposed April that moves into an approved May names April.
        Period longApril =
                new Period(
                        APRIL,
                        APRIL.atDay(1),
                        LocalDate.of(2027, 5, 2),
                        APRIL.atDay(20),
                        null,
                        null,
                        APRIL.atDay(28),
                        null,
                        APRIL.atDay(28),
                        APRIL.atDay(30),
                        APRIL.atDay(28),
                        APRIL.atDay(30));
        assertThat(CalendarRules.defect("2027-28", List.of(longApril), List.of(mayOverlapping)))
                .contains(new Defect(APRIL, "OVERLAP"));
        // Replacing an approved period with new dates does not overlap the old version of itself.
        assertThat(
                        CalendarRules.defect(
                                "2027-28", List.of(april()), List.of(april(), mayOverlapping)))
                .isEmpty();
    }

    private static Period withFreeze(Period p, LocalDate freeze) {
        return new Period(
                p.month(),
                p.start(),
                p.end(),
                freeze,
                null,
                null,
                p.lockTarget(),
                null,
                p.supplementaryFrom(),
                p.supplementaryTo(),
                p.mdpFrom(),
                p.mdpTo());
    }

    private static Period withLock(Period p, LocalDate lock) {
        return new Period(
                p.month(),
                p.start(),
                p.end(),
                p.freezeTarget(),
                null,
                null,
                lock,
                null,
                lock,
                lock,
                lock,
                lock);
    }

    /**
     * SRS-15-33: a period need not start in its own month, so the overlap check orders the periods
     * by their dates: April 16 Apr - 15 May and May 1 - 15 Apr do not overlap.
     */
    @Test
    void SRS_15_33_periodsAreComparedInDateOrderNotMonthOrder() {
        java.time.LocalDate d = java.time.LocalDate.of(2027, 4, 1);
        Period april =
                new Period(
                        APRIL,
                        d.plusDays(15),
                        d.plusDays(44),
                        d.plusDays(34),
                        null,
                        null,
                        d.plusDays(39),
                        null,
                        d.plusDays(39),
                        d.plusDays(44),
                        d.plusDays(39),
                        d.plusDays(44));
        Period may =
                new Period(
                        APRIL.plusMonths(1),
                        d,
                        d.plusDays(14),
                        d.plusDays(9),
                        null,
                        null,
                        d.plusDays(11),
                        null,
                        d.plusDays(11),
                        d.plusDays(14),
                        d.plusDays(11),
                        d.plusDays(14));
        assertThat(CalendarRules.defect("2027-28", List.of(april, may), List.of())).isEmpty();
    }
}
