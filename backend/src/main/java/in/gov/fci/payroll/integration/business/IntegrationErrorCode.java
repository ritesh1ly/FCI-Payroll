package in.gov.fci.payroll.integration.business;

import in.gov.fci.payroll.platform.error.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * Errors of the integration module (Module 23; docs/api/errors.md). The screen shows {@code
 * error.<code>} from its bundle (English and Hindi); the texts here are English defaults.
 */
public enum IntegrationErrorCode implements ErrorCode {
    /** SRS-INT-16 / BR-INT-008: the interface has no complete settings in force. */
    NOT_CONFIGURED(
            HttpStatus.SERVICE_UNAVAILABLE,
            "INT-503-NOT-CONFIGURED",
            "integration/not-configured",
            "Interface not configured",
            "Interface {0} is not configured: its retry, back-off and time limit are not set."),
    /** SRS-INT-6: the breaker is open after repeated failures. */
    CIRCUIT_OPEN(
            HttpStatus.SERVICE_UNAVAILABLE,
            "INT-503-CIRCUIT-OPEN",
            "integration/circuit-open",
            "Interface paused",
            "Interface {0} is paused after repeated failures until {1}."),
    /** SRS-INT-31: the call kept failing beyond the retry limit. */
    CALL_FAILED(
            HttpStatus.BAD_GATEWAY,
            "INT-502-CALL-FAILED",
            "integration/call-failed",
            "Interface call failed",
            "Interface {0} failed after {1} attempt(s): {2}."),
    /** The interface is not in the registry. */
    UNKNOWN_INTERFACE(
            HttpStatus.NOT_FOUND,
            "INT-404-INTERFACE",
            "integration/unknown-interface",
            "Interface not registered",
            "Interface {0} is not registered."),
    /** SRS-INT-1 / owner 2026-10-28: no adapter is built while the specification is not held. */
    NO_ADAPTER(
            HttpStatus.CONFLICT,
            "INT-409-NO-ADAPTER",
            "integration/no-adapter",
            "No adapter",
            "No adapter is built for interface {0}: its specification is not held."),
    /** SRS-INT-31: the exception is already resolved. */
    STATE(
            HttpStatus.CONFLICT,
            "INT-409-STATE",
            "integration/state",
            "Exception already resolved",
            "Exception {0} is {1}; it can no longer be changed."),
    /** SRS-INT-33 (X-03 l.5172): Reprocess Failed of a window with no open record. */
    WINDOW(
            HttpStatus.CONFLICT,
            "INT-409-WINDOW",
            "integration/window",
            "Nothing to reprocess",
            "Exchange window {0} has no open rejected or failed record to reprocess."),
    /** SRS-INT-31: the Administrator's reason is mandatory. */
    REASON(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "INT-422-REASON",
            "integration/reason",
            "Reason required",
            "Enter the reason (up to 1000 English characters)."),
    /** SRS-INT-4: the HRMS system credentials are missing or wrong (nothing else is said). */
    HRMS_UNAUTHORIZED(
            HttpStatus.UNAUTHORIZED,
            "INT-401-HRMS-AUTH",
            "integration/hrms-auth",
            "Not authorised",
            "The HRMS credentials are missing, wrong or expired."),
    /** SRS-INT-4 / SRS-INT-16: the key, client or signing secret is not set: nothing is served. */
    HRMS_AUTH_NOT_CONFIGURED(
            HttpStatus.SERVICE_UNAVAILABLE,
            "INT-503-HRMS-AUTH-NOT-CONFIGURED",
            "integration/hrms-auth-not-configured",
            "HRMS access not configured",
            "HRMS system access is not configured: the key, client or signing secret is not set."),
    /** SRS-INT-35: the status of a request Payroll never received. */
    HRMS_REQUEST_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "INT-404-HRMS-REQUEST",
            "integration/hrms-request-not-found",
            "Request not found",
            "No HRMS request {0} is held for this API."),
    /** Owner decision pending (PENDING API-23-8): the API is routed and refused. */
    HRMS_NOT_ENABLED(
            HttpStatus.NOT_IMPLEMENTED,
            "INT-501-HRMS-NOT-ENABLED",
            "integration/hrms-not-enabled",
            "Not enabled",
            "API {0} is not enabled: owner decision pending."),
    /** The body is not one JSON document of the expected shape (or repeats a key). */
    HRMS_MALFORMED(
            HttpStatus.BAD_REQUEST,
            "INT-400-HRMS-BODY",
            "integration/hrms-body",
            "Request not readable",
            "The request body is not valid JSON of the expected shape: {0}."),
    /** The body exceeds the size limit. */
    HRMS_TOO_LARGE(
            HttpStatus.CONTENT_TOO_LARGE,
            "INT-413-HRMS-BODY",
            "integration/hrms-body-too-large",
            "Request too large",
            "The request body is larger than {0} bytes."),
    /** SRS-INT-4: too many refused attempts from one address in the window. */
    HRMS_THROTTLED(
            HttpStatus.TOO_MANY_REQUESTS,
            "INT-429-HRMS-THROTTLED",
            "integration/hrms-throttled",
            "Too many refused attempts",
            "Too many refused HRMS attempts from this address; try again later."),
    /** SRS-INT-6 / R-INT: the interface's concurrency limit is full (X-03 shows the refusal). */
    BULKHEAD_FULL(
            HttpStatus.SERVICE_UNAVAILABLE,
            "INT-503-BULKHEAD-FULL",
            "integration/bulkhead-full",
            "Interface busy",
            "Interface {0} has reached its limit of concurrent calls."),
    /** SRS-INT-16: the API has no URL setting, or no bulkhead limit: it is not configured. */
    HRMS_API_NOT_CONFIGURED(
            HttpStatus.SERVICE_UNAVAILABLE,
            "INT-503-HRMS-API-NOT-CONFIGURED",
            "integration/hrms-api-not-configured",
            "API not configured",
            "HRMS API {0} is not configured: {1} is not set."),
    /** SRS-INT-33: the Administrator's HRMS API console is switched off on this server. */
    CONSOLE_DISABLED(
            HttpStatus.FORBIDDEN,
            "INT-403-CONSOLE-DISABLED",
            "integration/console-disabled",
            "API console disabled",
            "The HRMS API console is not enabled on this server"
                    + " (fci.integration.hrms.console.enabled)."),
    /** SRS-INT-12: an inbound record without a usable source key or payload. */
    RECORD(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "INT-422-RECORD",
            "integration/record",
            "Record not valid",
            "The inbound record is not valid: {0}.");

    private final HttpStatus status;
    private final String code;
    private final String type;
    private final String defaultTitle;
    private final String defaultDetail;

    IntegrationErrorCode(
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
}
