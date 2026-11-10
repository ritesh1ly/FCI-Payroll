package in.gov.fci.payroll.iam.business.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.gov.fci.payroll.platform.security.OtpGatedAction;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Credentials (lockout, administrator unlock and reset, expiry, hash algorithm), OTP challenges
 * (validity, attempts, single use, never reused after closing) and per-user screen access dates
 * (SRS A-04 / A-06; R-OTP).
 */
class IamModelTest {

    private static final Instant NOW = Instant.parse("2026-10-21T05:00:00Z");

    // ---------------------------------------------------------------- credential

    @Test
    void aNewCredentialMustBeChangedAndRecordsItsAlgorithm() {
        AppUserCredential c = AppUserCredential.initial(1L, "{argon2}abc", NOW);
        assertThat(c.isMustChange()).isTrue();
        assertThat(c.getFailedAttempts()).isZero();
        assertThat(c.getHashAlgorithm()).isEqualTo("ARGON2ID");
        assertThat(c.getPasswordChangedAt()).isEqualTo(NOW);
        assertThat(AppUserCredential.algorithmOf("{bcrypt}x")).isEqualTo("BCRYPT");
        assertThat(AppUserCredential.algorithmOf("{noop}x")).isEqualTo("UNKNOWN");
        assertThat(AppUserCredential.algorithmOf("argon2}x")).isEqualTo("UNKNOWN");
        assertThat(c.toString()).isEqualTo("AppUserCredential{userId=1}").doesNotContain("argon2");
        assertThatThrownBy(() -> AppUserCredential.initial(null, "h", NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> AppUserCredential.initial(1L, null, NOW))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void theLastAllowedFailureLocksForTheDurationAndResetsTheCount() {
        AppUserCredential c = AppUserCredential.initial(1L, "{argon2}h", NOW);
        assertThat(c.registerFailure(3, Duration.ofMinutes(15), NOW)).isFalse();
        assertThat(c.registerFailure(3, Duration.ofMinutes(15), NOW)).isFalse();
        assertThat(c.getFailedAttempts()).isEqualTo(2);
        assertThat(c.isLockedAt(NOW)).isFalse();
        assertThat(c.registerFailure(3, Duration.ofMinutes(15), NOW)).isTrue();
        assertThat(c.getFailedAttempts()).isZero();
        assertThat(c.getLockedUntil()).isEqualTo(NOW.plusSeconds(900));
        assertThat(c.isLockedAt(NOW.plusSeconds(899))).isTrue();
        assertThat(c.isLockedAt(NOW.plusSeconds(900))).as("unlocked at the end instant").isFalse();
        c.registerSuccess();
        assertThat(c.getLockedUntil()).isNull();
        assertThat(c.isLockedAt(NOW)).isFalse();
    }

    // SC-01-0142: a locked account is unlocked by OTP self-service.
    // SC-02-0044: the user unlocks the locked account by OTP self-service.
    @Test
    void SRS_G_49_aLockedUserUnlocksTheOwnAccountByAnOtpReset() {
        AppUserCredential c = AppUserCredential.initial(1L, "{argon2}h", NOW);
        for (int i = 0; i < 3; i++) {
            c.registerFailure(3, Duration.ofMinutes(15), NOW);
        }
        assertThat(c.isLockedAt(NOW)).isTrue();
        // The self-service reset (OTP) sets a new password: the lockout ends with it.
        c.replace("{argon2}new", NOW.plusSeconds(60));
        assertThat(c.isLockedAt(NOW.plusSeconds(61))).isFalse();
        assertThat(c.getFailedAttempts()).isZero();
        assertThat(c.isMustChange()).isFalse();
    }

    @Test
    void anAdministratorUnlockNeedsAReasonAndIsRecordedCut() {
        AppUserCredential c = AppUserCredential.initial(1L, "{argon2}h", NOW);
        c.registerFailure(1, Duration.ofHours(1), NOW);
        assertThatThrownBy(() -> c.unlockByAdministrator("admin", " ", NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> c.unlockByAdministrator("admin", null, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(c.isLockedAt(NOW)).as("still locked after a refused unlock").isTrue();
        c.unlockByAdministrator("a".repeat(60), "r".repeat(600), NOW.plusSeconds(5));
        assertThat(c.isLockedAt(NOW)).isFalse();
        assertThat(c.getFailedAttempts()).isZero();
        assertThat((String) ReflectionTestUtils.getField(c, "unlockedBy")).hasSize(50);
        assertThat((String) ReflectionTestUtils.getField(c, "unlockReason")).hasSize(500);
        assertThat(ReflectionTestUtils.getField(c, "unlockedAt")).isEqualTo(NOW.plusSeconds(5));
        c.unlockByAdministrator("admin", "short", NOW);
        assertThat(ReflectionTestUtils.getField(c, "unlockedBy")).isEqualTo("admin");
        assertThat(ReflectionTestUtils.getField(c, "unlockReason")).isEqualTo("short");
    }

    @Test
    void anOwnChangeClearsTheResetAndLockAndAnAdministratorResetMustBeChanged() {
        AppUserCredential c = AppUserCredential.initial(1L, "{argon2}h", NOW);
        c.markResetRequested(NOW);
        assertThat(c.getResetRequestedAt()).isEqualTo(NOW);
        c.registerFailure(1, Duration.ofHours(1), NOW);
        c.replace("{bcrypt}new", NOW.plusSeconds(60));
        assertThat(c.getPasswordHash()).isEqualTo("{bcrypt}new");
        assertThat(c.getHashAlgorithm()).isEqualTo("BCRYPT");
        assertThat(c.isMustChange()).isFalse();
        assertThat(c.getResetRequestedAt()).isNull();
        assertThat(c.getLockedUntil()).isNull();
        assertThat(c.getPasswordChangedAt()).isEqualTo(NOW.plusSeconds(60));
        assertThatThrownBy(() -> c.replace(null, NOW)).isInstanceOf(NullPointerException.class);
        c.resetByAdministrator("{argon2}tmp", NOW);
        assertThat(c.isMustChange()).isTrue();
        assertThat(c.getPasswordHash()).isEqualTo("{argon2}tmp");
        c.upgradeHash("{argon2}stronger");
        assertThat(c.getPasswordHash()).isEqualTo("{argon2}stronger");
        assertThat(c.getHashAlgorithm()).isEqualTo("ARGON2ID");
    }

    @Test
    void aPasswordExpiresStrictlyAfterItsMaximumAge() {
        AppUserCredential c = AppUserCredential.initial(1L, "{argon2}h", NOW);
        Duration age = Duration.ofDays(90);
        assertThat(c.isExpired(NOW.plus(age), age)).isFalse();
        assertThat(c.isExpired(NOW.plus(age).plusSeconds(1), age)).isTrue();
    }

    // ---------------------------------------------------------------- OTP challenge

    private static OtpChallenge challenge() {
        return OtpChallenge.issue(
                5L,
                "maker.delhi@fci-demo.test",
                OtpGatedAction.CYCLE_FREEZE_LOCK,
                "payroll_cycle_instance",
                "42",
                "HASH",
                "10.0.0.1",
                NOW,
                Duration.ofMinutes(5));
    }

    @Test
    void R_OTP_aChallengeIsIssuedByEmailAndCutToItsColumns() {
        OtpChallenge c = challenge();
        assertThat(c.getOutcome()).isEqualTo(OtpChallenge.Outcome.ISSUED);
        assertThat(c.getUserId()).isEqualTo(5L);
        assertThat(c.getGatedAction()).isEqualTo(OtpGatedAction.CYCLE_FREEZE_LOCK);
        assertThat(c.getEntityName()).isEqualTo("payroll_cycle_instance");
        assertThat(c.getEntityKey()).isEqualTo("42");
        assertThat(c.getIssuedAt()).isEqualTo(NOW);
        assertThat(c.expiresAt()).isEqualTo(NOW.plusSeconds(300));
        assertThat(ReflectionTestUtils.getField(c, "deliveryChannel")).isEqualTo("EMAIL");
        OtpChallenge long_ =
                OtpChallenge.issue(
                        5L,
                        "u".repeat(60),
                        OtpGatedAction.AUTH_LOGIN,
                        "e".repeat(120),
                        "k".repeat(120),
                        "H",
                        "i".repeat(50),
                        NOW,
                        Duration.ofSeconds(30));
        assertThat((String) ReflectionTestUtils.getField(long_, "challengedUser")).hasSize(50);
        assertThat(long_.getEntityName()).hasSize(100);
        assertThat(long_.getEntityKey()).hasSize(100);
        assertThat((String) ReflectionTestUtils.getField(long_, "sourceIp")).hasSize(45);
        OtpChallenge nulls =
                OtpChallenge.issue(
                        5L,
                        "u",
                        OtpGatedAction.AUTH_LOGIN,
                        null,
                        null,
                        "H",
                        null,
                        NOW,
                        Duration.ofSeconds(30));
        assertThat(nulls.getEntityName()).isNull();
        assertThatThrownBy(
                        () ->
                                OtpChallenge.issue(
                                        null,
                                        "u",
                                        OtpGatedAction.AUTH_LOGIN,
                                        null,
                                        null,
                                        "H",
                                        null,
                                        NOW,
                                        Duration.ZERO))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void R_OTP_theRightCodeBeforeExpirySucceedsOnce() {
        OtpChallenge c = challenge();
        assertThat(c.verify("HASH", 3, NOW.plusSeconds(299))).isTrue();
        assertThat(c.getOutcome()).isEqualTo(OtpChallenge.Outcome.SUCCESS);
        assertThat(ReflectionTestUtils.getField(c, "outcomeAt")).isEqualTo(NOW.plusSeconds(299));
        assertThat(c.verify("HASH", 3, NOW.plusSeconds(299))).as("never reused").isFalse();
    }

    @Test
    void R_OTP_aCodeAtOrAfterExpiryExpiresTheChallenge() {
        OtpChallenge c = challenge();
        assertThat(c.verify("HASH", 3, NOW.plusSeconds(300))).isFalse();
        assertThat(c.getOutcome()).isEqualTo(OtpChallenge.Outcome.EXPIRED);
    }

    @Test
    void R_OTP_wrongCodesLockOutAtTheMaximumAttempts() {
        OtpChallenge c = challenge();
        assertThat(c.verify("NOPE", 3, NOW)).isFalse();
        assertThat(c.verify("NOPE", 3, NOW)).isFalse();
        assertThat(c.getOutcome()).isEqualTo(OtpChallenge.Outcome.ISSUED);
        assertThat(c.verify("NOPE", 3, NOW)).isFalse();
        assertThat(c.getOutcome()).isEqualTo(OtpChallenge.Outcome.LOCKED_OUT);
        assertThat(c.verify("HASH", 3, NOW)).as("closed: even the right code fails").isFalse();
    }

    @Test
    void R_OTP_onlyAnIssuedChallengeCanBeExpiredOrAbandoned() {
        OtpChallenge expired = challenge();
        expired.expire(NOW);
        assertThat(expired.getOutcome()).isEqualTo(OtpChallenge.Outcome.EXPIRED);
        expired.abandon(NOW);
        assertThat(expired.getOutcome()).isEqualTo(OtpChallenge.Outcome.EXPIRED);
        OtpChallenge abandoned = challenge();
        abandoned.abandon(NOW);
        assertThat(abandoned.getOutcome()).isEqualTo(OtpChallenge.Outcome.ABANDONED);
        abandoned.expire(NOW);
        assertThat(abandoned.getOutcome()).isEqualTo(OtpChallenge.Outcome.ABANDONED);
    }

    @Test
    void R_OTP_aChallengeWithoutAStoredValidityLastsTenMinutes() {
        OtpChallenge c = challenge();
        ReflectionTestUtils.setField(c, "validitySeconds", null);
        assertThat(c.expiresAt()).isEqualTo(NOW.plusSeconds(600));
    }
}
