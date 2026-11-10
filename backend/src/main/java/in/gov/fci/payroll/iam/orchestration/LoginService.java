package in.gov.fci.payroll.iam.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.iam.business.PasswordHasher;
import in.gov.fci.payroll.iam.business.model.AppSession;
import in.gov.fci.payroll.iam.business.model.AppUser;
import in.gov.fci.payroll.iam.business.model.AppUserCredential;
import in.gov.fci.payroll.iam.business.model.LoginPersona;
import in.gov.fci.payroll.iam.business.model.OtpChallenge;
import in.gov.fci.payroll.iam.persistence.AccessRepositories;
import in.gov.fci.payroll.iam.persistence.IdentityRepositories;
import in.gov.fci.payroll.platform.config.ApiProperties;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.ErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.logging.Sensitive;
import in.gov.fci.payroll.platform.logging.SensitiveDataMasker;
import in.gov.fci.payroll.platform.notification.NotificationSender;
import in.gov.fci.payroll.platform.security.ActiveGroup;
import in.gov.fci.payroll.platform.security.OtpGatedAction;
import in.gov.fci.payroll.platform.security.SecureTokens;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sign-in with official e-mail, password, persona and login location (FCILekha login screen). No
 * MFA at login (HLD AD-05). The checks, in order:
 *
 * <ol>
 *   <li>credentials: unknown login and wrong password are the same 401 (timing equalised); five
 *       failures lock the credential for fifteen minutes;
 *   <li>account status: LOCKED, SUSPENDED and DEACTIVATED are refused (only after a correct
 *       password, so status is never an enumeration oracle);
 *   <li>persona: the persona must admit one of the user's active roles (EMPLOYEE: the directly
 *       granted eSeva role and a linked employee record);
 *   <li>location: covered by an active access group of an admitted role and of a location type that
 *       role may sign in at.
 * </ol>
 *
 * Every outcome writes an {@code audit_event}; failures commit even though the call fails.
 */
@Service
@Loggable
public class LoginService {

    /**
     * A sign-in request: user ID (or registered e-mail) and password. {@code roleCode} and {@code
     * locationId} optionally ask for a starting scope (API clients, tests); the browser sends
     * neither and switches scope in the top bar.
     */
    public record LoginRequest(
            String loginId,
            @Sensitive String password,
            String roleCode,
            Long locationId,
            String clientIp,
            String userAgent,
            IdType idType) {

        /** A sign-in by user ID or registered e-mail (the API clients' and tests' form). */
        public LoginRequest(
                String loginId,
                String password,
                String roleCode,
                Long locationId,
                String clientIp,
                String userAgent) {
            this(loginId, password, roleCode, locationId, clientIp, userAgent, IdType.USER_ID);
        }

        @Override
        public String toString() {
            return "LoginRequest{loginId="
                    + loginId
                    + ", role="
                    + roleCode
                    + ", location="
                    + locationId
                    + "}";
        }
    }

    /**
     * What the identifier is (OD-37): the user ID or the registered e-mail (the e-mail tab), or the
     * employee number (EIS) of the employee the account belongs to (the Employee ID tab).
     */
    public enum IdType {
        USER_ID,
        EMPLOYEE_ID,
        /** The registered mobile number ("Sign in with OTP on registered mobile", OD-41). */
        MOBILE
    }

    /** The established session and where the browser goes next. */
    public record LoginResult(
            SessionService.StartedSession session,
            String personaCode,
            Long locationId,
            String landingScreen) {}

    /**
     * A sign-in whose user ID and password were right, waiting for its OTP (SRS-G-48). Held only in
     * the browser's own pre-login session, never sent to the client: the OTP step can neither be
     * skipped nor completed from another browser.
     */
    public record PendingLogin(
            long userId,
            String loginId,
            long challengeId,
            String roleCode,
            Long locationId,
            Instant issuedAt,
            boolean passwordless)
            implements java.io.Serializable {

        /** A pending sign-in after the password (the user ID and password step). */
        public PendingLogin(
                long userId,
                String loginId,
                long challengeId,
                String roleCode,
                Long locationId,
                Instant issuedAt) {
            this(userId, loginId, challengeId, roleCode, locationId, issuedAt, false);
        }
    }

