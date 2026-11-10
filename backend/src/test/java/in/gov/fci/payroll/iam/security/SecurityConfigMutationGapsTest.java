package in.gov.fci.payroll.iam.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.iam.orchestration.AccessContextService;
import in.gov.fci.payroll.iam.orchestration.SessionService;
import in.gov.fci.payroll.platform.config.ApiProperties;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.ErrorCode;
import in.gov.fci.payroll.platform.error.ProblemResponder;
import in.gov.fci.payroll.platform.security.ProblemDetailAccessDeniedHandler;
import in.gov.fci.payroll.platform.security.ProblemDetailAuthenticationEntryPoint;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;

/**
 * The filter chains of {@link SecurityConfig} (SRS-G-48, SRS-SEC-4, SRS-SEC-14, WSTG-ATHZ-01),
 * built in a small web context with the collaborators mocked: which paths are open, what the
 * metrics credential may reach, the CSRF cookie attributes, the security headers and the neutral
 * answer to a rejected request.
 */
class SecurityConfigMutationGapsTest {

    private static final String METRICS_PASSWORD = "Metrics#Pass-2026";

    /** The collaborators of SecurityConfig, mocked; refusals answer their real status. */
    @Configuration
    @EnableWebSecurity
    @Import(SecurityConfig.class)
    static class TestBeans {
        static ApiProperties properties = bind(Map.of());

        @Bean
        ApiProperties apiProperties() {
            return properties;
        }

        @Bean
        ProblemResponder problemResponder() {
            ProblemResponder responder = mock(ProblemResponder.class);
            try {
                doAnswer(
                                call -> {
                                    ErrorCode code = call.getArgument(1);
                                    HttpServletResponse response = call.getArgument(3);
                                    response.setStatus(code.status().value());
                                    return null;
                                })
                        .when(responder)
                        .write(any(), any(), any(), any());
            } catch (java.io.IOException impossible) {
                throw new IllegalStateException(impossible);
            }
            return responder;
        }

        @Bean
        ProblemDetailAccessDeniedHandler deniedHandler() throws Exception {
            ProblemDetailAccessDeniedHandler handler = mock(ProblemDetailAccessDeniedHandler.class);
            doAnswer(
                            call -> {
                                ((HttpServletResponse) call.getArgument(1)).setStatus(403);
                                return null;
                            })
                    .when(handler)
                    .handle(any(), any(), any());
            return handler;
        }

        @Bean
        ProblemDetailAuthenticationEntryPoint entryPoint() throws Exception {
            ProblemDetailAuthenticationEntryPoint entry =
                    mock(ProblemDetailAuthenticationEntryPoint.class);
            doAnswer(
                            call -> {
                                ((HttpServletResponse) call.getArgument(1)).setStatus(401);
                                return null;
                            })
                    .when(entry)
                    .commence(any(), any(), any());
            return entry;
        }

        @Bean
        AccessContextService accessContextService() {
            return mock(AccessContextService.class);
        }

        @Bean
        SessionService sessionService() {
            return mock(SessionService.class);
        }

        @Bean
        AuditService auditService() {
            return mock(AuditService.class);
        }
    }

    private static ApiProperties bind(Map<String, Object> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bindOrCreate("fci.api", ApiProperties.class);
    }

    private AnnotationConfigWebApplicationContext context;

    @AfterEach
    void close() {
        if (context != null) {
            context.close();
        }
        TestBeans.properties = bind(Map.of());
    }

    private MockMvc mvc(
            Map<String, Object> apiSettings, Map<String, Object> env, String... profiles) {
        TestBeans.properties = bind(apiSettings);
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.getEnvironment().setActiveProfiles(profiles);
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("t", env));
        context.register(TestBeans.class);
        context.refresh();
        return MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private MockMvc online() {
        return mvc(Map.of(), Map.of(), "online");
    }

