package in.gov.fci.payroll.penalty.business;

import in.gov.fci.payroll.platform.error.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * Errors of the penalty module (Module 12; docs/api/errors.md). The screen shows {@code
 * error.<code>} from its bundle (English and Hindi); the texts here are English defaults.
 */
public enum PenaltyErrorCode implements ErrorCode {
    /** SRS-14-1, I-06 field table: the order's particulars or terms. */
    ORDER(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "PEN-422-ORDER",
            "penalty/order",
            "Order not valid",
            "The order is not valid: {0}."),
    /** SRS-14-12, SRS-G-31: the order copy is mandatory. */
    ORDER_COPY(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "PEN-422-ORDER-COPY",
            "penalty/order-copy",
            "Order copy required",
            "Attach the copy of the order."),
    /** SRS-14-1, CF-17: an employee's penalty comes from HRMS through I-21. */
    HRMS_ONLY(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "PEN-422-HRMS-ONLY",
            "penalty/hrms-only",
            "Received from HRMS",
            "An employee's {0} is received from HRMS through I-21; it is not keyed on I-06."),
    /** R-RULE: a setting the order needs is not configured. */
    NOT_CONFIGURED(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "PEN-422-NOT-CONFIGURED",
            "penalty/not-configured",
            "Not configured",
            "{0} is not configured."),
    /** SRS-14-7: no room at all under the deduction ceiling this month. */
    CEILING(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "PEN-422-CEILING",
            "penalty/ceiling",
            "No room under the ceiling",
            "There is no room under the deduction ceiling this month: lengthen the recovery or"
                    + " start it later.");

    private final HttpStatus status;
    private final String code;
    private final String type;
    private final String defaultTitle;
    private final String defaultDetail;

    PenaltyErrorCode(
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
