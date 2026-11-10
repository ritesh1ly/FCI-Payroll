package in.gov.fci.payroll.allowance.business;

import in.gov.fci.payroll.platform.error.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * Errors of the allowances module (Module 10; docs/api/errors.md). The screen shows {@code
 * error.<code>} from its bundle (English and Hindi); the texts here are English defaults.
 */
public enum AllowanceErrorCode implements ErrorCode {
    /** SRS I-03 field table: the claim's particulars. */
    CLAIM(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "ALW-422-CLAIM",
            "allowance/claim",
            "Claim not valid",
            "The claim is not valid: {0}."),
    /** SRS-11-6 (AL-3): an overlapping claim of the same type. */
    OVERLAP(
            HttpStatus.CONFLICT,
            "ALW-409-OVERLAP",
            "allowance/overlap",
            "Overlapping claim",
            "The period overlaps claim {0} of the same type ({1} to {2})."),
    /** SRS-11-14 (AL-4): a required supporting document is missing. */
    DOCUMENTS(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "ALW-422-DOCUMENTS",
            "allowance/documents",
            "Supporting document missing",
            "Attach the required supporting documents first: {0}."),
    /** R-RULE / AL-2: no confirmed M-15 norm in force for the claim type. */
    NOT_CONFIGURED(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "ALW-422-NOT-CONFIGURED",
            "allowance/not-configured",
            "Not configured",
            "{0} is not configured."),
    /** SRS-11-1: an employee's claim comes from HRMS only. */
    HRMS_ONLY(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "ALW-422-HRMS-ONLY",
            "allowance/hrms-only",
            "Received from HRMS",
            "An employee's claim is received from HRMS through I-21; it is not entered on I-03."),
    /** SRS-11-5: no medical payment to a retired or separated member. */
    RETIRED(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "ALW-422-RETIRED",
            "allowance/retired",
            "Member retired",
            "Member {0} has retired or separated: no medical reimbursement is paid."),
    /**
     * I-03 Employee / Labour Number (SRS l.2796, US-GAP-216): "Active member within the user's
     * scope" - a labour claim of a member not in service is refused.
     */
    INACTIVE(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "ALW-422-INACTIVE",
            "allowance/inactive",
            "Member not active",
            "Member {0} is not an active member ({1}): no claim is entered or validated."),
    /**
     * SRS use case precondition (l.2765, US-GAP-212): the claimant's category and eligibility are
     * set (labour master / HRMS); the M-15 norm of the type is set only by eligibility category.
     */
    CATEGORY(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "ALW-422-CATEGORY",
            "allowance/category",
            "Eligibility category not set",
            "The M-15 norm of {0} is set by eligibility category, and the member's category is"
                    + " not set: the claim is refused."),
    /** A step not possible in the claim's state. */
    STATE(
            HttpStatus.CONFLICT,
            "ALW-409-STATE",
            "allowance/state",
            "Not possible now",
            "The claim is {0}; this step is not possible now.");

    private final HttpStatus status;
    private final String code;
    private final String type;
    private final String defaultTitle;
    private final String defaultDetail;

    AllowanceErrorCode(
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