    /** The outcome of the user ID and password step. */
    public sealed interface LoginOutcome {}

    /** Signed in: no OTP is asked at sign-in (setting at-login false, or OTP switched off). */
    public record Signed(LoginResult result) implements LoginOutcome {}

    /**
     * The OTP was sent: to {@code channel} (SMS or EMAIL) at the masked {@code destination}, valid
     * {@code validSeconds}; {@code pending} goes into the pre-login session.
     */
    public record OtpRequired(
            PendingLogin pending, String channel, String destination, int validSeconds)
            implements LoginOutcome {}

    private sealed interface Attempt {}

    private record Success(LoginResult result) implements Attempt {}

    private record Pending(OtpRequired otp) implements Attempt {}

    private record Failure(ErrorCode code) implements Attempt {}

    private final AccessRepositories.Users users;
    private final IdentityRepositories.Credentials credentials;
    private final IdentityRepositories.Personas personas;
    private final IdentityRepositories.Sessions sessionHistory;
    private final ScopeResolver scopes;
    private final AccessContextService contexts;
    private final PasswordHasher hasher;
    private final SessionService sessions;
    private final AuditService audit;
    private final NotificationSender notifications;
    private final OtpService otps;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final ApiProperties properties;

    /** Sign-ins processed at once (fair queue); the rest wait their turn (see RateLimits). */
    private final java.util.concurrent.Semaphore admission;

    private final SystemParameters parameters;

    public LoginService(
            AccessRepositories.Users users,
            IdentityRepositories.Credentials credentials,
            IdentityRepositories.Personas personas,
            IdentityRepositories.Sessions sessionHistory,
            ScopeResolver scopes,
            AccessContextService contexts,
            PasswordHasher hasher,
            SessionService sessions,
            AuditService audit,
            NotificationSender notifications,
            OtpService otps,
            TransactionTemplate tx,
            Clock clock,
            ApiProperties properties,
            SystemParameters parameters) {
        this.users = users;
        this.credentials = credentials;
        this.personas = personas;
        this.sessionHistory = sessionHistory;
        this.scopes = scopes;
        this.contexts = contexts;
        this.hasher = hasher;
        this.sessions = sessions;
        this.audit = audit;
        this.notifications = notifications;
        this.otps = otps;
        this.tx = tx;
        this.clock = clock;
        this.properties = properties;
        this.admission =
                new java.util.concurrent.Semaphore(properties.rateLimits().loginConcurrent(), true);
        this.parameters = parameters;
    }

    /**
     * The user ID and password step (X-08 step 1): signed in, or the OTP sent to the registered
     * mobile (SRS-G-48) and the sign-in pending.
     */
    public LoginOutcome login(LoginRequest request) {
        long started = System.nanoTime();
        try {
            return admitted(() -> admittedLogin(request));
        } catch (FciException ex) {
            if (ex.getErrorCode() != CommonErrorCode.SERVICE_UNAVAILABLE) {
                holdRefusal(started);
            }
            throw ex;
        }
    }

