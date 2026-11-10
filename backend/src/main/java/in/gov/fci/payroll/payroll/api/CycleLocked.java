package in.gov.fci.payroll.payroll.api;

import java.time.Instant;

/**
 * Published in the lock's transaction once a staff cycle instance is LOCKED (plan 18 phase 18b;
 * SRS-15-11): the modules whose records of the month become final listen to it (NPS: the month's
 * rows become APPLIED, D18-6).
 *
 * @param payrollYymm the month, YYYYMM
 */
public record CycleLocked(
        long cycleInstanceId,
        long orgUnitId,
        String payrollYymm,
        String lockedBy,
        Instant lockedAt) {}
