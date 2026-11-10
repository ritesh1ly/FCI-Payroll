package in.gov.fci.payroll.platform.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.time.YearMonth;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * FCILekha backend settings ({@code fci.api.*}), shared by the online and the batch instance.
 * Defaults implement ADR-0008/0009 and the FCILekha RBAC design; secrets have no defaults and are
 * checked at start-up by StartupGuards.
 */
@Validated
@ConfigurationProperties(prefix = "fci.api")
public record ApiProperties(
        @Valid @NotNull @DefaultValue Password password,
        @Valid @NotNull @DefaultValue Lockout lockout,
        @Valid @NotNull @DefaultValue Session session,
        @Valid @NotNull @DefaultValue RateLimits rateLimits,
        @Valid @NotNull @DefaultValue Otp otp,
        @Valid @NotNull @DefaultValue Audit audit,
        @Valid @NotNull @DefaultValue Idempotency idempotency,
        @Valid @NotNull @DefaultValue Secrets secrets,
        @Valid @NotNull @DefaultValue Bootstrap bootstrap,
        @Valid @NotNull @DefaultValue Payroll payroll,
        @Valid @NotNull @DefaultValue Demo demo,
        @Valid @NotNull @DefaultValue I18n i18n,
        @DefaultValue("Asia/Kolkata") @NotNull ZoneId businessZone,
        @DefaultValue("true") boolean singleSession) {

    /**
     * Password policy (history depth 5 per the target DDL comment on app_user_password_history).
     */
    public record Password(
            @DefaultValue("5") @Min(1) @Max(24) int historySize,
            @DefaultValue("90d") @NotNull Duration maxAge,
            @DefaultValue("classpath:security/common-passwords.txt") @NotBlank
                    String commonPasswordsLocation) {}

    /** Lockout policy: five failures lock for fifteen minutes. */
    public record Lockout(
            @DefaultValue("5") @Min(3) @Max(10) int maxAttempts,
            @DefaultValue("15m") @NotNull Duration duration) {}

    /**
     * Browser session (ADR-0009): server-side (Spring Session JDBC), cookie {@code FCISESSION}
     * HttpOnly, Secure, SameSite=Lax; idle timeout by the session store, absolute timeout here.
     */
    public record Session(
            @DefaultValue("15m") @NotNull Duration idleTimeout,
            // SRS-SEC-14: an eSeva member's session idles out after 10 minutes (payroll users 15).
            // Fallback of the A-07 setting ESEVA_SESSION_IDLE_TIMEOUT.
            @DefaultValue("10m") @NotNull Duration esevaIdleTimeout,
            @DefaultValue("8h") @NotNull Duration absoluteTimeout,
            // How long an OTP step-up of a sensitive screen lasts before the user is asked again
            // (Module 02 D-2). Fallback of the A-07 setting STEP_UP_VALIDITY; the baseline equals
            // the SRS-SEC-14 idle timeout. >> OPEN << value per FCI's IT Security Policy at
            // go-live.
            @DefaultValue("15m") @NotNull Duration stepUpValidity,
            @DefaultValue("FCISESSION") @NotBlank String cookieName,
            @DefaultValue("true") boolean secureCookie,
            // Also bind the session to the client address (off by default: mobile networks and
            // NAT change addresses within a session; the browser binding always applies).
            @DefaultValue("false") boolean bindClientAddress) {}

    /**
     * Per-IP and per-identity limits of the public sign-in endpoints (ADR-0008). Sized for the
     * salary-day peak (owner 2026-10-25: 1,000 - 5,000 users active at once): an office reaches the
     * service from one NAT address, so the per-IP limits admit about 500 staff of one office
     * signing in within a few minutes; brute force stays bounded by the per-identity limits and the
     * lockout after five failures, which are unchanged. FCI IT tunes them per deployment.
     *
     * <p>{@code loginConcurrent} / {@code loginQueueSeconds}: sign-ins processed at once by one
     * instance and how long a further sign-in waits for its turn before a 503 "busy, retry"
     * (performance test 2026-10-27: 100+ sign-ins at once took every pooled connection, and the
     * desks already at work with them). Keep {@code loginConcurrent} well below the connection
     * pool; the hashing itself is bounded by {@code PasswordHasher}'s bulkhead.
     *
     * <p>{@code loginFailureFloorMillis} (threat T1; security test 2026-10-29): a refused user ID
     * and password step is answered no sooner than this after it arrived, so its timing does not
     * tell an existing login ID from an unknown one. The equal-timing dummy check matches only
     * accounts whose hash is in the application's own scheme; migrated BCrypt hashes (PENDING
     * PERF-R2) and hashes older than a parameter change verify slower until their next sign-in.
     * Keep it above the slowest accepted hash on the production host (bcrypt-12 about 0.4 s on the
     * development machine); 0 switches it off.
     */
    public record RateLimits(
            @DefaultValue("120") @Min(1) int loginPerIpPerMinute,
            @DefaultValue("5") @Min(1) int loginPerUsernamePerMinute,
            @DefaultValue("60") @Min(1) int forgotPerIpPerHour,
            @DefaultValue("3") @Min(1) int forgotPerIdentifierPerHour,
            @DefaultValue("120") @Min(1) int resetPerIpPerHour,
            @DefaultValue("8") @Min(1) int loginConcurrent,
            @DefaultValue("20") @Min(1) int loginQueueSeconds,
            @DefaultValue("600") @Min(0) int loginFailureFloorMillis) {}

    /**
     * One-time passwords for sign-in, sensitive actions and password reset.
     *
     * <p>{@code required} (project owner, 2026-10-23): Y = the critical activities ask for an OTP
     * (the gated approval actions, the cycle freeze / lock, the step-up of sensitive screens and
     * the role switch); N = they are confirmed without a code, for development and local machines
     * only. Y by default; N is refused in prod and in a UAT that is not a local demo stack
     * (StartupGuards). A password reset always asks for its OTP: it proves who the user is.
     *
     * <p>{@code atLogin} (SRS-G-48, SRS-G-21; owner 2026-10-29, OD-36): every sign-in asks, after
     * the user ID and password, for an OTP sent to the user's registered mobile (the registered
     * e-mail when no mobile is on record). True by default; false only where many automated
     * sign-ins share the demo users (the integration-test profile) - refused in prod and a real UAT
     * (StartupGuards). With {@code required = N} the login challenge is recorded as confirmed
     * without a code, as every other OTP action.
     *
     * <p>{@code mobileLogin} (owner 2026-10-29, OD-41; FCI to confirm against SRS-G-48): "Sign in
     * with OTP on registered mobile", the mobile number and its code without a password. Offered
     * only with {@code atLogin} and {@code required = Y} (never a sign-in without any check).
     */
    public record Otp(
            @DefaultValue("10m") @NotNull Duration ttl,
            @DefaultValue("5") @Min(1) @Max(10) int maxAttempts,
            @DefaultValue("5") @Min(1) @Max(500) int maxPerHour,
            // Wrong codes on one challenge after which the owner is alerted by e-mail and SMS; the
            // account is never locked for it (project owner, 2026-10-21).
            @DefaultValue("3") @Min(1) @Max(10) int alertAfterFailures,
            @DefaultValue("Y") @NotNull Verification required,
            @DefaultValue("true") boolean atLogin,
            @DefaultValue("true") boolean mobileLogin) {

        /** Whether the critical activities ask for an OTP. */
        public boolean verificationRequired() {
            return required == Verification.Y;
        }
    }

    /** {@code fci.api.otp.required}: Y (an OTP is asked) or N (development and local only). */
    public enum Verification {
        Y,
        N
    }

    /**
     * Audit query limits. Retention of audit_event is >> OPEN << OQ-10 (never purged here). The
     * export limit is the one of every list export, fci.export.max-rows (ExportProperties).
     */
    public record Audit(@DefaultValue("31") @Min(1) @Max(366) int maxQueryWindowDays) {}

    /**
     * The payroll period shown in the top bar. >> OPEN << the payroll calendar (S08) is outside
     * this build; the period is configuration until it is.
     */
    public record Payroll(
            @DefaultValue("2026-07") @NotNull YearMonth currentPeriod,
            @DefaultValue("OPEN") @NotBlank String periodStatus) {}

    /** Demo mode (profile {@code demo}): the login page shows the demo note; refused with prod. */
    public record Demo(@DefaultValue("false") boolean enabled) {}

    /** UI label bundles: preference > FCI_LOCALE cookie > Accept-Language > default. */
    public record I18n(
            @DefaultValue("en") @NotBlank String defaultLocale,
            @DefaultValue("FCI_LOCALE") @NotBlank String cookieName) {}

    /** Idempotency-Key retention. */
    public record Idempotency(@DefaultValue("24h") @NotNull Duration ttl) {}

    /**
     * Key material (Base64, 32 bytes each). {@code ephemeralKeys} (generated at start-up) is only
     * permitted in local, dev and test profiles. The audit HMAC key also keys OTP hashes.
     */
    public record Secrets(
            String auditHmacKey,
            String dataEncryptionKey,
            @DefaultValue("false") boolean ephemeralKeys) {}

    /**
     * First-deploy System Administrator (V009): created only when no active System Administrator
     * assignment exists. The password must be changed at first sign-in.
     */
    public record Bootstrap(
            @DefaultValue("sysadmin@fci.gov.in") @NotBlank String sysadminLoginId,
            String adminInitialPassword) {}
}
