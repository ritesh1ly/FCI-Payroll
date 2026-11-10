package in.gov.fci.payroll.labour.business;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Labour attendance rules (plan 20 phase 20b; SRS-2-2, SRS-2-35, SRS-2-38, SRS-3-1; BR-LABM-005;
 * D20-7, D20-8). Pure functions: no state, no I/O.
 */
public final class AttendanceRules {

    /** The code that means Absent only (SRS-2-11, SRS-4-1). */
    public static final String ABSENT = "A";

    private static final DateTimeFormatter YYYYMM = DateTimeFormatter.ofPattern("yyyyMM");

    /** One code of a payroll type's set (DDL {@code attendance_code_master}). */
    public record Code(String code, BigDecimal weight, boolean leave, boolean confirmed) {}

    /** The month's counts of one labour, derived from the daily marks (never typed). */
    public record MonthCount(
            int calendarDays,
            int marked,
            BigDecimal present,
            BigDecimal leave,
            BigDecimal absent) {}

    /** The state of a gang's month batch (D20-8), from its latest approval item. */
    public enum BatchState {
        /** Not yet submitted: marks may be saved. */
        OPEN,
        /** With the Payroll Checker: no change. */
        SUBMITTED,
        /** Returned by the Payroll Checker with reasons: marks may be saved again. */
        RETURNED,
        /** Approved: usable for computation; a change is a back-dated correction (SRS-2-39). */
        APPROVED;

        /** SRS-2-29: the marks of an open or returned batch may change. */
        public boolean editable() {
            return this == OPEN || this == RETURNED;
        }

        /** The state of the batch's latest approval status, or OPEN without one. */
        public static BatchState of(String approvalStatus) {
            if (approvalStatus == null) {
                return OPEN;
            }
            return switch (approvalStatus) {
                case "PENDING_APPROVAL", "PENDING_ATTACHMENT", "DRAFT" -> SUBMITTED;
                case "APPROVED" -> APPROVED;
                default -> RETURNED;
            };
        }
    }

    private AttendanceRules() {}

    /**
     * SRS-4-5 (DPS): the days of the previous month in the week of {@code day} when {@code day} is
     * the first Sunday of its month and its week (Monday to Sunday) began in the previous month;
     * empty otherwise.
     */
    public static List<LocalDate> splitWeekDays(LocalDate day) {
        if (day.getDayOfWeek() != DayOfWeek.SUNDAY || day.getDayOfMonth() >= 7) {
            return List.of();
        }
        LocalDate monday = day.minusDays(6);
        return monday.datesUntil(day.withDayOfMonth(1)).toList();
    }

    /**
     * SRS-4-5 (DPS): the first Sunday is marked absent when the labour was absent on every one of
     * the previous month's days of the split week. A day not marked is not an absence.
     */
    // OPEN-QUESTION: SRS-4-5 says "absent in the last week of the previous month"; read as absent
    // (code A) on every day of the split week that falls in that month (PENDING DPS-21a-1).
    public static boolean absentThroughout(List<LocalDate> days, Map<LocalDate, String> marks) {
        return !days.isEmpty() && days.stream().allMatch(d -> ABSENT.equals(marks.get(d)));
    }

    /**
     * SRS-3-1 / SRS-2-38: the month's counts from the marks. Present days are the code weights (F
     * counts 0.5); a leave code adds what it does not count as present to the leave days; a
     * non-leave code of weight 0 (A) is an absent day.
     */
    public static MonthCount month(YearMonth month, Collection<Code> marks) {
        BigDecimal present = BigDecimal.ZERO;
        BigDecimal leave = BigDecimal.ZERO;
        BigDecimal absent = BigDecimal.ZERO;
        for (Code c : marks) {
            present = present.add(c.weight());
            BigDecimal rest = BigDecimal.ONE.subtract(c.weight());
            if (c.leave()) {
                leave = leave.add(rest);
            } else {
                absent = absent.add(rest);
            }
        }
        return new MonthCount(month.lengthOfMonth(), marks.size(), present, leave, absent);
    }

    /**
     * SRS-2-38 (DDL {@code chk_lbr_att_m_cap}): the month never counts more days than the calendar
     * has.
     */
    public static boolean withinCalendar(MonthCount c) {
        BigDecimal days = BigDecimal.valueOf(c.calendarDays());
        return c.marked() <= c.calendarDays()
                && c.present().compareTo(days) <= 0
                && c.present().add(c.leave()).add(c.absent()).compareTo(days) <= 0;
    }

    /**
     * SRS-2-35 / SRS-2-2: what is wrong with a gang's day: FUTURE (after today), NOT_A_MEMBER (a
     * mark for someone not in the gang that day, retired members included), UNKNOWN_CODE (not in
     * the payroll type's set), UNMARKED (a member left without a mark: the day is saved whole).
     */
    public static Optional<String> dayDefect(
            LocalDate day,
            LocalDate today,
            Set<Long> expected,
            Map<Long, String> marks,
            Set<String> codeSet) {
        if (day.isAfter(today)) {
            return Optional.of("FUTURE");
        }
        for (Map.Entry<Long, String> m : marks.entrySet()) {
            if (!expected.contains(m.getKey())) {
                return Optional.of("NOT_A_MEMBER " + m.getKey());
            }
            if (m.getValue() == null || !codeSet.contains(m.getValue())) {
                return Optional.of("UNKNOWN_CODE " + m.getValue());
            }
        }
        if (!marks.keySet().containsAll(expected)) {
            return Optional.of("UNMARKED");
        }
        return Optional.empty();
    }

    /**
     * I-01 Attendance Exceptions (a gap, SRS-2-35 "every labour must be marked"): the days of the
     * month a membership expects a mark for, from its first day to the last of its end, the day of
     * retirement and {@code until}; 0 when the span is empty.
     */
    public static long markableDays(
            YearMonth month, LocalDate from, LocalDate to, LocalDate retirement, LocalDate until) {
        LocalDate start = from.isAfter(month.atDay(1)) ? from : month.atDay(1);
        LocalDate end = until.isAfter(month.atEndOfMonth()) ? month.atEndOfMonth() : until;
        if (to != null && to.isBefore(end)) {
            end = to;
        }
        if (retirement != null && retirement.isBefore(end)) {
            end = retirement;
        }
        return end.isBefore(start) ? 0 : start.datesUntil(end.plusDays(1)).count();
    }

    /** D20-8: the approval key of a gang's month batch. */
    public static String batchKey(long gangId, YearMonth month) {
        return gangId + ":" + yymm(month);
    }

    /** YYYYMM of a month. */
    public static String yymm(YearMonth month) {
        return month.format(YYYYMM);
    }

    /** The last day of the month that can be marked today: the month end, or today. */
    public static LocalDate lastMarkable(YearMonth month, LocalDate today) {
        LocalDate end = month.atEndOfMonth();
        return end.isAfter(today) ? today : end;
    }
}
