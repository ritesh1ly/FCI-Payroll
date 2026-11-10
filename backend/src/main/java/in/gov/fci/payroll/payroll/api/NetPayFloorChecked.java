package in.gov.fci.payroll.payroll.api;

import java.time.LocalDate;

/**
 * An Actual run of a cycle instance compared its results with the M-14 net-pay floor (SRS-15-13).
 * When no floor version is in force for the period, the cycle's records are held in Exceptions &
 * Holds (C-08) "until the Rule Administrator adds the version and the Rule Approver activates it"
 * (SRS l.2028, taxonomy Rule - R); a later Actual run that finds a version in force releases them.
 *
 * @param inForce whether a floor version of {@code payrollType} was in force on {@code periodDate}
 */
public record NetPayFloorChecked(
        long cycleInstanceId,
        long runId,
        String payrollType,
        LocalDate periodDate,
        boolean inForce) {}
