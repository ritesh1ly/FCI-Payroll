package in.gov.fci.payroll.leave.api;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Intake of the employee leave data HRMS sends (plan 19 phase 19a; SRS-LV-24, SRS-LV-25, SRS-LV-13,
 * SRS-LV-26; D19-1). The HRMS interface (module 23) calls it; no screen keys a leave value (R-SRC,
 * SRS-G-45). Leave types and encashable balances are stored as received; a leave event is
 * validated, and either held on the X-05 worklist (never applied on an assumption) or opens an I-21
 * task that applies its pay effect once the Payroll Checker approves it.
 */
public interface LeaveReceipts {

    /**
     * A leave type as HRMS holds it (M-13).
     *
     * @param encashable the HRMS encashable flag (BR-LEAVE-009)
     * @param halfDay whether HRMS sanctions it by the half day
     */
    record LeaveType(String code, String name, boolean encashable, boolean halfDay) {}

    /**
     * An encashable balance as HRMS holds it on a day (BR-LEAVE-003, BR-LEAVE-013).
     *
     * @param finYear the financial year it belongs to (YYYY-YY), if HRMS sends it
     */
    record Balance(String eis, String leaveType, String finYear, BigDecimal days, LocalDate asOf) {}

    /** What the event does to leave already received (DDL {@code leave_event_type}). */
    enum EventType {
        ADDITION,
        CANCELLATION,
        CONVERSION
    }

    /**
     * A leave event as sanctioned in HRMS.
     *
     * @param days the days sanctioned (a half day as 0.5 when the type allows it)
     * @param sanctionRef the HRMS sanction reference (required, SRS-LV-13)
     * @param hrmsRequestId the HRMS Request ID (idempotency, BR-INT-004)
     * @param eventType an addition, or the cancellation / conversion of an earlier sanction
     * @param earlierSanctionRef the sanction a cancellation or conversion acts on
     */
    record Event(
            String eis,
            String leaveType,
            LocalDate from,
            LocalDate to,
            BigDecimal days,
            boolean halfDay,
            String sanctionRef,
            String hrmsRequestId,
            String sanctioningAuthority,
            EventType eventType,
            String earlierSanctionRef) {}

    /**
     * An in-service leave encashment sanctioned in HRMS (SRS-LV-34, SRS-LV-17).
     *
     * @param days the days HRMS sanctioned for encashment
     * @param encashmentDate the date the pay is valued on (SRS-LV-16)
     * @param orderPermits whether the sanctioning order permits encashment under suspension
     *     (SRS-LV-22)
     */
    record EncashmentSanction(
            String eis,
            String leaveType,
            BigDecimal days,
            LocalDate encashmentDate,
            String sanctionRef,
            String hrmsRequestId,
            boolean orderPermits) {}

    /** What became of an event. */
    enum Outcome {
        /** Valid: recorded and an I-21 task opened for the Payroll Maker. */
        TASK_OPENED,
        /** Already received (same member, type, sanction and period): nothing new. */
        DUPLICATE,
        /** Not valid: held on X-05 and referred to HRMS (SRS-LV-13). */
        HELD
    }

    /**
     * The result of an event.
     *
     * @param defect why it was held, null otherwise
     */
    record Received(Outcome outcome, Long applicationId, Long taskId, String defect) {}

    /** Stores the leave type (insert or update), audited. */
    void receiveType(String actor, LeaveType type);

    /** Stores the balance of the member and type on its day (insert or replace), audited. */
    void receiveBalance(String actor, Balance balance);

    /** Validates the event, then records it and opens its task, or holds it on X-05. */
    Received receiveEvent(String actor, Event event);

    /**
     * Validates the sanction (encashable type, within the HRMS balance, not under suspension unless
     * the order permits, never twice), then opens its I-21 task, or holds it on X-05 with the
     * reason (an encashment above the balance names the balance).
     */
    Received receiveEncashment(String actor, EncashmentSanction sanction);
}
