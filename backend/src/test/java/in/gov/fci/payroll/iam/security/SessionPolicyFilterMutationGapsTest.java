package in.gov.fci.payroll.iam.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.iam.business.model.AppSession;
import in.gov.fci.payroll.iam.orchestration.AccessContextService;
import in.gov.fci.payroll.iam.orchestration.SessionService;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.ProblemResponder;
import in.gov.fci.payroll.platform.security.SecureTokens;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

/**
 * Mutation gaps of the session check: a replayed cookie (client changed) ends the session, is
 * recorded and answered as expired (OWASP session management); while a password change is due only
 * the allowed paths are reachable (threat E5); an unusable session on a public path goes on.
 */
class SessionPolicyFilterMutationGapsTest {

    private static final String SESSION_ID = "app-session-9f3c";

    private final AccessContextService contexts = mock(AccessContextService.class);
    private final ProblemResponder responder = mock(ProblemResponder.class);
    private final SessionService sessions = mock(SessionService.class);
    private final AuditService audit = mock(AuditService.class);
    private final SessionPolicyFilter filter =
            new SessionPolicyFilter(contexts, responder, sessions, audit, false);

    private static MockHttpSession signedIn(boolean passwordChange) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(WebSessions.USER_ID, "7");
        session.setAttribute(WebSessions.APP_SESSION, SESSION_ID);
        session.setAttribute(WebSessions.PASSWORD_CHANGE, String.valueOf(passwordChange));
        session.setAttribute(WebSessions.CLIENT_AGENT, SecureTokens.sha256Hex("Browser-A"));
        return session;
    }

    private static MockHttpServletRequest request(
            String path, MockHttpSession session, String agent) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setServletPath(path);
        request.setSession(session);
        request.addHeader("User-Agent", agent);
        return request;
    }

    @Test
    void aReplayedCookieEndsTheSessionIsRecordedAndAnsweredAsExpired() throws Exception {
        MockHttpSession session = signedIn(false);
        MockHttpServletRequest replay = request("/employees", session, "Browser-B");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(replay, response, chain);

        verify(sessions, times(1)).end(SESSION_ID, AppSession.EndReason.REVOCATION);
        verify(audit, times(1))
                .securityEvent(
                        AuditService.Security.SESSION_HIJACK_SUSPECTED,
                        "user:7",
                        "app_session",
                        SecureTokens.sha256Hex(SESSION_ID).substring(0, 16),
                        "client changed during the session");
        assertThat(session.isInvalid()).isTrue();
        verify(responder, times(1))
                .write(isNull(), eq(ApiErrorCode.SESSION_EXPIRED), eq(replay), eq(response));
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void theSigningInBrowserIsNotJudgedAsReplayed() throws Exception {
        when(contexts.isUsable(7L, SESSION_ID)).thenReturn(true);
        MockHttpSession session = signedIn(false);
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(
                request("/employees", session, "Browser-A"), new MockHttpServletResponse(), chain);
        verify(chain).doFilter(any(HttpServletRequest.class), any(HttpServletResponse.class));
        verify(sessions, never()).end(any(), any());
        assertThat(session.isInvalid()).isFalse();
    }

    @Test
    void threatE5_whileAPasswordChangeIsDueOtherPathsAreAnsweredPasswordChangeRequired()
            throws Exception {
        when(contexts.isUsable(7L, SESSION_ID)).thenReturn(true);
        MockHttpServletRequest blocked = request("/employees", signedIn(true), "Browser-A");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(blocked, response, chain);

        verify(responder, times(1))
                .write(
                        isNull(),
                        eq(ApiErrorCode.PASSWORD_CHANGE_REQUIRED),
                        eq(blocked),
                        eq(response));
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void threatE5_theProfilePasswordChangeLanguageMenuAndI18nStayReachable() throws Exception {
        when(contexts.isUsable(7L, SESSION_ID)).thenReturn(true);
        for (String path :
                new String[] {
                    "/auth/me",
                    "/auth/password/change",
                    "/auth/logout",
                    "/auth/csrf",
                    "/auth/locale",
                    "/menu",
                    "/i18n/en"
                }) {
            FilterChain chain = mock(FilterChain.class);
            filter.doFilter(
                    request(path, signedIn(true), "Browser-A"),
                    new MockHttpServletResponse(),
                    chain);
            verify(chain).doFilter(any(HttpServletRequest.class), any(HttpServletResponse.class));
        }
        verify(responder, never()).write(any(), any(), any(), any());
    }

    @Test
    void anUnusableSessionIsSignedOutAndAnsweredExpiredExceptOnAPublicPath() throws Exception {
        when(contexts.isUsable(7L, SESSION_ID)).thenReturn(false);
        MockHttpSession session = signedIn(false);
        MockHttpServletRequest closed = request("/employees", session, "Browser-A");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(closed, response, chain);
        assertThat(session.isInvalid()).isTrue();
        verify(responder, times(1))
                .write(isNull(), eq(ApiErrorCode.SESSION_EXPIRED), eq(closed), eq(response));
        verify(chain, never()).doFilter(any(), any());

        MockHttpSession again = signedIn(false);
        FilterChain publicChain = mock(FilterChain.class);
        filter.doFilter(
                request("/auth/login", again, "Browser-A"),
                new MockHttpServletResponse(),
                publicChain);
        assertThat(again.isInvalid()).isTrue();
        verify(publicChain).doFilter(any(HttpServletRequest.class), any(HttpServletResponse.class));
    }
}
