package in.gov.fci.payroll.iam.business;

import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.SecureTokens;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Password hashing behind a semaphore bulkhead (ADR-0008): Argon2id is deliberately expensive, so a
 * login flood must not starve the JVM. Callers that cannot get a permit within two seconds receive
 * 503. Passwords are NFKC-normalised before hashing and verification.
 */
@Component
public class PasswordHasher {

    private static final long PERMIT_TIMEOUT_MS = 2000;

    private final PasswordEncoder encoder;
    private final Semaphore permits;
    private final String dummyHash;

    public PasswordHasher(PasswordEncoder encoder) {
        this.encoder = encoder;
        this.permits = new Semaphore(Math.max(2, Runtime.getRuntime().availableProcessors()), true);
        // Verified against unknown usernames so that their timing matches real accounts.
        this.dummyHash = encoded(encoder.encode(SecureTokens.randomToken(24)));
    }

    public String hash(String rawPassword) {
        return guarded(() -> encoded(encoder.encode(PasswordPolicy.normalise(rawPassword))));
    }

    /**
     * Spring Security 7: {@code encode} may return null; a missing hash is never stored or used.
     */
    private static String encoded(String hash) {
        if (hash == null) {
            throw new IllegalStateException("the password encoder returned no hash");
        }
        return hash;
    }

    public boolean matches(String rawPassword, String hash) {
        return guarded(() -> encoder.matches(PasswordPolicy.normalise(rawPassword), hash));
    }

    /** Performs a full verification against a dummy hash; always returns false. */
    public boolean matchesDummy(String rawPassword) {
        guarded(
                () ->
                        encoder.matches(
                                PasswordPolicy.normalise(rawPassword == null ? "" : rawPassword),
                                dummyHash));
        return false;
    }

    /**
     * Whether {@code hash} uses outdated parameters and should be re-hashed after a successful
     * login.
     */
    public boolean needsUpgrade(String hash) {
        return encoder.upgradeEncoding(hash);
    }

    /** A random password nobody knows, for accounts that are activated through a reset link. */
    public String unusableHash() {
        return hash(SecureTokens.randomToken(32) + "aA1!");
    }

    private <T> T guarded(java.util.function.Supplier<T> work) {
        boolean acquired;
        try {
            acquired = permits.tryAcquire(PERMIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new FciException(CommonErrorCode.SERVICE_UNAVAILABLE, ex);
        }
        if (!acquired) {
            throw new FciException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
        try {
            return work.get();
        } finally {
            permits.release();
        }
    }
}
