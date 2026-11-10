package in.gov.fci.payroll.settlement.business;

import in.gov.fci.payroll.platform.error.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * Errors of the settlement module (Module 11; docs/api/errors.md). The screen shows {@code
 * error.<code>} from its bundle (English and Hindi); the texts here are English defaults.
 */
public enum SettlementErrorCode implements ErrorCode {
    /** SRS-10-5: no settlement without a separation date, or for a member still in service. */
    NOT_SETTLEABLE(
            HttpStatus.CONFLICT,
            "FNF-409-NOT-SETTLEABLE",
            "settlement/not-settleable",
            "Not settleable",
            "The settlement cannot run: {0}."),
    /** SRS-10-18: the action does not fit the case's stage. */
    STAGE(
            HttpStatus.CONFLICT,
            "FNF-409-STAGE",
            "settlement/stage",
            "Not at this stage",
            "The case is at stage {0}; this action needs stage {1}."),
    /** SRS-10-28: a source module cannot answer: never settled on partial data. */
    SOURCE(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "FNF-422-SOURCE",
            "settlement/source",
            "A source cannot answer",
            "The settlement cannot be computed: {0}."),
    /** SRS-10-14: an outstanding clearance holds the case. */
    CLEARANCE(
            HttpStatus.CONFLICT,
            "FNF-409-CLEARANCE",
            "settlement/clearance",
            "Clearance outstanding",
            "The clearance {0} is outstanding: it must be received or excluded with a reason."),
    /** SRS-10-21 / 10-22 / 10-25: the entry is not valid. */
    ENTRY(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "FNF-422-ENTRY",
            "settlement/entry",
            "Entry not valid",
            "The entry is not valid: {0}."),
    /** SRS-10-22 / 10-6: an approval the case needs first is still pending. */
    APPROVAL_PENDING(
            HttpStatus.CONFLICT,
            "FNF-409-APPROVAL",
            "settlement/approval-pending",
            "Approval pending",
            "The case needs the {0} first."),
    /**
     * SRS-10-14 / SRS-10-30 (Wave A review): the settlement is no longer the one submitted; the
     * Checker returns it.
     */
    CHANGED(
            HttpStatus.CONFLICT,
            "FNF-409-CHANGED",
            "settlement/changed",
            "Settlement changed",
            "The settlement was submitted at a net payable of {0} and now shows {1}: return it to"
                    + " the Maker to submit again.");

    private final HttpStatus status;
    private final String code;
    private final String type;
    private final String defaultTitle;
    private final String defaultDetail;

    SettlementErrorCode(
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
