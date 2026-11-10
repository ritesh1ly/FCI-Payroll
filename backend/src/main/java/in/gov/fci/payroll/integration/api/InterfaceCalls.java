package in.gov.fci.payroll.integration.api;

import java.util.concurrent.Callable;

/**
 * Every synchronous call or batch load through an interface goes here (SRS-INT-6, SRS-INT-16,
 * SRS-INT-31; HLD integration layer: one adapter per system with retry, back-off and circuit
 * breaker): the call is refused while the interface is not configured or its breaker is open; it
 * runs under the interface's time limit and is retried with its back-off; a call that keeps failing
 * beyond the retry limit opens the breaker at its threshold, goes to the exception queue and raises
 * the Integration Failure at once.
 */
public interface InterfaceCalls {

    /**
     * Calls through the interface.
     *
     * @throws in.gov.fci.payroll.platform.error.FciException INT-503-NOT-CONFIGURED,
     *     INT-503-CIRCUIT-OPEN or INT-502-CALL-FAILED
     */
    <T> T call(String interfaceCode, Callable<T> call);
}