    /**
     * T1: a refused password step answers no sooner than the floor after it arrived, whatever the
     * account's hash cost (an unknown login, a migrated BCrypt hash, an Argon2id one), so its
     * timing names no account. Waited after the admission permit is released: request threads are
     * virtual, so the wait holds neither a sign-in slot nor a platform thread. A busy refusal (503)
     * is answered at once.
     */
    private void holdRefusal(long startedNanos) {
        long remaining =
                java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(
                                properties.rateLimits().loginFailureFloorMillis())
                        - (System.nanoTime() - startedNanos);
        if (remaining <= 0) {
            return;
        }
        try {
            java.util.concurrent.TimeUnit.NANOSECONDS.sleep(remaining);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * X-08 step 2 (SRS-G-48): the OTP of a pending sign-in. A wrong, expired or used code is
     * refused (INVALID_OTP) and counted by the OTP service; the account must still be active,
     * unlocked and in scope; then the session starts, its OTP step-up already confirmed.
     */
    public LoginResult completeWithOtp(
            PendingLogin pending, @Sensitive String code, String clientIp, String userAgent) {
        return admitted(() -> admittedOtp(pending, code, clientIp, userAgent));
    }

    /** A new code for a pending sign-in (Resend OTP); the earlier code stops working. */
    public OtpRequired resendOtp(PendingLogin pending) {
        if (pending.passwordless() && pending.userId() == UNKNOWN) {
            return unknownMobile(pending.loginId(), clock.instant());
        }
        return Objects.requireNonNull(
                tx.execute(
                        status -> {
                            AppUser user =
                                    users.findById(pending.userId())
                                            .orElseThrow(
                                                    () ->
                                                            new FciException(
                                                                    ApiErrorCode.INVALID_OTP));
                            return sendLoginOtp(
                                    user,
                                    pending.loginId(),
                                    pending.roleCode(),
                                    pending.locationId(),
                                    clock.instant(),
                                    pending.passwordless());
                        }));
    }

    private <T> T admitted(java.util.function.Supplier<T> work) {
        boolean admitted;
        try {
            admitted =
                    admission.tryAcquire(
                            properties.rateLimits().loginQueueSeconds(),
                            java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new FciException(CommonErrorCode.SERVICE_UNAVAILABLE, ex);
        }
        if (!admitted) {
            throw new FciException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
        try {
            return work.get();
        } finally {
            admission.release();
        }
    }

    private LoginOutcome admittedLogin(LoginRequest request) {
        String loginId = AppUser.normaliseLogin(request.loginId());
        // The deliberately slow password hash runs outside any transaction, so a sign-in never
        // holds
        // a database connection while it hashes or waits for the hashing bulkhead (performance test
        // 2026-10-27: 100 sign-ins at once exhausted the pool and 35 failed with a 500).
        Verified verified = verifyOutsideTransaction(loginId, request.idType(), request.password());
        // TransactionTemplate#execute is @Nullable by contract; attempt(...) never returns null.
        Attempt attempt =
                java.util.Objects.requireNonNull(
                        tx.execute(status -> attempt(loginId, request, verified)));
        return switch (attempt) {
            case Success success -> new Signed(success.result());
            case Pending pending -> pending.otp();
            case Failure failure -> throw new FciException(failure.code());
        };
    }

    private LoginResult admittedOtp(
            PendingLogin pending, String code, String clientIp, String userAgent) {
        Attempt attempt =
                Objects.requireNonNull(
                        tx.execute(status -> otpAttempt(pending, code, clientIp, userAgent)));
        return switch (attempt) {
            case Success success -> success.result();
            case Pending ignored -> throw new IllegalStateException("an OTP step asks no OTP");
            case Failure failure -> throw new FciException(failure.code());
        };
    }

    private Attempt otpAttempt(
            PendingLogin pending, String code, String clientIp, String userAgent) {
        Instant now = clock.instant();
        String loginId = pending.loginId();
        Optional<AppUser> found = users.findById(pending.userId());
        Optional<AppUserCredential> credential =
                found.flatMap(u -> credentials.findById(u.getId()));
        if (found.isEmpty() || credential.isEmpty()) {
            return fail(loginId, null, ApiErrorCode.INVALID_OTP, "pending sign-in of no account");
        }
        AppUser user = found.get();
        AppUserCredential stored = credential.get();
        // Without a password (OD-41) the code is the only proof: checked first, so the account's
        // state is told only to the holder of its mobile.
        OtpChallenge challenge =
                pending.passwordless() ? verifyCode(user, loginId, pending, code) : null;
        // Locked or deactivated while the OTP was on its way: the code no longer opens a session.
        if (stored.isLockedAt(now)) {
            return fail(loginId, user, ApiErrorCode.TEMPORARILY_LOCKED, "credential locked");
        }
        ApiErrorCode statusDenial = statusDenial(user);
        if (statusDenial != null) {
            return fail(loginId, user, statusDenial, "account " + user.getStatus());
        }
        if (challenge == null) {
            challenge = verifyCode(user, loginId, pending, code);
        }
        Scoped scoped = chooseScope(loginId, user, pending.roleCode(), pending.locationId());
        if (scoped.failure() != null) {
            return scoped.failure();
        }
        return establish(
                loginId, user, stored, scoped.scope(), clientIp, userAgent, now, challenge.getId());
    }

    /** Verifies the sign-in code; a refusal is audited and rethrown. */
    private OtpChallenge verifyCode(
            AppUser user, String loginId, PendingLogin pending, String code) {
        try {
            return otps.verify(user, OtpGatedAction.AUTH_LOGIN, pending.challengeId(), code);
        } catch (FciException refused) {
            fail(loginId, user, refused.getErrorCode(), "sign-in OTP refused");
            throw refused;
        }
    }

    /** The user id of a pending mobile sign-in for a number no single account carries. */
    static final long UNKNOWN = 0L;

    /**
     * Whether "Sign in with OTP on registered mobile" is offered (OD-41): the setting, with the
     * sign-in OTP on and OTP verification required, so it is never a sign-in without any check.
     */
    public boolean mobileLoginAvailable() {
        return properties.otp().atLogin()
                && properties.otp().mobileLogin()
                && otps.verificationRequired();
    }

    /**
     * "Sign in with OTP on registered mobile" (OD-41): the code goes to the account that carries
     * the number, and the code alone signs in (completeWithOtp). A number no single account carries
     * gets the same answer and nothing is sent, so the page never tells which numbers are
     * registered; a locked or deactivated account learns it only after its code.
     */
    public OtpRequired requestMobileOtp(String typedMobile) {
        if (!mobileLoginAvailable()) {
            throw new FciException(CommonErrorCode.NOT_FOUND);
        }
        return admitted(() -> Objects.requireNonNull(tx.execute(status -> mobileOtp(typedMobile))));
    }

    private OtpRequired mobileOtp(String typed) {
        Instant now = clock.instant();
        String mobile = normaliseMobile(typed);
        List<AppUser> matches = users.findByRegisteredMobile(mobile);
        if (matches.size() == 1 && credentials.findById(matches.get(0).getId()).isPresent()) {
            AppUser user = matches.get(0);
            return sendLoginOtp(user, user.getLoginId(), null, null, now, true);
        }
        fail(null, null, ApiErrorCode.INVALID_CREDENTIALS, "mobile of no single account");
        return unknownMobile(mobile, now);
    }

    private OtpRequired unknownMobile(String mobile, Instant now) {
        return new OtpRequired(
                new PendingLogin(UNKNOWN, mobile, 0L, null, null, now, true),
                OtpChallenge.SMS,
                maskedMobile(mobile),
                Math.toIntExact(parameters.otpValidity().toSeconds()));
    }

    /**
     * The outcome of hashing outside the transaction: the stored hash it was checked against (null:
     * no account, a dummy hash was verified for equal timing), whether the password matched, and
     * the upgraded hash to store when the stored one uses outdated parameters.
     */
    record Verified(String hash, boolean matches, String upgraded) {

        /** The verification of {@code current}: this one if the hash is unchanged, else afresh. */
        boolean matches(String current, java.util.function.BooleanSupplier again) {
            return current.equals(hash) ? matches : again.getAsBoolean();
        }
    }

    private Verified verifyOutsideTransaction(String loginId, IdType idType, String password) {
        String hash =
                tx.execute(
                        status ->
                                lookup(loginId, idType)
                                        .flatMap(u -> credentials.findById(u.getId()))
                                        .map(AppUserCredential::getPasswordHash)
                                        .orElse(null));
        if (hash == null || password == null) {
            hasher.matchesDummy(password);
            return new Verified(hash, false, null);
        }
        boolean matches = hasher.matches(password, hash);
        String upgraded = matches && hasher.needsUpgrade(hash) ? hasher.hash(password) : null;
        return new Verified(hash, matches, upgraded);
    }

    private Attempt attempt(String loginId, LoginRequest request, Verified verified) {
        Instant now = clock.instant();
        Optional<AppUser> found = lookup(loginId, request.idType());
        Optional<AppUserCredential> credential =
                found.flatMap(u -> credentials.findById(u.getId()));
        if (found.isEmpty() || credential.isEmpty()) {
            // The equal-timing hash was computed outside the transaction.
            return fail(loginId, null, ApiErrorCode.INVALID_CREDENTIALS, "unknown login");
        }
        AppUser user = found.get();
        AppUserCredential stored = credential.get();
        if (stored.isLockedAt(now)) {
            // One hash was computed outside the transaction, as for any attempt (equal timing).
            return fail(loginId, user, ApiErrorCode.TEMPORARILY_LOCKED, "credential locked");
        }
        if (request.password() == null
                || !verified.matches(
                        stored.getPasswordHash(),
                        () -> hasher.matches(request.password(), stored.getPasswordHash()))) {
            boolean locked =
                    stored.registerFailure(
                            parameters.loginMaxAttempts(), parameters.lockoutDuration(), now);
            credentials.save(stored);
            if (locked) {
                audit.record(
                        loginId,
                        AuditService.Entry.change(
                                "app_user",
                                String.valueOf(user.getId()),
                                "LOCKOUT",
                                null,
                                "LOCKED_UNTIL " + stored.getLockedUntil(),
                                "Locked after "
                                        + parameters.loginMaxAttempts()
                                        + " failed sign-ins"));
                audit.securityEvent(
                        AuditService.Security.ACCOUNT_LOCKOUT,
                        loginId,
                        "app_user",
                        String.valueOf(user.getId()),
                        "failed sign-ins: " + parameters.loginMaxAttempts());
                if (user.getRegisteredEmail() != null) {
                    notifications.sendAccountLocked(
                            user.getRegisteredEmail(), user.getDisplayName());
                }
                return fail(
                        loginId,
                        user,
                        ApiErrorCode.TEMPORARILY_LOCKED,
                        "wrong password, now locked");
            }
            return fail(loginId, user, ApiErrorCode.INVALID_CREDENTIALS, "wrong password");
        }
        ApiErrorCode statusDenial = statusDenial(user);
        if (statusDenial != null) {
            return fail(loginId, user, statusDenial, "account " + user.getStatus());
        }
        Scoped scoped = chooseScope(loginId, user, request.roleCode(), request.locationId());
        if (scoped.failure() != null) {
            return scoped.failure();
        }
        // The password was right: an outdated hash is upgraded now, while the password is at hand.
        if (verified.upgraded() != null && stored.getPasswordHash().equals(verified.hash())) {
            stored.upgradeHash(verified.upgraded());
        } else if (hasher.needsUpgrade(stored.getPasswordHash())) {
            // The stored hash changed since it was verified (a concurrent change): hash in place.
            stored.upgradeHash(hasher.hash(request.password()));
        }
        credentials.save(stored);
        Long loginChallenge = null;
        if (properties.otp().atLogin()) {
            if (otps.verificationRequired()) {
                // SRS-G-48: the OTP to the registered contact; the session starts on its code.
                return new Pending(
                        sendLoginOtp(
                                user,
                                loginId,
                                request.roleCode(),
                                request.locationId(),
                                now,
                                false));
            }
            // OTP switched off (development / local only): the login challenge is still recorded.
            loginChallenge =
                    otps.confirmWithoutCode(
                                    user,
                                    OtpGatedAction.AUTH_LOGIN,
                                    "app_user",
                                    String.valueOf(user.getId()))
                            .getId();
        }
        return establish(
                loginId,
                user,
                stored,
                scoped.scope(),
                request.clientIp(),
                request.userAgent(),
                now,
                loginChallenge);
    }

    private static ApiErrorCode statusDenial(AppUser user) {
        return switch (user.getStatus()) {
            case ACTIVE -> null;
            case LOCKED -> ApiErrorCode.ACCOUNT_LOCKED;
            case SUSPENDED -> ApiErrorCode.ACCOUNT_SUSPENDED;
            case DEACTIVATED -> ApiErrorCode.ACCOUNT_DEACTIVATED;
        };
    }

    /** The scope a sign-in starts in, or why it cannot start (exactly one of the two is set). */
    private record Scoped(ScopeResolver.RoleScope scope, Failure failure) {}

    private Scoped chooseScope(String loginId, AppUser user, String roleCode, Long locationId) {
        // Scope: no persona or location at sign-in. The session starts in the user's previous
        // (office, role) if it is still valid, else in the first staff scope, else in eSeva; the
        // top-bar selectors switch it afterwards without signing out (wireframe v6.1, FRS V0.5
        // §28).
        LocalDate today = contexts.today();
        List<ScopeResolver.RoleScope> available =
                scopes.availableScopes(
                        user.getId(), user.getEmployeeId(), AccessContextService.ESEVA_ROLE, today);
        if (available.isEmpty()) {
            return new Scoped(
                    null,
                    fail(
                            loginId,
                            user,
                            ApiErrorCode.NO_ACTIVE_SCOPE,
                            "no active access group or eSeva role"));
        }
        if (roleCode == null && locationId == null) {
            return new Scoped(defaultScope(user.getId(), available), null);
        }
        Optional<ScopeResolver.RoleScope> asked =
                available.stream()
                        .filter(
                                o ->
                                        roleCode == null
                                                ? !o.isEseva()
                                                        && o.location().getId().equals(locationId)
                                                : o.matches(roleCode, locationId))
                        .findFirst();
        if (asked.isEmpty()) {
            return new Scoped(
                    null,
                    fail(
                            loginId,
                            user,
                            ApiErrorCode.LOCATION_NOT_IN_SCOPE,
                            "scope " + roleCode + " @" + locationId));
        }
        return new Scoped(asked.get(), null);
    }

    /** Issues the sign-in OTP to the registered mobile (or e-mail) and audits that it was sent. */
    private OtpRequired sendLoginOtp(
            AppUser user,
            String loginId,
            String roleCode,
            Long locationId,
            Instant now,
            boolean passwordless) {
        OtpChallenge challenge =
                otps.issue(
                        user, OtpGatedAction.AUTH_LOGIN, "app_user", String.valueOf(user.getId()));
        boolean sms = OtpChallenge.SMS.equals(challenge.getDeliveryChannel());
        audit.record(
                loginId,
                AuditService.Entry.change(
                        "app_user",
                        String.valueOf(user.getId()),
                        "LOGIN",
                        null,
                        "OTP_SENT " + challenge.getDeliveryChannel(),
                        "Password accepted; sign-in OTP sent"));
        return new OtpRequired(
                new PendingLogin(
                        user.getId(),
                        loginId,
                        challenge.getId(),
                        roleCode,
                        locationId,
                        now,
                        passwordless),
                challenge.getDeliveryChannel(),
                sms
                        ? maskedMobile(user.getRegisteredMobile())
                        : SensitiveDataMasker.maskEmail(user.getRegisteredEmail()),
                Math.toIntExact(parameters.otpValidity().toSeconds()));
    }

    /**
     * A mobile number as typed (spaces, dashes, +91 or a leading 0) reduced to the ten digits it is
     * registered with.
     */
    public static String normaliseMobile(String typed) {
        String digits = typed.replaceAll("[^0-9]", "");
        if (digits.length() == 12 && digits.startsWith("91")) {
            return digits.substring(2);
        }
        if (digits.length() == 11 && digits.startsWith("0")) {
            return digits.substring(1);
        }
        return digits;
    }

    /** The last four digits only (R-PII). */
    static String maskedMobile(String mobile) {
        return mobile.length() < 4 ? "****" : "******" + mobile.substring(mobile.length() - 4);
    }

    /**
     * Starts the session in {@code chosen}: the account's success recorded, the login audited and,
     * after a sign-in OTP, the session's OTP step-up confirmed (the challenge on app_session).
     */
    private Success establish(
            String loginId,
            AppUser user,
            AppUserCredential stored,
            ScopeResolver.RoleScope chosen,
            String clientIp,
            String userAgent,
            Instant now,
            Long loginChallenge) {
        LoginPersona persona =
                personas.findById(chosen.personaCode())
                        .orElseThrow(
                                () -> new IllegalStateException("persona " + chosen.personaCode()));
        // The token stays in the HTTP session; app_session keeps only its hash (target DDL v4.0).
        String sessionToken = SecureTokens.randomToken(24);
        AppSession session =
                AppSession.start(
                        SecureTokens.sha256Hex(sessionToken),
                        user.getId(),
                        persona.getCode(),
                        chosen.isEseva() ? null : chosen.location().getId(),
                        chosen.groups().stream().map(ActiveGroup::id).toList(),
                        user.getEmployeeId(),
                        clientIp,
                        userAgent,
                        now);
        if (loginChallenge != null) {
            session.confirmStepUp(loginChallenge, now);
        }
        stored.registerSuccess();
        credentials.save(stored);
        user.recordLogin(now);
        users.save(user);
        SessionService.StartedSession started = sessions.start(user, stored, session, sessionToken);
        audit.record(
                loginId,
                AuditService.Entry.change(
                        "app_user",
                        String.valueOf(user.getId()),
                        "LOGIN",
                        null,
                        "SUCCESS "
                                + chosen.roleCode()
                                + (session.getActiveLocationId() == null
                                        ? ""
                                        : " @" + session.getActiveLocationId()),
                        "Signed in"));
        return new Success(
                new LoginResult(
                        started,
                        persona.getCode(),
                        session.getActiveLocationId(),
                        persona.getLandingScreenCode()));
    }

    /**
     * The scope a sign-in starts in: the previous session's office and acting role when the user
     * still holds it, else the first staff scope (offices in hierarchy order), else eSeva.
     */
    private ScopeResolver.RoleScope defaultScope(
            Long userId, List<ScopeResolver.RoleScope> available) {
        Optional<AppSession> previous =
                sessionHistory.findFirstByUserIdOrderByStartedAtDesc(userId);
        if (previous.isPresent()) {
            Set<Long> previousGroups = Set.copyOf(previous.get().activeGroupIdList());
            Long previousLocation = previous.get().getActiveLocationId();
            Optional<ScopeResolver.RoleScope> same =
                    available.stream()
                            .filter(
                                    o ->
                                            Objects.equals(
                                                    o.isEseva() ? null : o.location().getId(),
                                                    previousLocation))
                            .filter(
                                    o ->
                                            o.isEseva()
                                                    ? previousGroups.isEmpty()
                                                    : o.groups().stream()
                                                            .anyMatch(
                                                                    g ->
                                                                            previousGroups.contains(
                                                                                    g.id())))
                            .findFirst();
            if (same.isPresent()) {
                return same.get();
            }
        }
        return available.stream().filter(o -> !o.isEseva()).findFirst().orElse(available.get(0));
    }

    /**
     * Sign-in identifier (HLD): the registered e-mail address or the login ID. An e-mail matches
     * only when exactly one account carries it.
     */
    private Optional<AppUser> lookup(String identifier, IdType idType) {
        if (identifier == null || identifier.isBlank()) {
            return Optional.empty();
        }
        if (idType == IdType.MOBILE) {
            // OD-41: the registered mobile; exactly one account carries it, else it is unknown.
            List<AppUser> matches = users.findByRegisteredMobile(normaliseMobile(identifier));
            return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
        }
        if (idType == IdType.EMPLOYEE_ID) {
            // OD-37: the employee number; unique within a zone only, so exactly one account.
            List<AppUser> matches =
                    users.findByEmployeeNumber(
                            identifier.strip().toUpperCase(java.util.Locale.ROOT));
            return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
        }
        if (identifier.indexOf('@') > 0) {
            List<AppUser> matches =
                    users.findByRegisteredEmail(
                            identifier.trim().toLowerCase(java.util.Locale.ROOT));
            return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
        }
        return users.findByLoginId(identifier);
    }

    /** Records the failed attempt in its own transaction (it survives the caller's failure). */
    private Failure fail(String loginId, AppUser user, ErrorCode code, String detail) {
        audit.recordIndependently(
                loginId == null ? "anonymous" : loginId,
                AuditService.Entry.change(
                        "app_user",
                        user == null ? "-" : String.valueOf(user.getId()),
                        "LOGIN",
                        null,
                        "FAILURE:" + code.code(),
                        "Sign-in refused: " + detail));
        return new Failure(code);
    }
}
