package in.gov.fci.payroll.integration.api;

/**
 * The adapter of one interface (HLD: one adapter per external system; SRS-INT-1, BR-INT-014): it
 * maps the counterparty's message or file to the Payroll data model. An adapter is built only once
 * the interface's specification is held (owner 2026-10-28); until then X-03 refuses Test connection
 * and Reprocess for the interface with "no adapter".
 */
public interface InterfaceAdapter {

    /** {@code interface_registry.interface_code}. */
    String interfaceCode();

    /** Test connection (SRS-INT-33): a one-line outcome, never a credential or personal data. */
    String testConnection();

    /**
     * Re-runs one staged record that failed beyond the retry limit (SRS-INT-31): applies it, or
     * throws with the exact reason.
     */
    void reprocess(long stagingId, String txnType, String payload);
}
