package in.gov.fci.payroll.iam.security;

import in.gov.fci.payroll.iam.orchestration.SessionService;
import in.gov.fci.payroll.platform.security.SecureTokens;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.Optional;
import org.springframework.session.FindByIndexNameSessionRepository;

/**
 * The browser's HTTP session (Spring Session JDBC). It carries only strings: the user id, the
 * {@code app_session} id, the password-change flag and the absolute expiry. Everything else (scope,
 * permissions) is read from the database on every request, so a session can never hold stale
 * rights. Only strings are stored, so nothing is ever Java-deserialised from the session table.
 */
public final class WebSessions {

    static final String USER_ID = "fci.userId";
    static final String APP_SESSION = "fci.appSession";
    static final String PASSWORD_CHANGE = "fci.passwordChangeRequired";
    static final String ABSOLUTE_EXPIRY = "fci.absoluteExpiresAt";

    /** SHA-256 of the signing-in browser's User-Agent and of its address (hijack detection). */
    static final String CLIENT_AGENT = "fci.clientAgent";

    static final String CLIENT_ADDRESS = "fci.clientAddress";

    /**
     * A sign-in waiting for its OTP (SRS-G-48), in the browser's own pre-login session only: {@code
     * userId|challengeId|issuedAt|role|location|P or M (password or mobile first)|loginId}
     * (strings: the session store keeps strings). Never sent to the client.
     */
    static final String PENDING_LOGIN = "fci.pendingLogin";

    /** Holds a pending sign-in in this browser's (pre-login) session. */
    public static void pendingLogin(
            HttpServletRequest request,
            in.gov.fci.payroll.iam.orchestration.LoginService.PendingLogin pending) {
        request.getSession(true)
                .setAttribute(
                        PENDING_LOGIN,
                        pending.userId()
                                + "|"
                                + pending.challengeId()
                                + "|"
                                + pending.issuedAt()
                                + "|"
                                + (pending.roleCode() == null ? "" : pending.roleCode())
                                + "|"
                                + (pending.locationId() == null ? "" : pending.locationId())
                                + "|"
                                + (pending.passwordless() ? "M" : "P")
                                + "|"
                                + pending.loginId());
    }

    /** The pending sign-in of this browser, if any (an unreadable value counts as none). */
    public static Optional<in.gov.fci.payroll.iam.orchestration.LoginService.PendingLogin>
            pendingLogin(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Object value = session == null ? null : session.getAttribute(PENDING_LOGIN);
        if (!(value instanceof String text)) {
            return Optional.empty();
        }
        String[] p = text.split("\\|", 7);
        if (p.length != 7 || !("M".equals(p[5]) || "P".equals(p[5]))) {
            return Optional.empty();
        }
        try {
            return Optional.of(
                    new in.gov.fci.payroll.iam.orchestration.LoginService.PendingLogin(
                            Long.parseLong(p[0]),
                            p[6],
                            Long.parseLong(p[1]),
                            p[3].isEmpty() ? null : p[3],
                            p[4].isEmpty() ? null : Long.valueOf(p[4]),
                            java.time.Instant.parse(p[2]),
                            "M".equals(p[5])));
        } catch (RuntimeException unreadable) {
            return Optional.empty();
        }
    }

