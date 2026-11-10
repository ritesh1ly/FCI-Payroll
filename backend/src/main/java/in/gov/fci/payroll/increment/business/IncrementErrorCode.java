package in.gov.fci.payroll.increment.business;

import in.gov.fci.payroll.platform.error.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * Errors of the increment and pay fixation module (Module 17; docs/api/errors.md). The screen shows
 * {@code error.<code>} from its bundle (English and Hindi); the texts here are English defaults.
 */
public enum IncrementErrorCode implements ErrorCode {
    /** SRS-13-23 / I-05: the Basic cannot be taken (bar, scale maximum, stage, configuration). */
    FLAGGED(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "INC-422-FLAGGED",
            "increment/flagged",
            "Basic not applicable",
            "The revised Basic of {0} cannot be applied: {1}."),
    /** SRS-13-5: an increment of the due period exists already. */
    DUPLICATE(
            HttpStatus.CONFLICT,
            "INC-409-DUPLICATE",
            "increment/duplicate",
            "Increment already given",
            "{0} already has an increment for the due period {1}."),
    /** SC-17-0030 (US-GAP-56): a scale code is unique on M-03. */
    SCALE_CODE_TAKEN(
            HttpStatus.CONFLICT,
            "INC-409-SCALE-CODE",
            "increment/scale-code",
            "Scale code taken",
            "The scale code {0} is already used; a scale code is unique."),
    /** The action does not fit the case's status. */
    STATE(
            HttpStatus.CONFLICT,
            "INC-409-STATE",
            "increment/state",
            "Not at this status",
            "The case is {0}; this action needs {1}."),
    /** SRS-13-18 / SRS-13-24: the entry is incomplete (order, reason, value). */
    ENTRY(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "INC-422-ENTRY",
            "increment/entry",
            "Entry not valid",
            "The entry is not valid: {0}."),
    /** R-RULE: a scale or rule this case needs is not configured. */
    NOT_CONFIGURED(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "INC-422-NOT-CONFIGURED",
            "increment/not-configured",
            "Not configured",
            "{0} is not configured.");

    private final HttpStatus status;
    private final String code;
    private final String type;
    private final String defaultTitle;
    private final String defaultDetail;

    IncrementErrorCode(
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
