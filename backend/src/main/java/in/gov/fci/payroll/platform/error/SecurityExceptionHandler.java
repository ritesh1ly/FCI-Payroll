package in.gov.fci.payroll.platform.error;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps method-security failures raised inside controllers ({@code @PreAuthorize}) to 403/401.
 * Failures raised by the filter chain are handled by {@link
 * in.gov.fci.payroll.platform.security.ProblemDetailAccessDeniedHandler} and {@link
 * in.gov.fci.payroll.platform.security.ProblemDetailAuthenticationEntryPoint}.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE - 20)
public class SecurityExceptionHandler {

    private final ProblemResponder responder;

    public SecurityExceptionHandler(ProblemResponder responder) {
        this.responder = responder;
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Object> handleAccessDenied(
            AccessDeniedException ex, HttpServletRequest request) {
        return responder.respond(ex, CommonErrorCode.ACCESS_DENIED, request);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Object> handleAuthentication(
            AuthenticationException ex, HttpServletRequest request) {
        return responder.respond(ex, CommonErrorCode.AUTHENTICATION_REQUIRED, request);
    }
}
