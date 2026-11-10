package in.gov.fci.payroll.platform.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import in.gov.fci.payroll.platform.error.ProblemResponder;
import in.gov.fci.payroll.platform.logging.MdcFilter;
import in.gov.fci.payroll.platform.logging.MdcSecurityContextFilter;
import in.gov.fci.payroll.platform.logging.ResultSummarizer;
import in.gov.fci.payroll.platform.security.JsonKeyGuardFilter;
import in.gov.fci.payroll.platform.security.ManagementPortFilter;
import jakarta.servlet.DispatcherType;
import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.slf4j.MDC;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.Ordered;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The beans of the shared web infrastructure, called directly: what each one is built with (names,
 * orders, dispatcher types, application name), found by mutation testing.
 */
@ResourceLock("thread-context")
class CommonInfrastructureConfigMutationGapsTest {

    private final CommonInfrastructureConfig config = new CommonInfrastructureConfig();

    private static FciCommonProperties properties(String appName) {
        return new FciCommonProperties(
                appName, "https://errors.fci.gov.in/", 1_048_576, 32, 0, "/actuator", 128);
    }

    @AfterEach
    void clear() {
        MDC.clear();
    }

    private static String appSeenByTheFilter(FilterRegistrationBean<MdcFilter> registration)
            throws Exception {
        AtomicReference<String> app = new AtomicReference<>();
        registration
                .getFilter()
                .doFilter(
                        new MockHttpServletRequest("GET", "/api/v1/ping"),
                        new MockHttpServletResponse(),
                        (request, response) -> app.set(MDC.get("app")));
        return app.get();
    }

    @Test
    void theMdcFilterIsFirstNamedAndCoversRequestErrorAndAsyncDispatches() {
        FilterRegistrationBean<MdcFilter> registration =
                config.fciMdcFilter(properties("fci-payroll"), new MockEnvironment());
        assertThat(registration).isNotNull();
        assertThat(registration.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
        assertThat(registration.getFilterName()).isEqualTo("fciMdcFilter");
        assertThat(registration.determineDispatcherTypes())
                .isEqualTo(
                        EnumSet.of(
                                DispatcherType.REQUEST,
                                DispatcherType.ERROR,
                                DispatcherType.ASYNC));
    }

    @Test
    void theConfiguredApplicationNameIsWrittenToTheLogContext() throws Exception {
        MockEnvironment environment =
                new MockEnvironment().withProperty("spring.application.name", "from-spring");
        assertThat(appSeenByTheFilter(config.fciMdcFilter(properties("fci-payroll"), environment)))
                .isEqualTo("fci-payroll");
    }

    @Test
    void withoutAConfiguredNameTheSpringApplicationNameIsUsedThenApp() throws Exception {
        MockEnvironment environment =
                new MockEnvironment().withProperty("spring.application.name", "from-spring");
        assertThat(appSeenByTheFilter(config.fciMdcFilter(properties(null), environment)))
                .isEqualTo("from-spring");
        assertThat(appSeenByTheFilter(config.fciMdcFilter(properties(null), new MockEnvironment())))
                .isEqualTo("app");
    }

    @Test
    void theManagementPortFilterIsSecondAndNamed() {
        FilterRegistrationBean<ManagementPortFilter> registration =
                config.fciManagementPortFilter(properties("x"));
        assertThat(registration).isNotNull();
        assertThat(registration.getFilter()).isInstanceOf(ManagementPortFilter.class);
        assertThat(registration.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 1);
        assertThat(registration.getFilterName()).isEqualTo("fciManagementPortFilter");
    }

    @Test
    void theJsonKeyGuardIsNamedAndRunsBeforeSecurity() {
        FilterRegistrationBean<JsonKeyGuardFilter> registration =
                config.fciJsonKeyGuardFilter(properties("x"), mock(ProblemResponder.class));
        assertThat(registration.getFilter()).isInstanceOf(JsonKeyGuardFilter.class);
        assertThat(registration.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 10);
        assertThat(registration.getFilterName()).isEqualTo("fciJsonKeyGuardFilter");
    }

    @Test
    void theSimpleBeansAreBuilt() {
        ProblemResponder responder = mock(ProblemResponder.class);
        assertThat(config.fciGlobalExceptionHandler(responder)).isNotNull();
        assertThat(config.eserLoggingAspect(new ResultSummarizer(), properties("x"))).isNotNull();
        assertThat(config.securityAuditLogger()).isNotNull();
        assertThat(config.fciContextPropagatingTaskDecorator()).isNotNull();
        CommonInfrastructureConfig.DataAccessConfiguration dataAccess =
                new CommonInfrastructureConfig.DataAccessConfiguration();
        assertThat(
                        dataAccess.fciDataAccessExceptionHandler(
                                responder, mock(ApplicationEventPublisher.class)))
                .isNotNull();
    }

    @Test
    void theSecurityBeansAreBuiltAndTheMdcSecurityFilterRunsRightAfterTheChain() {
        CommonInfrastructureConfig.SecurityConfiguration security =
                new CommonInfrastructureConfig.SecurityConfiguration();
        ProblemResponder responder = mock(ProblemResponder.class);
        assertThat(security.fciSecurityExceptionHandler(responder)).isNotNull();
        assertThat(security.problemDetailAuthenticationEntryPoint(responder)).isNotNull();
        assertThat(security.problemDetailAccessDeniedHandler(responder)).isNotNull();
        FilterRegistrationBean<MdcSecurityContextFilter> registration =
                security.fciMdcSecurityContextFilter();
        assertThat(registration.getFilter()).isInstanceOf(MdcSecurityContextFilter.class);
        assertThat(registration.getOrder())
                .isEqualTo(CommonInfrastructureConfig.SECURITY_FILTER_ORDER + 1);
        assertThat(registration.getFilterName()).isEqualTo("fciMdcSecurityContextFilter");
    }
}
