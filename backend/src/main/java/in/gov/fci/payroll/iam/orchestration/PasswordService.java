package in.gov.fci.payroll.iam.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.iam.business.PasswordHasher;
import in.gov.fci.payroll.iam.business.PasswordPolicy;
import in.gov.fci.payroll.iam.business.model.AppSession;
import in.gov.fci.payroll.iam.business.model.AppUser;
import in.gov.fci.payroll.iam.business.model.AppUserCredential;
import in.gov.fci.payroll.iam.business.model.PasswordHistoryEntry;
import in.gov.fci.payroll.iam.persistence.AccessRepositories;
import in.gov.fci.payroll.iam.persistence.IdentityRepositories;
import in.gov.fci.payroll.platform.approval.OtpProof;
import in.gov.fci.payroll.platform.config.ApiProperties;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.error.Violation;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.logging.Sensitive;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.OtpGatedAction;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Password change (signed in) and forgot-password with an e-mailed OTP. A new password must pass
 * the policy and differ from the current one and the last {@code historySize} (5) passwords. Hashes
 * are never returned or logged.
 */
@Service
@Loggable
public class PasswordService {

    private final AccessRepositories.Users users;
    private final IdentityRepositories.Credentials credentials;
    private final IdentityRepositories.PasswordHistory history;
    private final PasswordHasher hasher;
    private final PasswordPolicy policy;
    private final OtpService otps;
    private final SessionService sessions;
    private final AuditService audit;
    private final Clock clock;
    private final ApiProperties properties;
    private final SystemParameters parameters;

    public PasswordService(
            AccessRepositories.Users users,
            IdentityRepositories.Credentials credentials,
            IdentityRepositories.PasswordHistory history,
            PasswordHasher hasher,
            PasswordPolicy policy,
            OtpService otps,
            SessionService sessions,
            AuditService audit,
            Clock clock,
            ApiProperties properties,
            SystemParameters parameters) {
        this.users = users;
        this.credentials = credentials;
        this.history = history;
        this.hasher = hasher;
        this.policy = policy;
        this.otps = otps;
        this.sessions = sessions;
        this.audit = audit;
        this.clock = clock;
        this.properties = properties;
        this.parameters = parameters;
    }

    /** OTP entity of an own password change: the user's own account. */
    static final String CHANGE_ENTITY = "app_user.password";

    /**
     * Changes the caller's password; every other session of the user ends. SRS-G-21 / X-08 (l.894):
     * the user enters the current password, the new one and an OTP - also when the change is forced
     * (first use, administrator reset, or a password past its 90-day age, SRS-SEC-13). The current
     * password and the policy are checked first, so a refused password costs no code; then the OTP:
     * without one a challenge is sent and OTP_REQUIRED (428) answers with it.
     */
    // A failure is audited independently and a wrong OTP counted in OtpService's own
    // transaction; any refusal rolls the change back (no new password without the sessions ended).
    @Transactional
    public void change(
            AccessContext context,
            @Sensitive String currentPassword,
            @Sensitive String newPassword,
            OtpProof otp) {
        AppUser user =
                users.findById(context.userId())
                        .orElseThrow(() -> new FciException(ApiErrorCode.INVALID_GRANT));
        AppUserCredential credential =
                credentials
                        .findById(user.getId())
                        .orElseThrow(() -> new FciException(ApiErrorCode.INVALID_GRANT));
        if (currentPassword == null
                || !hasher.matches(currentPassword, credential.getPasswordHash())) {
            audit.recordIndependently(
                    user.getLoginId(),
                    AuditService.Entry.change(
                            "app_user",
                            String.valueOf(user.getId()),
                            "PASSWORD_CHANGE",
                            null,
                            "FAILURE:" + ApiErrorCode.CURRENT_PASSWORD_WRONG.code(),
                            "Current password wrong"));
            throw new FciException(ApiErrorCode.CURRENT_PASSWORD_WRONG);
        }
        validate(user, credential, newPassword);
        Long challenge = requireChangeOtp(user, otp);
        Instant now = store(user, credential, newPassword);
        sessions.endOtherSessions(user.getId(), context.sessionId(), now);
        audit.record(
                user.getLoginId(),
                AuditService.Entry.change(
                        "app_user",
                        String.valueOf(user.getId()),
                        "PASSWORD_CHANGE",
                        null,
                        "SUCCESS",
                        "Password changed by the user with OTP challenge " + challenge));
    }

