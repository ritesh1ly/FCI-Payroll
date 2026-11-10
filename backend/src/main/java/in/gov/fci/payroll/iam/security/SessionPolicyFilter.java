package in.gov.fci.payroll.iam.security;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.iam.business.model.AppSession;
import in.gov.fci.payroll.iam.orchestration.AccessContextService;
import in.gov.fci.payroll.iam.orchestration.SessionService;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.ProblemResponder;
import in.gov.fci.payroll.platform.security.SecureTokens;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.Set;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

/**
 * The session check of every request (in the Spring Security chain, after the context is loaded):
 *
 * <ul>
 *   <li>the {@code app_session} named by the cookie must still be usable: open, within the absolute
 *       timeout, account active. Otherwise the HTTP session is dropped and the call answered 401
 *       (sign-out, lock, deactivation and the 8-hour cap take effect on the next request);
 *   <li>while a password change is required only the profile, the password change, the language and
 *       sign-out are reachable (threat E5);
 *   <li>the request must come from the client that signed in (same browser, and with {@code
 *       fci.api.session.bind-client-address} the same address): a valid cookie replayed elsewhere
 *       ends the session, is answered like an expired one and is recorded as a security event
 *       (session hijacking, OWASP Session Management Cheat Sheet).
 * </ul>
 *
 * The OTP step-up of sensitive screens is checked per screen by the authorisation gate ({@code
 * AccessPolicy.gate}).
 *
 * <p>The idle timeout (15 minutes) is the HTTP session's own.
 */
public class SessionPolicyFilter extends OncePerRequestFilter {

    private static final UrlPathHelper PATHS = new UrlPathHelper();
    private static final Set<String> PASSWORD_CHANGE_ALLOWED =
            Set.of(
                    "/auth/me",
                    "/auth/password/change",
                    "/auth/logout",
                    "/auth/csrf",
                    "/auth/locale",
                    "/menu");

    private final AccessContextService contexts;
    private final ProblemResponder responder;
    private final SessionService sessions;
    private final AuditService audit;
    private final boolean bindAddress;

    public SessionPolicyFilter(
            AccessContextService contexts,
            ProblemResponder responder,
            SessionService sessions,
            AuditService audit,
            boolean bindAddress) {
        this.contexts = contexts;
        this.responder = responder;
        this.sessions = sessions;
        this.audit = audit;
        this.bindAddress = bindAddress;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<SessionUser> user = WebSessions.user(request);
        String path = PATHS.getPathWithinApplication(request);
        if (user.isPresent()) {
            if (WebSessions.clientChanged(request, bindAddress)) {
                sessions.end(user.get().sessionId(), AppSession.EndReason.REVOCATION);
                audit.securityEvent(
                        AuditService.Security.SESSION_HIJACK_SUSPECTED,
                        "user:" + user.get().userId(),
                        "app_session",
                        SecureTokens.sha256Hex(user.get().sessionId()).substring(0, 16),
                        "client changed during the session");
                WebSessions.signOut(request);
                responder.write(null, ApiErrorCode.SESSION_EXPIRED, request, response);
                return;
            }
            if (!contexts.isUsable(user.get().userId(), user.get().sessionId())) {
                WebSessions.signOut(request);
                if (!SecurityConfig.isPublic(path)) {
                    // OD-130: the 401 says why. Read from the records: a write to the HTTP session
                    // here would race with the page's other reads in the session store.
                    responder.write(
                            null,
                            sessions.endedForOffence(user.get().userId(), user.get().sessionId())
                                    ? ApiErrorCode.SESSION_ENDED_OFFENCE
                                    : ApiErrorCode.SESSION_EXPIRED,
                            request,
                            response);
                    return;
                }
            } else if (user.get().passwordChangeRequired()
                    && !PASSWORD_CHANGE_ALLOWED.contains(path)
                    && !path.startsWith("/i18n")) {
                responder.write(null, ApiErrorCode.PASSWORD_CHANGE_REQUIRED, request, response);
                return;
            }
        } else if (request.getRequestedSessionId() != null
                && !request.isRequestedSessionIdValid()
                && !SecurityConfig.isPublic(path)) {
            // SRS-SEC-14: the session store dropped an idle session before the session policy
            // saw it (its inactivity limit is the idle timeout): the request still names that
            // session, so it is answered as expired, not as never signed in.
            responder.write(null, ApiErrorCode.SESSION_EXPIRED, request, response);
            return;
        }
        chain.doFilter(request, response);
    }
}
