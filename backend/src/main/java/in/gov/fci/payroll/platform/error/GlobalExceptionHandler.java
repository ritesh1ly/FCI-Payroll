package in.gov.fci.payroll.platform.error;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

/**
 * Lowest-precedence advice: maps Spring MVC, Bean Validation, {@link FciException} and unexpected
 * exceptions to RFC 9457 responses. More specific advices (data access, security, resilience) are
 * ordered before it.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Pattern SAFE_FIELD = Pattern.compile("^[A-Za-z0-9_.\\[\\]]{1,64}$");
    private static final String IF_MATCH = "If-Match";
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final ProblemResponder responder;

    public GlobalExceptionHandler(ProblemResponder responder) {
        this.responder = responder;
    }

    @ExceptionHandler(FciException.class)
    public ResponseEntity<Object> handleFci(FciException ex, HttpServletRequest request) {
        return responder.respond(
                ex, ex.getErrorCode(), ex.getMessageArgs(), ex.getViolations(), null, request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Object> handleConstraintViolation(
            ConstraintViolationException ex, HttpServletRequest request) {
        List<Violation> violations = new ArrayList<>();
        for (ConstraintViolation<?> violation : ex.getConstraintViolations()) {
            violations.add(
                    new Violation(
                            safeField(leafName(violation.getPropertyPath())),
                            violation
                                    .getConstraintDescriptor()
                                    .getAnnotation()
                                    .annotationType()
                                    .getSimpleName(),
                            violation.getMessage()));
        }
        return responder.respond(
                ex, CommonErrorCode.VALIDATION_FAILED, null, violations, null, request);
    }

    /**
     * OD-144: every PDF render slot is taken (PdfRenderGate): 503 with {@code Retry-After}, so a
     * salary-day surge is told when to come back instead of holding a thread.
     */
    @ExceptionHandler(in.gov.fci.payroll.platform.export.PdfRenderGate.Busy.class)
    public ResponseEntity<Object> handleRenderBusy(
            in.gov.fci.payroll.platform.export.PdfRenderGate.Busy ex, HttpServletRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, Long.toString(ex.retryAfterSeconds()));
        return responder.respond(
                ex, CommonErrorCode.EXPORT_BUSY, null, List.of(), headers, request);
    }

    /** The export pool and its queue are full: the download is refused, not queued forever. */
    @ExceptionHandler(org.springframework.core.task.TaskRejectedException.class)
    public ResponseEntity<Object> handleExportRejected(
            org.springframework.core.task.TaskRejectedException ex, HttpServletRequest request) {
        return responder.respond(ex, CommonErrorCode.EXPORT_BUSY, request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception ex, HttpServletRequest request) {
        return responder.respond(ex, CommonErrorCode.INTERNAL_ERROR, request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex,
            Object body,
            HttpHeaders headers,
            HttpStatusCode statusCode,
            WebRequest request) {
        HttpServletRequest servletRequest = servletRequest(request);
        Locale locale = servletRequest == null ? Locale.ENGLISH : servletRequest.getLocale();
        List<Violation> violations = violations(ex, locale);
        ErrorCode code = errorCode(ex, statusCode, violations);
        return responder.respond(ex, code, null, violations, headers, servletRequest);
    }

    private static ErrorCode errorCode(
            Exception ex, HttpStatusCode statusCode, List<Violation> violations) {
        if (ex instanceof MissingRequestHeaderException missing) {
            if (IF_MATCH.equalsIgnoreCase(missing.getHeaderName())) {
                return CommonErrorCode.PRECONDITION_REQUIRED;
            }
            if (IDEMPOTENCY_KEY.equalsIgnoreCase(missing.getHeaderName())) {
                return CommonErrorCode.IDEMPOTENCY_KEY_REQUIRED;
            }
        }
        if (!violations.isEmpty()) {
            return CommonErrorCode.VALIDATION_FAILED;
        }
        return CommonErrorCode.forStatus(statusCode.value());
    }

    private List<Violation> violations(Exception ex, Locale locale) {
        List<Violation> violations = new ArrayList<>();
        if (ex instanceof MethodArgumentNotValidException invalid) {
            for (FieldError error : invalid.getBindingResult().getFieldErrors()) {
                violations.add(fieldViolation(error, locale));
            }
            invalid.getBindingResult()
                    .getGlobalErrors()
                    .forEach(error -> violations.add(violation("_object", error, locale)));
        } else if (ex instanceof HandlerMethodValidationException invalid) {
            invalid.getParameterValidationResults()
                    .forEach(
                            result -> {
                                if (result instanceof ParameterErrors errors) {
                                    errors.getFieldErrors()
                                            .forEach(
                                                    e -> violations.add(fieldViolation(e, locale)));
                                } else {
                                    String name = result.getMethodParameter().getParameterName();
                                    result.getResolvableErrors()
                                            .forEach(
                                                    e ->
                                                            violations.add(
                                                                    violation(name, e, locale)));
                                }
                            });
        } else if (ex instanceof HttpMessageNotReadableException unreadable
                && unreadable.getCause() instanceof UnrecognizedPropertyException unknown) {
            violations.add(
                    new Violation(
                            safeField(unknown.getPropertyName()),
                            "UnknownProperty",
                            responder
                                    .factory()
                                    .message(
                                            "validation.unknown-property",
                                            null,
                                            "This property is not allowed.",
                                            locale)));
        }
        return violations;
    }

    private Violation fieldViolation(FieldError error, Locale locale) {
        return violation(error.getField(), error, locale);
    }

    private Violation violation(String field, MessageSourceResolvable error, Locale locale) {
        String[] codes = error.getCodes();
        String code =
                codes == null || codes.length == 0
                        ? "Invalid"
                        : lastSegment(codes[codes.length - 1]);
        String message =
                responder
                        .factory()
                        .messageSource()
                        .getMessage(error, ProblemDetailFactory.resolveLocale(locale));
        return new Violation(safeField(field), code, message);
    }

    private static String lastSegment(String code) {
        int dot = code.lastIndexOf('.');
        return dot < 0 ? code : code.substring(dot + 1);
    }

    private static String leafName(Path path) {
        String leaf = null;
        for (Path.Node node : path) {
            leaf = node.getName();
        }
        return leaf;
    }

    /** Field names come from the request in some cases, so only safe identifiers are echoed. */
    private static String safeField(String field) {
        return field != null && SAFE_FIELD.matcher(field).matches() ? field : "_field";
    }

    private static HttpServletRequest servletRequest(WebRequest request) {
        return request instanceof NativeWebRequest nativeRequest
                ? nativeRequest.getNativeRequest(HttpServletRequest.class)
                : null;
    }
}
