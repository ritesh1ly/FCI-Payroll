package in.gov.fci.payroll.platform.notification;

/**
 * The generation of the member's document recorded a failure (SRS-NF-5 / SRS-NF-7 batch
 * generation): the e-mail is FAILED with that reason, never retried as it is, and listed for the
 * Payroll Maker (OD-148). The reason names no personal data (R-PII).
 */
public class DocumentFailedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DocumentFailedException(String reason) {
        super(reason);
    }
}
