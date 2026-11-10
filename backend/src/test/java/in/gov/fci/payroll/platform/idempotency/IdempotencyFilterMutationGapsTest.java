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
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartHttpServletRequest;
import org.springframework.mock.web.MockPart;

/** Kills the PIT survivors of {@link IdempotencyFilter} (SRS-EH-8, ADR-0011). */
class IdempotencyFilterMutationGapsTest {

    private final IdempotencyService service = mock(IdempotencyService.class);
    private final CurrentUser user = mock(CurrentUser.class);
    private final ProblemResponder responder = mock(ProblemResponder.class);
    private final IdempotencyFilter filter = new IdempotencyFilter(service, user, responder);

    private static MockHttpServletRequest post(String key) {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", "/api/v1/tax/challans");
        r.setContextPath("/api");
        if (key != null) {
            r.addHeader(IdempotencyFilter.HEADER, key);
        }
        r.setContentType("application/json");
        r.setContent("{}".getBytes(StandardCharsets.US_ASCII));
        return r;
    }

    @Test
    void SRS_EH_8_aMissingKeyIsRefusedWithIdempotencyKeyRequiredAndTheChainNotRun()
            throws Exception {
        when(user.userId()).thenReturn(Optional.of("3"));
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(post(null), new MockHttpServletResponse(), chain);
        verify(responder)
                .write(eq(null), eq(CommonErrorCode.IDEMPOTENCY_KEY_REQUIRED), any(), any());
        verify(chain, never()).doFilter(any(), any());
        verify(service, never()).begin(anyString(), anyString(), anyString());
    }

    @Test
    void SRS_EH_8_aKeyThatIsNotAUuidIsRefused() throws Exception {
        when(user.userId()).thenReturn(Optional.of("3"));
        filter.doFilter(post("not-a-uuid"), new MockHttpServletResponse(), mock(FilterChain.class));
        verify(responder)
                .write(eq(null), eq(CommonErrorCode.IDEMPOTENCY_KEY_REQUIRED), any(), any());
    }

