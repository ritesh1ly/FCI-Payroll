package in.gov.fci.payroll.leave.business;

import in.gov.fci.payroll.platform.error.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * Errors of the leave module (Module 19; docs/api/errors.md). The screen shows {@code error.<code>}
 * from its bundle (English and Hindi); the texts here are English defaults.
 */
public enum LeaveErrorCode implements ErrorCode {
    /** SRS-LV-24: a leave type or balance HRMS sent is not valid; the interface is told why. */
    RECEIPT(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "LV-422-RECEIPT",
            "leave/receipt",
            "Leave data not valid",
            "The leave data received is not valid: {0}."),
    /** SRS-LV-26: the leave event of the task cannot be applied. */
    EVENT(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "LV-422-EVENT",
            "leave/event",
            "Leave event not valid",
            "The leave event cannot be applied: {0}."),
    /** SRS-LV-14: a parameter version that cannot be proposed. */
    PARAMETER(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "LV-422-PARAMETER",
            "leave/parameter",
            "Parameter version not valid",
            "The parameter version is not valid: {0}."),
    /** SRS-LV-14: leave pay parameters are maintained at Headquarters only. */
    HQ_ONLY(
            HttpStatus.FORBIDDEN,
            "LV-403-HQ-ONLY",
            "leave/hq-only",
            "Headquarters only",
            "Leave pay parameters are maintained at Headquarters only."),
    /** SRS-LV-14: a version of the parameter already waits for the Payroll Checker. */
    PARAMETER_PENDING(
            HttpStatus.CONFLICT,
            "LV-409-PARAMETER-PENDING",
            "leave/parameter-pending",
            "A version is pending",
            "A version of {0} already waits for the Payroll Checker (approval {1})."),
    /** SRS-LV-14: no overlap: a new version starts after every approved one, on a free date. */
    PARAMETER_DATE(
            HttpStatus.CONFLICT,
            "LV-409-PARAMETER-DATE",
            "leave/parameter-date",
            "Date not free",
            "A version of {0} must start after {1}, on a date no other version holds."),
    /** R-RULE: a leave pay parameter (M-20) is not configured. */
    NOT_CONFIGURED(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "LV-422-NOT-CONFIGURED",
            "leave/not-configured",
            "Not configured",
            "{0} is not configured on M-20."),
    /** Plan 13 phase 13d (SRS-8-24): only CALCULATED leave encashment arrears are submitted. */
    ARREAR_STATE(
            HttpStatus.CONFLICT,
            "LEA-409-STATE",
            "leave/arrear-state",
            "Record not submittable",
            "Leave encashment arrear {0} is {1}; only a calculated record is submitted."),
    /** Plan 13 phase 13d (SRS-8-24): a submission names at least one record and a remark. */
    ARREAR_SELECTION(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "LEA-422-SELECTION",
            "leave/arrear-selection",
            "Selection not valid",
            "Select at least one record and give a remark ({0}).");

    private final HttpStatus status;
    private final String code;
    private final String type;
    private final String defaultTitle;
    private final String defaultDetail;

    LeaveErrorCode(
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
