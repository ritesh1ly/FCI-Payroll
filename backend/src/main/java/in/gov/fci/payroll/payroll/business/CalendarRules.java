package in.gov.fci.payroll.payroll.business;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * The checks of an M-10 payroll calendar proposal (plan 05 phase 05f), as pure functions: the first
 * defect found, or empty when the proposal may go to the Checker.
 *
 * <ul>
 *   <li>SRS M-10 field table: period start / end, freeze and lock targets and the supplementary /
 *       MDP window are mandatory; the freeze and lock targets lie within the period and the lock
 *       target is on or after the freeze target; the supplementary / MDP window opens only after
 *       the month-end lock (SRS-15-2), read as on or after the lock target, and ends on or after it
 *       opens.
 *   <li>SRS M-10 alternate flow (FRS 6.11.8.1): a period's dates do not overlap an adjacent period:
 *       checked over the proposal together with the approved periods it does not replace.
 *   <li>The month belongs to the financial year the proposal is for (April to March).
 * </ul>
 *
 * The compute-by, verify-by and disbursement dates are optional and the SRS sets no rule on them.
 */
public final class CalendarRules {

    /**
     * One period of the calendar (DDL {@code payroll_calendar}); the freeze target is the input
     * cut-off.
     */
    public record Period(
            YearMonth month,
            LocalDate start,
            LocalDate end,
            LocalDate freezeTarget,
            LocalDate computeBy,
            LocalDate verifyBy,
            LocalDate lockTarget,
            LocalDate disbursement,
            LocalDate supplementaryFrom,
            LocalDate supplementaryTo,
            LocalDate mdpFrom,
            LocalDate mdpTo) {}

    /**
     * What is wrong, with the month it is wrong for.
     *
     * @param code MISSING, NOT_IN_YEAR, TWICE, END_BEFORE_START, FREEZE_OUTSIDE, LOCK_OUTSIDE,
     *     LOCK_BEFORE_FREEZE, SUPPLEMENTARY_BEFORE_LOCK, SUPPLEMENTARY_REVERSED, MDP_BEFORE_LOCK,
     *     MDP_REVERSED, OVERLAP
     */
    public record Defect(YearMonth month, String code) {}

    private CalendarRules() {}

    /**
     * The first defect of {@code proposed} for {@code finYear}, checked month by month and then for
     * overlaps with {@code approved} (the periods of the calendar it does not replace).
     */
    public static Optional<Defect> defect(
            String finYear, List<Period> proposed, List<Period> approved) {
        Set<YearMonth> seen = new HashSet<>();
        for (Period p : proposed) {
            Optional<String> own = own(finYear, p);
            if (own.isPresent()) {
                return Optional.of(new Defect(p.month(), own.get()));
            }
            if (!seen.add(p.month())) {
                return Optional.of(new Defect(p.month(), "TWICE"));
            }
        }
        Map<YearMonth, Period> calendar = new TreeMap<>();
        for (Period p : approved) {
            calendar.put(p.month(), p);
        }
        for (Period p : proposed) {
            calendar.put(p.month(), p);
        }
        List<Period> ordered = new ArrayList<>(calendar.values());
        ordered.sort(Comparator.comparing(Period::start));
        for (int i = 1; i < ordered.size(); i++) {
            Period before = ordered.get(i - 1);
            Period after = ordered.get(i);
            if (!after.start().isAfter(before.end())) {
                YearMonth blamed = seen.contains(after.month()) ? after.month() : before.month();
                return Optional.of(new Defect(blamed, "OVERLAP"));
            }
        }
        return Optional.empty();
    }

    private static Optional<String> own(String finYear, Period p) {
        if (p.month() == null
                || p.start() == null
                || p.end() == null
                || p.freezeTarget() == null
                || p.lockTarget() == null
                || p.supplementaryFrom() == null
                || p.supplementaryTo() == null
                || p.mdpFrom() == null
                || p.mdpTo() == null) {
            return Optional.of("MISSING");
        }
        if (!CycleKeys.finYear(p.month()).equals(finYear)) {
            return Optional.of("NOT_IN_YEAR");
        }
        if (p.end().isBefore(p.start())) {
            return Optional.of("END_BEFORE_START");
        }
        if (outside(p.freezeTarget(), p)) {
            return Optional.of("FREEZE_OUTSIDE");
        }
        if (outside(p.lockTarget(), p)) {
            return Optional.of("LOCK_OUTSIDE");
        }
        if (p.lockTarget().isBefore(p.freezeTarget())) {
            return Optional.of("LOCK_BEFORE_FREEZE");
        }
        if (p.supplementaryFrom().isBefore(p.lockTarget())) {
            return Optional.of("SUPPLEMENTARY_BEFORE_LOCK");
        }
        if (p.supplementaryTo().isBefore(p.supplementaryFrom())) {
            return Optional.of("SUPPLEMENTARY_REVERSED");
        }
        if (p.mdpFrom().isBefore(p.lockTarget())) {
            return Optional.of("MDP_BEFORE_LOCK");
        }
        if (p.mdpTo().isBefore(p.mdpFrom())) {
            return Optional.of("MDP_REVERSED");
        }
        return Optional.empty();
    }

    private static boolean outside(LocalDate date, Period p) {
        return date.isBefore(p.start()) || date.isAfter(p.end());
    }
}
