package in.gov.fci.payroll.platform.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.platform.error.ProblemResponder;
import in.gov.fci.payroll.platform.security.CurrentUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartHttpServletRequest;
import org.springframework.mock.web.MockPart;

/**
 * ADR-0011 for file uploads: a multipart POST is fingerprinted from its parts and passed on as it
 * is. Reading its raw body (as for JSON) would leave the container nothing to parse and lose every
 * form field of the upload (found by the Module 01 Playwright suite: "The request could not be
 * read" on every order and HRMS export upload).
 */
class IdempotencyFilterMultipartTest {

    private static MockMultipartHttpServletRequest upload(String file, String reference) {
        MockMultipartHttpServletRequest request = new MockMultipartHttpServletRequest();
        request.setMethod("POST");
        request.setContextPath("/api");
        request.setRequestURI("/api/v1/off-cycle/7/order");
        request.setContentType("multipart/form-data; boundary=e2e");
        request.setQueryString("x=1");
        request.addHeader(IdempotencyFilter.HEADER, UUID.randomUUID().toString());
        request.addPart(
                new MockPart("file", "order.pdf", file.getBytes(StandardCharsets.US_ASCII)));
        request.addPart(new MockPart("reference", reference.getBytes(StandardCharsets.US_ASCII)));
        request.addParameter("reference", reference);
        return request;
    }

    @Test
    void ADR_0011_aMultipartUploadReachesTheControllerUnwrappedAndHashedFromItsParts()
            throws Exception {
        IdempotencyService service = mock(IdempotencyService.class);
        CurrentUser user = mock(CurrentUser.class);
        when(user.userId()).thenReturn(Optional.of("3"));
        when(service.begin(eq("3"), anyString(), anyString()))
                .thenReturn(new IdempotencyService.Decision.Proceed("r1"));
        IdempotencyFilter filter =
                new IdempotencyFilter(service, user, mock(ProblemResponder.class));
        MockMultipartHttpServletRequest request = upload("%PDF-1.7 a", "RO/14");
        AtomicReference<ServletRequest> seen = new AtomicReference<>();
        FilterChain chain = (req, res) -> seen.set(req);

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        // The very request: its parsed form fields (here the reference) are still there.
        assertThat(seen.get()).isSameAs(request);
        assertThat(seen.get().getParameter("reference")).isEqualTo("RO/14");
        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(service).begin(eq("3"), anyString(), hash.capture());
        assertThat(hash.getValue()).hasSize(64);
    }

    @Test
    void ADR_0011_theFingerprintChangesWithTheFileAndTheFieldsNotWithPartOrder() throws Exception {
        String a = IdempotencyFilter.multipartFingerprint(upload("%PDF-1.7 a", "RO/14"));
        assertThat(IdempotencyFilter.multipartFingerprint(upload("%PDF-1.7 a", "RO/14")))
                .isEqualTo(a);
        assertThat(IdempotencyFilter.multipartFingerprint(upload("%PDF-1.7 b", "RO/14")))
                .isNotEqualTo(a);
        assertThat(IdempotencyFilter.multipartFingerprint(upload("%PDF-1.7 a", "RO/15")))
                .isNotEqualTo(a);
        assertThat(a).startsWith("?x=1").contains("file|order.pdf|10|").contains("reference||5|");
    }
}
