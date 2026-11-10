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

import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.ProblemResponder;
import in.gov.fci.payroll.platform.security.CurrentUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartHttpServletRequest;

/**
 * SRS-EH-8, ADR-0011: a state-changing POST is processed once per Idempotency-Key. A retry gets the
 * stored answer; a retry while the first still runs is refused (409); a server error is not stored,
 * so the client may retry; an anonymous request and a GET are not deduplicated here.
 */
class IdempotencyFilterTest {

    private final IdempotencyService service = mock(IdempotencyService.class);
    private final CurrentUser user = mock(CurrentUser.class);
    private final ProblemResponder responder = mock(ProblemResponder.class);
    private final IdempotencyFilter filter = new IdempotencyFilter(service, user, responder);

    private static MockHttpServletRequest post(String body) {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", "/api/v1/queries");
        r.setContextPath("/api");
        r.addHeader(IdempotencyFilter.HEADER, UUID.randomUUID().toString());
        r.setContentType("application/json");
        r.setContent(body.getBytes(StandardCharsets.UTF_8));
        return r;
    }

    @Test
    void SRS_EH_8_anAnonymousRequestAndANonApiPostPassStraightThrough() throws Exception {
        when(user.userId()).thenReturn(Optional.empty());
        AtomicBoolean reached = new AtomicBoolean();
        filter.doFilter(post("{}"), new MockHttpServletResponse(), (req, res) -> reached.set(true));
        assertThat(reached).isTrue();
        verify(service, never()).begin(anyString(), anyString(), anyString());
        // Sign-in and GET requests are never filtered.
        MockHttpServletRequest signIn = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        signIn.setContextPath("/api");
        assertThat(filter.shouldNotFilter(signIn)).isTrue();
        MockHttpServletRequest get = new MockHttpServletRequest("GET", "/api/v1/queries");
        get.setContextPath("/api");
        assertThat(filter.shouldNotFilter(get)).isTrue();
    }

    @Test
    void SRS_EH_8_aRetryWhileTheFirstRunsIsRefusedAndAReplayWithoutABodyStaysEmpty()
            throws Exception {
        when(user.userId()).thenReturn(Optional.of("3"));
        FilterChain unreachable =
                (req, res) -> {
                    throw new AssertionError("the controller must not run twice");
                };
        when(service.begin(eq("3"), anyString(), anyString()))
                .thenReturn(new IdempotencyService.Decision.InProgress());
        MockHttpServletRequest running = post("{\"a\":1}");
        MockHttpServletResponse refused = new MockHttpServletResponse();
        filter.doFilter(running, refused, unreachable);
        verify(responder).write(null, CommonErrorCode.IDEMPOTENCY_IN_PROGRESS, running, refused);

        when(service.begin(eq("3"), anyString(), anyString()))
                .thenReturn(new IdempotencyService.Decision.Replay(204, null, null));
        MockHttpServletResponse replay = new MockHttpServletResponse();
        filter.doFilter(post("{\"a\":1}"), replay, unreachable);
        assertThat(replay.getStatus()).isEqualTo(204);
        assertThat(replay.getHeader(IdempotencyFilter.REPLAYED_HEADER)).isEqualTo("true");
        assertThat(replay.getContentAsByteArray()).isEmpty();
        assertThat(replay.getContentType()).isNull();
    }

    @Test
    void SRS_EH_8_aServerErrorIsNotStoredSoTheClientMayRetry() throws Exception {
        when(user.userId()).thenReturn(Optional.of("3"));
        when(service.begin(eq("3"), anyString(), anyString()))
                .thenReturn(new IdempotencyService.Decision.Proceed("r9"));
        FilterChain failing =
                (req, res) ->
                        ((HttpServletResponse) res)
                                .setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        filter.doFilter(post("{}"), new MockHttpServletResponse(), failing);
        verify(service).abandon("r9");
        verify(service, never()).complete(anyString(), anyInt(), any(), any());
    }

    @Test
    void ADR_0011_aMalformedOrOversizedUploadIsRefusedBeforeTheController() throws Exception {
        when(user.userId()).thenReturn(Optional.of("3"));
        FilterChain unreachable =
                (req, res) -> {
                    throw new AssertionError("refused before the controller");
                };
        MockMultipartHttpServletRequest malformed =
                new MockMultipartHttpServletRequest() {
                    @Override
                    public java.util.Collection<jakarta.servlet.http.Part> getParts()
                            throws ServletException {
                        throw new ServletException("no boundary");
                    }
                };
        MockMultipartHttpServletRequest tooLarge =
                new MockMultipartHttpServletRequest() {
                    @Override
                    public java.util.Collection<jakarta.servlet.http.Part> getParts() {
                        throw new IllegalStateException("part over the limit");
                    }
                };
        for (MockMultipartHttpServletRequest r :
                new MockMultipartHttpServletRequest[] {malformed, tooLarge}) {
            r.setMethod("POST");
            r.setContextPath("/api");
            r.setRequestURI("/api/v1/off-cycle/7/order");
            r.setContentType("multipart/form-data; boundary=x");
            r.addHeader(IdempotencyFilter.HEADER, UUID.randomUUID().toString());
        }
        MockHttpServletResponse a = new MockHttpServletResponse();
        filter.doFilter(malformed, a, unreachable);
        verify(responder).write(null, CommonErrorCode.MALFORMED_REQUEST, malformed, a);
        MockHttpServletResponse b = new MockHttpServletResponse();
        filter.doFilter(tooLarge, b, unreachable);
        verify(responder).write(null, CommonErrorCode.PAYLOAD_TOO_LARGE, tooLarge, b);
    }
}
