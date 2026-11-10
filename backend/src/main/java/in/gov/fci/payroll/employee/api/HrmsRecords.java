package in.gov.fci.payroll.employee.api;

import java.time.LocalDate;
import java.util.Optional;

/**
 * Reads an employee's current record from HRMS (SRS-1-70: "on submission, fetch the employee's
 * current record from HRMS"). Implemented by the HRMS interface (module 23); until a bean exists,
 * an HRMS Update Request records the fetch as failed, "not configured", and can be fetched again
 * (plan 16 D16-5).
 */
public interface HrmsRecords {

    /** The value HRMS holds for the field, with its effective date when HRMS gives one. */
    record Fetched(String value, LocalDate effectiveDate) {}

    /**
     * The employee's current value of a field with pay effect in HRMS; empty when HRMS holds none.
     *
     * @throws Unavailable when HRMS cannot be read (the request is recorded as FETCH_FAILED)
     */
    Optional<Fetched> current(long employeeId, String fieldCode);

    /**
     * What HRMS holds for an employee now, read live for M-01 (PENDING API-16-7).
     *
     * @param available HRMS answered; false when it could not be read (or holds no such employee)
     * @param basic the basic pay HRMS holds, or null
     * @param status the employee status HRMS holds, or null
     */
    record Snapshot(boolean available, java.math.BigDecimal basic, String status) {}

    /** Nothing read: HRMS is not reachable or no interface is configured. */
    Snapshot UNAVAILABLE = new Snapshot(false, null, null);

    /** The live read of {@code empNum}; never throws for an unreachable HRMS. */
    default Snapshot snapshot(String empNum) {
        return UNAVAILABLE;
    }

    /** HRMS could not be read; the message is logged, never shown. */
    class Unavailable extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public Unavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
