package in.gov.fci.payroll.platform.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.platform.error.ProblemResponder;
import in.gov.fci.payroll.platform.security.CurrentUser;
import jakarta.servlet.FilterChain;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * SRS-EH-8, ADR-0011 edge cases: a POST without a Content-Type is fingerprinted from its body like
 * any other (it is not a multipart upload); an answer larger than the stored-body limit (256 KiB)
 * reaches the client but is not kept for a replay, so its record is released; an answer of exactly
 * the limit is kept.
 */
class IdempotencyFilterBranchGapsTest {

    private final IdempotencyService service = mock(IdempotencyService.class);
    private final CurrentUser user = mock(CurrentUser.class);
    private final IdempotencyFilter filter =
            new IdempotencyFilter(service, user, mock(ProblemResponder.class));

    IdempotencyFilterBranchGapsTest() {
        when(user.userId()).thenReturn(Optional.of("3"));
    }

    private static MockHttpServletRequest post(String contentType, String body) {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", "/api/v1/queries");
        r.setContextPath("/api");
        r.addHeader(IdempotencyFilter.HEADER, UUID.randomUUID().toString());
        r.setContentType(contentType);
        r.setContent(body.getBytes(StandardCharsets.UTF_8));
        return r;
    }

    private void proceedAs(String record) {
        when(service.begin(eq("3"), anyString(), anyString()))
                .thenReturn(new IdempotencyService.Decision.Proceed(record));
    }

    @Test
    void SRS_EH_8_aPostWithoutAContentTypeIsHashedFromItsBodyAndStored() throws Exception {
        proceedAs("r1");
        AtomicReference<String> seen = new AtomicReference<>();
        FilterChain chain =
                (req, res) -> {
                    seen.set(
                            new String(
                                    req.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
                    res.getWriter().write("ok");
                };
        filter.doFilter(post(null, "plain"), new MockHttpServletResponse(), chain);
        assertThat(seen.get()).isEqualTo("plain");
        verify(service).complete(eq("r1"), eq(200), any(), eq("ok"));
        verify(service, never()).abandon(anyString());
    }

    @Test
    void SRS_EH_8_anAnswerOverTheStoredLimitReachesTheClientButIsNotKept() throws Exception {
        proceedAs("r2");
        String large = "x".repeat(256 * 1024 + 1);
        FilterChain chain = (req, res) -> res.getWriter().write(large);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(post("application/json", "{}"), response, chain);
        assertThat(response.getContentAsString()).hasSize(large.length());
        verify(service).abandon("r2");
        verify(service, never()).complete(anyString(), anyInt(), any(), any());
    }

    @Test
    void SRS_EH_8_anAnswerExactlyAtTheStoredLimitIsKept() throws Exception {
        proceedAs("r3");
        String limit = "y".repeat(256 * 1024);
        FilterChain chain = (req, res) -> res.getWriter().write(limit);
        filter.doFilter(post("application/json", "{}"), new MockHttpServletResponse(), chain);
        verify(service).complete(eq("r3"), eq(200), any(), eq(limit));
    }
}
