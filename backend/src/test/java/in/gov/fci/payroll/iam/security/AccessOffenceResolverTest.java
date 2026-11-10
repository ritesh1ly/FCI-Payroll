package in.gov.fci.payroll.iam.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.iam.orchestration.SecurityOffences;
import in.gov.fci.payroll.iam.orchestration.SecurityOffences.Action;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import jakarta.servlet.DispatcherType;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.Ordered;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.servlet.HandlerMapping;

/** OD-130: what is an offence, and what the response says. */
class AccessOffenceResolverTest {

    private final SecurityOffences offences = mock(SecurityOffences.class);
    private final AccessOffenceResolver resolver = new AccessOffenceResolver(offences);
    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x/42");
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @BeforeEach
    void signedIn() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(WebSessions.USER_ID, "7");
        session.setAttribute(WebSessions.APP_SESSION, "token");
        session.setAttribute(WebSessions.PASSWORD_CHANGE, "false");
        request.setSession(session);
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/v1/employees/{eis}");
        ended(true);
    }

    private void ended(boolean sessionEnded) {
        when(offences.handle(any()))
                .thenReturn(
                        new SecurityOffences.Outcome(
                                1, sessionEnded ? Action.LOGOUT : Action.LOGGED, sessionEnded));
    }

    private static FciException denied() {
        return new FciException(
                        ApiErrorCode.PERMISSION_DENIED,
                        new Object[] {"M-01", "VIEW"},
                        List.of(),
                        null)
                .asAccessOffence();
    }

    @Test
    void SRS_G_25_OD_130_aPermissionRefusalIsAnOffenceAndTheResponseSaysTheSessionEnded() {
        assertThat(resolver.resolveException(request, response, null, denied())).isNull();

        ArgumentCaptor<SecurityOffences.Refusal> refusal =
                ArgumentCaptor.forClass(SecurityOffences.Refusal.class);
        verify(offences).handle(refusal.capture());
        assertThat(refusal.getValue())
                .isEqualTo(
                        new SecurityOffences.Refusal(
                                7L,
                                SecurityOffences.sessionRef("token"),
                                "GET",
                                "/v1/employees/{eis}",
                                "M-01",
                                "VIEW",
                                "AUTHZ-403-PERMISSION"));
        assertThat(response.getHeader(AccessOffenceResolver.SESSION_ENDED_HEADER))
                .isEqualTo("AUTH-401-OFFENCE");
    }

    @Test
    void OD_130_anOutOfScopeRefusalIsAnOffence() {
        resolver.resolveException(
                request,
                response,
                null,
                new FciException(ApiErrorCode.OUT_OF_SCOPE).asAccessOffence());

        verify(offences).handle(any());
    }

    @Test
    void OD_130_theMethodSecurityDenialOfAnExpressionIsAnOffence() {
        resolver.resolveException(request, response, null, new AccessDeniedException("no"));

        ArgumentCaptor<SecurityOffences.Refusal> refusal =
                ArgumentCaptor.forClass(SecurityOffences.Refusal.class);
        verify(offences).handle(refusal.capture());
        assertThat(refusal.getValue().screen()).isEqualTo("-");
        assertThat(refusal.getValue().action()).isEqualTo("-");
        assertThat(refusal.getValue().code()).isEqualTo(CommonErrorCode.ACCESS_DENIED.code());
    }

    @Test
    void OD_130_aRecordOutsideTheScopeStaysA404ByteForByteAndTheNextCallSaysWhy() {
        FciException notFound = new FciException(CommonErrorCode.NOT_FOUND).asAccessOffence();

        assertThat(resolver.resolveException(request, response, null, notFound)).isNull();

        verify(offences).handle(any());
        assertThat(response.getHeaderNames()).isEmpty();
        assertThat(response.getContentAsByteArray()).isEmpty();
    }

    @Test
    void OD_130_aMissingRecordIsNoOffence() {
        resolver.resolveException(
                request, response, null, new FciException(CommonErrorCode.NOT_FOUND));

        verify(offences, never()).handle(any());
    }

    @Test
    void OD_130_otherRefusalsAreNoOffence() {
        for (Exception other :
                List.of(
                        new FciException(ApiErrorCode.READ_ONLY_CONSOLIDATED),
                        new FciException(ApiErrorCode.STEP_UP_REQUIRED),
                        new FciException(CommonErrorCode.VALIDATION_FAILED),
                        new IllegalStateException("boom"))) {
            resolver.resolveException(request, response, null, other);
        }

        verify(offences, never()).handle(any());
    }

    @Test
    void OD_130_aRefusalWithoutABrowserSessionIsNoOffence() {
        MockHttpServletRequest machine = new MockHttpServletRequest("GET", "/hrms/x");

        resolver.resolveException(machine, response, null, denied());

        verify(offences, never()).handle(any());
    }

    @Test
    void OD_130_anErrorDispatchIsNotCountedAgain() {
        request.setDispatcherType(DispatcherType.ERROR);

        resolver.resolveException(request, response, null, denied());

        verify(offences, never()).handle(any());
    }

    @Test
    void OD_130_oneRequestCountsOnce() {
        resolver.resolveException(request, response, null, denied());
        resolver.resolveException(request, response, null, denied());

        verify(offences, times(1)).handle(any());
    }

    @Test
    void OD_130_withLogoutOffTheResponseAndTheSessionStayAsTheyAre() {
        ended(false);

        resolver.resolveException(request, response, null, denied());

        assertThat(response.getHeader(AccessOffenceResolver.SESSION_ENDED_HEADER)).isNull();
    }

    @Test
    void OD_130_anUnmappedRouteIsNamedWithoutItsValues() {
        request.removeAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);

        resolver.resolveException(request, response, null, denied());

        ArgumentCaptor<SecurityOffences.Refusal> refusal =
                ArgumentCaptor.forClass(SecurityOffences.Refusal.class);
        verify(offences).handle(refusal.capture());
        assertThat(refusal.getValue().pathPattern()).isEqualTo("unmatched");
    }

    @Test
    void OD_130_itRunsBeforeTheHandlersThatWriteTheResponse() {
        assertThat(resolver.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    }
}
