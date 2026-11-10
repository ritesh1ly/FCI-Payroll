package in.gov.fci.payroll.integration.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * The system-to-system credentials of the HRMS APIs (SRS-INT-4), held outside the code and never
 * defaulted in application.yaml: each value comes from the environment or a secret file.
 *
 * <pre>
 * fci.integration.hrms.security.api-key / api-key-file              the api-key header value
 * fci.integration.hrms.security.client-id                           the /token user name
 * fci.integration.hrms.security.client-secret-hash / -hash-file     bcrypt hash ({bcrypt}...) of the password
 * fci.integration.hrms.security.token-secret / token-secret-file    HMAC key signing the bearer tokens (32+ chars)
 * fci.integration.hrms.security.token-lifetime-seconds              bearer token life (default 900)
 * fci.integration.hrms.security.max-failures / failure-window-seconds  refused attempts per address before 429 (10 / 300)
 * </pre>
 *
 * Unset = the token API and every other HRMS API refuse (503). A secret is never logged: {@link
 * #toString()} shows only which settings are present.
 */
@ConfigurationProperties("fci.integration.hrms.security")
public record HrmsSecurityProperties(
        String apiKey,
        String apiKeyFile,
        String clientId,
        String clientSecretHash,
        String clientSecretHashFile,
        String tokenSecret,
        String tokenSecretFile,
        Integer tokenLifetimeSeconds,
        Integer maxFailures,
        Integer failureWindowSeconds) {

    /** The canonical constructor, the one Spring binds. */
    @ConstructorBinding
    public HrmsSecurityProperties {}

    /** Without the throttle settings (10 refused attempts in 300 seconds). */
    public HrmsSecurityProperties(
            String apiKey,
            String apiKeyFile,
            String clientId,
            String clientSecretHash,
            String clientSecretHashFile,
            String tokenSecret,
            String tokenSecretFile,
            Integer tokenLifetimeSeconds) {
        this(
                apiKey,
                apiKeyFile,
                clientId,
                clientSecretHash,
                clientSecretHashFile,
                tokenSecret,
                tokenSecretFile,
                tokenLifetimeSeconds,
                null,
                null);
    }

    /** Refused attempts from one address that lock it out for the window (default 10). */
    public int failureLimit() {
        return maxFailures == null || maxFailures < 1 ? 10 : maxFailures;
    }

    /** The window of the throttle and the lock-out, in seconds (default 300). */
    public int failureWindow() {
        return failureWindowSeconds == null || failureWindowSeconds < 1
                ? 300
                : failureWindowSeconds;
    }

    /** Lifetime when the setting is absent: short, 15 minutes (SRS-INT-4: short-lived tokens). */
    public static final int DEFAULT_LIFETIME_SECONDS = 900;

    /** The token signing key must be at least this long (HMAC-SHA-256). */
    public static final int MIN_SECRET_LENGTH = 32;

    /** The api-key, from the setting or its file. */
    public Optional<String> key() {
        return value(apiKey, apiKeyFile);
    }

    /** The bcrypt hash of the client password. */
    public Optional<String> secretHash() {
        return value(clientSecretHash, clientSecretHashFile);
    }

    /** The token signing secret, only when long enough. */
    public Optional<String> signingSecret() {
        return value(tokenSecret, tokenSecretFile).filter(s -> s.length() >= MIN_SECRET_LENGTH);
    }

    /** The configured client user name. */
    public Optional<String> client() {
        return clientId == null || clientId.isBlank()
                ? Optional.empty()
                : Optional.of(clientId.strip());
    }

    /** The bearer token life in seconds. */
    public int lifetime() {
        return tokenLifetimeSeconds == null || tokenLifetimeSeconds < 1
                ? DEFAULT_LIFETIME_SECONDS
                : tokenLifetimeSeconds;
    }

    /** All of key, client, hash and signing secret are set. */
    public boolean complete() {
        return key().isPresent()
                && client().isPresent()
                && secretHash().isPresent()
                && signingSecret().isPresent();
    }

    private static Optional<String> value(String direct, String file) {
        if (direct != null && !direct.isBlank()) {
            return Optional.of(direct.strip());
        }
        if (file == null || file.isBlank()) {
            return Optional.empty();
        }
        try {
            String text = Files.readString(Path.of(file.strip()), StandardCharsets.UTF_8).strip();
            return text.isEmpty() ? Optional.empty() : Optional.of(text);
        } catch (IOException | RuntimeException ex) {
            return Optional.empty();
        }
    }

    @Override
    public String toString() {
        return "HrmsSecurityProperties[key="
                + key().isPresent()
                + ", client="
                + client().isPresent()
                + ", secretHash="
                + secretHash().isPresent()
                + ", signingSecret="
                + signingSecret().isPresent()
                + "]";
    }
}
