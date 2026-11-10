package in.gov.fci.payroll.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.ProblemResponder;
import jakarta.servlet.FilterChain;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Kills the PIT survivors of {@link JsonKeyGuardFilter} (SRS-EH-8 key guard, ASVS V5.1). */
class JsonKeyGuardFilterMutationGapsTest {

    private static final int MAX = 64;

    private final ProblemResponder responder = mock(ProblemResponder.class);
    private final JsonKeyGuardFilter filter = new JsonKeyGuardFilter(responder, MAX, 8);

    private static MockHttpServletRequest request(String contentType, byte[] body) {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", "/api/v1/x");
        if (contentType != null) {
            r.setContentType(contentType);
        }
        r.setContent(body);
        return r;
    }

    private static byte[] json(int length) {
        // {"a":"xxx"} padded to exactly length bytes
        StringBuilder sb = new StringBuilder("{\"a\":\"");
        while (sb.length() < length - 2) {
            sb.append('x');
        }
        sb.append("\"}");
        return sb.toString().getBytes(StandardCharsets.US_ASCII);
    }

    @Test
    void SRS_EH_8_aBodyOfExactlyTheLimitPassesBothTheDeclaredAndTheReadCheck() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(
                request("application/json", json(MAX)), new MockHttpServletResponse(), chain);
        verify(chain).doFilter(any(), any());
        verify(responder, never()).write(any(), any(), any(), any());
    }

    @Test
    void SRS_EH_8_aDeclaredLengthOneOverTheLimitIsRefused() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(
                request("application/json", json(MAX + 1)), new MockHttpServletResponse(), chain);
        verify(responder).write(eq(null), eq(CommonErrorCode.PAYLOAD_TOO_LARGE), any(), any());
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void SRS_EH_8_anUndeclaredBodyOneOverTheLimitIsRefusedWhenRead() throws Exception {
        // chunked: no declared length, so only the bounded read can refuse it
        MockHttpServletRequest r = request("application/json", json(MAX + 1));
        r.setContent(json(MAX + 1));
        MockHttpServletRequest chunked =
                new MockHttpServletRequest("POST", "/api/v1/x") {
                    @Override
                    public long getContentLengthLong() {
                        return -1;
                    }
                };
        chunked.setContentType("application/json");
        chunked.setContent(json(MAX + 1));
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(chunked, new MockHttpServletResponse(), chain);
        verify(responder).write(eq(null), eq(CommonErrorCode.PAYLOAD_TOO_LARGE), any(), any());
        verify(chain, never()).doFilter(any(), any());

        MockHttpServletRequest atLimit =
                new MockHttpServletRequest("POST", "/api/v1/x") {
                    @Override
                    public long getContentLengthLong() {
                        return -1;
                    }
                };
        atLimit.setContentType("application/json");
        atLimit.setContent(json(MAX));
        FilterChain chain2 = mock(FilterChain.class);
        filter.doFilter(atLimit, new MockHttpServletResponse(), chain2);
        verify(chain2).doFilter(any(), any());
    }

    @Test
    void SRS_EH_8_anEmptyBodyIsPassedOnWithoutInspection() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(
                request("application/json", new byte[0]), new MockHttpServletResponse(), chain);
        verify(chain).doFilter(any(), any());
        verify(responder, never()).write(any(), any(), any(), any());
    }

    @Test
    void SRS_EH_8_aRequestWithoutContentTypeIsNotInspected() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        byte[] duplicate = "{\"a\":1,\"a\":2}".getBytes(StandardCharsets.US_ASCII);
        filter.doFilter(request(null, duplicate), new MockHttpServletResponse(), chain);
        verify(chain).doFilter(any(), any());
        verify(responder, never()).write(any(), any(), any(), any());
    }

    @Test
    void SRS_EH_8_propertyNameBoundaries() {
        assertThat(JsonKeyGuardFilter.isAllowedName("a".repeat(128))).isTrue();
        assertThat(JsonKeyGuardFilter.isAllowedName("a".repeat(129))).isFalse();
        assertThat(JsonKeyGuardFilter.isAllowedName("a")).isTrue();
        assertThat(JsonKeyGuardFilter.isAllowedName("")).isFalse();
        assertThat(JsonKeyGuardFilter.isAllowedName(null)).isFalse();
        // a '.' or NUL in first, middle and last position; '$' only matters first
        assertThat(JsonKeyGuardFilter.isAllowedName(".a")).isFalse();
        assertThat(JsonKeyGuardFilter.isAllowedName("a.b")).isFalse();
        assertThat(JsonKeyGuardFilter.isAllowedName("a.")).isFalse();
        assertThat(JsonKeyGuardFilter.isAllowedName("\0a")).isFalse();
        assertThat(JsonKeyGuardFilter.isAllowedName("a\0b")).isFalse();
        assertThat(JsonKeyGuardFilter.isAllowedName("a\0")).isFalse();
        assertThat(JsonKeyGuardFilter.isAllowedName("$a")).isFalse();
        assertThat(JsonKeyGuardFilter.isAllowedName("a$")).isTrue();
    }

    @Test
    void SRS_EH_8_anEmptyBodyIsNeverHandedToTheKeyInspection() throws Exception {
        JsonKeyGuardFilter spied = org.mockito.Mockito.spy(filter);
        FilterChain chain = mock(FilterChain.class);

        spied.doFilter(
                request("application/json", new byte[0]), new MockHttpServletResponse(), chain);

        verify(spied, never()).inspect(any());
        verify(chain).doFilter(any(), any());

        // a one-byte body is inspected (the boundary is exactly one byte)
        FilterChain second = mock(FilterChain.class);
        spied.doFilter(
                request("application/json", "{".getBytes(StandardCharsets.US_ASCII)),
                new MockHttpServletResponse(),
                second);
        verify(spied).inspect(any());
        verify(responder).write(eq(null), eq(CommonErrorCode.MALFORMED_REQUEST), any(), any());
        verify(second, never()).doFilter(any(), any());
    }
}
