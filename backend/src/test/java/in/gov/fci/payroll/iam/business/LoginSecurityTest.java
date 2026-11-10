package in.gov.fci.payroll.iam.business;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import java.time.Duration;
import java.util.OptionalLong;
import java.util.concurrent.Semaphore;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Sign-in protections (ADR-0008): the per-key token-bucket throttle with its retry-after, the
 * normalised identity and IP keys, and the password hasher's bounded concurrency, NFKC
 * normalisation, dummy verification and upgrade check.
 */
class LoginSecurityTest {

    /** A transparent encoder: "{plain}" + raw; anything else needs an upgrade. */
    private static final class PlainEncoder implements PasswordEncoder {
        int matches;

        @Override
        public String encode(CharSequence raw) {
            return "{plain}" + raw;
        }

        @Override
        public boolean matches(CharSequence raw, String encoded) {
            matches++;
            return encode(raw).equals(encoded);
        }

        @Override
        public boolean upgradeEncoding(String encoded) {
            return !encoded.startsWith("{plain}");
        }
    }

    // ---------------------------------------------------------------- throttle

    @Test
    void ADR_0008_aKeyGetsItsPermitsThenARetryAfterOfAtLeastOneSecond() {
        LoginThrottle throttle = new LoginThrottle();
        assertThat(throttle.tryAcquire("login", "k", 2, Duration.ofMinutes(1))).isEmpty();
        assertThat(throttle.tryAcquire("login", "k", 2, Duration.ofMinutes(1))).isEmpty();
        OptionalLong wait = throttle.tryAcquire("login", "k", 2, Duration.ofMinutes(1));
        assertThat(wait).isPresent();
        assertThat(wait.getAsLong()).isBetween(1L, 60L);
        // Another key or scope has its own bucket.
        assertThat(throttle.tryAcquire("login", "other", 2, Duration.ofMinutes(1))).isEmpty();
        assertThat(throttle.tryAcquire("reset", "k", 2, Duration.ofMinutes(1))).isEmpty();
    }

    @Test
    void ADR_0008_aRetryAfterBelowOneSecondIsReportedAsOne() {
        LoginThrottle throttle = new LoginThrottle();
        // One permit per 950 ms: any refusal waits less than a second and is reported as 1. On a
        // busy machine the bucket may refill between calls, so take permits until one is refused.
        OptionalLong wait = OptionalLong.empty();
        for (int i = 0; i < 10 && wait.isEmpty(); i++) {
            wait = throttle.tryAcquire("s", "k", 1, Duration.ofMillis(950));
        }
        assertThat(wait).hasValue(1L);
    }

    @Test
    void ADR_0008_identitiesAreKeyedCaseAndSpaceInsensitiveAndHashed() {
        String a = LoginThrottle.identityKey("  Maker.Delhi@FCI-demo.test ");
        assertThat(a)
                .isEqualTo(LoginThrottle.identityKey("maker.delhi@fci-demo.test"))
                .startsWith("id:");
        assertThat(a).doesNotContain("maker");
        assertThat(LoginThrottle.identityKey(null)).isEqualTo(LoginThrottle.identityKey(""));
        assertThat(LoginThrottle.identityKey("a")).isNotEqualTo(LoginThrottle.identityKey("b"));
        assertThat(LoginThrottle.ipKey("10.0.0.1")).isEqualTo("ip:10.0.0.1");
        assertThat(LoginThrottle.ipKey(null)).isEqualTo("ip:unknown");
    }

    // ---------------------------------------------------------------- hasher

    @Test
    void passwordsAreNormalisedBeforeHashingAndMatching() {
        PasswordHasher hasher = new PasswordHasher(new PlainEncoder());
        String hash = hasher.hash("ＡＢＣ１x");
        assertThat(hash).isEqualTo("{plain}ABC1x");
        assertThat(hasher.matches("ABC1x", hash)).isTrue();
        assertThat(hasher.matches("ABC1y", hash)).isFalse();
    }

    @Test
    void anUnknownUserIsVerifiedAgainstADummyHashAndNeverMatches() {
        PlainEncoder encoder = new PlainEncoder();
        PasswordHasher hasher = new PasswordHasher(encoder);
        assertThat(hasher.matchesDummy("anything")).isFalse();
        assertThat(hasher.matchesDummy(null)).isFalse();
        assertThat(encoder.matches).as("the dummy comparison really ran").isEqualTo(2);
    }

    @Test
    void anOldEncodingNeedsAnUpgradeAndAnUnusableHashMatchesNothingTyped() {
        PasswordHasher hasher = new PasswordHasher(new PlainEncoder());
        assertThat(hasher.needsUpgrade("{bcrypt}x")).isTrue();
        assertThat(hasher.needsUpgrade("{plain}x")).isFalse();
        String unusable = hasher.unusableHash();
        assertThat(unusable).startsWith("{plain}").endsWith("aA1!").hasSizeGreaterThan(20);
        assertThat(hasher.unusableHash()).isNotEqualTo(unusable);
    }

    @Test
    void whenEveryPermitIsTakenHashingIsRefusedAsUnavailable() throws Exception {
        PasswordHasher hasher = new PasswordHasher(new PlainEncoder());
        Semaphore permits = (Semaphore) ReflectionTestUtils.getField(hasher, "permits");
        int all = permits.availablePermits();
        permits.acquire(all);
        try {
            assertThatThrownBy(() -> hasher.hash("x"))
                    .isInstanceOfSatisfying(
                            FciException.class,
                            e ->
                                    assertThat(e.getErrorCode())
                                            .isEqualTo(CommonErrorCode.SERVICE_UNAVAILABLE));
        } finally {
            permits.release(all);
        }
        assertThat(permits.availablePermits()).isEqualTo(all);
        assertThat(hasher.matches("x", "{plain}x")).as("permit released after use").isTrue();
        assertThat(permits.availablePermits()).isEqualTo(all);
    }

    @Test
    void anInterruptedWaitIsRefusedAndKeepsTheInterrupt() {
        PasswordHasher hasher = new PasswordHasher(new PlainEncoder());
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> hasher.hash("x"))
                    .isInstanceOfSatisfying(
                            FciException.class,
                            e ->
                                    assertThat(e.getErrorCode())
                                            .isEqualTo(CommonErrorCode.SERVICE_UNAVAILABLE));
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }
}
