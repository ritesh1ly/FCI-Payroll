package in.gov.fci.payroll.iam.web;

import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.ProblemResponder;
import in.gov.fci.payroll.platform.logging.CriticalLog;
import in.gov.fci.payroll.platform.logging.MdcKeys;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 429 with {@code Retry-After} for {@link RateLimitedException} (before the generic handler). */
@RestControllerAdvice
@Profile("online")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RateLimitExceptionHandler {

    private final ProblemResponder responder;

    public RateLimitExceptionHandler(ProblemResponder responder) {
        this.responder = responder;
    }

    @ExceptionHandler(RateLimitedException.class)
    public ResponseEntity<Object> rateLimited(RateLimitedException ex, HttpServletRequest request) {
        // OD-130: only the sign-in endpoints throttle here; a hit is a burst of attempts.
        CriticalLog.log(
                CriticalLog.Event.SIGN_IN_FAILURE_BURST,
                CriticalLog.fields(
                        "path", request.getRequestURI(), "ip", MDC.get(MdcKeys.CLIENT_IP)));
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, Long.toString(ex.retryAfterSeconds()));
        return responder.respond(
                ex, CommonErrorCode.TOO_MANY_REQUESTS, null, List.of(), headers, request);
    }
}
