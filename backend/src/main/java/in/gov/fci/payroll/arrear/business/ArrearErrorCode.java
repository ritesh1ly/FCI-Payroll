package in.gov.fci.payroll.arrear.business;

import in.gov.fci.payroll.platform.error.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * Errors of the arrears module (Module 13; docs/api/errors.md). The screen shows {@code
 * error.<code>} from its bundle (English and Hindi); the texts here are English defaults.
 */
public enum ArrearErrorCode implements ErrorCode {
    /** SRS-8-14: the action does not fit the event's status. */
    STATE(
            HttpStatus.CONFLICT,
            "ARR-409-STATE",
            "arrears/state",
            "Not at this status",
            "The arrear event is {0}; this action needs {1}."),
    /** SRS-21-9 / I-08: a remark is mandatory on every back-dated event. */
    REMARK(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "ARR-422-REMARK",
            "arrears/remark",
            "Remark required",
            "Enter a remark: it is mandatory on every arrear or back-dated event."),
    /** SRS-8-25: only Headquarters defines or changes a wage revision. */
    WR_HQ_ONLY(
            HttpStatus.FORBIDDEN,
            "WR-403-HQ-ONLY",
            "arrears/wage-revision-hq",
            "Headquarters only",
            "A wage revision is defined and changed at Headquarters only."),
    /** SRS-8-25: a part of the revision that this release does not compute yet. */
    WR_NOT_AVAILABLE(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "WR-422-NOT-AVAILABLE",
            "arrears/wage-revision-not-available",
            "Not available yet",
            "{0} is not available yet ({1})."),
    /** SRS-8-25: the step does not fit the revision's status. */
    WR_STATE(
            HttpStatus.CONFLICT,
            "WR-409-STATE",
            "arrears/wage-revision-state",
            "Not at this status",
            "The wage revision is {0}; this step needs {1}."),
    /** SRS-8-25: the definition is incomplete or inconsistent. */
    WR_DEFINITION(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "WR-422-DEFINITION",
            "arrears/wage-revision-definition",
            "Definition not valid",
            "The wage revision definition is not valid: {0}."),
    /** R-RULE (plan 13 phase 13g): a value the arrear needs is not configured. */
    NOT_CONFIGURED(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "ARR-422-NOT-CONFIGURED",
            "arrears/not-configured",
            "Not configured",
            "{0} is not configured."),
    /**
     * SRS-8-6: the event reaches further back than the Headquarters back-date limit; it is computed
     * only as a Headquarters-approved exception with its order.
     */
    BEYOND_WINDOW(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "ARR-422-BEYOND-WINDOW",
            "arrears/beyond-window",
            "Beyond the back-date limit",
            "The event starts before the back-date limit of {0} months; it needs a Headquarters"
                    + " exception with its order.");

    private final HttpStatus status;
    private final String code;
    private final String type;
    private final String defaultTitle;
    private final String defaultDetail;

    ArrearErrorCode(
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
