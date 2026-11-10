package in.gov.fci.payroll.employee.business;

import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Rules of the I-17 life-cycle events and M-11 transfers (target DDL v4.0 S03). Pure: what an event
 * does to the employment status, which events fit the current status, and the date rules.
 */
public final class EventRules {

    private EventRules() {}

    /** {@code employee_lifecycle_event.event_type} (CHECK of the target DDL). */
    public static final Set<String> EVENT_TYPES =
            Set.of(
                    "SUSPENSION",
                    "REVOCATION",
                    "REINSTATEMENT",
                    "DISMISSAL",
                    "SALARY_RELEASE",
                    "SALARY_STOP",
                    "HOLD",
                    "ABSCONDING",
                    "DEPUTATION_OUT",
                    "DEPUTATION_IN",
                    "SEPARATION");

    /** Statuses after which only a REINSTATEMENT may be recorded (and no transfer). */
    public static final Set<String> SEPARATED_STATUSES = Set.of("SEPARATED", "DISMISSED");

    /**
     * The employment status an approved event sets (EMPLOYMENT_STATUS list); pay-only events set
     * none.
     */
    private static final Map<String, String> STATUS_AFTER =
            Map.of(
                    "SUSPENSION", "SUSPENDED",
                    "REVOCATION", "IN_SERVICE",
                    "REINSTATEMENT", "IN_SERVICE",
                    "DISMISSAL", "DISMISSED",
                    "ABSCONDING", "ABSCONDING",
                    "DEPUTATION_OUT", "ON_DEPUTATION",
                    "DEPUTATION_IN", "IN_SERVICE",
                    "SEPARATION", "SEPARATED");

    /** Events that end the employee's access (SRS-G-36: deactivated in the same act). */
    public static final Set<String> ENDS_SERVICE = Set.of("SEPARATION", "DISMISSAL");

    /**
     * I-17 field table, Effective Date (SRS l.3877; US-GAP-277): "Cannot precede the joining date".
     * A member without a joining date on record is not refused here (nothing to compare).
     *
     * @throws FciException EMP-422-BEFORE-JOINING naming both dates
     */
    public static void requireNotBeforeJoining(LocalDate eventDate, LocalDate joiningDate) {
        if (eventDate != null && joiningDate != null && eventDate.isBefore(joiningDate)) {
            throw new FciException(
                    ApiErrorCode.EMPLOYEE_EVENT_BEFORE_JOINING,
                    new Object[] {eventDate.toString(), joiningDate.toString()},
                    List.of(),
                    null);
        }
    }

    /** The status {@code eventType} sets; empty for a pay-only, unknown or missing type. */
    public static Optional<String> statusAfter(String eventType) {
        // Map.of refuses a null lookup: no type sets no status.
        return eventType == null
                ? Optional.empty()
                : Optional.ofNullable(STATUS_AFTER.get(eventType));
    }

    /**
     * A valid event: a known type; a status-changing event dated today or earlier (its effect
     * applies on approval); an end date not before the event; subsistence 0-100 %; a salary release
     * range as YYYYMM, from not after to; and an event that fits the current status.
     */
    public static void validateEvent(
            String type,
            LocalDate date,
            LocalDate end,
            BigDecimal subsistence,
            String releaseFrom,
            String releaseTo,
            String currentStatus,
            LocalDate today) {
        if (type == null
                || !EVENT_TYPES.contains(type)
                || date == null
                || (end != null && end.isBefore(date))
                || (subsistence != null
                        && (subsistence.signum() < 0
                                || subsistence.compareTo(BigDecimal.valueOf(100)) > 0))
                || !yymm(releaseFrom)
                || !yymm(releaseTo)
                || (releaseFrom != null
                        && !releaseFrom.isBlank()
                        && releaseTo != null
                        && !releaseTo.isBlank()
                        && releaseFrom.compareTo(releaseTo) > 0)) {
            throw new FciException(CommonErrorCode.VALIDATION_FAILED);
        }
        if (STATUS_AFTER.containsKey(type) && date.isAfter(today)) {
            throw new FciException(ApiErrorCode.EMPLOYEE_FUTURE_DATE);
        }
        boolean fits =
                switch (type) {
                    case "REINSTATEMENT" -> true;
                    case "REVOCATION" -> "SUSPENDED".equals(currentStatus);
                    case "DEPUTATION_IN" -> "ON_DEPUTATION".equals(currentStatus);
                    default -> !SEPARATED_STATUSES.contains(currentStatus);
                };
        if (!fits) {
            throw new FciException(
                    ApiErrorCode.EMPLOYEE_EVENT_NOT_ALLOWED,
                    new Object[] {currentStatus},
                    List.of(),
                    null);
        }
    }

    /**
     * A valid transfer: dated today or earlier (applied on approval; future transfers come from
     * HRMS), joining not before relieving, joining time HH:MM, split-wage days given and not
     * negative.
     */
    public static void validateTransfer(
            LocalDate transferDate,
            LocalDate relieving,
            LocalDate joining,
            String joiningTime,
            boolean splitWage,
            Integer fromDays,
            Integer toDays,
            LocalDate today) {
        if (transferDate == null
                || (relieving != null && joining != null && joining.isBefore(relieving))
                || (joiningTime != null
                        && !joiningTime.isBlank()
                        && !joiningTime.matches("^([01]\\d|2[0-3]):[0-5]\\d$"))
                || (splitWage
                        && (fromDays == null
                                || toDays == null
                                || fromDays < 0
                                || toDays < 0
                                || fromDays + toDays > 31))) {
            throw new FciException(CommonErrorCode.VALIDATION_FAILED);
        }
        if (transferDate.isAfter(today)) {
            throw new FciException(ApiErrorCode.EMPLOYEE_FUTURE_DATE);
        }
    }

    private static boolean yymm(String value) {
        return value == null || value.isBlank() || value.matches("^20\\d{2}(0[1-9]|1[0-2])$");
    }
}
