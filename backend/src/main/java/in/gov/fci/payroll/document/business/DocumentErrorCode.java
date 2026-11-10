package in.gov.fci.payroll.document.business;

import in.gov.fci.payroll.platform.error.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * Errors of the document module (docs/api/errors.md). The screen shows {@code error.<code>} from
 * its own bundle (English and Hindi); the texts here are the English defaults for logs and API
 * clients. No message ever repeats the file name or other request input.
 */
public enum DocumentErrorCode implements ErrorCode {
    /** SRS-G-30: only PDF, JPG and PNG, recognised from the bytes, not from the name or header. */
    FILE_TYPE(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "DOC-422-TYPE",
            "document/file-type",
            "File type not accepted",
            "Attach a PDF, JPG or PNG file."),
    /** SRS-G-30 / CF-13: the file-size limit is a setting (fci.documents.max-file-bytes). */
    FILE_SIZE(
            HttpStatus.CONTENT_TOO_LARGE,
            "DOC-413-SIZE",
            "document/file-size",
            "File too large",
            "The file is larger than the limit of {0} MB."),
    /** SRS-G-30 / CF-13: the file-count limit per transaction is a setting. */
    FILE_COUNT(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "DOC-422-COUNT",
            "document/file-count",
            "Too many files",
            "A transaction may carry at most {0} files."),
    EMPTY_FILE(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "DOC-422-EMPTY",
            "document/empty",
            "Empty file",
            "The file is empty."),
    /** OC-3: order / reference number mandatory for a Sanction Order or a Circular. */
    REFERENCE_REQUIRED(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "DOC-422-REFERENCE",
            "document/reference-required",
            "Reference number required",
            "Enter the order or reference number for this document type."),
    /** SRS-G-30, DDL S25: an order needs its type, date and issuing authority. */
    ORDER_PARTICULARS(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "DOC-422-PARTICULARS",
            "document/order-particulars",
            "Order particulars missing",
            "Enter the document type, order date and issuing authority."),
    UNKNOWN_TYPE(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "DOC-422-DOC-TYPE",
            "document/unknown-type",
            "Unknown document type",
            "Choose a document type from the list."),
    /** SRS-G-30: every file is virus-scanned before storage. */
    INFECTED(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "DOC-422-INFECTED",
            "document/infected",
            "File refused",
            "The file did not pass the virus scan and was not stored."),
    SCAN_UNAVAILABLE(
            HttpStatus.SERVICE_UNAVAILABLE,
            "DOC-503-SCAN",
            "document/scan-unavailable",
            "Virus scan unavailable",
            "The file could not be scanned now. Try again later."),
    /** SRS-G-31: after submission a file can only be added, never replaced. */
    REPLACE_AFTER_SUBMIT(
            HttpStatus.CONFLICT,
            "DOC-409-SUBMITTED",
            "document/submitted",
            "Already submitted",
            "After submission a file can only be added, not replaced.");

    private final HttpStatus status;
    private final String code;
    private final String type;
    private final String defaultTitle;
    private final String defaultDetail;

    DocumentErrorCode(
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
