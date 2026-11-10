package in.gov.fci.payroll.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.platform.error.ProblemDetailFactory;
import in.gov.fci.payroll.platform.error.ProblemDetailWriter;
import in.gov.fci.payroll.platform.error.ProblemResponder;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/**
 * Edge cases of the request guards (SRS-NFR security baseline): a JSON request without a body has
 * nothing to inspect and goes on; a key that is null is never allowed; the management-port check
 * reads the container's decoded path even when the container gives no servlet path or no path at
 * all (the root).
 */
class PlatformFilterBranchGapsTest {

    private final ProblemResponder responder =
            new ProblemResponder(
                    new ProblemDetailFactory("https://errors.fci.gov.in/", null, Clock.systemUTC()),
                    new ProblemDetailWriter(new JsonMapper()));

    @Test
    void anEmptyJsonBodyHasNothingToInspectAndReachesTheController() throws Exception {
        JsonKeyGuardFilter filter = new JsonKeyGuardFilter(responder, 2048, 8);
        MockHttpServletRequest empty = new MockHttpServletRequest("POST", "/api/v1/users");
        empty.setContentType("application/json");
        empty.setContent(new byte[0]);
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(empty, response, chain);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
        assertThat(chain.getRequest().getInputStream().readAllBytes()).isEmpty();
    }

    @Test
    void aNullKeyIsNeverAllowedAndAMalformedDocumentIsRefused() {
        JsonKeyGuardFilter filter = new JsonKeyGuardFilter(responder, 2048, 8);
        assertThat(JsonKeyGuardFilter.isAllowedName(null)).isFalse();
        assertThat(filter.inspect("{\"a\":".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .isEqualTo(in.gov.fci.payroll.platform.error.CommonErrorCode.MALFORMED_REQUEST);
        assertThat(filter.inspect("{\"a\":1}".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .isNull();
    }

    @Test
    void theManagementPortCheckReadsAMissingServletPathOrPathInfoAsEmpty() throws Exception {
        ManagementPortFilter filter = new ManagementPortFilter(8081, "/actuator");
        // Only a path info (no servlet path): /actuator/health on the management port passes.
        MockHttpServletRequest pathInfoOnly = new MockHttpServletRequest("GET", "/actuator/health");
        pathInfoOnly.setLocalPort(8081);
        pathInfoOnly.setServletPath(null);
        pathInfoOnly.setPathInfo("/actuator/health");
        MockFilterChain passed = new MockFilterChain();
        filter.doFilter(pathInfoOnly, new MockHttpServletResponse(), passed);
        assertThat(passed.getRequest()).isSameAs(pathInfoOnly);
        // Neither: the root ("/") is application traffic, refused on the management port...
        MockHttpServletRequest root = new MockHttpServletRequest("GET", "/");
        root.setLocalPort(8081);
        root.setServletPath(null);
        root.setPathInfo(null);
        MockHttpServletResponse refused = new MockHttpServletResponse();
        filter.doFilter(root, refused, new MockFilterChain());
        assertThat(refused.getStatus()).isEqualTo(404);
        // ...and served on the application port.
        MockHttpServletRequest appRoot = new MockHttpServletRequest("GET", "/");
        appRoot.setLocalPort(8080);
        appRoot.setServletPath("");
        MockFilterChain served = new MockFilterChain();
        filter.doFilter(appRoot, new MockHttpServletResponse(), served);
        assertThat(served.getRequest()).isSameAs(appRoot);
    }
}
