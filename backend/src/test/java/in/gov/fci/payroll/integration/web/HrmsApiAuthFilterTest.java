package in.gov.fci.payroll.integration.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import in.gov.fci.payroll.integration.api.HrmsApi;
import in.gov.fci.payroll.integration.business.IntegrationErrorCode;
import in.gov.fci.payroll.integration.config.HrmsApiProperties;
import in.gov.fci.payroll.integration.config.HrmsApiProperties.ApiUrl;
import in.gov.fci.payroll.integration.config.HrmsSecurityProperties;
import in.gov.fci.payroll.integration.orchestration.HrmsAccessService;
import in.gov.fci.payroll.integration.orchestration.HrmsRefusals;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.error.ProblemResponder;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;

/**
 * SRS-INT-4: the filter reads each credential header once, passes the calling address for the
 * throttle, answers a refusal as a problem and sets an authentication only for this request.
 */
class HrmsApiAuthFilterTest {

    private static final String KEY = "filter-key-0123456789abcdef";
    private static final String SECRET = "filter-signing-secret-0123456789abcdef";
    private static final String PASSWORD = "filter-pw-0123456789abcdef";

    private final HrmsAccessService access = mock(HrmsAccessService.class);
    private final ProblemResponder responder = mock(ProblemResponder.class);
    private final HrmsApiRoutes routes =
            new HrmsApiRoutes(
                    new HrmsApiProperties(
                            null,
                            Map.of(
                                    "token", new ApiUrl("hrms/token"),
                                    "request_ota", new ApiUrl("hrms/request-ota"))));
    private final HrmsApiAuthFilter filter = new HrmsApiAuthFilter(routes, access, responder);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static MockHttpServletRequest request(String path) {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", path);
        r.setRemoteAddr("10.1.2.3");
        return r;
    }

    @Test
    void SRS_INT_4_theTokenApiNeedsOnlyTheKeyAndPassesTheAddress() throws Exception {
        MockHttpServletRequest r = request("/hrms/token");
        r.addHeader("api-key", KEY);
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(r, new MockHttpServletResponse(), chain);
        verify(access).requireKey(KEY, "10.1.2.3");
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void SRS_INT_4_everyOtherApiNeedsTheKeyAndTheBearerToken() throws Exception {
        MockHttpServletRequest r = request("/hrms/request-ota");
        r.addHeader("api-key", KEY);
        r.addHeader("Authorization", "Bearer abc");
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(r, new MockHttpServletResponse(), chain);
        verify(access).requireKeyAndToken(KEY, "Bearer abc", "10.1.2.3");
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void SRS_INT_4_aHeaderSentTwiceHasNoSingleReadingAndCountsAsAbsent() throws Exception {
        MockHttpServletRequest r = request("/hrms/request-ota");
        r.addHeader("api-key", KEY);
        r.addHeader("api-key", "second");
        r.addHeader("Authorization", "Bearer a");
        r.addHeader("Authorization", "Bearer b");
        filter.doFilter(r, new MockHttpServletResponse(), new MockFilterChain());
        verify(access).requireKeyAndToken(isNull(), isNull(), eq("10.1.2.3"));
    }

    @Test
    void SRS_INT_4_aRefusalIsAProblemAndNothingContinues() throws Exception {
        FciException refused =
                new FciException(
                        IntegrationErrorCode.HRMS_UNAUTHORIZED, new Object[0], List.of(), null);
        doThrow(refused).when(access).requireKeyAndToken(any(), any(), any());
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request("/hrms/request-ota"), new MockHttpServletResponse(), chain);
        verify(responder)
                .write(eq(refused), eq(IntegrationErrorCode.HRMS_UNAUTHORIZED), any(), any());
        assertThat(chain.getRequest()).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void SRS_INT_4_theAuthenticationCarriesOnlyTheSystemAuthority() throws Exception {
        MockHttpServletRequest r = request("/hrms/request-ota");
        MockFilterChain chain =
                new MockFilterChain() {
                    @Override
                    public void doFilter(
                            jakarta.servlet.ServletRequest request,
                            jakarta.servlet.ServletResponse response) {
                        var auth = SecurityContextHolder.getContext().getAuthentication();
                        assertThat(auth.getAuthorities())
                                .extracting(Object::toString)
                                .containsExactly("HRMS_SYSTEM");
                        assertThat(auth.getCredentials()).isNull();
                    }
                };
        filter.doFilter(r, new MockHttpServletResponse(), chain);
    }

    // The real service behind the filter: a brute force from one address is answered 429.
    @Test
    void SRS_INT_4_pastTheLimitAnAddressIsThrottledAndOthersAreNot() {
        var props =
                new HrmsSecurityProperties(
                        KEY,
                        null,
                        "c",
                        PasswordEncoderFactories.createDelegatingPasswordEncoder().encode(PASSWORD),
                        null,
                        SECRET,
                        null,
                        600,
                        3,
                        60);
        HrmsAccessService real =
                new HrmsAccessService(props, mock(HrmsRefusals.class), Clock.systemUTC());
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> real.requireKey("wrong", "10.9.9.9"))
                    .hasMessage("INT-401-HRMS-AUTH");
        }
        assertThatThrownBy(() -> real.requireKey(KEY, "10.9.9.9"))
                .as("even the right key, while locked")
                .hasMessage("INT-429-HRMS-THROTTLED");
        assertThatThrownBy(() -> real.exchange("c", PASSWORD, "10.9.9.9"))
                .hasMessage("INT-429-HRMS-THROTTLED");
        real.requireKey(KEY, "10.8.8.8");
        assertThat(real.exchange("c", PASSWORD, "10.8.8.8").accessToken()).isNotBlank();
        verify(mock(HrmsRefusals.class), never()).record(any(), any());
    }

    @Test
    void SRS_INT_4_aSuccessClearsTheCountSoSlowTyposDoNotLockOut() {
        var props =
                new HrmsSecurityProperties(
                        KEY,
                        null,
                        "c",
                        PasswordEncoderFactories.createDelegatingPasswordEncoder().encode(PASSWORD),
                        null,
                        SECRET,
                        null,
                        600,
                        3,
                        60);
        HrmsAccessService real =
                new HrmsAccessService(props, mock(HrmsRefusals.class), Clock.systemUTC());
        for (int round = 0; round < 3; round++) {
            assertThatThrownBy(() -> real.exchange("c", "wrong", "10.7.7.7"))
                    .hasMessage("INT-401-HRMS-AUTH");
            assertThatThrownBy(() -> real.exchange("c", "wrong", "10.7.7.7"))
                    .hasMessage("INT-401-HRMS-AUTH");
            assertThat(real.exchange("c", PASSWORD, "10.7.7.7").accessToken()).isNotBlank();
        }
        assertThat(HrmsApi.TOKEN.method()).isEqualTo("POST");
    }
}
