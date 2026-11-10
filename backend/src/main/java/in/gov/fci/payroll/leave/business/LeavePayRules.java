package in.gov.fci.payroll.leave.business;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * The pay reduction of a month's leave (plan 19 phase 19d; SRS-LV-15, SRS-LV-27, SRS-LV-22; KB
 * LV-6; D19-4; the components subject to LWP are the M-07 attribute, US-GAP-298). Pure: no
 * framework, no I/O.
 *
 * <p>BR-LEAVE-007: reduction of a component = (its monthly amount / divisor) x days; the days of
 * leave without pay or extraordinary leave count in full (FULL_DEDUCTION), half-pay leave at half
 * (HALF_PAY). The divisor is the M-20 LWP divisor, or the calendar days of the month. A leave event
 * spanning months counts the days that fall in the month: in proportion when HRMS sanctioned fewer
 * days than the span (half days). A cancellation received in the open period gives the days back.
 * SRS-LV-22: the days reduced never exceed the days in the payroll month.
 */
public final class LeavePayRules {

    public static final String FULL_DEDUCTION = "FULL_DEDUCTION";
    public static final String HALF_PAY = "HALF_PAY";

    private static final BigDecimal HALF = new BigDecimal("0.5");

    private LeavePayRules() {}

    /** One applied leave transaction as the month reads it. */
    public record Leave(
            LocalDate from,
            LocalDate to,
            BigDecimal days,
            String wageEffect,
            boolean cancellation) {}

    /**
     * The days of pay lost in {@code month}: each transaction's days in the month, in full or at
     * half by its effect; a cancellation subtracts; never below zero.
     */
    public static BigDecimal daysLost(List<Leave> leave, YearMonth month) {
        BigDecimal total = BigDecimal.ZERO;
        for (Leave l : leave) {
            BigDecimal weight =
                    switch (l.wageEffect()) {
                        case FULL_DEDUCTION -> BigDecimal.ONE;
                        case HALF_PAY -> HALF;
                        default -> BigDecimal.ZERO;
                    };
            if (weight.signum() == 0) {
                continue;
            }
            BigDecimal inMonth = daysInMonth(l, month).multiply(weight);
            total = l.cancellation() ? total.subtract(inMonth) : total.add(inMonth);
        }
        return total.max(BigDecimal.ZERO);
    }

    /**
     * SRS-LV-22: the days of {@code month} between joining and separation, both counted (none when
     * the member joined after the month or left before it).
     */
    public static long daysOnRolls(LocalDate joining, LocalDate separation, YearMonth month) {
        LocalDate start =
                joining != null && joining.isAfter(month.atDay(1)) ? joining : month.atDay(1);
        LocalDate end =
                separation != null && separation.isBefore(month.atEndOfMonth())
                        ? separation
                        : month.atEndOfMonth();
        return end.isBefore(start) ? 0 : ChronoUnit.DAYS.between(start, end) + 1;
    }

    /** The sanctioned days of {@code l} that fall in {@code month}. */
    static BigDecimal daysInMonth(Leave l, YearMonth month) {
        LocalDate start = l.from().isAfter(month.atDay(1)) ? l.from() : month.atDay(1);
        LocalDate end = l.to().isBefore(month.atEndOfMonth()) ? l.to() : month.atEndOfMonth();
        if (end.isBefore(start)) {
            return BigDecimal.ZERO;
        }
        long overlap = ChronoUnit.DAYS.between(start, end) + 1;
        long span = ChronoUnit.DAYS.between(l.from(), l.to()) + 1;
        if (l.days().compareTo(BigDecimal.valueOf(span)) >= 0) {
            return BigDecimal.valueOf(overlap);
        }
        // Fewer days sanctioned than the span (half days): the days in proportion to the overlap.
        return l.days()
                .multiply(BigDecimal.valueOf(overlap))
                .divide(BigDecimal.valueOf(span), 2, RoundingMode.HALF_UP);
    }

    /**
     * The divisor of the month: the configured number, or the calendar days of the month for {@link
     * LeaveParameterRules#CALENDAR_DAYS}; empty when not configured (R-RULE).
     */
    public static Optional<BigDecimal> divisor(
            Optional<BigDecimal> number, Optional<String> text, YearMonth month) {
        if (text.isPresent() && LeaveParameterRules.CALENDAR_DAYS.equals(text.get().strip())) {
            return Optional.of(BigDecimal.valueOf(month.lengthOfMonth()));
        }
        return number.filter(n -> n.signum() > 0);
    }

    /**
     * SRS-LV-15: the reduction of one component, rounded once to the rupee half-up (SRS-DM-6), at
     * most the component's amount.
     */
    public static BigDecimal reduction(BigDecimal amount, BigDecimal divisor, BigDecimal days) {
        BigDecimal r =
                amount.multiply(days)
                        .divide(divisor, 10, RoundingMode.HALF_UP)
                        .setScale(0, RoundingMode.HALF_UP);
        return r.min(amount);
    }

    /** SRS-LV-22: the days lost never exceed the days in the payroll month. */
    public static boolean withinMonth(BigDecimal days, YearMonth month) {
        return days.compareTo(BigDecimal.valueOf(month.lengthOfMonth())) <= 0;
    }
}
