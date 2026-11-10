package in.gov.fci.payroll.employee.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * The one way an HRMS change of an employee field enters Payroll (I-21; SRS-1-19, SRS-1-28,
 * SRS-G-45; plan 16 D16-2): the HRMS interface (module 23) calls it for every change it receives,
 * the X-07 fallback reconcile for an uploaded order. No Payroll screen types a change. A field
 * without pay effect (name, gender, marital status) is applied at once with an audit entry; any
 * other opens an I-21 task with the value locked.
 */
public interface HrmsChanges {

    /** Where a change came from (DDL {@code hrms_change_task.task_source}). */
    enum Source {
        HRMS_PUSH,
        UPDATE_REQUEST_FETCH,
        FALLBACK_RECONCILE
    }

    /**
     * One change as received.
     *
     * @param fieldCode {@code hrms_pay_effect_field.field_code}
     * @param newValue the value in HRMS, as text (dates YYYY-MM-DD, amounts plain)
     * @param hrmsRequestId the HRMS Request ID (idempotency key with the field; may be null for a
     *     fetch)
     * @param hrmsReason I-21 "Reason for Change" (SRS l.3850: from HRMS, 500 characters, read-only
     *     in Payroll); null when HRMS sends none
     */
    record Change(
            long employeeId,
            String fieldCode,
            String newValue,
            LocalDate effectiveDate,
            String hrmsRequestId,
            Source source,
            String hrmsReason) {

        /** A change received without a reason. */
        public Change(
                long employeeId,
                String fieldCode,
                String newValue,
                LocalDate effectiveDate,
                String hrmsRequestId,
                Source source) {
            this(employeeId, fieldCode, newValue, effectiveDate, hrmsRequestId, source, null);
        }
    }

    /** What was done with it: the task (or the auto-applied record) and whether it was new. */
    record Received(long taskId, String taskNo, String status, boolean duplicate) {}

    /**
     * Records the change. Idempotent: the same HRMS Request ID and field returns the task opened
     * before (BR-INT-004).
     */
    Received receive(Change change);

    /** The I-21 tasks not yet decided and the oldest one's receipt (X-03 shows their age). */
    record Awaiting(long count, Instant oldestReceivedAt) {}

    /** HRMS changes awaiting I-21 approval (SRS X-03 Enterprise Interfaces). */
    Awaiting awaiting();

    /**
     * One task of a field still waiting for its decision (order details, Checker, HQ Establishment
     * or returned to the Maker).
     *
     * @param newValue the value HRMS sent, read-only
     */
    record Open(long taskId, String taskNo, String status, String newValue) {}

    /**
     * The member's tasks of {@code fieldCode} still waiting for their decision, oldest first: for
     * an owning module whose record a newer HRMS record replaces (Wave C review: the NPS record of
     * a month, SRS-9-23).
     */
    List<Open> open(long employeeId, String fieldCode);

    /**
     * Closes a task still waiting as SUPERSEDED by a newer record from HRMS (never deleted, R-DEL),
     * linked to the task that replaces it when there is one, and audited (R-AUD). A task already
     * decided is left as it is. A task already with the Checker cannot be approved afterwards
     * (HRC-409-SUPERSEDED); a rejection or a return of it leaves it SUPERSEDED.
     *
     * @param supersededByTaskId the newer task, or null when the newer record applied at once
     * @return whether the task was closed
     */
    boolean supersede(long taskId, Long supersededByTaskId, String actor, String reason);
}
