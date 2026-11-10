package in.gov.fci.payroll.eseva.business;

import in.gov.fci.payroll.platform.error.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * Errors of the eSeva module (Module 14; docs/api/errors.md). The screen shows {@code error.<code>}
 * from its bundle (English and Hindi); the texts here are English defaults.
 */
public enum EsevaErrorCode implements ErrorCode {
    /** SRS-17-8 / D14-5: a support preview names its reason (printable, at most 500 characters). */
    REASON(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "ESEVA-422-REASON",
            "eseva/reason",
            "Reason required",
            "Enter the reason for opening the member's eSeva view (at most 500 characters)."),
    /** SRS-17-6: a member's own eSeva view is not previewed by the member. */
    OWN_RECORD(
            HttpStatus.CONFLICT,
            "ESEVA-409-OWN-RECORD",
            "eseva/own-record",
            "Own record",
            "Your own eSeva view is opened on E-01, not as a support preview.");

    private final HttpStatus status;
    private final String code;
    private final String type;
    private final String defaultTitle;
    private final String defaultDetail;

    EsevaErrorCode(
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
