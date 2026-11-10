package in.gov.fci.payroll.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ManagementPortFilterTest {

    private static final int APP_PORT = 8080;
    private static final int MANAGEMENT_PORT = 8081;

    private final ManagementPortFilter filter =
            new ManagementPortFilter(MANAGEMENT_PORT, "/actuator/");

    @ParameterizedTest(name = "port {0} path {1} -> {2}")
    @CsvSource({
        "8081, /actuator/health, 200",
        "8081, /actuator, 200",
        "8081, /actuator/prometheus, 200",
        "8081, /v1/users, 404",
        "8081, /actuatorx, 404",
        "8080, /actuator/health, 404",
        "8080, /actuator, 404",
        "8080, /v1/users, 200",
        "8080, /actuatorx, 200"
    })
    void separatesActuatorFromApplicationTraffic(int port, String servletPath, int expected)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/bff" + servletPath);
        request.setContextPath("/bff");
        request.setServletPath(servletPath);
        request.setLocalPort(port);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(expected);
        assertThat(chain.getRequest() != null).isEqualTo(expected == 200);
    }

    @Test
    void usesDecodedServletPathSoEncodedPathsCannotBypass() throws Exception {
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/bff/act%75ator/health");
        request.setContextPath("/bff");
        request.setServletPath("/actuator/health");
        request.setLocalPort(APP_PORT);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(404);
    }

    @Test
    void combinesServletPathAndPathInfo() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/bff/actuator/health");
        request.setServletPath("/actuator");
        request.setPathInfo("/health");
        request.setLocalPort(MANAGEMENT_PORT);
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void disabledWhenPortNotConfigured() throws Exception {
        ManagementPortFilter disabled = new ManagementPortFilter(0, "/actuator");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        request.setServletPath("/actuator/health");
        request.setLocalPort(APP_PORT);
        MockFilterChain chain = new MockFilterChain();

        disabled.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
    }
}
