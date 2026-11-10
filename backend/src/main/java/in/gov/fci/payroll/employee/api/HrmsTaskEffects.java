package in.gov.fci.payroll.employee.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;

/**
 * The effect of an approved I-21 task whose field another module owns (plan 09 phase 09c; SRS-G-45,
 * SRS-12-17, SRS-12-19): loans and advances received from HRMS reach the loan ledger only when
 * their task is approved, from the effective date. The employee module owns I-21 and this port; the
 * owning module registers a bean for its field codes, so I-21 never depends on it.
 */
public interface HrmsTaskEffects {

    /**
     * The approved task.
     *
     * @param newValue the value HRMS sent (read-only), e.g. the HRMS loan record as JSON
     */
    record Task(
            long taskId,
            String taskNo,
            long employeeId,
            String fieldCode,
            String newValue,
            LocalDate effectiveDate,
            String hrmsRequestId) {}

    /** The I-21 field codes this module applies. */
    Set<String> fields();

    /**
     * Refuses a value HRMS sent that the owning module cannot apply (checked at intake, so a task
     * is never opened on a value that could not be approved).
     *
     * @throws RuntimeException naming what is wrong
     */
    void check(String fieldCode, long employeeId, String newValue);

    /**
     * The I-21 route of the field's task when it is not the standard one, as the route's action
     * (e.g. {@code SUBMIT_LOAN_FORECLOSURE} for route {@code I21_SUBMIT_LOAN_FORECLOSURE}).
     */
    default Optional<String> route(String fieldCode) {
        return Optional.empty();
    }

    /**
     * Refuses the final approval of the task before any OTP is asked or verified (Wave D review 2,
     * SRS-G-21): the owner's preconditions that can change while the task waits (e.g. a newer NPS
     * record of the month). The owner re-checks in {@link #apply}. Nothing by default.
     *
     * @throws RuntimeException naming what is wrong
     */
    default void beforeApproval(Task task) {}

    /** Applies the approved task, in the decision's transaction. */
    void apply(Task task, String checker, long approvalId, Instant decidedAt);

    /**
     * What approving the task would do, for the Checker before the decision (US-GAP-58: the
     * encashment cap and amount); empty when the field shows none.
     */
    default Optional<String> preview(String fieldCode, long employeeId, String newValue) {
        return Optional.empty();
    }

    /**
     * Whether the task shows the month-by-month pay impact preview (UC I-21 l.3813, US-GAP-276):
     * false for a field whose change applies to one stated month itself, not from an effective date
     * onwards (Wave C review: the NPS record of a month, SRS-9-23).
     */
    default boolean showsPayImpact(String fieldCode) {
        return true;
    }
}
