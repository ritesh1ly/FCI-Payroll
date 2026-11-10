package in.gov.fci.payroll.platform.error;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.platform.export.PdfRenderGate;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.json.JsonMapper;

/**
 * OD-144, performance SLA: a PDF refused because every render slot is taken is answered 503
 * COM-503-EXPORT-BUSY with Retry-After and no-store, as a problem document.
 */
@ResourceLock("thread-context")
class PdfBusyHandlerTest {

    private final GlobalExceptionHandler handler =
            new GlobalExceptionHandler(
                    new ProblemResponder(
                            new ProblemDetailFactory(
                                    "https://errors.fci.gov.in",
                                    null,
                                    Clock.fixed(
                                            Instant.parse("2026-11-09T05:00:00Z"), ZoneOffset.UTC)),
                            new ProblemDetailWriter(new JsonMapper())));

    @Test
    void OD_144_everySlotTakenIs503WithRetryAfter() {
        ResponseEntity<Object> r =
                handler.handleRenderBusy(
                        new PdfRenderGate.Busy(5),
                        new MockHttpServletRequest("GET", "/v1/payslips/mine/202609/pdf"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(r.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("5");
        assertThat(r.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(String.valueOf(r.getBody())).contains("COM-503-EXPORT-BUSY");
    }
}
