package in.gov.fci.payroll.iam.business.model;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.hibernate.type.YesNoConverter;

/**
 * {@code app_user_credential} (target DDL S27). Only an adaptive one-way hash is stored; the entity
 * never leaves the service layer (no DTO carries it).
 */
@Entity
@Table(name = "app_user_credential")
public class AppUserCredential {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "hash_algorithm", nullable = false, length = 20)
    private String hashAlgorithm;

    @Column(name = "password_changed_at", nullable = false)
    private Instant passwordChangedAt;

    @Convert(converter = YesNoConverter.class)
    @Column(name = "must_change_flag", nullable = false, length = 1)
    private Boolean mustChange;

    @Column(name = "failed_attempt_count", nullable = false)
    private Integer failedAttempts;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "reset_requested_at")
    private Instant resetRequestedAt;

    @Column(name = "unlocked_by", length = 50)
    private String unlockedBy;

    @Column(name = "unlocked_at")
    private Instant unlockedAt;

    @Column(name = "unlock_reason", length = 500)
    private String unlockReason;

    @Version
    @Column(name = "row_version", nullable = false)
    private Long rowVersion;

    protected AppUserCredential() {}

    public static AppUserCredential initial(Long userId, String hash, Instant now) {
        AppUserCredential credential = new AppUserCredential();
        credential.userId = Objects.requireNonNull(userId);
        credential.passwordHash = Objects.requireNonNull(hash);
        credential.hashAlgorithm = algorithmOf(hash);
        credential.passwordChangedAt = now;
        credential.mustChange = true;
        credential.failedAttempts = 0;
        return credential;
    }

    /** {@code {argon2}…} -> ARGON2ID, {@code {bcrypt}…} -> BCRYPT. */
    static String algorithmOf(String hash) {
        if (hash.startsWith("{argon2}")) {
            return "ARGON2ID";
        }
        if (hash.startsWith("{bcrypt}")) {
            return "BCRYPT";
        }
        return "UNKNOWN";
    }

    public boolean isLockedAt(Instant now) {
        return lockedUntil != null && now.isBefore(lockedUntil);
    }

    /**
     * Counts a failed sign-in. Returns true when this failure locked the credential (the caller
     * writes the security audit event).
     */
    public boolean registerFailure(int maxAttempts, Duration lockDuration, Instant now) {
        failedAttempts = failedAttempts + 1;
        if (failedAttempts >= maxAttempts) {
            lockedUntil = now.plus(lockDuration);
            failedAttempts = 0;
            return true;
        }
        return false;
    }

    public void registerSuccess() {
        failedAttempts = 0;
        lockedUntil = null;
    }

    /** Clears the lockout (self-service: a new password, reset by OTP). */
    public void unlock() {
        failedAttempts = 0;
        lockedUntil = null;
    }

    /**
     * An administrator unlocks the account with a recorded reason (FRS V0.5 §6.10.6; target DDL
     * v4.0 chk_app_user_cred_unlock).
     */
    public void unlockByAdministrator(String administrator, String reason, Instant now) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("An unlock needs a reason");
        }
        unlock();
        unlockedBy = administrator.substring(0, Math.min(50, administrator.length()));
        unlockedAt = now;
        unlockReason = reason.substring(0, Math.min(500, reason.length()));
    }

    /** A new password chosen by the user (change or OTP reset). */
    public void replace(String newHash, Instant now) {
        passwordHash = Objects.requireNonNull(newHash);
        hashAlgorithm = algorithmOf(newHash);
        passwordChangedAt = now;
        mustChange = false;
        resetRequestedAt = null;
        unlock();
    }

    /** A temporary password set by an administrator: must be changed at the next sign-in. */
    public void resetByAdministrator(String newHash, Instant now) {
        replace(newHash, now);
        mustChange = true;
    }

    /** Re-hash on login when the stored hash uses outdated parameters (same password). */
    public void upgradeHash(String newHash) {
        passwordHash = newHash;
        hashAlgorithm = algorithmOf(newHash);
    }

    public void markResetRequested(Instant now) {
        resetRequestedAt = now;
    }

    public boolean isExpired(Instant now, Duration maxAge) {
        return passwordChangedAt.plus(maxAge).isBefore(now);
    }

    public Long getUserId() {
        return userId;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getHashAlgorithm() {
        return hashAlgorithm;
    }

    public Instant getPasswordChangedAt() {
        return passwordChangedAt;
    }

    public boolean isMustChange() {
        return Boolean.TRUE.equals(mustChange);
    }

    public int getFailedAttempts() {
        return failedAttempts;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }

    public Instant getResetRequestedAt() {
        return resetRequestedAt;
    }

    @Override
    public String toString() {
        return "AppUserCredential{userId=" + userId + "}";
    }
}
