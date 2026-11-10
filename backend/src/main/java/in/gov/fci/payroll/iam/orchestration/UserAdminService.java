package in.gov.fci.payroll.iam.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.employee.api.EmployeeDirectory;
import in.gov.fci.payroll.iam.business.ClientDevices;
import in.gov.fci.payroll.iam.business.model.AppSession;
import in.gov.fci.payroll.iam.business.model.AppUser;
import in.gov.fci.payroll.iam.business.model.AppUserCredential;
import in.gov.fci.payroll.iam.business.model.UserPreference;
import in.gov.fci.payroll.iam.persistence.AccessRepositories;
import in.gov.fci.payroll.iam.persistence.IdentityRepositories;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.logging.Sensitive;
import in.gov.fci.payroll.platform.persistence.Likes;
import in.gov.fci.payroll.platform.security.AccessContext;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A-01 Users (System Administrator). Creates accounts with a temporary password that must be
 * changed at first sign-in, edits contact data, suspends, deactivates (final), unlocks and resets
 * passwords. Access is granted only through access-group assignments (A-04), never here. No hash,
 * secret or failed-attempt detail ever leaves this service.
 */
@Service
@Loggable
public class UserAdminService {

    private static final Pattern EMAIL =
            Pattern.compile("^[a-z0-9._%+-]{1,64}@[a-z0-9.-]{1,180}\\.[a-z]{2,24}$");

    public record UserRow(
            Long id,
            String loginId,
            String displayName,
            String category,
            String status,
            String employeeEis,
            boolean locked,
            boolean mustChangePassword,
            Instant lastLoginAt,
            Long rowVersion) {}

    public record NewUser(
            String loginId,
            String displayName,
            AppUser.Category category,
            String employeeEis,
            String mobile,
            @Sensitive String temporaryPassword) {

        @Override
        public String toString() {
            return "NewUser{loginId=" + loginId + ", category=" + category + "}";
        }
    }

    public record PageView<T>(List<T> items, int page, int size, long total) {}

    /** One sign-in of the A-01 login record (SRS-G-50): when, from where, on what, how it ended. */
    public record LoginRecord(
            Long sessionId,
            Instant startedAt,
            Instant endedAt,
            String endReason,
            String sourceIp,
            String device,
            boolean open) {}

    /** The outcome of a group suspension: who was suspended, who was already not active. */
    public record SuspendOutcome(List<Long> suspended, List<Long> skipped) {}

    /** At most this many accounts in one group suspension (a request, not a bulk import). */
    public static final int MAX_SUSPEND = 100;

    /** OD-130: the {@code security_event} types of the user's offence record. */
    private static final List<AuditService.Security> OFFENCE_RECORD =
            List.of(
                    AuditService.Security.UNAUTHORIZED_ACCESS,
                    AuditService.Security.UNAUTHORIZED_ACCESS_NOTED,
                    AuditService.Security.ACCOUNT_SUSPENDED_OFFENCE);

    private final AccessRepositories.Users users;
    private final IdentityRepositories.Credentials credentials;
    private final IdentityRepositories.Preferences preferences;
    private final EmployeeDirectory employees;
    private final PasswordService passwords;
    private final SessionService sessions;
    private final AccessContextService contexts;
    private final AccessPolicy policy;
    private final AuditService audit;
    private final Clock clock;
    private final AdminScope adminScope;
    private final AccountChangeAlerts alerts;
    private final SecurityOffences offences;