    /**
     * The OTP of an own password change, bound to the user's account. With verification switched
     * off (development, {@code fci.api.otp.required = N}) a confirmed challenge is still recorded.
     */
    private Long requireChangeOtp(AppUser user, OtpProof otp) {
        String key = String.valueOf(user.getId());
        if (!otps.verificationRequired()) {
            return otps.confirmWithoutCode(
                            user, OtpGatedAction.AUTH_OWN_PASSWORD_CHANGE, CHANGE_ENTITY, key)
                    .getId();
        }
        if (otp == null || otp.challengeId() == null || otp.code() == null) {
            Long issued =
                    otps.issue(user, OtpGatedAction.AUTH_OWN_PASSWORD_CHANGE, CHANGE_ENTITY, key)
                            .getId();
            throw new FciException(
                    ApiErrorCode.OTP_REQUIRED, new Object[] {issued}, List.of(), null);
        }
        return otps.verify(
                        user,
                        OtpGatedAction.AUTH_OWN_PASSWORD_CHANGE,
                        otp.challengeId(),
                        otp.code(),
                        CHANGE_ENTITY,
                        key)
                .getId();
    }

    /**
     * Sends a reset OTP to the account's registered e-mail. Silent for unknown or inactive logins
     * (the caller always answers 202).
     */
    @Transactional
    public void requestReset(String loginId) {
        Optional<AppUser> user =
                users.findByLoginId(AppUser.normaliseLogin(loginId))
                        .filter(u -> u.getStatus() == AppUser.Status.ACTIVE);
        if (user.isEmpty()) {
            audit.record(
                    AppUser.normaliseLogin(loginId),
                    AuditService.Entry.change(
                            "app_user",
                            "-",
                            "PASSWORD_RESET_REQUEST",
                            null,
                            "IGNORED",
                            "Unknown or inactive login"));
            return;
        }
        try {
            otps.issue(
                    user.get(),
                    OtpGatedAction.AUTH_OWN_PASSWORD_CHANGE,
                    "app_user",
                    String.valueOf(user.get().getId()));
        } catch (FciException rateLimited) {
            // Silently capped: the response must not reveal that the account exists.
            return;
        }
        credentials
                .findById(user.get().getId())
                .ifPresent(
                        c -> {
                            c.markResetRequested(clock.instant());
                            credentials.save(c);
                        });
        audit.record(
                user.get().getLoginId(),
                AuditService.Entry.change(
                        "app_user",
                        String.valueOf(user.get().getId()),
                        "PASSWORD_RESET_REQUEST",
                        null,
                        "OTP_SENT",
                        "Reset OTP sent"));
    }

    /**
     * Completes a reset with the OTP; all sessions of the user end.
     *
     * <p>Public endpoint, so nothing about the account is told before the OTP is proved (security
     * review 2026-10-31): the policy check that needs no account data runs first and alike for any
     * login (an unknown one is then INVALID_OTP, as a wrong code is); the checks against the
     * account (its name in the password, the current and recent passwords) run only after the OTP,
     * so the endpoint is no password oracle and costs no password hashing without a valid code. A
     * reused password after a valid code needs a new code.
     */
    @Transactional
    public void reset(String loginId, @Sensitive String code, @Sensitive String newPassword) {
        String normalised = AppUser.normaliseLogin(loginId);
        validatePolicy(normalised, null, null, newPassword);
        AppUser user =
                users.findByLoginId(normalised)
                        .filter(u -> u.getStatus() == AppUser.Status.ACTIVE)
                        .orElseThrow(() -> new FciException(ApiErrorCode.INVALID_OTP));
        AppUserCredential credential =
                credentials
                        .findById(user.getId())
                        .orElseThrow(() -> new FciException(ApiErrorCode.INVALID_OTP));
        otps.verifyLatest(user, OtpGatedAction.AUTH_OWN_PASSWORD_CHANGE, code);
        validate(user, credential, newPassword);
        Instant now = replace(user, credential, newPassword);
        sessions.endAllSessions(user.getId(), now, AppSession.EndReason.REVOCATION);
        audit.record(
                user.getLoginId(),
                AuditService.Entry.change(
                        "app_user",
                        String.valueOf(user.getId()),
                        "PASSWORD_RESET",
                        null,
                        "SUCCESS",
                        "Password reset with OTP"));
    }

