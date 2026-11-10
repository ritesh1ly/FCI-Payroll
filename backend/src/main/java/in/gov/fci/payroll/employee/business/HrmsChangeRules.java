package in.gov.fci.payroll.employee.business;

import in.gov.fci.payroll.platform.logging.PiiMasks;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.EnumSet;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/**
 * The rules of an I-21 HRMS change task (plan 16 phase 16a), as pure functions.
 *
 * <ul>
 *   <li>SRS-1-58 / BR-INT-002: a change received before the payroll freeze of the month is paid in
 *       that month; one received after it is paid next month as a back-dated event, and never
 *       applied to a frozen period.
 *   <li>SRS-1-28 / SRS-G-31: the Maker records the order number, date and issuing authority (or
 *       says the order is held in HRMS, or links a bulk order) and cannot change the value.
 *   <li>SRS-1-61: OTP at the Checker's approval only for an increment-date or Basic Pay change.
 *   <li>SRS-1-22 / SRS-1-23: a DOB change shows the recalculated retirement date and EPS cut-off;
 *       when the new retirement date is already past, the HQ Establishment authority approves after
 *       the Checker.
 * </ul>
 */
public final class HrmsChangeRules {

    /** DDL {@code hrms_change_task.task_status}. */
    public enum Status {
        AUTO_APPLIED,
        PENDING_ORDER_DETAILS,
        PENDING_CHECKER,
        PENDING_HQ_ESTABLISHMENT,
        RETURNED_TO_MAKER,
        APPROVED,
        REJECTED_RETURNED_TO_HRMS,
        SUPERSEDED,
        CANCELLED
    }

    /** The routes of the approval workflow (I21_SUBMIT, I21_SUBMIT_OTP, I21_SUBMIT_HQ). */
    public enum Route {
        SUBMIT,
        SUBMIT_OTP,
        SUBMIT_HQ
    }

    /** The month a change is paid in and whether it was received before that month's freeze. */
    public record Timing(YearMonth target, boolean beforeFreeze) {}

    /** The states in which a task still waits (SRS-1-69: they hold the month-end lock). */
    public static final Set<Status> PENDING =
            EnumSet.of(
                    Status.PENDING_ORDER_DETAILS,
                    Status.PENDING_CHECKER,
                    Status.PENDING_HQ_ESTABLISHMENT,
                    Status.RETURNED_TO_MAKER);

    /** Cycle states before the freeze (no cycle yet counts as before). */
    private static final Set<String> OPEN = Set.of("INITIATED");

    private static final int ORDER_FIELD = 200;

    private HrmsChangeRules() {}

    /**
     * SRS-1-58: received while the current month's cycle is not yet frozen (or not initiated), the
     * change is paid this month and holds its lock while pending; otherwise next month, not holding
     * the lock.
     *
     * @param cycleStatus the status of the paying unit's cycle of {@code current}, null when none
     */
    public static Timing timing(YearMonth current, String cycleStatus) {
        if (cycleStatus == null || OPEN.contains(cycleStatus)) {
            return new Timing(current, true);
        }
        return new Timing(current.plusMonths(1), false);
    }

    /**
     * The pay impact of a task by month (US-GAP-276; UC I-21 l.3813 "every task shows its pay
     * impact preview"), from the rules the SRS states: the value applies from its effective date
     * and is paid from the month the task is paid in (SRS-1-58); the months before that, from the
     * effective month on, are already paid at the old value, so their arrear or recovery is raised
     * through Arrears (SRS-1-28).
     *
     * @param appliesFrom the month of the effective date
     * @param paidFrom the first month paid at the new value
     * @param arrearFrom the first month of the arrear or recovery, or null when there is none
     * @param arrearTo the last month of the arrear or recovery, or null when there is none
     */
    public record PayImpact(
            YearMonth appliesFrom, YearMonth paidFrom, YearMonth arrearFrom, YearMonth arrearTo) {}

    /**
     * The pay impact of a change effective {@code effective} paid in {@code paidIn}; a change
     * effective after that month is paid from its own month, with no arrear.
     */
    public static PayImpact payImpact(LocalDate effective, YearMonth paidIn) {
        YearMonth from = YearMonth.from(effective);
        if (from.isBefore(paidIn)) {
            return new PayImpact(from, paidIn, from, paidIn.minusMonths(1));
        }
        return new PayImpact(from, from, null, null);
    }

    /** SRS-1-28: the Maker may (re)submit only a task waiting for its order details or returned. */
    public static boolean submittable(Status status) {
        return status == Status.PENDING_ORDER_DETAILS || status == Status.RETURNED_TO_MAKER;
    }

