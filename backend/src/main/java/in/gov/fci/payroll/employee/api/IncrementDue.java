package in.gov.fci.payroll.employee.api;

import java.time.LocalDate;
import java.util.Optional;

/**
 * SRS-13-1 (US-GAP-51): the member's next increment due date, for the M-01 record. Implemented by
 * the increment module (module 17), which owns {@code employee_increment_due}; the employee module
 * only shows it.
 */
public interface IncrementDue {

    /** The next increment due date on record, if any. */
    Optional<LocalDate> nextDue(long employeeId);
}
