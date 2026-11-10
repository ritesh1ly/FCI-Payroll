package in.gov.fci.payroll.integration.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The connection pool of the outbound HRMS WebClient (owner 2026-11-05; SRS-INT-16, R-INT): a named
 * Reactor Netty pool with a bound on connections and on callers waiting for one, idle and lifetime
 * limits and background eviction. These are infrastructure sizes, not business rules, so they have
 * working defaults; the per-API time limits, retries and back-off come from {@code
 * interface_config}, the per-API concurrency from {@code fci.integration.hrms.bulkhead.<code>}.
 *
 * <pre>
 * fci.integration.hrms.client.max-connections: 50
 * fci.integration.hrms.client.pending-acquire-max-count: 100
 * fci.integration.hrms.client.pending-acquire-timeout-seconds: 5
 * fci.integration.hrms.client.max-idle-seconds: 30
 * fci.integration.hrms.client.max-life-seconds: 300
 * fci.integration.hrms.client.evict-in-background-seconds: 30
 * fci.integration.hrms.client.api-key / api-key-file                what Payroll presents to HRMS
 * fci.integration.hrms.client.bearer-token / bearer-token-file      (secrets: environment or file, never logged)
 * </pre>
 *
 * TLS verification is on (the JVM trust store); there is no setting to turn it off.
 */
@ConfigurationProperties("fci.integration.hrms.client")
public record HrmsClientProperties(
        Integer maxConnections,
        Integer pendingAcquireMaxCount,
        Integer pendingAcquireTimeoutSeconds,
        Integer maxIdleSeconds,
        Integer maxLifeSeconds,
        Integer evictInBackgroundSeconds,
        String apiKey,
        String apiKeyFile,
        String bearerToken,
        String bearerTokenFile) {

    /** The api-key Payroll presents to HRMS, from the setting or its secret file. */
    public java.util.Optional<String> key() {
        return secret(apiKey, apiKeyFile);
    }

    /** The bearer token Payroll presents to HRMS, from the setting or its secret file. */
    public java.util.Optional<String> bearer() {
        return secret(bearerToken, bearerTokenFile);
    }

    static java.util.Optional<String> secret(String direct, String file) {
        if (direct != null && !direct.isBlank()) {
            return java.util.Optional.of(direct.strip());
        }
        if (file == null || file.isBlank()) {
            return java.util.Optional.empty();
        }
        try {
            String text =
                    java.nio.file.Files.readString(
                                    java.nio.file.Path.of(file.strip()),
                                    java.nio.charset.StandardCharsets.UTF_8)
                            .strip();
            return text.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(text);
        } catch (java.io.IOException | RuntimeException ex) {
            return java.util.Optional.empty();
        }
    }

    @Override
    public String toString() {
        return "HrmsClientProperties[key="
                + key().isPresent()
                + ", bearer="
                + bearer().isPresent()
                + "]";
    }

    /** Connections the pool holds. */
    public int connections() {
        return positive(maxConnections, 50);
    }

    /** Callers that may wait for a connection. */
    public int pending() {
        return positive(pendingAcquireMaxCount, 100);
    }

    /** How long a caller waits for a connection. */
    public Duration pendingTimeout() {
        return Duration.ofSeconds(positive(pendingAcquireTimeoutSeconds, 5));
    }

    /** An idle connection is closed after this. */
    public Duration maxIdle() {
        return Duration.ofSeconds(positive(maxIdleSeconds, 30));
    }

    /** A connection is replaced after this. */
    public Duration maxLife() {
        return Duration.ofSeconds(positive(maxLifeSeconds, 300));
    }

    /** The pool checks for idle and expired connections at this interval. */
    public Duration evictInterval() {
        return Duration.ofSeconds(positive(evictInBackgroundSeconds, 30));
    }

    private static int positive(Integer value, int fallback) {
        return value == null || value < 1 ? fallback : value;
    }
}