    public UserAdminService(
            AccessRepositories.Users users,
            IdentityRepositories.Credentials credentials,
            IdentityRepositories.Preferences preferences,
            EmployeeDirectory employees,
            PasswordService passwords,
            SessionService sessions,
            AccessContextService contexts,
            AccessPolicy policy,
            AuditService audit,
            Clock clock,
            AdminScope adminScope,
            AccountChangeAlerts alerts,
            SecurityOffences offences) {
        this.adminScope = adminScope;
        this.alerts = alerts;
        this.offences = offences;
        this.users = users;
        this.credentials = credentials;
        this.preferences = preferences;
        this.employees = employees;
        this.passwords = passwords;
        this.sessions = sessions;
        this.contexts = contexts;
        this.policy = policy;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageView<UserRow> list(
            AccessContext context, String text, AppUser.Status status, int page, int size) {
        policy.require(context, "A-01", "VIEW");
        String like =
                text == null || text.isBlank()
                        ? null
                        : "%" + Likes.escape(text.strip().toLowerCase(Locale.ROOT)) + "%";
        PageRequest request =
                PageRequest.of(
                        Math.max(0, page), Math.min(Math.max(1, size), 100), Sort.by("loginId"));
        // A zone / region / division administrator sees only the accounts of its own offices.
        Page<AppUser> found;
        if (adminScope.isNational(context)) {
            found = users.search(like, status, request);
        } else {
            found =
                    context.dataScope().isEmpty()
                            ? Page.empty(request)
                            : users.searchAmong(like, status, context.dataScope(), request);
        }
        Instant now = clock.instant();
        return new PageView<>(
                found.getContent().stream().map(u -> row(u, now)).toList(),
                found.getNumber(),
                found.getSize(),
                found.getTotalElements());
    }

    @Transactional(readOnly = true)
    public UserRow get(AccessContext context, Long userId) {
        policy.require(context, "A-01", "VIEW");
        AppUser user =
                users.findById(userId)
                        .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
        adminScope.requireUserVisible(context, user);
        return row(user, clock.instant());
    }

    @Transactional
    public UserRow create(AccessContext context, NewUser request) {
        policy.require(context, "A-01", "CREATE");
        String login = AppUser.normaliseLogin(request.loginId());
        if (login == null
                || !EMAIL.matcher(login).matches()
                || request.category() == null
                || request.displayName() == null
                || request.displayName().isBlank()) {
            throw new FciException(CommonErrorCode.VALIDATION_FAILED);
        }
        if (users.existsByLoginId(login)) {
            throw new FciException(ApiErrorCode.LOGIN_TAKEN);
        }
        Long employeeId = null;
        if (request.employeeEis() != null && !request.employeeEis().isBlank()) {
            employeeId =
                    employees
                            .idOf(request.employeeEis())
                            .orElseThrow(() -> new FciException(CommonErrorCode.VALIDATION_FAILED));
        }
        adminScope.requireNewUser(context, employeeId);
        AppUser user =
                users.save(
                        AppUser.create(
                                login,
                                employeeId,
                                request.displayName().strip(),
                                request.category(),
                                request.mobile(),
                                login,
                                employees.locationOf(employeeId).orElse(context.locationId()),
                                context.loginId(),
                                clock.instant()));
        passwords.setTemporary(user, request.temporaryPassword());
        preferences.save(new UserPreference(user.getId(), "en", clock.instant()));
        audit.record(
                context.loginId(),
                AuditService.Entry.change(
                                "app_user",
                                String.valueOf(user.getId()),
                                "CREATE",
                                null,
                                login,
                                "User created")
                        .module("A-01"));
        return row(user, clock.instant());
    }

    @Transactional
    public UserRow updateContact(
            AccessContext context, Long userId, String displayName, String mobile) {
        return updateContact(context, userId, displayName, mobile, null);
    }

    /**
     * As above, against the version the caller read ({@code If-Match}; SRS-15-29, SRS-NF-26): a
     * version another user has since changed is refused naming who changed it and when.
     */
    @Transactional
    public UserRow updateContact(
            AccessContext context,
            Long userId,
            String displayName,
            String mobile,
            Long expectedVersion) {
        policy.require(context, "A-01", "EDIT");
        AppUser user = load(context, userId);
        if (expectedVersion != null && !expectedVersion.equals(user.getRowVersion())) {
            AuditService.LastChange last =
                    audit.lastChange("app_user", String.valueOf(userId))
                            .orElse(new AuditService.LastChange("-", null));
            throw new FciException(
                    ApiErrorCode.RECORD_CHANGED,
                    new Object[] {
                        last.changedBy(),
                        last.changedAt() == null ? "-" : last.changedAt().toString(),
                        user.getRowVersion()
                    },
                    List.of(),
                    null);
        }
        String before = user.getDisplayName();
        String mobileBefore = user.getRegisteredMobile();
        user.updateContact(
                displayName == null || displayName.isBlank()
                        ? user.getDisplayName()
                        : displayName.strip(),
                mobile,
                user.getRegisteredEmail());
        // SRS-15-29 / SRS-NF-26: flushed, so the answer holds the new row version for the next
        // If-Match (a plain save bumps it only at commit, after the row was built).
        users.saveAndFlush(user);
        audit.record(
                context.loginId(),
                AuditService.Entry.change(
                                "app_user",
                                String.valueOf(userId),
                                "display_name",
                                before,
                                user.getDisplayName(),
                                "Contact updated")
                        .module("A-01"));
        String mobileAfter = user.getRegisteredMobile();
        if (!java.util.Objects.equals(blankToNull(mobileBefore), blankToNull(mobileAfter))) {
            // SRS-20-2: every Enterable field audited (masked, R-PII); the owner is told at the
            // number it had before (security review 2026-10-31: no unseen sign-in takeover).
            audit.record(
                    context.loginId(),
                    AuditService.Entry.change(
                                    "app_user",
                                    String.valueOf(userId),
                                    "registered_mobile",
                                    maskMobile(mobileBefore),
                                    maskMobile(mobileAfter),
                                    "Contact updated")
                            .module("A-01"));
            alerts.changed(user, blankToNull(mobileBefore), "registered mobile");
        }
        return row(user, clock.instant());
    }

    /** SUSPENDED, DEACTIVATED (final) or ACTIVE (reinstate a suspension or lock). */
    @Transactional
    public UserRow changeStatus(
            AccessContext context,
            Long userId,
            AppUser.Status next,
            AppUser.DeactivationReason reason,
            String note) {
        policy.require(context, "A-01", next == AppUser.Status.DEACTIVATED ? "DELETE" : "EDIT");
        // SRS-G-49: a suspension or deactivation is recorded with its reason; a reinstatement may
        // carry one.
        if (next != AppUser.Status.ACTIVE && (note == null || note.isBlank())) {
            throw new FciException(ApiErrorCode.REASON_REQUIRED);
        }
        if (userId.equals(context.userId())) {
            throw new FciException(ApiErrorCode.SELF_ASSIGNMENT);
        }
        AppUser user = load(context, userId);
        AppUser.Status before = user.getStatus();
        if (before == AppUser.Status.DEACTIVATED || before == next || next == null) {
            throw new FciException(ApiErrorCode.INVALID_STATUS_CHANGE);
        }
        boolean reinstated = next == AppUser.Status.ACTIVE && before == AppUser.Status.SUSPENDED;
        // OD-130: only an Administrator takes the account back after offences, and says why.
        if (reinstated
                && (note == null || note.isBlank())
                && offences.isSuspendedForOffences(userId)) {
            throw new FciException(ApiErrorCode.REASON_REQUIRED);
        }
        Instant now = clock.instant();
        user.changeStatus(next, reason, now);
        users.save(user);
        if (reinstated) {
            offences.resetCount(
                    context.loginId(),
                    userId,
                    note == null || note.isBlank() ? "Reinstated" : note);
        }
        if (next == AppUser.Status.ACTIVE) {
            credentials
                    .findById(userId)
                    .ifPresent(
                            c -> {
                                c.unlockByAdministrator(
                                        context.loginId(),
                                        note == null || note.isBlank() ? "Reactivated" : note,
                                        now);
                                credentials.save(c);
                            });
        } else {
            sessions.endAllSessions(userId, now, AppSession.EndReason.ADMIN_REVOKE);
        }
        contexts.evictAll();
        audit.record(
                context.loginId(),
                AuditService.Entry.change(
                                "app_user",
                                String.valueOf(userId),
                                "user_status",
                                before.name(),
                                next.name(),
                                note == null || note.isBlank() ? "Status changed" : note)
                        .module("A-01"));
        return row(user, now);
    }

    /**
     * Clears a temporary lockout after failed sign-ins; the reason is recorded on the credential.
     */
    @Transactional
    public UserRow unlock(AccessContext context, Long userId, String reason) {
        policy.require(context, "A-01", "EDIT");
        if (reason == null || reason.isBlank()) {
            throw new FciException(ApiErrorCode.REASON_REQUIRED);
        }
        AppUser user = load(context, userId);
        AppUserCredential credential =
                credentials
                        .findById(userId)
                        .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
        Instant now = clock.instant();
        credential.unlockByAdministrator(context.loginId(), reason.strip(), now);
        credentials.save(credential);
        audit.securityEvent(
                AuditService.Security.ACCOUNT_UNLOCK,
                context.loginId(),
                "app_user",
                String.valueOf(userId),
                reason.strip());
        audit.record(
                context.loginId(),
                AuditService.Entry.change(
                                "app_user",
                                String.valueOf(userId),
                                "LOCKOUT",
                                "LOCKED",
                                "UNLOCKED",
                                reason.strip())
                        .module("A-01"));
        return row(user, now);
    }

    /**
     * SRS-G-49: ends every open session of a user at once, without changing the account, with a
     * recorded reason (the user may sign in again). Refused for the administrator's own account and
     * outside the administrator's scope. Answers how many sessions were open.
     */
    @Transactional
    public int terminateSessions(AccessContext context, Long userId, String reason) {
        policy.require(context, "A-01", "EDIT");
        String why = requireReason(reason);
        if (userId.equals(context.userId())) {
            throw new FciException(ApiErrorCode.SELF_ASSIGNMENT);
        }
        AppUser user = load(context, userId);
        Instant now = clock.instant();
        int open = sessions.openCount(user.getId());
        sessions.endAllSessions(user.getId(), now, AppSession.EndReason.ADMIN_REVOKE);
        contexts.evictAll();
        audit.securityEvent(
                AuditService.Security.SESSION_TERMINATED,
                context.loginId(),
                "app_user",
                String.valueOf(userId),
                why);
        audit.record(
                context.loginId(),
                AuditService.Entry.change(
                                "app_user",
                                String.valueOf(userId),
                                "SESSION",
                                open + " open",
                                "TERMINATED",
                                why)
                        .module("A-01"));
        return open;
    }

    /**
     * SRS-G-49: suspends a group of accounts during a security incident, with one recorded reason;
     * their sessions end at once. All or nothing: one account outside the administrator's scope, or
     * the administrator's own, refuses the whole request. Accounts already not active are skipped.
     */
    @Transactional
    public SuspendOutcome suspend(AccessContext context, List<Long> userIds, String reason) {
        policy.require(context, "A-01", "EDIT");
        String why = requireReason(reason);
        if (userIds == null || userIds.isEmpty() || userIds.size() > MAX_SUSPEND) {
            throw new FciException(CommonErrorCode.VALIDATION_FAILED);
        }
        List<AppUser> targets = new ArrayList<>();
        for (Long id : new LinkedHashSet<>(userIds)) {
            if (id == null) {
                throw new FciException(CommonErrorCode.VALIDATION_FAILED);
            }
            if (id.equals(context.userId())) {
                throw new FciException(ApiErrorCode.SELF_ASSIGNMENT);
            }
            targets.add(load(context, id));
        }
        Instant now = clock.instant();
        List<Long> suspended = new ArrayList<>();
        List<Long> skipped = new ArrayList<>();
        for (AppUser user : targets) {
            if (user.getStatus() != AppUser.Status.ACTIVE) {
                skipped.add(user.getId());
                continue;
            }
            user.changeStatus(AppUser.Status.SUSPENDED, null, now);
            users.save(user);
            sessions.endAllSessions(user.getId(), now, AppSession.EndReason.ADMIN_REVOKE);
            audit.securityEvent(
                    AuditService.Security.ACCOUNT_SUSPENDED,
                    context.loginId(),
                    "app_user",
                    String.valueOf(user.getId()),
                    why);
            audit.record(
                    context.loginId(),
                    AuditService.Entry.change(
                                    "app_user",
                                    String.valueOf(user.getId()),
                                    "user_status",
                                    AppUser.Status.ACTIVE.name(),
                                    AppUser.Status.SUSPENDED.name(),
                                    why)
                            .module("A-01"));
            suspended.add(user.getId());
        }
        contexts.evictAll();
        return new SuspendOutcome(suspended, skipped);
    }

    /**
     * OD-130: the user's offence record for the Administrator's decision: each refused call, the
     * suspension with its justification and the reinstatements, newest first.
     */
    @Transactional(readOnly = true)
    public PageView<AuditService.SecurityEventView> offences(
            AccessContext context, Long userId, int page, int size) {
        policy.require(context, "A-01", "VIEW");
        AppUser user = load(context, userId);
        org.springframework.data.domain.Page<AuditService.SecurityEventView> rows =
                audit.securityEventsOf(user.getLoginId(), OFFENCE_RECORD, page, size);
        return new PageView<>(rows.getContent(), page, size, rows.getTotalElements());
    }

    /** SRS-G-50: the user's latest sign-ins, newest first (time, address, device and browser). */
    @Transactional(readOnly = true)
    public List<LoginRecord> logins(AccessContext context, Long userId) {
        policy.require(context, "A-01", "VIEW");
        AppUser user = load(context, userId);
        return sessions.recent(user.getId()).stream()
                .map(
                        s ->
                                new LoginRecord(
                                        s.getId(),
                                        s.getCreatedAt(),
                                        s.getEndedAt(),
                                        s.getEndReason(),
                                        s.getSourceIp(),
                                        ClientDevices.describe(s.getUserAgent()),
                                        s.isOpen()))
                .toList();
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new FciException(ApiErrorCode.REASON_REQUIRED);
        }
        return reason.strip();
    }

