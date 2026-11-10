package in.gov.fci.payroll.platform.error;

/**
 * The severity of a catalogued message (SRS-EH-1; DDL {@code message_catalogue.severity}), held
 * against the message, never the screen.
 *
 * <ul>
 *   <li>{@link #BLOCKING}: the action cannot proceed. Every refusal answered as a problem response
 *       is Blocking unless it is a Security condition.
 *   <li>{@link #WARNING}: the action may proceed after acknowledgement. Warnings are not refusals:
 *       they are acknowledged where they are raised (C-01 Warn pre-payroll checks, SRS-15-39; X-05
 *       entries raised as WARNING).
 *   <li>{@link #INFORMATIONAL}: shown for information only.
 *   <li>{@link #SECURITY}: a security condition (SRS-EH-5): access refused, a sign-in refused, a
 *       lockout, an attempted edit of a history row (held against the code, {@link
 *       ApiErrorCode#severity()}). The detail is logged as a security event, never put in the user
 *       text.
 * </ul>
 */
public enum MessageSeverity {
    BLOCKING,
    WARNING,
    INFORMATIONAL,
    SECURITY;

    /**
     * SRS-EH-1, SRS-EH-5: the severity of a refusal by its HTTP status. Unauthenticated (401),
     * forbidden (403: outside the Access Group scope, a missing permission, the Maker deciding the
     * own item) and locked (423: an account or OTP lockout) are Security conditions; every other
     * refusal is Blocking unless its code is a Security condition of its own (an attempted history
     * edit, AUD-405-IMMUTABLE / AUD-409-IMMUTABLE).
     */
    public static MessageSeverity ofStatus(int status) {
        return status == 401 || status == 403 || status == 423 ? SECURITY : BLOCKING;
    }
}
