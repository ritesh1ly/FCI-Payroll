package in.gov.fci.payroll.employee.api;

import java.time.LocalDate;
import java.util.Optional;

/**
 * The work-slips a member works on, for the origin check of an M-11 transfer (UC M-11 main flow,
 * SRS l.1321: "no work-slip or computation exists there after it"). Implemented by the labour
 * module, which owns {@code work_slip}; the employee module never reads that table itself
 * (ARCHITECTURE.md: a module never reads another module's tables). The port lives here, not in the
 * labour module, because the labour module already depends on {@code employee::api}.
 */
public interface OriginWorkSlips {

    /**
     * The earliest date, on or after {@code from}, of a work-slip the member works on that is not
     * cancelled or rejected, if any.
     */
    Optional<LocalDate> firstFrom(long employeeId, LocalDate from);
}
