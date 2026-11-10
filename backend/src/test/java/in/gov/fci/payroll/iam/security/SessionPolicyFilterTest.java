package in.gov.fci.payroll.iam.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.iam.orchestration.AccessContextService;
import in.gov.fci.payroll.iam.orchestration.SessionService;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.ProblemResponder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * SRS-SEC-14: a request that names a session the store has already dropped (idle beyond its
 * inactivity limit) is answered SESSION_EXPIRED, not as never signed in; a request without a
 * session, or one to a public path, goes on.
 */
class SessionPolicyFilterTest {

    private final ProblemResponder responder = mock(ProblemResponder.class);
    private final SessionPolicyFilter filter =
            new SessionPolicyFilter(
                    mock(AccessContextService.class),
                    responder,
                    mock(SessionService.class),
                    mock(AuditService.class),
                    false);

    private static MockHttpServletRequest request(String path, String sessionId) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setServletPath(path);
        if (sessionId != null) {
            request.setRequestedSessionId(sessionId);
            request.setRequestedSessionIdValid(false);
        }
        return request;
    }

    @Test
    void SRS_SEC_14_aDroppedSessionIsAnsweredAsExpired() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest stale = request("/auth/me", "gone-session");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(stale, response, chain);
        verify(responder)
                .write(isNull(), eq(ApiErrorCode.SESSION_EXPIRED), eq(stale), eq(response));
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void SRS_SEC_14_noSessionOrAPublicPathGoesOn() throws Exception {
        FilterChain none = mock(FilterChain.class);
        MockHttpServletRequest fresh = request("/auth/me", null);
        filter.doFilter(fresh, new MockHttpServletResponse(), none);
        verify(none).doFilter(any(HttpServletRequest.class), any(HttpServletResponse.class));

        FilterChain login = mock(FilterChain.class);
        filter.doFilter(
                request("/auth/login", "gone-session"), new MockHttpServletResponse(), login);
        verify(login).doFilter(any(HttpServletRequest.class), any(HttpServletResponse.class));

        MockHttpServletRequest valid = request("/auth/me", null);
        valid.setRequestedSessionId("live");
        valid.setRequestedSessionIdValid(true);
        FilterChain live = mock(FilterChain.class);
        filter.doFilter(valid, new MockHttpServletResponse(), live);
        verify(live).doFilter(any(HttpServletRequest.class), any(HttpServletResponse.class));
        verify(responder, never()).write(any(), eq(ApiErrorCode.SESSION_EXPIRED), eq(fresh), any());
    }
}