    /**
     * Administrator reset (A-02): a temporary password that must be changed at the next sign-in. In
     * the caller's transaction.
     */
    public void setTemporary(AppUser user, @Sensitive String temporaryPassword) {
        Optional<AppUserCredential> existing = credentials.findById(user.getId());
        if (existing.isEmpty()) {
            validatePolicy(user, temporaryPassword);
            credentials.save(
                    AppUserCredential.initial(
                            user.getId(), hasher.hash(temporaryPassword), clock.instant()));
            return;
        }
        AppUserCredential credential = existing.get();
        validate(user, credential, temporaryPassword);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        history.save(new PasswordHistoryEntry(user.getId(), now, credential.getPasswordHash()));
        credential.resetByAdministrator(hasher.hash(temporaryPassword), now);
        credentials.save(credential);
        prune(user.getId());
    }

    private Instant replace(AppUser user, AppUserCredential credential, String newPassword) {
        validate(user, credential, newPassword);
        return store(user, credential, newPassword);
    }

    /** Keeps the old hash in the history and stores the new, already validated, one. */
    private Instant store(AppUser user, AppUserCredential credential, String newPassword) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        history.save(new PasswordHistoryEntry(user.getId(), now, credential.getPasswordHash()));
        credential.replace(hasher.hash(newPassword), now);
        credentials.save(credential);
        prune(user.getId());
        return now;
    }

    private void validate(AppUser user, AppUserCredential credential, String newPassword) {
        validatePolicy(user, newPassword);
        if (hasher.matches(newPassword, credential.getPasswordHash())) {
            throw new FciException(ApiErrorCode.PASSWORD_REUSED);
        }
        List<PasswordHistoryEntry> previous = history.findNewestFirst(user.getId());
        int depth = Math.max(0, parameters.passwordHistory() - 1);
        for (PasswordHistoryEntry entry : previous.subList(0, Math.min(depth, previous.size()))) {
            if (hasher.matches(newPassword, entry.getPasswordHash())) {
                throw new FciException(ApiErrorCode.PASSWORD_REUSED);
            }
        }
    }

    private void validatePolicy(AppUser user, String newPassword) {
        validatePolicy(
                user.getLoginId(),
                user.getEmployeeId() == null ? null : String.valueOf(user.getEmployeeId()),
                user.getRegisteredEmail(),
                newPassword);
    }

    private void validatePolicy(
            String loginId, String employeeId, String email, String newPassword) {
        if (newPassword == null) {
            throw policyFailure(List.of(PasswordPolicy.Violation.TOO_SHORT));
        }
        List<PasswordPolicy.Violation> violations =
                policy.check(
                        newPassword,
                        loginId,
                        employeeId,
                        email,
                        parameters.passwordMinLength(),
                        // SRS-G-49: the character mix is an A-07 setting.
                        parameters.passwordCharacterClasses());
        if (!violations.isEmpty()) {
            throw policyFailure(violations);
        }
    }

    /** Keeps the newest {@code historySize - 1} previous hashes (plus the current one = 5). */
    private void prune(Long userId) {
        List<PasswordHistoryEntry> previous = history.findNewestFirst(userId);
        int keep = Math.max(0, parameters.passwordHistory() - 1);
        for (PasswordHistoryEntry stale :
                previous.subList(Math.min(keep, previous.size()), previous.size())) {
            history.delete(stale);
        }
    }

    private static FciException policyFailure(List<PasswordPolicy.Violation> violations) {
        return new FciException(
                ApiErrorCode.PASSWORD_POLICY,
                violations.stream()
                        .map(
                                v ->
                                        new Violation(
                                                "newPassword",
                                                v.name(),
                                                "password.policy." + v.name()))
                        .toList());
    }
}
