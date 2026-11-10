package in.gov.fci.payroll.platform.notification;

/**
 * The mail server refused the recipient address itself (a bounce known at sending: unknown or
 * invalid mailbox; OD-148, PENDING MAIL-2). Retrying cannot help, so the e-mail is marked failed
 * and bounced at once and listed for the Payroll Maker. The message names no address (R-PII).
 */
public class RecipientRejectedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public RecipientRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