    /**
     * SRS-G-31: what is wrong with the order details: MISSING (neither an order nor "held in
     * HRMS"), TOO_LONG, IN_FUTURE (an order dated after today).
     */
    public static Optional<String> orderDefect(
            String orderNo,
            LocalDate orderDate,
            String authority,
            boolean heldInHrms,
            LocalDate today) {
        if (heldInHrms) {
            return Optional.empty();
        }
        if (blank(orderNo) || orderDate == null || blank(authority)) {
            return Optional.of("MISSING");
        }
        if (orderNo.strip().length() > 60 || authority.strip().length() > ORDER_FIELD) {
            return Optional.of("TOO_LONG");
        }
        if (orderDate.isAfter(today)) {
            return Optional.of("IN_FUTURE");
        }
        return Optional.empty();
    }

    /** SRS-1-61 / SRS-1-23: the approval route of a task. */
    public static Route route(
            boolean otpRequired, boolean hqIfRetirementPast, boolean retirementPast) {
        if (otpRequired) {
            return Route.SUBMIT_OTP;
        }
        return hqIfRetirementPast && retirementPast ? Route.SUBMIT_HQ : Route.SUBMIT;
    }

    /**
     * SRS-1-22: the retirement date from a date of birth and the superannuation age (A-07
     * RETIREMENT_AGE): the last day of the month in which the member attains the age; a member born
     * on the first of a month retires on the last day of the month before.
     */
    // OPEN-QUESTION: plan 16 D16-7 - the day convention (FR 56 practice) is not stated by the SRS /
    // FRS;
    // logged for FCI in OWNER_DECISIONS and PENDING 16-retirement.
    public static LocalDate retirementDate(LocalDate birthDate, int age) {
        LocalDate attains = birthDate.plusYears(age);
        YearMonth month = YearMonth.from(attains);
        if (birthDate.getDayOfMonth() == 1) {
            month = month.minusMonths(1);
        }
        return month.atEndOfMonth();
    }

    /**
     * SRS-1-22 / SRS-1-59: the EPS cut-off: the day the member attains the EPS age (58).
     *
     * <p>OPEN-QUESTION CLR-129 (BMAD TR-039): for a 29 February birth in a non-leap year the source
     * does not say 28 February or 1 March; java.time gives 28 February. Not pinned by a test.
     */
    public static LocalDate epsCutoff(LocalDate birthDate, int age) {
        return birthDate.plusYears(age);
    }

    /**
     * SRS-1-24: a recalculated retirement date in the current or the next month alerts the Maker
     * and the Checker before the month-end lock.
     */
    public static boolean retiresSoon(LocalDate retirement, YearMonth current) {
        YearMonth month = YearMonth.from(retirement);
        return month.equals(current) || month.equals(current.plusMonths(1));
    }

    /**
     * SRS-1-68: a pending task stays pending beyond the days set on A-07 (I21_AGEING_DAYS). While
     * the days are not set no task is overdue: the count of days pending is still shown.
     */
    public static boolean overdue(boolean pending, long daysPending, OptionalLong ageingDays) {
        return pending && ageingDays.isPresent() && daysPending > ageingDays.getAsLong();
    }

    /** I-21 Reason for Change (SRS l.3850): Alphanumeric (500), from HRMS. */
    public static final int REASON_LENGTH = 500;

    /**
     * I-21 Reason for Change (SRS l.3850) as held: HRMS's text stripped, at most {@link
     * #REASON_LENGTH} characters, every character outside printable ASCII shown as '?' (R-ASCII:
     * the database is US7ASCII). A change is never refused for its reason text (OD-71): the pay
     * effect it carries must reach the Maker. Blank or absent: none.
     */
    public static String reason(String received) {
        if (received == null || received.isBlank()) {
            return null;
        }
        StringBuilder held = new StringBuilder();
        for (char c : received.strip().toCharArray()) {
            held.append(c >= 0x20 && c <= 0x7E ? c : '?');
            if (held.length() == REASON_LENGTH) {
                break;
            }
        }
        return held.toString();
    }

    /** SRS-1-28: the HCA- task number of the year, from the task id. */
    public static String taskNo(int year, long id) {
        return "HCA-" + year + "-" + String.format("%04d", id);
    }

    /**
     * SRS-1-54 / SRS-1-56: Restricted values shown masked on I-21, its registers and exports: PAN,
     * Aadhaar and a bank account by their last four characters only ({@code PiiMasks}; SRS l.754 /
     * l.802, BMAD TR-035); other values as they are.
     */
    public static String mask(String fieldCode, String value) {
        if (value == null) {
            return null;
        }
        return switch (fieldCode) {
            case "PAN" -> blank(value) ? value : PiiMasks.pan(value);
            case "AADHAAR", "BANK_ACCOUNT" -> blank(value) ? value : PiiMasks.lastFour(value);
            default -> value;
        };
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
