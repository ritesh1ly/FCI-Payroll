package in.gov.fci.payroll.platform.error;

import in.gov.fci.payroll.platform.logging.LogSanitizer;
import in.gov.fci.payroll.platform.logging.MdcKeys;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

/**
 * The single exception boundary: turns an exception into a problem response and logs it exactly
 * once (ESER "Exception" phase). 5xx is logged at ERROR with the stack trace; 401/403 at WARN;
 * other 4xx at INFO without the exception message, which may echo request input.
 */
public class ProblemResponder {

    private static final Logger log = LoggerFactory.getLogger(ProblemResponder.class);

    private final ProblemDetailFactory factory;
    private final ProblemDetailWriter writer;

    public ProblemResponder(ProblemDetailFactory factory, ProblemDetailWriter writer) {
        this.factory = factory;
        this.writer = writer;
    }

    public ResponseEntity<Object> respond(
            Exception ex,
            ErrorCode code,
            Object[] args,
            List<Violation> violations,
            HttpHeaders headers,
            HttpServletRequest request) {
        logOnce(ex, code);
        ProblemDetail problem = factory.create(code, request, args, violations);
        HttpHeaders responseHeaders = new HttpHeaders();
        if (headers != null) {
            responseHeaders.putAll(headers);
        }
        responseHeaders.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        responseHeaders.setCacheControl("no-store");
        return ResponseEntity.status(code.status())
                .headers(responseHeaders)
                .body(ProblemDetailWriter.toMap(problem));
    }

    public ResponseEntity<Object> respond(
            Exception ex, ErrorCode code, HttpServletRequest request) {
        return respond(ex, code, null, List.of(), null, request);
    }

    /**
     * Writes the problem directly to the response; for filters and security handlers. A filter that
     * answers before the Spring Security chain (the JSON key guard, the idempotency filter) gets no
     * header writer, so the two headers every problem needs are set here (ASVS V14.4.4 / V14.4.5;
     * security review 2026-11-04): the browser never sniffs it, no cache keeps it.
     */
    public void write(
            Exception ex, ErrorCode code, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        logOnce(ex, code);
        if (!response.containsHeader("X-Content-Type-Options")) {
            response.setHeader("X-Content-Type-Options", "nosniff");
        }
        writer.write(response, factory.create(code, request, null, List.of()));
    }

    public ProblemDetailFactory factory() {
        return factory;
    }

    private static void logOnce(Exception ex, ErrorCode code) {
        int status = code.status().value();
        String exception = ex == null ? "none" : ex.getClass().getName();
        String durationMs = MDC.get(MdcKeys.DURATION_MS);
        if (status >= 500) {
            log.error(
                    "event=EXCEPTION errorCode={} status={} exception={} durationMs={} message={}",
                    code.code(),
                    status,
                    exception,
                    durationMs,
                    ex == null ? "" : LogSanitizer.sanitize(ex.getMessage(), 512),
                    ex);
        } else if (status == 401 || status == 403) {
            log.warn(
                    "event=EXCEPTION errorCode={} status={} exception={} durationMs={}",
                    code.code(),
                    status,
                    exception,
                    durationMs);
        } else {
            log.info(
                    "event=EXCEPTION errorCode={} status={} exception={} durationMs={}",
                    code.code(),
                    status,
                    exception,
                    durationMs);
        }
    }
}
