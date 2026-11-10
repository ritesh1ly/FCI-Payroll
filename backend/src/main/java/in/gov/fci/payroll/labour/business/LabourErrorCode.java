package in.gov.fci.payroll.labour.business;

import in.gov.fci.payroll.platform.error.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * Errors of the labour module (Module 20; docs/api/errors.md). The screen shows {@code
 * error.<code>} from its bundle (English and Hindi); the texts here are English defaults.
 */
public enum LabourErrorCode implements ErrorCode {
    /** SRS-2-30 (M-02): the Labour Master entry is not valid. */
    ENTRY(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "LBR-422-ENTRY",
            "labour/entry",
            "Labour Master entry not valid",
            "The Labour Master entry is not valid: {0}."),
    /** SRS-3-11 / LC-9: an identifier is already held (PAN, CPF code, UAN). */
    DUPLICATE(
            HttpStatus.CONFLICT,
            "LBR-409-DUPLICATE",
            "labour/duplicate",
            "Already held",
            "{0} is already held by another member."),
    /** SRS-2-1 / SRS-2-31 (M-09): the gang is not valid. */
    GANG(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "LBR-422-GANG",
            "labour/gang",
            "Gang not valid",
            "The gang is not valid: {0}."),
    /** SRS-2-35 / SRS-2-2 (I-01): the day's attendance is not valid. */
    ATTENDANCE(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "LBR-422-ATTENDANCE",
            "labour/attendance",
            "Attendance not valid",
            "The attendance is not valid: {0}."),
    /** SRS-2-38: one status per labour per date across all gangs and units. */
    MARKED(
            HttpStatus.CONFLICT,
            "LBR-409-MARKED",
            "labour/marked",
            "Already marked",
            "{0} is already marked {1} on {2} in gang {3} at depot {4}."),
    /** SRS-2-3 / SRS-2-2: the day's mark cannot change (work-slip filed, past retirement). */
    BLOCKED(
            HttpStatus.CONFLICT,
            "LBR-409-BLOCKED",
            "labour/blocked",
            "Attendance cannot change",
            "The attendance of {0} on {1} cannot change: {2}."),
    /** SRS-2-4 / SRS-2-42 (I-02): the work-slip is not valid. */
    WORK_SLIP(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "LBR-422-WORK-SLIP",
            "labour/work-slip",
            "Work-slip not valid",
            "The work-slip is not valid: {0}."),
    /** SRS-3-9: the work repeats a saved work-slip (operation, norm, slab and time frame). */
    DUPLICATE_SLIP(
            HttpStatus.CONFLICT,
            "LBR-409-DUPLICATE-SLIP",
            "labour/duplicate-slip",
            "Duplicate work-slip",
            "The work repeats work-slip {0}."),
    /** SRS-4-13 (DPS): the month's booking-slips are not all approved by the Depot Manager. */
    BOOKING_PENDING(
            HttpStatus.CONFLICT,
            "LBR-409-BOOKING-PENDING",
            "labour/booking-pending",
            "Booking-slips not approved",
            "{0} booking-slip(s) wait for the Depot Manager: {1}."),
    /** SRS-3-12 (M-06): the depot norm set is not valid. */
    NORM(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "LBR-422-NORM",
            "labour/norm",
            "Norm set not valid",
            "The norm set is not valid: {0}."),
    /** SRS-4-11 (M-05): the labour rate set is not valid. */
    RATE(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "LBR-422-RATE",
            "labour/rate",
            "Rate set not valid",
            "The rate set is not valid: {0}."),
    /** SRS-2-6 / SRS-2-47 (M-02, M-09): the transfer or attachment is not allowed. */
    TRANSFER(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "LBR-422-TRANSFER",
            "labour/transfer",
            "Transfer not allowed",
            "The transfer is not allowed: {0}."),
    /** A setting the computation needs is not configured (never assumed). */
    NOT_CONFIGURED(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "LBR-422-NOT-CONFIGURED",
            "labour/not-configured",
            "Not configured",
            "{0} is not configured."),
    /** The action does not fit the record's status. */
    STATE(
            HttpStatus.CONFLICT,
            "LBR-409-STATE",
            "labour/state",
            "Not at this status",
            "{0} is {1}; this action needs {2}.");

    private final HttpStatus status;
    private final String code;
    private final String type;
    private final String defaultTitle;
    private final String defaultDetail;

    LabourErrorCode(
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
