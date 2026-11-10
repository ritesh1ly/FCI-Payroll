package in.gov.fci.payroll.iam.business.model;

import in.gov.fci.payroll.platform.security.OtpGatedAction;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * {@code otp_challenge} (target DDL v4.0 S05). The code goes to the acting user's own registered
 * contact (HLD §3.11.3, SRS-G-3) and is challenged for one of the eighteen gated actions. FCILekha
 * extensions: {@code user_id} (the owner the code is checked against) and {@code code_hash} (its
 * HMAC; the code itself is never stored).
 */
@Entity
@Table(name = "otp_challenge")
public class OtpChallenge {

    /** {@code outcome}. */
    public enum Outcome {
        ISSUED,
        SUCCESS,
        EXPIRED,
        LOCKED_OUT,
        /** Superseded by a newer challenge of the same user and action. */
        ABANDONED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "otp_challenge_id")
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "gated_action", nullable = false, length = 40)
    private OtpGatedAction gatedAction;

    @Column(name = "entity_name", length = 100)
    private String entityName;

    @Column(name = "entity_key", length = 100)
    private String entityKey;

    @Column(name = "challenged_user", nullable = false, length = 50)
    private String challengedUser;

    @Column(name = "delivery_channel", nullable = false, length = 10)
    private String deliveryChannel;

    /** {@code delivery_channel}: the registered e-mail. */
    public static final String EMAIL = "EMAIL";

    /** {@code delivery_channel}: the registered mobile (SRS-G-48). */
    public static final String SMS = "SMS";

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "validity_seconds")
    private Integer validitySeconds;

    @Column(name = "attempt_count", nullable = false)
    private Integer attempts;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 15)
    private Outcome outcome;

    @Column(name = "outcome_at")
    private Instant outcomeAt;

    @Column(name = "source_ip", length = 45)
    private String sourceIp;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "code_hash", nullable = false, length = 64)
    private String codeHash;

    protected OtpChallenge() {}

    public static OtpChallenge issue(
            Long userId,
            String loginId,
            OtpGatedAction gatedAction,
            String entityName,
            String entityKey,
            String codeHash,
            String sourceIp,
            Instant now,
            Duration validity) {
        return issue(
                userId,
                loginId,
                gatedAction,
                entityName,
                entityKey,
                codeHash,
                sourceIp,
                now,
                validity,
                EMAIL);
    }

    /** As {@link #issue}, sent through {@code channel} ({@value #EMAIL} or {@value #SMS}). */
    public static OtpChallenge issue(
            Long userId,
            String loginId,
            OtpGatedAction gatedAction,
            String entityName,
            String entityKey,
            String codeHash,
            String sourceIp,
            Instant now,
            Duration validity,
            String channel) {
        if (!EMAIL.equals(channel) && !SMS.equals(channel)) {
            throw new IllegalArgumentException("delivery channel " + channel);
        }
        OtpChallenge challenge = new OtpChallenge();
        challenge.userId = Objects.requireNonNull(userId);
        challenge.challengedUser = truncate(Objects.requireNonNull(loginId), 50);
        challenge.gatedAction = Objects.requireNonNull(gatedAction);
        challenge.entityName = truncate(entityName, 100);
        challenge.entityKey = truncate(entityKey, 100);
        challenge.codeHash = Objects.requireNonNull(codeHash);
        challenge.deliveryChannel = channel;
        challenge.sourceIp = truncate(sourceIp, 45);
        challenge.issuedAt = now;
        challenge.validitySeconds = Math.toIntExact(validity.toSeconds());
        challenge.attempts = 0;
        challenge.outcome = Outcome.ISSUED;
        return challenge;
    }

    /**
     * Verifies one attempt. A wrong code counts; the challenge locks out after {@code maxAttempts};
     * a successful challenge is consumed and can never be used again.
     */
    public boolean verify(String presentedHash, int maxAttempts, Instant now) {
        if (outcome != Outcome.ISSUED) {
            return false;
        }
        if (!now.isBefore(expiresAt())) {
            close(Outcome.EXPIRED, now);
            return false;
        }
        if (!MessageDigest.isEqual(
                codeHash.getBytes(StandardCharsets.US_ASCII),
                presentedHash.getBytes(StandardCharsets.US_ASCII))) {
            attempts = attempts + 1;
            if (attempts >= maxAttempts) {
                close(Outcome.LOCKED_OUT, now);
            }
            return false;
        }
        close(Outcome.SUCCESS, now);
        return true;
    }

    /**
     * OTP verification switched off ({@code fci.api.otp.required = N}, development and local only):
     * the challenge is closed as confirmed at once, without a code ever being sent or entered.
     */
    public void confirmWithoutCode(Instant now) {
        if (outcome == Outcome.ISSUED) {
            close(Outcome.SUCCESS, now);
        }
    }

    /** Housekeeping: an open challenge past its validity. */
    public void expire(Instant now) {
        if (outcome == Outcome.ISSUED) {
            close(Outcome.EXPIRED, now);
        }
    }

    /** A newer challenge of the same user and action replaces this one. */
    public void abandon(Instant now) {
        if (outcome == Outcome.ISSUED) {
            close(Outcome.ABANDONED, now);
        }
    }

    private void close(Outcome result, Instant now) {
        outcome = result;
        outcomeAt = now;
    }

    public Instant expiresAt() {
        return issuedAt.plusSeconds(validitySeconds == null ? 600 : validitySeconds);
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    /** Wrong codes presented so far. */
    public int getAttempts() {
        return attempts == null ? 0 : attempts;
    }

    public OtpGatedAction getGatedAction() {
        return gatedAction;
    }

    public String getEntityName() {
        return entityName;
    }

    public String getEntityKey() {
        return entityKey;
    }

    public Outcome getOutcome() {
        return outcome;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    /** When the challenge was closed (verified, locked out, abandoned); SRS-G-22 cooling-off. */
    public Instant getOutcomeAt() {
        return outcomeAt;
    }

    public String getDeliveryChannel() {
        return deliveryChannel;
    }
}
