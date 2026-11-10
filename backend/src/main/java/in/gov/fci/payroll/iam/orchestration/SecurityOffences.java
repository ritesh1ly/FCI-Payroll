package in.gov.fci.payroll.iam.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.audit.api.AuditService.Security;
import in.gov.fci.payroll.iam.business.model.AppSession;
import in.gov.fci.payroll.iam.business.model.AppUser;
import in.gov.fci.payroll.iam.persistence.AccessRepositories;
import in.gov.fci.payroll.platform.config.ApiProperties;
import in.gov.fci.payroll.platform.config.SecurityOffenceProperties;
import in.gov.fci.payroll.platform.logging.CriticalLog;
import in.gov.fci.payroll.platform.logging.MdcKeys;
import in.gov.fci.payroll.platform.security.SecureTokens;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * OD-130 (owner 2026-11-07; SRS-G-25 records the refused call, the enforcement is the owner's, FCI
 * security to verify): what follows a signed-in user's API call refused for lack of right or office
 * scope. In one transaction of its own, which survives the refused request: {@code security_event},
 * the count since the last reinstatement, the end of the user's sessions, and at the threshold
 * {@code user_status = SUSPENDED} with the justification in the event and the audit trail. The
 * critical log entry follows the commit, so it never reports what did not happen.
 *
 * <p>SUSPENDED and never LOCKED: the SRS lets a user unlock LOCKED by OTP self-service, and only an
 * Administrator may decide here. DEACTIVATED is never touched (its reasons are fixed by the DDL).
 *
 * <p>At most one offence per session (coordinator's correction to OD-130): an offence ends the
 * session, so every refused call of that session, concurrent ones included, is the same offence.
 * One screen sends several reads at once (M-01 five), and one stale link must not suspend anyone.
 * The first counts; the rest are {@code UNAUTHORIZED_ACCESS_NOTED} and the log, with no count, so
 * the threshold means that many separate sessions.
 *
 * <p>Concurrency: the user's row is locked before reading or counting, so a race inside a session
 * counts once and a race across two sessions counts twice. An administrator's whole group is
 * locked, in id order, so that two administrators reaching the threshold together cannot both pass
 * the last-administrator check.
 *
 * <p>A failure here never changes the refusal the caller gets: the request stays refused, and the
 * critical log says the record failed.
 */
@Component
public class SecurityOffences {

    private static final Logger log = LoggerFactory.getLogger(SecurityOffences.class);

    /** What was done about an offence, as the critical log and the response need it. */
    public enum Action {
        /** The switch is off, or nothing is configured to happen: recorded only. */
        LOGGED,
        LOGOUT,
        SUSPENDED,
        /** The threshold was reached on the last active administrator: not suspended. */
        LAST_ADMIN_PROTECTED,
        /** This session's offence is already counted: noted, nothing more happens. */
        ALREADY_COUNTED,
        /** The record itself failed; the call stays refused. */
        RECORD_FAILED
    }

    /**
     * The refused call: identifiers only, never a request value. {@code session}: a one-way
     * reference to the session (a hash of its token, never the token).
     */
    public record Refusal(
            Long userId,
            String session,
            String method,
            String pathPattern,
            String screen,
            String action,
            String code) {}

    /** The result. {@code sessionEnded}: the user's sessions were ended by this offence. */
    public record Outcome(long offenceNumber, Action action, boolean sessionEnded) {}

    /** How many offences a justification names (the newest; the record keeps them all). */
    private static final int JUSTIFICATION_ROWS = 10;

    /** The role a suspension never leaves without an active holder (OD-130). */
    private static final String ADMINISTRATOR = "SYSTEM_ADMINISTRATOR";

    private static final String USER_ENTITY = "app_user";

    /** {@code target_entity} of a refused call: the API, in the session that made it. */
    private static final String API_PREFIX = "api:";

    private static final int SESSION_REF_LENGTH = 16;

    private static final String SYSTEM_ACTOR = "SYSTEM";
    private static final int AUDIT_REASON_MAX = 500;

    private final AccessRepositories.Users users;
    private final SessionService sessions;
    private final AccessContextService contexts;
    private final AuditService audit;
    private final SecurityOffenceProperties settings;
    private final ApiProperties properties;
    private final Clock clock;
    private final TransactionOperations own;

    @Autowired
    public SecurityOffences(
            AccessRepositories.Users users,
            SessionService sessions,
            AccessContextService contexts,
            AuditService audit,
            SecurityOffenceProperties settings,
            ApiProperties properties,
            Clock clock,
            PlatformTransactionManager transactions) {
        this(
                users,
                sessions,
                contexts,
                audit,
                settings,
                properties,
                clock,
                ownTransaction(transactions));
    }

    SecurityOffences(
            AccessRepositories.Users users,
            SessionService sessions,
            AccessContextService contexts,
            AuditService audit,
            SecurityOffenceProperties settings,
            ApiProperties properties,
            Clock clock,
            TransactionOperations own) {
        this.users = users;
        this.sessions = sessions;
        this.contexts = contexts;
        this.audit = audit;
        this.settings = settings;
        this.properties = properties;
        this.clock = clock;
        this.own = own;
    }

    private static TransactionOperations ownTransaction(PlatformTransactionManager transactions) {
        TransactionTemplate template = new TransactionTemplate(transactions);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    /** Records the offence and acts on it; never throws (the caller is answering a refusal). */
    public Outcome handle(Refusal refusal) {
        Outcome outcome;
        try {
            outcome = Objects.requireNonNull(own.execute(tx -> record(refusal)));
        } catch (RuntimeException failed) {
            // The call stays refused whatever failed here; no request value is in the exception.
            log.error("event=SECURITY_OFFENCE_RECORD_FAILED userId={}", refusal.userId(), failed);
            outcome = new Outcome(0, Action.RECORD_FAILED, false);
        }
        if (outcome.sessionEnded()) {
            // After the commit: a concurrent call must not cache the session as still usable.
            contexts.evictAll();
        }
        CriticalLog.log(
                CriticalLog.Event.SECURITY_OFFENCE,
                CriticalLog.fields(
                        "user",
                        refusal.userId(),
                        "offence",
                        outcome.offenceNumber(),
                        "method",
                        refusal.method(),
                        "path",
                        refusal.pathPattern(),
                        "ip",
                        MDC.get(MdcKeys.CLIENT_IP),
                        "action",
                        outcome.action()));
        return outcome;
    }

    private Outcome record(Refusal refusal) {
        Locked locked = lock(refusal.userId());
        AppUser user = locked.user();
        String login = user.getLoginId();
        String target = pathTarget(refusal);
        String detail = detail(refusal);
        String origin = origin(refusal.session());
        if (!settings.on()) {
            audit.securityEventInTransaction(
                    Security.UNAUTHORIZED_ACCESS_NOTED, login, origin, target, detail);
            return new Outcome(0, Action.LOGGED, false);
        }
        Instant since = lastReset(user.getId());
        if (audit.hasSecurityEvent(login, Security.UNAUTHORIZED_ACCESS, origin)) {
            audit.securityEventInTransaction(
                    Security.UNAUTHORIZED_ACCESS_NOTED, login, origin, target, detail);
            // The session ended with its counted offence: this call is told so, nothing is ended
            // again (a late straggler must not end the session of a new sign-in).
            return new Outcome(
                    audit.countSecurityEvents(login, Security.UNAUTHORIZED_ACCESS, since),
                    Action.ALREADY_COUNTED,
                    settings.logsOut());
        }
        audit.securityEventInTransaction(
                Security.UNAUTHORIZED_ACCESS, login, origin, target, detail);
        long count = audit.countSecurityEvents(login, Security.UNAUTHORIZED_ACCESS, since);
        Instant now = clock.instant();
        if (settings.suspendsAt(count) && user.getStatus() == AppUser.Status.ACTIVE) {
            if (locked.isLastActiveAdministrator()) {
                return settings.logsOut()
                        ? new Outcome(count, Action.LAST_ADMIN_PROTECTED, endSessions(user, now))
                        : new Outcome(count, Action.LAST_ADMIN_PROTECTED, false);
            }
            suspend(user, count, since, now);
            return new Outcome(count, Action.SUSPENDED, true);
        }
        return settings.logsOut()
                ? new Outcome(count, Action.LOGOUT, endSessions(user, now))
                : new Outcome(count, Action.LOGGED, false);
    }

    /** The user's row locked, with every administrator's row when the user is one of them. */
    private Locked lock(Long userId) {
        List<Long> holders = users.findHoldersOfRole(ADMINISTRATOR, today());
        if (!holders.contains(userId)) {
            return new Locked(
                    users.lockById(userId)
                            .orElseThrow(() -> new IllegalStateException("user row vanished")),
                    List.of());
        }
        List<AppUser> administrators = users.lockAll(holders);
        AppUser user =
                administrators.stream()
                        .filter(u -> u.getId().equals(userId))
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("user row vanished"));
        return new Locked(user, administrators);
    }

    /** The locked rows; {@code administrators} is empty for a user who is not one. */
    private record Locked(AppUser user, List<AppUser> administrators) {

        /** Read under the locks: no other administrator is active. */
        boolean isLastActiveAdministrator() {
            return !administrators.isEmpty()
                    && administrators.stream()
                            .noneMatch(
                                    u ->
                                            !u.getId().equals(user.getId())
                                                    && u.getStatus() == AppUser.Status.ACTIVE);
        }
    }

    private boolean endSessions(AppUser user, Instant now) {
        sessions.endAllSessions(user.getId(), now, AppSession.EndReason.REVOCATION);
        return true;
    }

    private void suspend(AppUser user, long count, Instant since, Instant now) {
        String why = justification(user.getLoginId(), count, since);
        user.changeStatus(AppUser.Status.SUSPENDED, null, now);
        users.save(user);
        sessions.endAllSessions(user.getId(), now, AppSession.EndReason.ADMIN_REVOKE);
        audit.securityEventInTransaction(
                Security.ACCOUNT_SUSPENDED_OFFENCE,
                user.getLoginId(),
                USER_ENTITY,
                String.valueOf(user.getId()),
                why);
        audit.record(
                SYSTEM_ACTOR,
                AuditService.Entry.change(
                                USER_ENTITY,
                                String.valueOf(user.getId()),
                                "user_status",
                                AppUser.Status.ACTIVE.name(),
                                AppUser.Status.SUSPENDED.name(),
                                why.length() <= AUDIT_REASON_MAX
                                        ? why
                                        : why.substring(0, AUDIT_REASON_MAX))
                        .module("A-01"));
    }

    /** Each offence's time, path and address, newest first (OD-130: "proper justification"). */
    private String justification(String login, long count, Instant since) {
        String offences =
                audit
                        .recentSecurityEvents(
                                login, Security.UNAUTHORIZED_ACCESS, since, JUSTIFICATION_ROWS)
                        .stream()
                        .map(
                                e ->
                                        e.occurredAt().truncatedTo(ChronoUnit.SECONDS)
                                                + " "
                                                + e.target()
                                                + " from "
                                                + e.sourceIp())
                        .collect(Collectors.joining("; "));
        return "Suspended by the application: "
                + count
                + " unauthorized access attempts since the last reinstatement: "
                + offences;
    }

    /**
     * The start of the count: the last reset, else the beginning. The count is of offences only, so
     * a status change for another reason leaves it alone.
     */
    private Instant lastReset(Long userId) {
        return audit.latestSecurityEvent(
                        Security.OFFENCE_COUNT_RESET, USER_ENTITY, String.valueOf(userId))
                .orElse(Instant.EPOCH);
    }

    /** The one-way reference of a session in its events: never the token. */
    public static String sessionRef(String token) {
        return SecureTokens.sha256Hex(token).substring(0, SESSION_REF_LENGTH);
    }

    /** {@code target_entity} of a refused call of that session. */
    static String origin(String sessionRef) {
        return API_PREFIX + sessionRef;
    }

    /** The account's standing suspension is one the application made for offences. */
    public boolean isSuspendedForOffences(Long userId) {
        Optional<Instant> suspended =
                audit.latestSecurityEvent(
                        Security.ACCOUNT_SUSPENDED_OFFENCE, USER_ENTITY, String.valueOf(userId));
        return suspended.isPresent() && suspended.get().isAfter(lastReset(userId));
    }

    /**
     * Called by the A-01 reinstatement, in its transaction: the count starts again. Written for
     * every reinstatement of a suspended account (OD-130 "since the last reinstatement").
     */
    public void resetCount(String administrator, Long userId, String reason) {
        audit.securityEventInTransaction(
                Security.OFFENCE_COUNT_RESET,
                administrator,
                USER_ENTITY,
                String.valueOf(userId),
                reason);
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(properties.businessZone()));
    }

    private static String pathTarget(Refusal refusal) {
        return refusal.method() + " " + refusal.pathPattern();
    }

    /** Method, path pattern and the refused gate: no query string, no body, no person. */
    private static String detail(Refusal refusal) {
        return "method="
                + refusal.method()
                + " path="
                + refusal.pathPattern()
                + " screen="
                + refusal.screen()
                + " action="
                + refusal.action()
                + " code="
                + refusal.code();
    }
}
