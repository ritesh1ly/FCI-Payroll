package in.gov.fci.payroll.integration.api;

import java.time.LocalDate;
import java.util.Optional;

/**
 * The resilience settings of an interface (SRS-INT-6, SRS-INT-16, BR-INT-008; DDL {@code
 * interface_config}): retry count, back-off, time limit, breaker threshold and acknowledgement
 * timeout, set as per FCI's IT policy at go-live. None is a coded constant: an interface without a
 * complete row in force is not configured and nothing is exchanged through it (plan 23 D23-3).
 */
public interface InterfaceSettings {

    /** One interface's settings in force. */
    record Settings(
            String interfaceCode,
            int retryCount,
            int backoffSeconds,
            String backoffStrategy,
            int timeoutSeconds,
            boolean circuitBreakerRequired,
            Integer circuitBreakerThreshold,
            Integer ackTimeoutSeconds,
            String counterpartySla,
            LocalDate effectiveFrom,
            boolean confirmed) {}

    /** The complete settings in force today, or empty when the interface is not configured. */
    Optional<Settings> inForce(String interfaceCode);
}
