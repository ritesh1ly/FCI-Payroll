package in.gov.fci.payroll.employee.business;

import java.util.Optional;

/**
 * The rules of an HRMS Update Request (plan 16 phase 16c; SRS-1-70; DDL {@code
 * hrms_update_request}), as pure functions. A request never changes a value by itself: it opens an
 * I-21 task when HRMS already holds a different value, links the task HRMS already sent, or closes
 * with "not yet in HRMS".
 */
public final class HrmsUpdateRules {

    /** DDL {@code hrms_update_request.request_status}. */
    public enum Status {
        SUBMITTED,
        TASK_OPENED,
        LINKED_EXISTING,
        NOT_YET_IN_HRMS,
        FETCH_FAILED,
        CLOSED
    }

    /** DDL {@code hrms_update_request.fetch_result}. */
    public enum FetchResult {
        DIFFERS,
        NO_CHANGE,
        FETCH_FAILED
    }

    /** DDL {@code raised_channel}: the employee in eSeva or the Payroll Maker on I-21. */
    public enum Channel {
        ESEVA,
        PAYROLL_MAKER
    }

    /** The message closing a request whose change is not yet in HRMS (SRS-1-70). */
    public static final String NOT_YET_IN_HRMS = "HRMS_NOT_YET_UPDATED";

    /** The message of a fetch that failed or has no HRMS interface yet (D16-5). */
    public static final String FETCH_FAILED = "HRMS_FETCH_FAILED";

    public static final String FETCH_NOT_CONFIGURED = "HRMS_FETCH_NOT_CONFIGURED";

    private static final int REFERENCE = 60;

    private HrmsUpdateRules() {}

    /**
     * SRS-1-70: HRMS differs from Payroll when it holds a value and that value is not Payroll's
     * (compared without surrounding blanks); no value in HRMS, or the same value, is "no change".
     */
    public static FetchResult compare(Optional<String> hrmsValue, String payrollValue) {
        if (hrmsValue.isEmpty() || hrmsValue.get().isBlank()) {
            return FetchResult.NO_CHANGE;
        }
        String payroll = payrollValue == null ? "" : payrollValue.strip();
        return hrmsValue.get().strip().equals(payroll)
                ? FetchResult.NO_CHANGE
                : FetchResult.DIFFERS;
    }

    /**
     * SRS-1-70: the request's defect, if any: NOT_PAY_EFFECT (only a field with pay effect may be
     * requested), NO_DATE (the effective date is required), REFERENCE_TOO_LONG.
     */
    public static Optional<String> defect(
            boolean fieldHasPayEffect, boolean hasEffectiveDate, String hrmsReference) {
        if (!fieldHasPayEffect) {
            return Optional.of("NOT_PAY_EFFECT");
        }
        if (!hasEffectiveDate) {
            return Optional.of("NO_DATE");
        }
        if (hrmsReference != null && hrmsReference.strip().length() > REFERENCE) {
            return Optional.of("REFERENCE_TOO_LONG");
        }
        return Optional.empty();
    }

    /** SRS-1-70: only a request whose fetch failed may be fetched again. */
    public static boolean refetchable(Status status) {
        return status == Status.FETCH_FAILED;
    }

    /** The HUR- request number of the year, from the request id. */
    public static String requestNo(int year, long id) {
        return "HUR-" + year + "-" + String.format("%04d", id);
    }
}