    /** Forgets this browser's pending sign-in (completed, expired or abandoned). */
    public static void clearPendingLogin(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.removeAttribute(PENDING_LOGIN);
        }
    }

    private WebSessions() {}

    /** The signed-in user of this request's session, if any. */
    public static Optional<SessionUser> user(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return Optional.empty();
        }
        Object userId = session.getAttribute(USER_ID);
        Object appSession = session.getAttribute(APP_SESSION);
        if (!(userId instanceof String id) || !(appSession instanceof String sid)) {
            return Optional.empty();
        }
        return Optional.of(
                new SessionUser(
                        Long.valueOf(id),
                        sid,
                        "true".equals(session.getAttribute(PASSWORD_CHANGE))));
    }

    static final String WORKING_PERIOD = "fci.workingPeriod";

    /** The payroll period the session chose to work on (top-bar selector), if any. */
    public static Optional<java.time.YearMonth> workingPeriod(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Object value = session == null ? null : session.getAttribute(WORKING_PERIOD);
        return value instanceof String text
                ? Optional.of(java.time.YearMonth.parse(text))
                : Optional.empty();
    }

    /** Records the session's working period (validated by the caller against the calendar). */
    public static void workingPeriod(HttpServletRequest request, java.time.YearMonth period) {
        request.getSession().setAttribute(WORKING_PERIOD, period.toString());
    }

    /** When the session ends at the latest (8-hour cap), if signed in. */
    public static Optional<java.time.Instant> absoluteExpiresAt(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Object value = session == null ? null : session.getAttribute(ABSOLUTE_EXPIRY);
        return value instanceof String text
                ? Optional.of(java.time.Instant.parse(text))
                : Optional.empty();
    }

    /**
     * Sets the idle timeout of this request's session; it follows the acting scope (SRS-SEC-14:
     * eSeva members 10 minutes, payroll users 15).
     */
    public static void idleTimeout(HttpServletRequest request, java.time.Duration timeout) {
        request.getSession().setMaxInactiveInterval((int) timeout.toSeconds());
    }

    /** The idle timeout of this request's session, in seconds (0 without a session). */
    public static int idleTimeoutSeconds(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return session == null ? 0 : session.getMaxInactiveInterval();
    }

    /**
     * After a successful sign-in: a NEW session id (session-fixation protection), the values, and
     * the principal-name index used by the single-session policy.
     */
    public static void signIn(HttpServletRequest request, SessionService.StartedSession started) {
        HttpSession existing = request.getSession(false);
        if (existing != null) {
            existing.invalidate();
        }
        HttpSession session = request.getSession(true);
        session.setAttribute(USER_ID, String.valueOf(started.userId()));
        session.setAttribute(APP_SESSION, started.sessionId());
        session.setAttribute(PASSWORD_CHANGE, String.valueOf(started.passwordChangeRequired()));
        session.setAttribute(ABSOLUTE_EXPIRY, started.absoluteExpiresAt().toString());
        session.setAttribute(CLIENT_AGENT, agent(request));
        session.setAttribute(CLIENT_ADDRESS, address(request));
        // Idle timeout from the A-07 settings (ADM-SYS-001): SESSION_IDLE_TIMEOUT, or
        // ESEVA_SESSION_IDLE_TIMEOUT in the eSeva scope (SRS-SEC-14).
        idleTimeout(request, started.idleTimeout());
        session.setAttribute(
                FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME,
                String.valueOf(started.userId()));
    }

    /**
     * Whether the request comes from another client than the one that signed in: another browser
     * (User-Agent), or with {@code bindAddress} another address. A session cookie replayed from
     * another machine or browser (session hijacking) is therefore refused even though the cookie is
     * valid. Sessions started before the binding existed carry no fingerprint and are not judged.
     */
    public static boolean clientChanged(HttpServletRequest request, boolean bindAddress) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return false;
        }
        Object agent = session.getAttribute(CLIENT_AGENT);
        if (agent != null && !agent.equals(agent(request))) {
            return true;
        }
        Object address = session.getAttribute(CLIENT_ADDRESS);
        return bindAddress && address != null && !address.equals(address(request));
    }

    private static String agent(HttpServletRequest request) {
        String value = request.getHeader("User-Agent");
        return SecureTokens.sha256Hex(value == null ? "" : value);
    }

    private static String address(HttpServletRequest request) {
        String value = request.getRemoteAddr();
        return SecureTokens.sha256Hex(value == null ? "" : value);
    }

    /** After a password change: the gate opens and the session id rotates. */
    public static void passwordChanged(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.setAttribute(PASSWORD_CHANGE, "false");
            request.changeSessionId();
        }
    }

    public static void signOut(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }
}
