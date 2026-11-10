package in.gov.fci.payroll.general.orchestration;

import in.gov.fci.payroll.general.business.GeneralErrorCode;
import in.gov.fci.payroll.payroll.api.PendingSalary;
import in.gov.fci.payroll.platform.error.FciException;
import java.time.YearMonth;
import java.util.List;

/** The pending salary that I-20 and I-23 pay, computed with the regular rules (never typed). */
final class Salaries {

    private Salaries() {}

    /**
     * The member's computed salary for the month.
     *
     * @throws FciException OFC-422-COMPUTE when nothing is payable (salary stopped / held, no
     *     basic) or a rate, pay code or member field is missing - the amount is never taken as zero
     */
    static PendingSalary.Result payable(PendingSalary pending, Long employeeId, YearMonth month) {
        PendingSalary.Result r;
        try {
            r = pending.compute(employeeId, month);
        } catch (PendingSalary.NotComputable ex) {
            throw notComputable(ex.getMessage());
        }
        if (!r.computed()) {
            throw notComputable(r.skipReason());
        }
        return r;
    }

    private static FciException notComputable(String reason) {
        return new FciException(
                GeneralErrorCode.OFFCYCLE_NOT_COMPUTABLE, new Object[] {reason}, List.of(), null);
    }
}
