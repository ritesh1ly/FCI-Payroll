package in.gov.fci.payroll.iam.security;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.iam.orchestration.AccessContextService;
import in.gov.fci.payroll.iam.orchestration.SessionService;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.ProblemResponder;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

/** OD-130: the 401 after a session ended says why, read from the records. */
class SessionPolicyFilterOffenceTest {

    private final AccessContextService contexts = mock(AccessContextService.class);
    private final ProblemResponder responder = mock(ProblemResponder.class);
    private final SessionService sessions = mock(SessionService.class);
    private final SessionPolicyFilter filter =
            new SessionPolicyFilter(contexts, responder, sessions, mock(AuditService.class), false);
    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/x");
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @BeforeEach
    void endedSession() {
        request.setServletPath("/v1/x");
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(WebSessions.USER_ID, "7");
        session.setAttribute(WebSessions.APP_SESSION, "token");
        session.setAttribute(WebSessions.PASSWORD_CHANGE, "false");
        request.setSession(session);
        when(contexts.isUsable(7L, "token")).thenReturn(false);
    }

    @Test
    void OD_130_theNextCallAfterAnOffenceIsThe401ThatSaysWhy() throws Exception {
        when(sessions.endedForOffence(7L, "token")).thenReturn(true);

        filter.doFilter(request, response, mock(FilterChain.class));

        verify(responder)
                .write(isNull(), eq(ApiErrorCode.SESSION_ENDED_OFFENCE), eq(request), eq(response));
    }

    @Test
    void OD_130_anySessionEndedOtherwiseStaysAnOrdinaryExpiry() throws Exception {
        filter.doFilter(request, response, mock(FilterChain.class));

        verify(responder)
                .write(isNull(), eq(ApiErrorCode.SESSION_EXPIRED), eq(request), eq(response));
    }
}
