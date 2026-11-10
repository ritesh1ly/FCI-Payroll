package in.gov.fci.payroll.iam.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.iam.business.model.AppSession;
import in.gov.fci.payroll.iam.business.model.AppUser;
import in.gov.fci.payroll.iam.business.model.AppUserCredential;
import in.gov.fci.payroll.iam.persistence.AccessRepositories;
import in.gov.fci.payroll.iam.persistence.IdentityRepositories;
import in.gov.fci.payroll.platform.config.ApiProperties;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.security.SecureTokens;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sign-in sessions ({@code app_session}, target DDL v4.0): the session's active scope (persona,
 * office, admitted groups) lives here, server side. The browser holds only the HTTP session cookie;
 * the HTTP session holds the session token, and this row only its hash. The security filters check
 * it on every request, so ending a session here signs the browser out at once. Single-session
 * policy (ADR-0009): a new sign-in ends the older ones (end reason SUPERSEDED).
 */
@Service
@Loggable
public class SessionService {

    /** What the web layer stores in the HTTP session after a successful sign-in. */
    public record StartedSession(
            String sessionId,
            Long userId,
            boolean passwordChangeRequired,
            Instant absoluteExpiresAt,
            java.time.Duration idleTimeout) {}

    private final IdentityRepositories.Sessions sessions;
    private final AccessRepositories.Users users;
    private final AccessContextService contexts;
    private final AuditService audit;
    private final Clock clock;
    private final ApiProperties properties;
    private final SystemParameters parameters;
    private final SessionTimeouts timeouts;

    public SessionService(
            IdentityRepositories.Sessions sessions,
            AccessRepositories.Users users,
            AccessContextService contexts,
            AuditService audit,
            Clock clock,
            ApiProperties properties,
            SystemParameters parameters,
            SessionTimeouts timeouts) {
        this.sessions = sessions;
        this.users = users;
        this.contexts = contexts;
        this.audit = audit;
        this.clock = clock;
        this.properties = properties;
        this.parameters = parameters;
        this.timeouts = timeouts;
    }

    /**
     * Opens {@code session} for {@code token}; with the single-session policy every other open
     * session ends. In a transaction.
     */
    public StartedSession start(
            AppUser user, AppUserCredential credential, AppSession session, String token) {
        Instant now = clock.instant();
        if (parameters.singleSession()) {
            endAllSessions(user.getId(), now, AppSession.EndReason.SUPERSEDED);
        }
        sessions.save(session);
        boolean passwordChangeRequired =
                credential == null
                        || credential.isMustChange()
                        || credential.isExpired(now, parameters.passwordMaxAge());
        return new StartedSession(
                token,
                user.getId(),
                passwordChangeRequired,
                now.plus(parameters.sessionAbsoluteTimeout()),
                // SRS-SEC-14: 15 minutes for payroll users, 10 for eSeva members (A-07 settings).
                timeouts.idle(session));
    }

    /** Sign-out of the session of {@code token}. Unknown or already-ended sessions are ignored. */
    @Transactional
    public void end(String token) {
        end(token, AppSession.EndReason.LOGOUT);
    }

    /** Ends the session of {@code token} for {@code reason} (sign-out, suspected hijacking …). */
    @Transactional
    public void end(String token, AppSession.EndReason reason) {
        sessions.findByToken(token)
                .filter(AppSession::isOpen)
                .ifPresent(
                        session -> {
                            endSession(session, reason, clock.instant());
                            String login =
                                    users.findById(session.getUserId())
                                            .map(AppUser::getLoginId)
                                            .orElse("unknown");
                            audit.record(
                                    login,
                                    AuditService.Entry.change(
                                            "app_user",
                                            String.valueOf(session.getUserId()),
                                            "LOGOUT",
                                            null,
                                            "SUCCESS",
                                            "User signed out"));
                        });
    }

    /**
     * OD-130: this session was ended by an offence it committed: it was revoked and holds a counted
     * offence. Asked only of a session that can no longer be used, so never on a normal request.
     */
    public boolean endedForOffence(Long userId, String token) {
        boolean revoked =
                sessions.findByToken(token)
                        .filter(s -> !s.isOpen())
                        .map(AppSession::getEndReason)
                        .filter(
                                r ->
                                        AppSession.EndReason.REVOCATION.name().equals(r)
                                                || AppSession.EndReason.ADMIN_REVOKE
                                                        .name()
                                                        .equals(r))
                        .isPresent();
        return revoked
                && users.findById(userId)
                        .filter(
                                u ->
                                        audit.hasSecurityEvent(
                                                u.getLoginId(),
                                                AuditService.Security.UNAUTHORIZED_ACCESS,
                                                SecurityOffences.origin(
                                                        SecurityOffences.sessionRef(token))))
                        .isPresent();
    }

    /** Ends every open session of a user (single session, lock, deactivation). In a transaction. */
    public void endAllSessions(Long userId, Instant now, AppSession.EndReason reason) {
        for (AppSession open : sessions.findByUserIdAndEndedAtIsNull(userId)) {
            endSession(open, reason, now);
        }
    }

    /** How many sessions of the user are open now. */
    public int openCount(Long userId) {
        return sessions.findByUserIdAndEndedAtIsNull(userId).size();
    }

    /** The user's latest sessions, newest first (the A-01 login record, SRS-G-50). */
    public List<AppSession> recent(Long userId) {
        return sessions.findTop20ByUserIdOrderByStartedAtDesc(userId);
    }

    /**
     * Ends every open session of a user except the one of {@code keepToken} (after a password
     * change).
     */
    public void endOtherSessions(Long userId, String keepToken, Instant now) {
        String keep = keepToken == null ? null : SecureTokens.sha256Hex(keepToken);
        for (AppSession open : sessions.findByUserIdAndEndedAtIsNull(userId)) {
            if (!open.getTokenHash().equals(keep)) {
                endSession(open, AppSession.EndReason.REVOCATION, now);
            }
        }
    }

    private void endSession(AppSession session, AppSession.EndReason reason, Instant now) {
        session.end(reason, now);
        sessions.save(session);
        contexts.evictSession(session.getTokenHash());
    }
}
