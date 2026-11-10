package in.gov.fci.payroll.iam.business;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import in.gov.fci.payroll.platform.security.SecureTokens;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import java.time.Duration;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Rate limits and failure counters for the unauthenticated endpoints (ADR-0008). Identities
 * (usernames, e-mail addresses) are only ever used as SHA-256 hashes, and counters exist for
 * unknown login IDs too, so limits never reveal which accounts exist. Account lockout after five
 * failures is LoginService's (app_user_credential). In-memory (one online instance); several online
 * instances need a shared Bucket4j backend (follow-up ADR).
 */
@Component
@org.springframework.context.annotation.Profile("online")
public class LoginThrottle {

    private final Cache<String, Bucket> buckets =
            Caffeine.newBuilder().expireAfterAccess(2, TimeUnit.HOURS).maximumSize(200_000).build();

    /**
     * Takes one token from the bucket {@code scope:key}. Returns empty when allowed, otherwise the
     * number of seconds until the next request may be made.
     */
    public OptionalLong tryAcquire(String scope, String key, int permits, Duration period) {
        Bucket bucket =
                buckets.get(
                        scope + ":" + key,
                        k ->
                                Bucket.builder()
                                        .addLimit(
                                                Bandwidth.builder()
                                                        .capacity(permits)
                                                        .refillGreedy(permits, period)
                                                        .build())
                                        .build());
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        return probe.isConsumed()
                ? OptionalLong.empty()
                : OptionalLong.of(
                        Math.max(
                                1,
                                TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill())));
    }

    /** Stable, non-reversible key for an identity. */
    public static String identityKey(String identity) {
        String normalised = identity == null ? "" : identity.strip().toLowerCase(Locale.ROOT);
        return "id:" + SecureTokens.sha256Hex(normalised);
    }

    public static String ipKey(String ip) {
        return "ip:" + (ip == null ? "unknown" : ip);
    }
}
