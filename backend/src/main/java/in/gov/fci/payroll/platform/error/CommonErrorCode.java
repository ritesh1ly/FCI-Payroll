package in.gov.fci.payroll.platform.error;

import org.springframework.http.HttpStatus;

/** Errors shared by every FCI application. */
public enum CommonErrorCode implements ErrorCode {
    VALIDATION_FAILED(
            HttpStatus.BAD_REQUEST,
            "COM-400-VALIDATION",
            "request/validation-failed",
            "Validation failed",
            "One or more fields are invalid."),
    MALFORMED_REQUEST(
            HttpStatus.BAD_REQUEST,
            "COM-400-MALFORMED",
            "request/malformed",
            "Malformed request",
            "The request could not be read."),
    FORBIDDEN_JSON_KEY(
            HttpStatus.BAD_REQUEST,
            "COM-400-JSON-KEY",
            "request/forbidden-key",
            "Malformed request",
            "The request contains a property name that is not allowed."),
    /** A page size not offered (the configured series 10, 20, 50, 100) or a negative page. */
    PAGE_REQUEST(
            HttpStatus.BAD_REQUEST,
            "COM-400-PAGE",
            "request/page",
            "Page not available",
            "Choose one of the offered page sizes and a page number from 1."),
    IDEMPOTENCY_KEY_REQUIRED(
            HttpStatus.BAD_REQUEST,
            "COM-400-IDEMPOTENCY-KEY",
            "request/idempotency-key-required",
            "Idempotency key required",
            "This operation requires a valid Idempotency-Key header."),
    AUTHENTICATION_REQUIRED(
            HttpStatus.UNAUTHORIZED,
            "COM-401-AUTH",
            "auth/authentication-required",
            "Authentication required",
            "Please sign in to continue."),
    ACCESS_DENIED(
            HttpStatus.FORBIDDEN,
            "COM-403-DENIED",
            "auth/access-denied",
            "Access denied",
            "You do not have permission to perform this action."),
    NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "COM-404-NOT-FOUND",
            "resource/not-found",
            "Not found",
            "The requested resource was not found."),
    METHOD_NOT_ALLOWED(
            HttpStatus.METHOD_NOT_ALLOWED,
            "COM-405-METHOD",
            "request/method-not-allowed",
            "Method not allowed",
            "This operation is not supported on this resource."),
    NOT_ACCEPTABLE(
            HttpStatus.NOT_ACCEPTABLE,
            "COM-406-NOT-ACCEPTABLE",
            "request/not-acceptable",
            "Not acceptable",
            "The requested response format is not supported."),
    CONFLICT(
            HttpStatus.CONFLICT,
            "COM-409-CONFLICT",
            "resource/conflict",
            "Conflict",
            "The request conflicts with the current state of the resource."),
    DUPLICATE(
            HttpStatus.CONFLICT,
            "COM-409-DUPLICATE",
            "resource/duplicate",
            "Already exists",
            "A record with the same unique value already exists."),
    IDEMPOTENCY_IN_PROGRESS(
            HttpStatus.CONFLICT,
            "COM-409-IDEMPOTENCY-BUSY",
            "request/idempotency-in-progress",
            "Request in progress",
            "A request with this Idempotency-Key is still being processed."),
    PRECONDITION_FAILED(
            HttpStatus.PRECONDITION_FAILED,
            "COM-412-STALE",
            "resource/stale",
            "Record changed",
            "The record was changed by someone else. Reload it and try again."),
    PAYLOAD_TOO_LARGE(
            HttpStatus.CONTENT_TOO_LARGE,
            "COM-413-TOO-LARGE",
            "request/too-large",
            "Request too large",
            "The request body is too large."),
    UNSUPPORTED_MEDIA_TYPE(
            HttpStatus.UNSUPPORTED_MEDIA_TYPE,
            "COM-415-MEDIA-TYPE",
            "request/unsupported-media-type",
            "Unsupported media type",
            "The request content type is not supported."),
    IDEMPOTENCY_KEY_MISMATCH(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "COM-422-IDEMPOTENCY-MISMATCH",
            "request/idempotency-key-mismatch",
            "Idempotency key reused",
            "This Idempotency-Key was already used for a different request."),
    PRECONDITION_REQUIRED(
            HttpStatus.PRECONDITION_REQUIRED,
            "COM-428-IF-MATCH",
            "request/precondition-required",
            "Version required",
            "This update requires the If-Match header with the record version."),
    TOO_MANY_REQUESTS(
            HttpStatus.TOO_MANY_REQUESTS,
            "COM-429-RATE-LIMIT",
            "request/rate-limited",
            "Too many requests",
            "Too many requests. Please wait and try again."),
    /**
     * A list export larger than one download may carry (fci.export.max-rows): narrow the filter.
     */
    EXPORT_TOO_LARGE(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "COM-422-EXPORT-TOO-LARGE",
            "export/too-large",
            "Export too large",
            "The list has more rows than one download may carry. Narrow the filter."),
    /** Every export thread is busy and the wait queue is full. */
    EXPORT_BUSY(
            HttpStatus.SERVICE_UNAVAILABLE,
            "COM-503-EXPORT-BUSY",
            "export/busy",
            "Exports busy",
            "Too many downloads are running. Please try again in a minute."),
    INTERNAL_ERROR(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "COM-500-INTERNAL",
            "internal/unexpected",
            "Unexpected error",
            "An unexpected error occurred. Quote the correlation id when contacting support."),
    SERVICE_UNAVAILABLE(
            HttpStatus.SERVICE_UNAVAILABLE,
            "COM-503-UNAVAILABLE",
            "upstream/unavailable",
            "Service temporarily unavailable",
            "The service is temporarily unavailable. Please try again shortly.");

    private final HttpStatus status;
    private final String code;
    private final String type;
    private final String defaultTitle;
    private final String defaultDetail;

    CommonErrorCode(
            HttpStatus status,
            String code,
            String type,
            String defaultTitle,
            String defaultDetail) {
        this.status = status;
        this.code = code;
        this.type = type;
        this.defaultTitle = defaultTitle;
        this.defaultDetail = defaultDetail;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public HttpStatus status() {
        return status;
    }

    @Override
    public String type() {
        return type;
    }

    @Override
    public String defaultTitle() {
        return defaultTitle;
    }

    @Override
    public String defaultDetail() {
        return defaultDetail;
    }

    /** The closest catalogued error for an HTTP status raised by the framework. */
    public static CommonErrorCode forStatus(int status) {
        return switch (status) {
            case 400 -> MALFORMED_REQUEST;
            case 401 -> AUTHENTICATION_REQUIRED;
            case 403 -> ACCESS_DENIED;
            case 404 -> NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            case 406 -> NOT_ACCEPTABLE;
            case 409 -> CONFLICT;
            case 412 -> PRECONDITION_FAILED;
            case 413 -> PAYLOAD_TOO_LARGE;
            case 415 -> UNSUPPORTED_MEDIA_TYPE;
            case 428 -> PRECONDITION_REQUIRED;
            case 429 -> TOO_MANY_REQUESTS;
            case 503 -> SERVICE_UNAVAILABLE;
            default -> status >= 500 ? INTERNAL_ERROR : MALFORMED_REQUEST;
        };
    }
}
