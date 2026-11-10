package in.gov.fci.payroll.platform.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.ProblemResponder;
import in.gov.fci.payroll.platform.security.CurrentUser;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * SRS-EH-8 / ADR-0011 hardening (security review 2026-11-04): the fingerprint of a request includes
 * its query string (W-6), and a body the JSON key guard does not bound is read only up to the
 * largest size NGINX lets through (W-7).
 */
class IdempotencyFilterHardeningTest {

    private final IdempotencyService service = mock(IdempotencyService.class);
    private final CurrentUser user = mock(CurrentUser.class);
    private final ProblemResponder responder = mock(ProblemResponder.class);
    private final IdempotencyFilter filter = new IdempotencyFilter(service, user, responder);

    private static MockHttpServletRequest post(String key, String query, byte[] body) {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", "/api/v1/tax/challans");
        r.setContextPath("/api");
        r.addHeader(IdempotencyFilter.HEADER, key);
        r.setQueryString(query);
        r.setContentType("text/plain");
        r.setContent(body);
        return r;
    }

    @Test
    void SRS_EH_8_theQueryStringIsPartOfTheFingerprint() throws Exception {
        when(user.userId()).thenReturn(Optional.of("3"));
        when(service.begin(eq("3"), anyString(), anyString()))
                .thenReturn(new IdempotencyService.Decision.Mismatch());
        String key = UUID.randomUUID().toString();
        byte[] body = "same".getBytes(StandardCharsets.US_ASCII);
        List<String> hashes = new ArrayList<>();
        for (String query : new String[] {"finYear=2026-27", "finYear=2025-26", null}) {
            filter.doFilter(
                    post(key, query, body), new MockHttpServletResponse(), new MockFilterChain());
        }
        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(service, org.mockito.Mockito.times(3)).begin(eq("3"), eq(key), hash.capture());
        hashes.addAll(hash.getAllValues());
        assertThat(hashes).doesNotHaveDuplicates();
    }

    @Test
    void SRS_EH_8_aBodyLargerThanNginxAllowsIsRefusedUnreadAndUnrecorded() throws Exception {
        when(user.userId()).thenReturn(Optional.of("3"));
        byte[] big = new byte[IdempotencyFilter.MAX_BODY_BYTES + 1];
        MockHttpServletRequest request = post(UUID.randomUUID().toString(), null, big);
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        verify(responder).write(any(), eq(CommonErrorCode.PAYLOAD_TOO_LARGE), any(), any());
        verify(service, never()).begin(anyString(), anyString(), anyString());
        assertThat(chain.getRequest()).isNull();

        // Exactly the limit is read and fingerprinted.
        when(service.begin(eq("3"), anyString(), anyString()))
                .thenReturn(new IdempotencyService.Decision.Mismatch());
        byte[] limit = new byte[IdempotencyFilter.MAX_BODY_BYTES];
        filter.doFilter(
                post(UUID.randomUUID().toString(), null, limit),
                new MockHttpServletResponse(),
                new MockFilterChain());
        verify(service).begin(eq("3"), anyString(), anyString());
    }
}
