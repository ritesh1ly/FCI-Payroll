package in.gov.fci.payroll.platform.logging;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.DispatcherType;
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

@ResourceLock("thread-context")
class MdcFilterTest {

    private final MdcFilter filter = new MdcFilter("bff");

    @AfterEach
    void clear() {
        MDC.clear();
    }

    @Test
    void acceptsWellFormedIncomingCorrelationIdAndEchoesIt() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/bff/v1/me");
        request.addHeader(MdcKeys.CORRELATION_HEADER, "abc-123-def-456");
        request.setRemoteAddr("10.1.2.3");
        MockHttpServletResponse response = new MockHttpServletResponse();
        Map<String, String> seen = new HashMap<>();

        filter.doFilter(request, response, capture(seen));

        assertThat(seen)
                .containsEntry(MdcKeys.CORRELATION_ID, "abc-123-def-456")
                .containsEntry(MdcKeys.APP, "bff")
                .containsEntry(MdcKeys.HTTP_METHOD, "GET")
                .containsEntry(MdcKeys.URI, "/bff/v1/me")
                .containsEntry(MdcKeys.CLIENT_IP, "10.1.2.3");
        assertThat(response.getHeader(MdcKeys.CORRELATION_HEADER)).isEqualTo("abc-123-def-456");
        assertThat(request.getAttribute(MdcKeys.CORRELATION_ATTRIBUTE))
                .isEqualTo("abc-123-def-456");
    }

    @Test
    void replacesMalformedOrInjectedCorrelationIdWithUuid() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
        request.addHeader(MdcKeys.CORRELATION_HEADER, "evil\r\nINFO forged");
        MockHttpServletResponse response = new MockHttpServletResponse();
        Map<String, String> seen = new HashMap<>();

        filter.doFilter(request, response, capture(seen));

        String id = seen.get(MdcKeys.CORRELATION_ID);
        assertThat(UUID.fromString(id)).isNotNull();
        assertThat(response.getHeader(MdcKeys.CORRELATION_HEADER)).isEqualTo(id);
    }

    @Test
    void generatesCorrelationIdWhenMissing() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/x"), response, new MockFilterChain());
        assertThat(response.getHeader(MdcKeys.CORRELATION_HEADER)).hasSize(36);
    }

    @Test
    void clearsMdcAfterRequestEvenWhenChainThrows() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
        try {
            filter.doFilter(
                    request,
                    new MockHttpServletResponse(),
                    (req, res) -> {
                        throw new IllegalStateException("boom");
                    });
        } catch (Exception expected) {
            assertThat(expected).isInstanceOf(IllegalStateException.class);
        }
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void sanitisesUriAndRejectsUnparseableRemoteAddress() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x%0d%0aforged");
        request.setRequestURI("/x\r\nforged");
        request.setRemoteAddr("not an ip");
        Map<String, String> seen = new HashMap<>();

        filter.doFilter(request, new MockHttpServletResponse(), capture(seen));

        assertThat(seen.get(MdcKeys.URI)).isEqualTo("/x\\r\\nforged");
        assertThat(seen.get(MdcKeys.CLIENT_IP)).isEqualTo("unknown");
    }

    @Test
    void reusesCorrelationIdOnErrorDispatch() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/error");
        request.setDispatcherType(DispatcherType.ERROR);
        request.setAttribute(MdcKeys.CORRELATION_ATTRIBUTE, "first-pass-id-123");
        Map<String, String> seen = new HashMap<>();

        filter.doFilter(request, new MockHttpServletResponse(), capture(seen));

        assertThat(seen).containsEntry(MdcKeys.CORRELATION_ID, "first-pass-id-123");
    }

    @Test
    void validatesCorrelationIdFormat() {
        assertThat(MdcFilter.isValidCorrelationId("abcdefgh")).isTrue();
        assertThat(MdcFilter.isValidCorrelationId("short")).isFalse();
        assertThat(MdcFilter.isValidCorrelationId("a".repeat(65))).isFalse();
        assertThat(MdcFilter.isValidCorrelationId("has space ok?")).isFalse();
        assertThat(MdcFilter.isValidCorrelationId(null)).isFalse();
    }

    @Test
    void anInvalidCorrelationAttributeOfAnErrorDispatchFallsBackToTheHeaderThenToAUuid()
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/error");
        request.setDispatcherType(DispatcherType.ERROR);
        request.setAttribute(MdcKeys.CORRELATION_ATTRIBUTE, "bad id\r\n");
        request.addHeader(MdcKeys.CORRELATION_HEADER, "header-id-12345");
        Map<String, String> seen = new HashMap<>();
        filter.doFilter(request, new MockHttpServletResponse(), capture(seen));
        assertThat(seen).containsEntry(MdcKeys.CORRELATION_ID, "header-id-12345");

        MockHttpServletRequest notText = new MockHttpServletRequest("GET", "/error");
        notText.setAttribute(MdcKeys.CORRELATION_ATTRIBUTE, 12345678);
        Map<String, String> other = new HashMap<>();
        filter.doFilter(notText, new MockHttpServletResponse(), capture(other));
        assertThat(UUID.fromString(other.get(MdcKeys.CORRELATION_ID))).isNotNull();
    }

    @Test
    void theCorrelationIdIsValidFromEightToSixtyFourCharactersOnly() {
        assertThat(MdcFilter.isValidCorrelationId("a".repeat(7))).isFalse();
        assertThat(MdcFilter.isValidCorrelationId("a".repeat(8))).isTrue();
        assertThat(MdcFilter.isValidCorrelationId("a".repeat(64))).isTrue();
        assertThat(MdcFilter.isValidCorrelationId("")).isFalse();
        assertThat(MdcFilter.isValidCorrelationId("abcdefg_h")).as("underscore").isFalse();
        assertThat(MdcFilter.isValidCorrelationId("abcdefgh\n")).as("trailing newline").isFalse();
    }

    @Test
    void anIpv6AddressIsKeptAndAMissingOrOverlongOneIsUnknown() throws Exception {
        for (String[] pair :
                new String[][] {
                    {"2001:db8::1", "2001:db8::1"},
                    {"fe80::1%12", "fe80::1%12"},
                    {"fe80::1%eth0", "unknown"},
                    {null, "unknown"},
                    {"", "unknown"},
                    {"1", "unknown"},
                    {"1".repeat(65), "unknown"},
                    {"10.0.0.1, 10.0.0.2", "unknown"}
                }) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
            request.setRemoteAddr(pair[0]);
            Map<String, String> seen = new HashMap<>();
            filter.doFilter(request, new MockHttpServletResponse(), capture(seen));
            assertThat(seen.get(MdcKeys.CLIENT_IP)).as("for %s", pair[0]).isEqualTo(pair[1]);
        }
    }

    @Test
    void aForwardingHeaderIsNeverTrustedForTheClientIp() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
        request.setRemoteAddr("10.1.2.3");
        request.addHeader("X-Forwarded-For", "203.0.113.9");
        Map<String, String> seen = new HashMap<>();
        filter.doFilter(request, new MockHttpServletResponse(), capture(seen));
        assertThat(seen.get(MdcKeys.CLIENT_IP)).isEqualTo("10.1.2.3");
    }

    @Test
    void anOverlongUriAndMethodAreCutAndTheAppNameIsSanitised() throws Exception {
        MdcFilter named = new MdcFilter("bff\r\nforged-application-name-that-is-far-too-long");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
        request.setRequestURI("/" + "u".repeat(400));
        Map<String, String> seen = new HashMap<>();
        named.doFilter(request, new MockHttpServletResponse(), capture(seen));
        assertThat(seen.get(MdcKeys.URI).length()).isLessThan(300);
        assertThat(seen.get(MdcKeys.URI)).startsWith("/");
        assertThat(seen.get(MdcKeys.APP)).doesNotContain("\r").doesNotContain("\n");
        assertThat(seen.get(MdcKeys.APP).length()).isLessThanOrEqualTo(40);
    }

    @Test
    void outsideARequestThereIsNoCorrelationIdAndAfterOneThereIsNone() throws Exception {
        assertThat(MdcFilter.currentCorrelationId()).isNull();
        filter.doFilter(
                new MockHttpServletRequest("GET", "/x"),
                new MockHttpServletResponse(),
                new MockFilterChain());
        assertThat(MdcFilter.currentCorrelationId()).isNull();
    }

    @Test
    void theFilterAlsoRunsOnErrorDispatches() {
        assertThat(new MdcFilter("bff").shouldNotFilterErrorDispatch()).isFalse();
    }

    private static MockFilterChain capture(Map<String, String> into) {
        return new MockFilterChain(
                new jakarta.servlet.http.HttpServlet() {
                    @Override
                    protected void service(
                            jakarta.servlet.http.HttpServletRequest req,
                            jakarta.servlet.http.HttpServletResponse res) {
                        into.putAll(MDC.getCopyOfContextMap());
                        into.put("currentCorrelationId", MdcFilter.currentCorrelationId());
                    }
                });
    }
}
