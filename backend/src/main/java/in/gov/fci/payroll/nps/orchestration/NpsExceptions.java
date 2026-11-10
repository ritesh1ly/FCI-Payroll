package in.gov.fci.payroll.nps.orchestration;

import in.gov.fci.payroll.general.api.ExceptionWorklist;
import in.gov.fci.payroll.nps.business.NpsRules.Mismatch;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * SRS-9-1a: the X-05 / C-08 exception of an NPS record (gate G2), raised for a mismatch
 * (HRMS-owned, corrected in HRMS) and cleared once the month's record is consistent. One helper for
 * every way a record changes: the monthly intake and an approved I-21 NPS change (Wave C review:
 * the approved change also clears it).
 */
@Component
class NpsExceptions {

    private final JdbcTemplate jdbc;
    private final ExceptionWorklist worklist;

    NpsExceptions(JdbcTemplate jdbc, ExceptionWorklist worklist) {
        this.jdbc = jdbc;
        this.worklist = worklist;
    }

    /** Raises the record's mismatch on X-05 and links it, or clears an open one. */
    void raiseOrClear(
            String actor, long id, long employeeId, Long cycleId, Mismatch status, String yymm) {
        if (status.blocksLock()) {
            Long exceptionId =
                    worklist.raise(
                            actor,
                            new ExceptionWorklist.NewEntry(
                                    cycleId,
                                    employeeId,
                                    "I-14",
                                    NpsIntakeService.EXCEPTION,
                                    "BLOCKING",
                                    "G2",
                                    null,
                                    NpsIntakeService.ENTITY,
                                    "mismatch_status",
                                    "HRMS",
                                    "HRMS",
                                    "NPS "
                                            + yymm
                                            + ": "
                                            + (status == Mismatch.FLAG_WITHOUT_AMOUNT
                                                    ? "NPS flag without amount"
                                                    : "amount without NPS flag")));
            jdbc.update(
                    "UPDATE nps_deduction SET exception_id = ? WHERE nps_deduction_id = ?",
                    exceptionId,
                    id);
        } else {
            worklist.clear(
                    actor,
                    NpsIntakeService.EXCEPTION,
                    cycleId,
                    employeeId,
                    "mismatch_status",
                    "NPS record of " + yymm + " corrected in HRMS");
        }
    }
}