    @Test
    void SRS_EH_8_aReplayAnswersWithTheStoredStatusContentTypeAndBody() throws Exception {
        when(user.userId()).thenReturn(Optional.of("3"));
        when(service.begin(eq("3"), anyString(), anyString()))
                .thenReturn(
                        new IdempotencyService.Decision.Replay(
                                201, "application/json", "{\"a\":1}"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(post(UUID.randomUUID().toString()), response, mock(FilterChain.class));
        assertThat(response.getStatus()).isEqualTo(201);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getHeader(IdempotencyFilter.REPLAYED_HEADER)).isEqualTo("true");
        assertThat(response.getContentAsString()).isEqualTo("{\"a\":1}");
    }

    @Test
    void SRS_EH_8_aReplayWithoutContentTypeOrBodySetsNeither() throws Exception {
        when(user.userId()).thenReturn(Optional.of("3"));
        when(service.begin(eq("3"), anyString(), anyString()))
                .thenReturn(new IdempotencyService.Decision.Replay(204, null, null));
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(post(UUID.randomUUID().toString()), response, mock(FilterChain.class));
        assertThat(response.getStatus()).isEqualTo(204);
        assertThat(response.getContentType()).isNull();
        assertThat(response.getContentAsByteArray()).isEmpty();
    }

    @Test
    void SRS_EH_8_aMismatchIsRefusedWithTheMismatchCode() throws Exception {
        when(user.userId()).thenReturn(Optional.of("3"));
        when(service.begin(eq("3"), anyString(), anyString()))
                .thenReturn(new IdempotencyService.Decision.Mismatch());
        filter.doFilter(
                post(UUID.randomUUID().toString()),
                new MockHttpServletResponse(),
                mock(FilterChain.class));
        verify(responder)
                .write(eq(null), eq(CommonErrorCode.IDEMPOTENCY_KEY_MISMATCH), any(), any());
    }

    @Test
    void SRS_EH_8_anInProgressKeyIsRefusedWithTheInProgressCode() throws Exception {
        when(user.userId()).thenReturn(Optional.of("3"));
        when(service.begin(eq("3"), anyString(), anyString()))
                .thenReturn(new IdempotencyService.Decision.InProgress());
        filter.doFilter(
                post(UUID.randomUUID().toString()),
                new MockHttpServletResponse(),
                mock(FilterChain.class));
        verify(responder)
                .write(eq(null), eq(CommonErrorCode.IDEMPOTENCY_IN_PROGRESS), any(), any());
    }

    private void proceedWith(int status, int bodyLength) throws Exception {
        when(user.userId()).thenReturn(Optional.of("3"));
        when(service.begin(eq("3"), anyString(), anyString()))
                .thenReturn(new IdempotencyService.Decision.Proceed("rec-1"));
        FilterChain chain =
                (req, res) -> {
                    ((HttpServletResponse) res).setStatus(status);
                    res.getOutputStream().write(new byte[bodyLength]);
                };
        filter.doFilter(post(UUID.randomUUID().toString()), new MockHttpServletResponse(), chain);
    }

    @Test
    void SRS_EH_8_aStoredBodyOfExactlyTheLimitIsCompletedNotAbandoned() throws Exception {
        proceedWith(200, 256 * 1024);
        verify(service).complete(eq("rec-1"), eq(200), any(), anyString());
        verify(service, never()).abandon(anyString());
    }

    @Test
    void SRS_EH_8_aStoredBodyOneByteOverTheLimitIsAbandoned() throws Exception {
        proceedWith(200, 256 * 1024 + 1);
        verify(service, never()).complete(anyString(), anyInt(), any(), any());
        verify(service).abandon("rec-1");
    }

    @Test
    void SRS_EH_8_status499IsStored() throws Exception {
        proceedWith(499, 2);
        verify(service).complete(eq("rec-1"), eq(499), any(), anyString());
        verify(service, never()).abandon(anyString());
    }

    @Test
    void SRS_EH_8_aServerErrorIsAbandonedSoTheKeyCanBeRetried() throws Exception {
        proceedWith(500, 2);
        verify(service, never()).complete(anyString(), anyInt(), any(), any());
        verify(service).abandon("rec-1");
    }

    private static MockMultipartHttpServletRequest multipart() {
        MockMultipartHttpServletRequest request = new MockMultipartHttpServletRequest();
        request.setMethod("POST");
        request.setRequestURI("/api/v1/x");
        request.setContentType("multipart/form-data; boundary=e2e");
        return request;
    }

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    @Test
    void ADR_0011_partsAreFingerprintedInNameOrderWhateverTheArrivalOrder() throws Exception {
        MockMultipartHttpServletRequest request = multipart();
        request.addPart(new MockPart("zeta", b("2")));
        request.addPart(new MockPart("alpha", b("1")));
        String fingerprint = IdempotencyFilter.multipartFingerprint(request);
        assertThat(fingerprint.indexOf("alpha|"))
                .isPositive()
                .isLessThan(fingerprint.indexOf("zeta|"));
    }

    @Test
    void ADR_0011_partsOfTheSameNameAreOrderedByFileNameAndANullFileNameSortsFirst()
            throws Exception {
        MockMultipartHttpServletRequest request = multipart();
        request.addPart(new MockPart("f", "b.txt", b("2")));
        request.addPart(new MockPart("f", "a.txt", b("1")));
        request.addPart(new MockPart("f", b("0")));
        String fingerprint = IdempotencyFilter.multipartFingerprint(request);
        assertThat(fingerprint).contains("\nf||1|").contains("\nf|a.txt|1|");
        assertThat(fingerprint.indexOf("\nf||1|")).isLessThan(fingerprint.indexOf("\nf|a.txt|1|"));
        assertThat(fingerprint.indexOf("\nf|a.txt|1|"))
                .isLessThan(fingerprint.indexOf("\nf|b.txt|1|"));
    }
}
