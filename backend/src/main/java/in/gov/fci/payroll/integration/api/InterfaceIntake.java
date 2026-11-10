package in.gov.fci.payroll.integration.api;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The envelope of an exchange window and its records (SRS-INT-11, SRS-INT-12, SRS-INT-31,
 * SRS-INT-32; DDL {@code interface_run}, {@code intf_staging_txn}, {@code
 * interface_acknowledgement}, {@code interface_exception}). An adapter opens a window, stages each
 * record as received (idempotent on its source key), marks it applied or rejected with the exact
 * reason, and completes the window: the counts are checked and an unbalanced window is FAILED.
 */
public interface InterfaceIntake {

    /** One inbound record as received; the payload is kept as received, never merged away. */
    record Inbound(
            String txnType,
            String requestId,
            String hrmsRequestId,
            Long employeeId,
            String payload,
            String reprocessOfRequestId) {}

    /** What became of a staged record. */
    enum Outcome {
        STAGED,
        DUPLICATE
    }

    /** The staging row (the earlier one for a duplicate) and the outcome. */
    record Staged(long stagingId, Outcome outcome) {}

    /** A completed window: its status (OK, PARTIAL or FAILED) and counts. */
    record Completed(long runId, String status, long received, long accepted, long rejected) {}

    /** Opens the envelope of one exchange window. */
    long open(String interfaceCode, String batchReference, Instant windowStart, Instant windowEnd);

    /**
     * Stages one record of the window (a key already staged is a duplicate, never staged twice).
     */
    Staged stage(long runId, Inbound record);

    /** The record was applied to its Payroll entity. */
    void applied(long stagingId, String appliedEntity, String appliedKey);

    /**
     * The record failed validation: rejected with the exact reason, queued for the Administrator.
     */
    void rejected(long stagingId, String reason);

    /** Completes the window from its staged records; an unbalanced window is FAILED. */
    Completed complete(long runId, BigDecimal controlTotal);

    /**
     * Completes a window that reports only its counts (a bulk pull whose records the counterparty
     * holds): received must equal accepted + rejected, otherwise the window is FAILED.
     */
    Completed completeCounts(
            long runId, long received, long accepted, long rejected, String detail);
}
