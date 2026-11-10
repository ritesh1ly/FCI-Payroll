package in.gov.fci.payroll.cpf.business;

import java.util.List;

/**
 * The CPF Self-Service Portal interface (SRS-6-10, SRS-6-42, SRS-6-44, SRS-6-52; DDL {@code
 * cpf_ssp_sync_log}): a port. The mechanism is open (KB DB-7) and belongs to module 23; until it
 * exists the shipped implementation is not configured and every change is logged SKIPPED with its
 * reason, never silently dropped (DDL comment on {@code cpf_ssp_sync_log}). Tracker API-07-5.
 */
public interface SspGateway {

    /**
     * A member change the portal must learn of.
     *
     * @param trigger MASTER_CHANGE, CYCLE_LEDGER, RECONCILIATION, REQUEST, PARENT_ZONE_CHANGE or
     *     OB_CORRECTION
     */
    record Change(Long employeeId, String trigger, List<String> fields) {}

    /** Whether an interface to the portal is configured. */
    boolean configured();

    /**
     * Sends the change (only when configured); a failure is thrown.
     *
     * @param idempotencyKey Wave D review 7: the key of the change's log row ({@code
     *     CPF-SSP-<sync_id>}); a resend of the same row carries the same key, so the portal applies
     *     it once
     */
    void send(Change change, String idempotencyKey);
}