    /** Sets a temporary password (must change at next sign-in); every session of the user ends. */
    @Transactional
    public void resetPassword(
            AccessContext context, Long userId, @Sensitive String temporaryPassword) {
        policy.require(context, "A-01", "EDIT");
        if (userId.equals(context.userId())) {
            throw new FciException(ApiErrorCode.SELF_ASSIGNMENT);
        }
        AppUser user = load(context, userId);
        passwords.setTemporary(user, temporaryPassword);
        sessions.endAllSessions(userId, clock.instant(), AppSession.EndReason.ADMIN_REVOKE);
        audit.record(
                context.loginId(),
                AuditService.Entry.change(
                                "app_user",
                                String.valueOf(userId),
                                "PASSWORD_RESET",
                                null,
                                "TEMPORARY",
                                "Reset by administrator")
                        .module("A-01"));
        alerts.changed(user, null, "password (a temporary password was set)");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /** R-PII: a mobile number by its last two digits in the audit trail. */
    static String maskMobile(String mobile) {
        String s = blankToNull(mobile);
        if (s == null) {
            return null;
        }
        return s.length() <= 2 ? "**" : "*".repeat(s.length() - 2) + s.substring(s.length() - 2);
    }

    /** The user, if inside the administrator's scope (403 otherwise, a security event). */
    private AppUser load(AccessContext context, Long userId) {
        AppUser user =
                users.findById(userId)
                        .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
        adminScope.requireUser(context, user);
        return user;
    }

    private UserRow row(AppUser user, Instant now) {
        AppUserCredential credential = credentials.findById(user.getId()).orElse(null);
        String eis =
                user.getEmployeeId() == null
                        ? null
                        : employees.eisOf(user.getEmployeeId()).orElse(null);
        return new UserRow(
                user.getId(),
                user.getLoginId(),
                user.getDisplayName(),
                user.getCategory().name(),
                user.getStatus().name(),
                eis,
                credential != null && credential.isLockedAt(now),
                credential == null || credential.isMustChange(),
                user.getLastLoginAt(),
                user.getRowVersion());
    }
}
