package in.gov.fci.payroll.payroll.batch.computation;

import in.gov.fci.payroll.jobs.batch.support.InvalidRecordException;
import in.gov.fci.payroll.payroll.business.PayDataException;

/**
 * A member the pay rules could not compute: skipped into the failure log like any invalid record
 * (the run goes on), and carrying where the failed value is corrected for the X-05 worklist.
 */
class MemberBlockedException extends InvalidRecordException {

    private static final long serialVersionUID = 1L;

    private final transient PayDataException.Where where;

    MemberBlockedException(PayDataException cause) {
        super(cause.getMessage());
        this.where = cause.where();
    }

    /** Where the value is corrected, or null when the rule does not know it. */
    PayDataException.Where where() {
        return where;
    }
}
