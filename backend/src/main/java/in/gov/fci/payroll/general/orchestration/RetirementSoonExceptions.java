package in.gov.fci.payroll.general.orchestration;

import in.gov.fci.payroll.employee.api.EmployeeEvents;
import in.gov.fci.payroll.general.api.ExceptionWorklist;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * SRS-1-24 (FRS §7.9.3, UC-04 A2; US-GAP-45): a date of birth approved on I-21 whose recalculated
 * retirement falls in the current or next month is raised on the X-05 worklist as a WARNING for the
 * member's office, so the Payroll Maker and Checker see it before the month-end lock and can
 * prepare separation and final settlement. It does not block the cycle.
 */
@Component
public class RetirementSoonExceptions {

    static final String CODE = "RETIREMENT_SOON";

    private final ExceptionWorklist worklist;

    public RetirementSoonExceptions(ExceptionWorklist worklist) {
        this.worklist = worklist;
    }

    @EventListener
    public void on(EmployeeEvents.RetirementSoon soon) {
        worklist.raise(
                soon.decidedBy(),
                new ExceptionWorklist.NewEntry(
                        null,
                        soon.employeeId(),
                        "I-21",
                        CODE,
                        "WARNING",
                        null,
                        null,
                        "employee",
                        "retirement_date",
                        "CALC",
                        "O-05",
                        "Retires on "
                                + soon.retirementDate()
                                + " (recalculated from the approved date of birth): prepare the"
                                + " separation and the final settlement before the lock"));
    }
}
