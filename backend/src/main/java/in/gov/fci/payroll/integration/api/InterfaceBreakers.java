package in.gov.fci.payroll.integration.api;

/**
 * The circuit breaker of an interface for a caller that runs its own retries per record (SRS-INT-6,
 * SRS-INT-16, BR-INT-008; OD-148 member e-mail): the same database-held breaker as {@link
 * InterfaceCalls}, with the interface's settings, shown on X-03. While it is open the caller sends
 * nothing and defers; at the probe time one call goes through; a failure counts towards the
 * threshold and raises the Integration Failure.
 */
public interface InterfaceBreakers {

    /**
     * Whether a call may go now: the breaker is closed, or open with its probe time come (it turns
     * HALF_OPEN and this call is the probe). False while open, or when the interface has no
     * settings in force.
     */
    boolean admit(String interfaceCode);

    /** The call succeeded: the breaker closes and its failure count resets. */
    void succeeded(String interfaceCode);

    /**
     * The call failed for a reason of the counterparty (outage, refused connection, rejected
     * credentials), not of one record: counts one failure, opens the breaker at its threshold and
     * raises the Integration Failure on X-03.
     *
     * @param reason identifiers only, no personal data (SRS-SEC-4)
     */
    void failed(String interfaceCode, String reason);
}
