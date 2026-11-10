package in.gov.fci.payroll.reporting.business;

import in.gov.fci.payroll.platform.error.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * Errors of the reporting module (Module 15; docs/api/errors.md). The screen shows {@code
 * error.<code>} from its bundle (English and Hindi); the texts here are English defaults.
 */
public enum ReportErrorCode implements ErrorCode {
    /** SRS-18-24: the output cannot be generated; the reason and the stage required. */
    BLOCKED(
            HttpStatus.CONFLICT,
            "REP-409-BLOCKED",
            "reporting/blocked",
            "Report blocked",
            "{0} cannot be generated: {1}."),
    /** SRS-18-8: the control totals do not match the posted payroll; the release is halted. */
    CONTROL_TOTALS(
            HttpStatus.CONFLICT,
            "REP-409-CONTROL-TOTALS",
            "reporting/control-totals",
            "Control totals do not match",
            "{0}: the report shows {1}, the posted payroll {2}; the release is halted."),
    /** The report's parameters are not valid. */
    PARAMETERS(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "REP-422-PARAMETERS",
            "reporting/parameters",
            "Parameters not valid",
            "The report parameters are not valid: {0}."),
    /** SRS-18-10 (M-12): the proposed version is not valid. */
    VERSION(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "REP-422-VERSION",
            "reporting/version",
            "Version not valid",
            "The report version is not valid: {0}.");

    private final HttpStatus status;
    private final String code;
    private final String type;
    private final String defaultTitle;
    private final String defaultDetail;

    ReportErrorCode(
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
