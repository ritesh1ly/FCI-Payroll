package in.gov.fci.payroll.iam.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.iam.orchestration.AccessContextService;
import in.gov.fci.payroll.iam.orchestration.SessionService;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.ProblemResponder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Branch gaps of the session security layer (SRS-SEC-13, SRS-SEC-14): the public-path test, the
 * session policy filter's public and i18n exemptions, the deferred security context and the
 * current-user lookup.
 */
@ResourceLock("thread-context")
class SecurityBranchGapsTest {

    private final AccessContextService contexts = mock(AccessContextService.class);
    private final ProblemResponder responder = mock(ProblemResponder.class);
    private final SessionPolicyFilter filter =
            new SessionPolicyFilter(
                    contexts,
                    responder,
                    mock(SessionService.class),
                    mock(AuditService.class),
                    false);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static MockHttpServletRequest signedIn(String path, boolean passwordChange) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setServletPath(path);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(WebSessions.USER_ID, "42");
        session.setAttribute(WebSessions.APP_SESSION, "app-session-1");
        session.setAttribute(WebSessions.PASSWORD_CHANGE, String.valueOf(passwordChange));
        request.setSession(session);
        return request;
    }

    @Test
    void SRS_SEC_14_publicPathsAreTheListAndTheWholeI18nTreeOnly() {
        assertThat(SecurityConfig.isPublic("/auth/login")).isTrue();
        assertThat(SecurityConfig.isPublic("/i18n")).isTrue();
        assertThat(SecurityConfig.isPublic("/i18n/hi")).isTrue();
        assertThat(SecurityConfig.isPublic("/i18nx")).as("a prefix is not the tree").isFalse();
        assertThat(SecurityConfig.isPublic("/auth/me")).isFalse();
    }

    @Test
    void SRS_SEC_14_anUnusableSessionIsSignedOutAndGoesOnOnlyToAPublicPath() throws Exception {
        when(contexts.isUsable(42L, "app-session-1")).thenReturn(false);
        MockHttpServletRequest request = signedIn("/auth/login", false);
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        verify(chain).doFilter(any(HttpServletRequest.class), any(HttpServletResponse.class));
        assertThat(WebSessions.user(request)).as("signed out").isEmpty();

        FilterChain closed = mock(FilterChain.class);
        MockHttpServletRequest privatePath = signedIn("/auth/me", false);
        filter.doFilter(privatePath, new MockHttpServletResponse(), closed);
        verify(closed, never()).doFilter(any(), any());
        verify(responder).write(any(), eq(ApiErrorCode.SESSION_EXPIRED), eq(privatePath), any());

        when(contexts.isUsable(42L, "app-session-1")).thenReturn(true);
        FilterChain open = mock(FilterChain.class);
        filter.doFilter(signedIn("/employees", false), new MockHttpServletResponse(), open);
        verify(open).doFilter(any(HttpServletRequest.class), any(HttpServletResponse.class));
    }

    @Test
    void SRS_SEC_13_aPendingPasswordChangeStillReadsTheLabels() throws Exception {
        when(contexts.isUsable(42L, "app-session-1")).thenReturn(true);
        FilterChain labels = mock(FilterChain.class);
        filter.doFilter(signedIn("/i18n/hi", true), new MockHttpServletResponse(), labels);
        verify(labels).doFilter(any(HttpServletRequest.class), any(HttpServletResponse.class));

        FilterChain allowed = mock(FilterChain.class);
        filter.doFilter(signedIn("/auth/me", true), new MockHttpServletResponse(), allowed);
        verify(allowed).doFilter(any(HttpServletRequest.class), any(HttpServletResponse.class));

        FilterChain other = mock(FilterChain.class);
        filter.doFilter(signedIn("/employees", true), new MockHttpServletResponse(), other);
        verify(other, never()).doFilter(any(), any());
    }

    @Test
    void SRS_SEC_13_theDeferredContextIsGeneratedOnlyWithoutASignedInUser() {
        SessionSecurityContextRepository repository = new SessionSecurityContextRepository();
        var anonymous = repository.loadDeferredContext(new MockHttpServletRequest());
        assertThat(anonymous.isGenerated()).isTrue();
        assertThat(anonymous.get()).as("cached").isSameAs(anonymous.get());

        var signed = repository.loadDeferredContext(signedIn("/auth/me", false));
        assertThat(signed.isGenerated()).isFalse();
        assertThat(signed.get().getAuthentication().getPrincipal())
                .isEqualTo(new SessionUser(42L, "app-session-1", false));
    }

    @Test
    void SRS_SEC_13_theCurrentUserIsOnlyAnAuthenticatedSessionPrincipal() {
        SecurityContextCurrentUser current = new SecurityContextCurrentUser();
        assertThat(current.userId()).as("no authentication").isEmpty();

        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                new SessionUser(7L, "s-7", false), null));
        assertThat(current.userId()).as("not authenticated").isEmpty();

        SecurityContextHolder.getContext()
                .setAuthentication(new TestingAuthenticationToken("metrics", null, "METRICS"));
        assertThat(current.userId()).as("another principal type").isEmpty();

        TestingAuthenticationToken session =
                new TestingAuthenticationToken(
                        new SessionUser(7L, "s-7", false), null, "ROLE_USER");
        SecurityContextHolder.getContext().setAuthentication(session);
        assertThat(current.userId()).contains("7");
        assertThat(current.sessionId()).contains("s-7");
    }
}
