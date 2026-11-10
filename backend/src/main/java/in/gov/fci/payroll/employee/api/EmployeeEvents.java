package in.gov.fci.payroll.employee.api;

import java.time.LocalDate;

/**
 * Domain events of the employee module, published in the transaction that applies an approved I-17
 * event or M-11 transfer, so a listener's change (iam: SRS-G-36, the account is deactivated or the
 * access withdrawn "in the same act") commits or rolls back with it.
 */
public final class EmployeeEvents {

    private EmployeeEvents() {}

    /** An approved SEPARATION or DISMISSAL: the holder's account is deactivated. */
    public record EmployeeSeparated(
            Long employeeId,
            Long eventId,
            String eventType,
            LocalDate eventDate,
            String reason,
            String decidedBy) {}

    /**
     * Any approved I-17 life-cycle event (suspension, reinstatement, deputation out / in,
     * separation, ...), for modules whose state follows the member's (the CPF ledger: SRS-6-9 sets
     * the ex-member interest stop on separation, clears it on reinstatement and never applies it on
     * deputation).
     *
     * @param endDate the event's end date, if it has one (e.g. a deputation)
     */
    public record EmployeeLifecycleApplied(
            Long employeeId,
            Long eventId,
            String eventType,
            LocalDate eventDate,
            LocalDate endDate,
            String decidedBy) {}

    /**
     * SRS-1-12: the Payroll Checker returned a new record still in onboarding; it is back in
     * Payroll Setup Pending and Establishment / HR of its paying unit is told the reason.
     */
    public record OnboardingReturned(
            long employeeId, String eis, long payingUnitId, String reason, String decidedBy) {}

    /**
     * SRS-1-24 (US-GAP-45): an approved date of birth whose recalculated retirement falls in the
     * current or next month, so the Payroll Maker and Checker are alerted before the month-end lock
     * (separation and final settlement can be prepared).
     */
    public record RetirementSoon(
            long employeeId, LocalDate retirementDate, Long approvalId, String decidedBy) {}

    /** An approved transfer: all rights at the old unit are withdrawn from the transfer date. */
    public record EmployeeTransferred(
            Long employeeId,
            Long transferId,
            Long fromLocationId,
            Long toLocationId,
            LocalDate transferDate,
            String orderReference,
            String decidedBy) {}
}
