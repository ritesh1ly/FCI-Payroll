package in.gov.fci.payroll.payroll.api;

import java.time.Instant;

/**
 * SRS-15-30: published in the return's transaction so the Maker is notified ("notify the Maker").
 * No notification module listens yet; C-02 shows the open return to the Maker meanwhile.
 *
 * @param scope INSTANCE, ORG_UNIT or MEMBERS (DDL {@code cycle_return.return_scope})
 */
public record CycleReturned(
        long returnId,
        long cycleInstanceId,
        String period,
        String unitCode,
        String scope,
        String reason,
        String returnedBy,
        Instant returnedAt) {}