    private static String basic(String user, String password) {
        return "Basic "
                + Base64.getEncoder()
                        .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, Object> metricsEnv() {
        return Map.of("fci.api.management.password-hash", "{noop}" + METRICS_PASSWORD);
    }

    private static int get(MockMvc mvc, String path, String authorization) throws Exception {
        var builder = MockMvcRequestBuilders.get(path);
        if (authorization != null) {
            builder.header("Authorization", authorization);
        }
        return mvc.perform(builder).andReturn().getResponse().getStatus();
    }

    // ---- the actuator chain ----

    @Test
    void SRS_SEC_4_healthAndInfoAreOpenMetricsNeedTheCredential() throws Exception {
        MockMvc mvc = mvc(Map.of(), metricsEnv(), "online");
        assertThat(get(mvc, "/actuator/health", null)).isEqualTo(404);
        assertThat(get(mvc, "/actuator/info", null)).isEqualTo(404);
        assertThat(get(mvc, "/actuator/metrics/jvm.memory.used", null)).isEqualTo(401);
        assertThat(get(mvc, "/actuator/prometheus", null)).isEqualTo(401);
        assertThat(get(mvc, "/actuator/prometheus", basic("metrics", METRICS_PASSWORD)))
                .as("authenticated, no handler behind it")
                .isEqualTo(404);
        assertThat(get(mvc, "/actuator/prometheus", basic("metrics", "wrong"))).isEqualTo(401);
        assertThat(get(mvc, "/actuator/prometheus", basic("other", METRICS_PASSWORD)))
                .isEqualTo(401);
    }

    @Test
    void SRS_SEC_4_withoutAConfiguredHashNoCredentialOpensTheMetrics() throws Exception {
        MockMvc mvc = mvc(Map.of(), Map.of(), "online");
        assertThat(get(mvc, "/actuator/prometheus", basic("metrics", METRICS_PASSWORD)))
                .isEqualTo(401);
        assertThat(get(mvc, "/actuator/prometheus", basic("metrics", ""))).isEqualTo(401);
    }

    @Test
    void SRS_SEC_4_anyOtherActuatorEndpointIsDeniedEvenToTheMetricsUser() throws Exception {
        MockMvc mvc = mvc(Map.of(), metricsEnv(), "online");
        assertThat(get(mvc, "/actuator/env", basic("metrics", METRICS_PASSWORD))).isEqualTo(403);
        assertThat(get(mvc, "/actuator/chaosmonkey", basic("metrics", METRICS_PASSWORD)))
                .as("chaos-monkey profile not active")
                .isEqualTo(403);
    }

    @Test
    void SRS_SEC_4_theChaosMonkeyEndpointIsOpenedOnlyWithItsProfile() throws Exception {
        MockMvc mvc = mvc(Map.of(), metricsEnv(), "online", "chaos-monkey");
        assertThat(get(mvc, "/actuator/chaosmonkey", basic("metrics", METRICS_PASSWORD)))
                .isEqualTo(404);
        assertThat(get(mvc, "/actuator/chaosmonkey/status", basic("metrics", METRICS_PASSWORD)))
                .isEqualTo(404);
        assertThat(get(mvc, "/actuator/chaosmonkey", null)).isEqualTo(401);
    }

    // ---- the browser chain ----

    @Test
    void SRS_G_48_theCsrfCookieIsReadableByTheSpaOnPathSlashAndSameSiteStrict() throws Exception {
        MockMvc mvc = online();
        MvcResult result =
                mvc.perform(MockMvcRequestBuilders.get("/api/auth/csrf").contextPath("/api"))
                        .andReturn();
        jakarta.servlet.http.Cookie cookie = result.getResponse().getCookie("XSRF-TOKEN");
        assertThat(cookie).isNotNull();
        assertThat(cookie.getPath()).as("not the /api context path").isEqualTo("/");
        assertThat(cookie.isHttpOnly()).as("readable by the SPA").isFalse();
        assertThat(cookie.getAttribute("SameSite")).isEqualTo("Strict");
        assertThat(cookie.getSecure()).isTrue();
    }

    @Test
    void SRS_G_48_theCsrfCookieIsNotSecureWhenTheSettingSaysSo() throws Exception {
        MockMvc mvc = mvc(Map.of("fci.api.session.secure-cookie", "false"), Map.of(), "online");
        jakarta.servlet.http.Cookie cookie =
                mvc.perform(MockMvcRequestBuilders.get("/auth/csrf"))
                        .andReturn()
                        .getResponse()
                        .getCookie("XSRF-TOKEN");
        assertThat(cookie).isNotNull();
        assertThat(cookie.getAttribute("SameSite")).isEqualTo("Strict");
        assertThat(cookie.getSecure()).isFalse();
    }

    @Test
    void SRS_G_48_aSignedOutCallerReachesOnlyThePublicPaths() throws Exception {
        MockMvc mvc = online();
        assertThat(get(mvc, "/auth/csrf", null)).isEqualTo(404);
        assertThat(get(mvc, "/i18n/hi", null)).isEqualTo(404);
        assertThat(get(mvc, "/i18n", null)).isEqualTo(404);
        assertThat(get(mvc, "/v1/inbox", null)).isEqualTo(401);
        assertThat(get(mvc, "/auth/me", null)).isEqualTo(401);
        assertThat(get(mvc, "/menu", null)).isEqualTo(401);
    }

    @Test
    void SRS_G_48_aWriteWithoutTheCsrfTokenIsForbiddenAndACrossSiteWriteTooBeforeTheTokenCheck()
            throws Exception {
        MockMvc mvc = online();
        assertThat(
                        mvc.perform(MockMvcRequestBuilders.post("/auth/login"))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(403);
        assertThat(
                        mvc.perform(
                                        MockMvcRequestBuilders.post("/auth/login")
                                                .header("Sec-Fetch-Site", "cross-site"))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(403);
    }

    @Test
    void SRS_SEC_4_everyChainSendsTheSecurityHeaders() throws Exception {
        MockMvc mvc = online();
        for (String path : List.of("/v1/inbox", "/actuator/prometheus", "/something-else")) {
            MockHttpServletResponse response =
                    mvc.perform(MockMvcRequestBuilders.get(path).secure(true))
                            .andReturn()
                            .getResponse();
            assertThat(response.getHeader("Content-Security-Policy"))
                    .as(path)
                    .isEqualTo("default-src 'none'; frame-ancestors 'none'");
            assertThat(response.getHeader("X-Frame-Options")).as(path).isEqualTo("DENY");
            assertThat(response.getHeader("Referrer-Policy")).as(path).isEqualTo("no-referrer");
            assertThat(response.getHeader("Strict-Transport-Security"))
                    .as(path)
                    .isEqualTo("max-age=31536000 ; includeSubDomains");
            assertThat(response.getHeader("Permissions-Policy"))
                    .as(path)
                    .isEqualTo("geolocation=(), camera=(), microphone=()");
        }
    }

    @Test
    void SRS_G_48_anyOtherPathIsDeniedByTheLastChain() throws Exception {
        MockMvc mvc = online();
        assertThat(get(mvc, "/something-else", null)).isEqualTo(403);
        assertThat(get(mvc, "/v2/other", null)).isEqualTo(403);
    }

    @Test
    void WSTG_ATHZ_01_aRequestTheFirewallRejectsIsAnsweredWithAMalformedRequestProblem()
            throws Exception {
        ProblemResponder responder = mock(ProblemResponder.class);
        SecurityConfig config =
                new SecurityConfig(
                        mock(ProblemDetailAccessDeniedHandler.class),
                        mock(ProblemDetailAuthenticationEntryPoint.class));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/%2e%2e/x");
        MockHttpServletResponse response = new MockHttpServletResponse();
        config.requestRejectedHandler(responder)
                .handle(request, response, new RequestRejectedException("encoded dot"));
        verify(responder)
                .write(eq(null), eq(CommonErrorCode.MALFORMED_REQUEST), eq(request), eq(response));
    }
}
