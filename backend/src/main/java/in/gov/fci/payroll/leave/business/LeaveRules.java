package in.gov.fci.payroll.leave.business;

import in.gov.fci.payroll.leave.api.LeaveReceipts;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The checks on leave data received from HRMS (plan 19 phase 19a). Pure: no framework, no I/O.
 *
 * <p>SRS-LV-25 / SRS-LV-13 (BR-LEAVE-004): a leave event is applied only when the member and period
 * match and the sanction reference is present; anything else is held and referred to HRMS, never
 * applied on an assumption. SRS-LV-21 (BR-LEAVE-013): a balance is never negative. The days of an
 * event fit its period (a half day only for a type HRMS sanctions by the half day).
 */
public final class LeaveRules {

    /** DDL {@code leave_type_reference.leave_type_code} VARCHAR2(15). */
    private static final Pattern TYPE_CODE = Pattern.compile("^[A-Z0-9_]{1,15}$");

    private static final Pattern NAME = Pattern.compile("^[\\x20-\\x7E]{1,150}$");
    private static final Pattern REFERENCE = Pattern.compile("^[\\x20-\\x7E]{1,50}$");
    private static final Pattern REQUEST_ID = Pattern.compile("^[\\x20-\\x7E]{1,60}$");
    private static final Pattern FIN_YEAR = Pattern.compile("^\\d{4}-\\d{2}$");
    private static final BigDecimal HALF = new BigDecimal("0.5");

    /** The DDL {@code days NUMBER(5,2)} ceiling. */
    private static final BigDecimal MAX_DAYS = new BigDecimal("999.99");

    private LeaveRules() {}

    /** A leave type as received: its code and name (printable ASCII, R-ASCII). */
    public static Optional<String> typeDefect(String code, String name) {
        if (code == null || !TYPE_CODE.matcher(code.strip()).matches()) {
            return Optional.of("LEAVE_TYPE_CODE");
        }
        if (name == null || !NAME.matcher(name.strip()).matches()) {
            return Optional.of("LEAVE_TYPE_NAME");
        }
        return Optional.empty();
    }

    /** SRS-LV-21: a balance is never negative; it has a day, and a year of the form YYYY-YY. */
    public static Optional<String> balanceDefect(
            boolean knownType, BigDecimal days, LocalDate asOf, String finYear) {
        if (!knownType) {
            return Optional.of("LEAVE_TYPE");
        }
        if (days == null || days.signum() < 0 || days.compareTo(MAX_DAYS) > 0) {
            return Optional.of("BALANCE");
        }
        if (asOf == null) {
            return Optional.of("AS_OF_DATE");
        }
        if (finYear != null && !FIN_YEAR.matcher(finYear).matches()) {
            return Optional.of("FIN_YEAR");
        }
        return Optional.empty();
    }

    /**
     * SRS-LV-25 / SRS-LV-13: why an event cannot be applied, or empty when it can.
     *
     * @param memberFound whether the EIS names an employee
     * @param knownType whether the leave type was received from HRMS (M-13)
     * @param typeHalfDay whether that type is sanctioned by the half day
     */
    public static Optional<String> eventDefect(
            LeaveReceipts.Event e, boolean memberFound, boolean knownType, boolean typeHalfDay) {
        if (!memberFound) {
            return Optional.of("MEMBER");
        }
        if (e.sanctionRef() == null || e.sanctionRef().isBlank()) {
            return Optional.of("NO_SANCTION_REF");
        }
        if (!REFERENCE.matcher(e.sanctionRef().strip()).matches()) {
            return Optional.of("SANCTION_REF");
        }
        if (e.hrmsRequestId() != null && !REQUEST_ID.matcher(e.hrmsRequestId()).matches()) {
            return Optional.of("REQUEST_ID");
        }
        if (!knownType) {
            return Optional.of("LEAVE_TYPE");
        }
        if (e.from() == null || e.to() == null || e.to().isBefore(e.from())) {
            return Optional.of("PERIOD");
        }
        Optional<String> days = daysDefect(e, typeHalfDay);
        if (days.isPresent()) {
            return days;
        }
        if (e.eventType() == null) {
            return Optional.of("EVENT_TYPE");
        }
        if (e.eventType() != LeaveReceipts.EventType.ADDITION
                && (e.earlierSanctionRef() == null
                        || !REFERENCE.matcher(e.earlierSanctionRef().strip()).matches())) {
            return Optional.of("EARLIER_SANCTION");
        }
        return Optional.empty();
    }

    /** The days are positive, whole or half (half only for a half-day type), within the period. */
    private static Optional<String> daysDefect(LeaveReceipts.Event e, boolean typeHalfDay) {
        BigDecimal days = e.days();
        if (days == null || days.signum() <= 0) {
            return Optional.of("DAYS");
        }
        BigDecimal halves = days.divide(HALF);
        if (halves.stripTrailingZeros().scale() > 0) {
            return Optional.of("DAYS");
        }
        boolean whole = days.stripTrailingZeros().scale() <= 0;
        if ((!whole || e.halfDay()) && !typeHalfDay) {
            return Optional.of("HALF_DAY");
        }
        if (days.compareTo(BigDecimal.valueOf(spanDays(e.from(), e.to()))) > 0) {
            return Optional.of("DAYS");
        }
        return Optional.empty();
    }

    /** The calendar days from {@code from} to {@code to}, both included. */
    public static long spanDays(LocalDate from, LocalDate to) {
        return ChronoUnit.DAYS.between(from, to) + 1;
    }
}
