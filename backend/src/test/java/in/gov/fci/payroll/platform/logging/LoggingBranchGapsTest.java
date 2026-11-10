package in.gov.fci.payroll.platform.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Edge cases of the structured logging context: a correlation attribute that is not a string, or
 * not a valid id, is replaced; a request without a remote address logs "unknown"; an authentication
 * not (yet) authenticated names no user; an enum result is logged by value; a security event
 * without attributes carries an empty map.
 */
@ResourceLock("thread-context")
class LoggingBranchGapsTest {

    @AfterEach
    void clear() {
        MDC.clear();
        SecurityContextHolder.clearContext();
    }

    private static MockFilterChain capture(Map<String, String> into) {
        return new MockFilterChain(
                new jakarta.servlet.http.HttpServlet() {
                    @Override
                    protected void service(
                            jakarta.servlet.http.HttpServletRequest req,
                            jakarta.servlet.http.HttpServletResponse res) {
                        Map<String, String> mdc = MDC.getCopyOfContextMap();
                        if (mdc != null) {
                            into.putAll(mdc);
                        }
                    }
                });
    }

    @Test
    void aCorrelationAttributeThatIsNotAValidStringIsReplacedAndNoRemoteAddressIsUnknown()
            throws Exception {
        MdcFilter filter = new MdcFilter("api");
        MockHttpServletRequest notAString = new MockHttpServletRequest("GET", "/x");
        notAString.setAttribute(MdcKeys.CORRELATION_ATTRIBUTE, 12345678);
        notAString.setRemoteAddr(null);
        Map<String, String> seen = new HashMap<>();
        filter.doFilter(notAString, new MockHttpServletResponse(), capture(seen));
        assertThat(UUID.fromString(seen.get(MdcKeys.CORRELATION_ID))).isNotNull();
        assertThat(seen).containsEntry(MdcKeys.CLIENT_IP, "unknown");

        MockHttpServletRequest invalid = new MockHttpServletRequest("GET", "/x");
        invalid.setAttribute(MdcKeys.CORRELATION_ATTRIBUTE, "bad id\r\n");
        invalid.addHeader(MdcKeys.CORRELATION_HEADER, "header-id-1234");
        Map<String, String> second = new HashMap<>();
        filter.doFilter(invalid, new MockHttpServletResponse(), capture(second));
        assertThat(second).containsEntry(MdcKeys.CORRELATION_ID, "header-id-1234");
    }

    @Test
    void anAuthenticationNotYetAuthenticatedNamesNoUser() throws Exception {
        SecurityContextHolder.getContext()
                .setAuthentication(UsernamePasswordAuthenticationToken.unauthenticated("u1", "p"));
        Map<String, String> seen = new HashMap<>();
        new MdcSecurityContextFilter()
                .doFilter(
                        new MockHttpServletRequest("GET", "/x"),
                        new MockHttpServletResponse(),
                        capture(seen));
        assertThat(seen).doesNotContainKey(MdcKeys.USER_ID);
    }

    private enum Colour {
        AMBER
    }

    @Test
    void anEnumResultIsLoggedByValueAndAPlainObjectByTypeOnly() {
        ResultSummarizer summarizer = new ResultSummarizer();
        assertThat(summarizer.summarize(Colour.AMBER)).isEqualTo("{type=Colour, value=AMBER}");
        assertThat(summarizer.summarize(new Object())).isEqualTo("{type=Object}");
    }

    @Test
    void aSecurityEventWithoutAttributesCarriesAnEmptyMapAndCopiesGivenOnes() {
        SecurityEvent bare =
                new SecurityEvent(
                        "LOGIN_FAILED",
                        SecurityEvent.Outcome.FAILURE,
                        null,
                        null,
                        null,
                        null,
                        null);
        assertThat(bare.attributes()).isEmpty();
        assertThat(bare.actorId()).isEqualTo("anonymous");
        Map<String, String> given = new HashMap<>(Map.of("k", "v"));
        SecurityEvent copied =
                new SecurityEvent(
                        "LOGIN_FAILED",
                        SecurityEvent.Outcome.FAILURE,
                        "u",
                        null,
                        null,
                        null,
                        given);
        given.put("k2", "v2");
        assertThat(copied.attributes()).containsOnlyKeys("k");
    }
}
