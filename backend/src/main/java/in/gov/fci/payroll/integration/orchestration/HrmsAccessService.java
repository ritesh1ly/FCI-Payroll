package in.gov.fci.payroll.integration.orchestration;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import in.gov.fci.payroll.integration.business.HrmsTokens;
import in.gov.fci.payroll.integration.business.IntegrationErrorCode;
import in.gov.fci.payroll.integration.config.HrmsSecurityProperties;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.SecureTokens;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * System-to-system access of the HRMS APIs (SRS-INT-4; plan 23 section 7): the {@code api-key}
 * header is compared in constant time with the key held in settings; the {@code Authorization:
 * Bearer} token is issued by the token API in exchange for the client user name and password (the
 * password checked against its bcrypt hash, the user name in constant time, both always evaluated)
 * and is a short-lived HMAC-signed value (stateless, verifiable on any instance). Anything unset
 * refuses everything with a 503; every refusal is recorded for X-03 without saying which part was
 * wrong. Refused attempts are counted per calling address: past the limit in the window (settings,
 * default 10 in 300 seconds) the address is answered 429 until the window ends, so a key or
 * password cannot be guessed by brute force; a success clears the count. The counter is in memory
 * (one online instance, as the sign-in limits; several instances need a shared store, PENDING
 * API-23-16). No secret is logged or returned.
 */
@Service
public class HrmsAccessService {

    /** The issued token. */
    public record Token(String accessToken, int expiresIn) {}

    private static final String BEARER = "Bearer ";
    private static final String UNKNOWN_ADDRESS = "unknown";

    private final HrmsSecurityProperties settings;
    private final HrmsRefusals refusals;
    private final Clock clock;
    private final PasswordEncoder passwords =
            PasswordEncoderFactories.createDelegatingPasswordEncoder();
    private final Cache<String, AtomicInteger> failures;

    public HrmsAccessService(HrmsSecurityProperties settings, HrmsRefusals refusals, Clock clock) {
        this.settings = settings;
        this.refusals = refusals;
        this.clock = clock;
        this.failures =
                Caffeine.newBuilder()
                        .expireAfterWrite(Duration.ofSeconds(settings.failureWindow()))
                        .maximumSize(100_000)
                        .build();
    }

    /** Refuses with 503 while the key, client or signing secret is not set. */
    private void requireConfigured() {
        if (!settings.complete()) {
            refusals.record(
                    HrmsRefusals.AUTH_INTERFACE,
                    "HRMS access refused: the key, client or signing secret is not configured");
            throw new FciException(
                    IntegrationErrorCode.HRMS_AUTH_NOT_CONFIGURED, new Object[0], List.of(), null);
        }
    }

    private void requireNotThrottled(String remote) {
        AtomicInteger count = failures.getIfPresent(remote);
        if (count != null && count.get() >= settings.failureLimit()) {
            refusals.record(
                    HrmsRefusals.AUTH_INTERFACE,
                    "HRMS call refused: too many refused attempts from one address");
            throw new FciException(
                    IntegrationErrorCode.HRMS_THROTTLED, new Object[0], List.of(), null);
        }
    }

    /** The api-key header alone (the token API). */
    public void requireKey(String apiKeyHeader) {
        requireKey(apiKeyHeader, UNKNOWN_ADDRESS);
    }

    /** The api-key header alone, counting refusals against the calling address. */
    public void requireKey(String apiKeyHeader, String remote) {
        requireConfigured();
        requireNotThrottled(remote);
        if (!SecureTokens.constantTimeEquals(settings.key().orElse(""), apiKeyHeader)) {
            refuse("HRMS call refused: api-key missing or wrong", remote);
        }
    }

    /** The api-key header and a valid bearer token (every other HRMS API). */
    public void requireKeyAndToken(String apiKeyHeader, String authorization) {
        requireKeyAndToken(apiKeyHeader, authorization, UNKNOWN_ADDRESS);
    }

    /** The api-key header and a valid bearer token, counting refusals against the address. */
    public void requireKeyAndToken(String apiKeyHeader, String authorization, String remote) {
        requireKey(apiKeyHeader, remote);
        String token =
                authorization != null
                                && authorization.regionMatches(true, 0, BEARER, 0, BEARER.length())
                        ? authorization.substring(BEARER.length()).strip()
                        : null;
        Optional<HrmsTokens.Claims> claims =
                HrmsTokens.verify(settings.signingSecret().orElse(""), token, clock.instant());
        if (claims.isEmpty()
                || !SecureTokens.constantTimeEquals(
                        settings.client().orElse(""), claims.get().subject())) {
            refuse("HRMS call refused: bearer token missing, wrong or expired", remote);
        }
        failures.invalidate(remote);
    }

    /** The token API: user name and password in exchange for a bearer token. */
    public Token exchange(String username, String password) {
        return exchange(username, password, UNKNOWN_ADDRESS);
    }

    /** The token API, counting refusals against the calling address. */
    public Token exchange(String username, String password, String remote) {
        requireConfigured();
        requireNotThrottled(remote);
        boolean user =
                SecureTokens.constantTimeEquals(
                        settings.client().orElse(""), username == null ? "" : username);
        boolean secret = matches(password);
        if (!user || !secret) {
            refuse("HRMS token exchange refused: wrong client credentials", remote);
        }
        failures.invalidate(remote);
        int life = settings.lifetime();
        String id = SecureTokens.randomToken(16);
        String token =
                HrmsTokens.issue(
                        settings.signingSecret().orElseThrow(),
                        settings.client().orElseThrow(),
                        clock.instant(),
                        life,
                        id);
        return new Token(token, life);
    }

    private boolean matches(String password) {
        String hash = settings.secretHash().orElse("");
        try {
            return password != null && !password.isEmpty() && passwords.matches(password, hash);
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private void refuse(String reason, String remote) {
        failures.get(remote, k -> new AtomicInteger()).incrementAndGet();
        refusals.record(HrmsRefusals.AUTH_INTERFACE, reason);
        throw new FciException(
                IntegrationErrorCode.HRMS_UNAUTHORIZED, new Object[0], List.of(), null);
    }
}
