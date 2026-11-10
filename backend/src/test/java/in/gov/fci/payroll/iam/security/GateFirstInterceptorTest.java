package in.gov.fci.payroll.iam.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.iam.orchestration.AccessPolicy;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.method.HandlerMethod;

/**
 * SRS-G-25 / SRS-22-8 (deny by default, ASVS V4.1.1 / V4.1.5): the endpoint gate answers before the
 * request is read, with the gate's own refusal.
 */
class GateFirstInterceptorTest {

    private final AccessPolicy policy = mock(AccessPolicy.class);
    private final GateFirstInterceptor interceptor = new GateFirstInterceptor(policy);
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    static class Handlers {
        @PreAuthorize("@authz.gate('M-01','EDIT')")
        public void gated() {}

        @PreAuthorize("@authz.gateEither( 'M-08' , 'CREATE','M-08','APPROVE' )")
        public void either() {}

        @PreAuthorize("@authz.can('A-05','VIEW')")
        public void can() {}

        @PreAuthorize("@authz.gate(#request.screen(), #request.action())")
        public void dynamic() {}

        @PreAuthorize("isAuthenticated()")
        public void open() {}

        public void unannotated() {}
    }

    @PreAuthorize("@authz.gate('X-02','VIEW')")
    static class ClassGated {
        public void inherited() {}

        @PreAuthorize("permitAll()")
        public void own() {}
    }

    private static HandlerMethod handler(Object bean, String name) throws Exception {
        return new HandlerMethod(bean, bean.getClass().getMethod(name));
    }

    private static MockHttpServletRequest request() {
        return new MockHttpServletRequest("POST", "/v1/x");
    }

    @Test
    void SRS_G_25_aStaticGateIsCheckedBeforeTheRequestIsRead() throws Exception {
        when(policy.gate("M-01", "EDIT"))
                .thenThrow(
                        new FciException(
                                ApiErrorCode.PERMISSION_DENIED,
                                new Object[] {"M-01", "EDIT"},
                                java.util.List.of(),
                                null));
        HandlerMethod gated = handler(new Handlers(), "gated");
        assertThatThrownBy(() -> interceptor.preHandle(request(), response, gated))
                .isInstanceOf(FciException.class);
        // The parsed gate is kept: the second request is gated the same way.
        assertThatThrownBy(() -> interceptor.preHandle(request(), response, gated))
                .isInstanceOf(FciException.class);
        verify(policy, times(2)).gate("M-01", "EDIT");
    }

    @Test
    void SRS_G_25_aHeldGatePassesAndEitherFormIsAskedAsSuch() throws Exception {
        when(policy.gate("M-01", "EDIT")).thenReturn(true);
        assertThat(interceptor.preHandle(request(), response, handler(new Handlers(), "gated")))
                .isTrue();
        when(policy.gateEither("M-08", "CREATE", "M-08", "APPROVE")).thenReturn(true);
        assertThat(interceptor.preHandle(request(), response, handler(new Handlers(), "either")))
                .isTrue();
        verify(policy).gateEither("M-08", "CREATE", "M-08", "APPROVE");
    }

    @Test
    void SRS_G_25_aFalseCanIsTheGenericRefusal() throws Exception {
        HandlerMethod can = handler(new Handlers(), "can");
        when(policy.can("A-05", "VIEW")).thenReturn(false);
        assertThatThrownBy(() -> interceptor.preHandle(request(), response, can))
                .isInstanceOf(AccessDeniedException.class);
        when(policy.can("A-05", "VIEW")).thenReturn(true);
        assertThat(interceptor.preHandle(request(), response, can)).isTrue();
    }

    @Test
    void SRS_G_25_theClassGateAppliesUnlessTheMethodHasItsOwn() throws Exception {
        when(policy.gate("X-02", "VIEW")).thenReturn(true);
        assertThat(
                        interceptor.preHandle(
                                request(), response, handler(new ClassGated(), "inherited")))
                .isTrue();
        verify(policy).gate("X-02", "VIEW");
        assertThat(interceptor.preHandle(request(), response, handler(new ClassGated(), "own")))
                .isTrue();
        verify(policy, times(1)).gate(anyString(), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"dynamic", "open", "unannotated"})
    void SRS_G_25_aDynamicOrOpenExpressionIsLeftToMethodSecurity(String name) throws Exception {
        assertThat(interceptor.preHandle(request(), response, handler(new Handlers(), name)))
                .isTrue();
        verify(policy, never()).gate(anyString(), anyString());
        verify(policy, never()).can(anyString(), anyString());
    }

    @Test
    void SRS_G_25_asyncAndErrorDispatchesAndNonMethodHandlersAreNotAskedAgain() throws Exception {
        for (DispatcherType type :
                new DispatcherType[] {DispatcherType.ASYNC, DispatcherType.ERROR}) {
            MockHttpServletRequest again = request();
            again.setDispatcherType(type);
            assertThat(interceptor.preHandle(again, response, handler(new Handlers(), "gated")))
                    .isTrue();
        }
        assertThat(interceptor.preHandle(request(), response, new Object())).isTrue();
        verify(policy, never()).gate(anyString(), anyString());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "@authz.gate('M-01','EDIT') or true",
                "@authz.gate('m-01','EDIT')",
                "@authz.gate('M-01')",
                "@authz.gateEither('M-01','EDIT')",
                "@authz.can('M-01','edit')",
                "hasRole('ADMIN')"
            })
    void SRS_G_25_onlyTheExactStaticFormsAreEvaluatedEarly(String expression) {
        assertThat(GateFirstInterceptor.parse(expression))
                .isInstanceOf(GateFirstInterceptor.None.class);
    }

    @Test
    void SRS_G_25_theStaticFormsAreParsed() {
        assertThat(GateFirstInterceptor.parse("@authz.gate('M-01','EDIT')"))
                .isEqualTo(new GateFirstInterceptor.Single("M-01", "EDIT"));
        assertThat(
                        GateFirstInterceptor.parse(
                                "@authz.gateEither('M-08','CREATE','I-04','APPROVE')"))
                .isEqualTo(new GateFirstInterceptor.Either("M-08", "CREATE", "I-04", "APPROVE"));
        assertThat(GateFirstInterceptor.parse("@authz.can('A-05','VIEW')"))
                .isEqualTo(new GateFirstInterceptor.Can("A-05", "VIEW"));
    }
}
