package in.gov.fci.payroll.platform.notification;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.SendFailedException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLException;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;

/**
 * What a failed e-mail means for its retry (OD-148; SRS-17-12, SRS-INT-6, SRS-INT-16; RFC 5321
 * reply codes; PENDING MAIL-2). The class decides the next step, never the record's data:
 *
 * <ul>
 *   <li>{@link Kind#TRANSIENT}: 4xx (421 service not available, 450 / 451 / 452), a time-out, a
 *       refused or unknown connection, a TLS failure, or anything not recognised: retried with
 *       back-off and jitter, and counted against the channel's breaker;
 *   <li>{@link Kind#RECIPIENT}: 550 / 551 / 553 / 554 or an address the server refused: failed at
 *       once and bounced;
 *   <li>{@link Kind#SIZE}: 552 (message too large): sent again without the PDF or failed, by the
 *       channel's setting, never retried as it is;
 *   <li>{@link Kind#AUTH}: rejected credentials (530 / 534 / 535): the channel pauses, the e-mail's
 *       attempt is not spent;
 *   <li>{@link Kind#PERMANENT}: any other 5xx (500 / 501 / 503 / 504 syntax or command refused):
 *       failed at once, not a bounce.
 * </ul>
 */
public final class MailFailures {

    /** The class of a failure. */
    public enum Kind {
        TRANSIENT,
        RECIPIENT,
        SIZE,
        AUTH,
        PERMANENT
    }

    /** A failure's class and its SMTP reply code (0 when none was given). */
    public record Failure(Kind kind, int code) {}

    private static final Pattern REPLY = Pattern.compile("(?<![0-9])([45][0-9]{2})(?![0-9])");

    private MailFailures() {}

    /** The class of {@code failure}: the most specific cause decides. */
    public static Failure classify(Throwable failure) {
        if (failure instanceof RecipientRejectedException) {
            return new Failure(Kind.RECIPIENT, 550);
        }
        int code = 0;
        for (Throwable t = failure; t != null && t.getCause() != t; t = t.getCause()) {
            if (t instanceof MailAuthenticationException
                    || t instanceof AuthenticationFailedException) {
                return new Failure(Kind.AUTH, 535);
            }
            int c = code(t);
            if (c != 0 && code == 0) {
                code = c;
            }
            if (t instanceof SendFailedException s
                    && s.getInvalidAddresses() != null
                    && s.getInvalidAddresses().length > 0
                    && (code == 0 || code >= 500)) {
                return new Failure(Kind.RECIPIENT, code == 0 ? 550 : code);
            }
            if (t instanceof SocketTimeoutException
                    || t instanceof ConnectException
                    || t instanceof UnknownHostException
                    || t instanceof SSLException) {
                return new Failure(Kind.TRANSIENT, code);
            }
        }
        if (failure instanceof MailSendException m) {
            for (Exception e : m.getFailedMessages().values()) {
                Failure inner = classify(e);
                if (inner.kind() != Kind.TRANSIENT || inner.code() != 0) {
                    return inner;
                }
            }
        }
        return ofCode(code);
    }

    /** The class of an SMTP reply code alone. */
    static Failure ofCode(int code) {
        Kind kind =
                switch (code) {
                    case 530, 534, 535 -> Kind.AUTH;
                    case 550, 551, 553, 554 -> Kind.RECIPIENT;
                    case 552 -> Kind.SIZE;
                    default -> code >= 500 ? Kind.PERMANENT : Kind.TRANSIENT;
                };
        return new Failure(kind, code);
    }

    /**
     * The 4xx / 5xx reply code carried by {@code t}: the mail library puts the server's reply ("552
     * 5.3.4 Message size exceeds ...") in the exception's text; the first code in it.
     */
    static int code(Throwable t) {
        String message = t.getMessage();
        if (message == null) {
            return 0;
        }
        Matcher m = REPLY.matcher(message);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }
}
