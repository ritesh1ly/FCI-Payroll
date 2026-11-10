package in.gov.fci.payroll.nps.api;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Intake of the NPS record HRMS sends for a member and month (plan 18 phase 18a; SRS-9-1, SRS-9-1a,
 * SRS-9-2, SRS-9-23; D18-1, D18-2). The HRMS interface (module 23) calls it; no screen keys an NPS
 * value (R-SRC). A record for a month already received replaces its values until the month is
 * locked, audited before and after, except a change with pay effect, which waits for its I-21 task
 * (US-GAP-291); after the lock it is refused (a back-dated change comes through I-21).
 */
public interface NpsReceipts {

    /**
     * One record as HRMS sends it.
     *
     * @param eis the employee number
     * @param payrollYymm the month, YYYYMM
     * @param npsMember the HRMS NPS flag
     * @param pran the PRAN, a reference only (never validated with the CRA)
     * @param employeeAmount the employee NPS of the month, as computed by HRMS
     * @param employerAmount the employer NPS of the month, as computed by HRMS
     * @param effectiveFrom from when the values apply, if HRMS sends it
     */
    record Receipt(
            String eis,
            String payrollYymm,
            boolean npsMember,
            String pran,
            BigDecimal employeeAmount,
            BigDecimal employerAmount,
            LocalDate effectiveFrom) {}

    /**
     * The stored record.
     *
     * @param mismatchStatus CONSISTENT, FLAG_WITHOUT_AMOUNT, AMOUNT_WITHOUT_FLAG or
     *     RESOLVED_IN_HRMS
     * @param replaced whether an earlier record of the month was replaced
     * @param heldForTask SRS-9-23 (US-GAP-291): the I-21 task a change with pay effect waits on
     *     (the record of the month unchanged meanwhile); null when the record was applied at once
     */
    record Received(
            long npsDeductionId, String mismatchStatus, boolean replaced, String heldForTask) {}

    /** Stores the record (insert or replace), raising or clearing its mismatch on X-05 / C-08. */
    Received receive(String actor, Receipt receipt);
}
