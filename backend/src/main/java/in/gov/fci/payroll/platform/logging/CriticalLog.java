package in.gov.fci.payroll.platform.logging;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;
import org.slf4j.spi.LoggingEventBuilder;

/**
 * OD-130: the one shape of an event an alert will match later: logger {@code SECURITY_AUDIT}
 * (routed to {@code api-security-audit.json} and {@code batch-security-audit.json}), level ERROR, a
 * marker named after the event, and the message {@code event=<NAME> severity=CRITICAL key=value
 * ...}. The same pairs are also structured key values ({@code critical.<key>}) so a log search can
 * use either. Event names are listed in {@code docs/engineering/CRITICAL_LOG_EVENTS.md}.
 *
 * <p>Values are identifiers and reason codes only: never a request body, a name, a login, a PAN or
 * a secret. Each value is sanitised (no line break can forge a second event) and a key that names a
 * secret is redacted.
 */
public final class CriticalLog {

    /** The alertable events; each name is also the SLF4J marker. */
    public enum Event {
        /** A signed-in user's call refused for lack of right or office scope (SRS-G-25, OD-130). */
        SECURITY_OFFENCE,
        /** {@code fci.security.offence.enabled = N} on a deployment that is not development. */
        SECURITY_OFFENCE_DISABLED,
        /** Sign-in limits hit: a burst of attempts on one address or identity (ADR-0008). */
        SIGN_IN_FAILURE_BURST,
        /** Too many wrong passwords: the account is locked for a while (SRS-G-22). */
        ACCOUNT_LOCKED,
        /** Too many wrong OTP codes: the challenge is locked out (SRS-G-22). */
        OTP_LIMIT_EXCEEDED,
        /** A session cookie came from another browser or address. */
        SESSION_HIJACK_SUSPECTED,
        /** The database refused a change to the audit trail (SRS-20-21). */
        AUDIT_TAMPER,
        /** An authorised undo of a posted-side transaction took effect (SRS-15-3). */
        UNDO_APPLIED,
        /** An interface's circuit breaker opened (SRS-INT, X-03). */
        INTEGRATION_BREAKER_OPEN,
        /** A batch job ended FAILED (SRS-NF-2). */
        BATCH_FAILED
    }

    private static final Logger LOG = LoggerFactory.getLogger(SecurityAuditLogger.LOGGER_NAME);
    private static final int MAX_VALUE_LENGTH = 128;

    private CriticalLog() {}

    /** Writes {@code event} with {@code fields}, in their order. */
    public static void log(Event event, Map<String, ?> fields) {
        Marker marker = MarkerFactory.getMarker(event.name());
        LoggingEventBuilder builder = LOG.atError().addMarker(marker);
        StringBuilder message =
                new StringBuilder("event=").append(event.name()).append(" severity=CRITICAL");
        for (Map.Entry<String, ?> field : fields.entrySet()) {
            String key = LogSanitizer.sanitize(field.getKey(), MAX_VALUE_LENGTH);
            String value =
                    LogSanitizer.isSensitiveName(key)
                            ? LogSanitizer.REDACTED
                            : LogSanitizer.sanitize(
                                    String.valueOf(field.getValue()), MAX_VALUE_LENGTH);
            builder = builder.addKeyValue("critical." + key, value);
            message.append(' ').append(key).append('=').append(value.replace(' ', '_'));
        }
        builder.log(message.toString());
    }

    /** Key-value pairs in order: {@code fields("user", 7, "action", "LOGOUT")}. */
    public static Map<String, Object> fields(Object... pairs) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            out.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return out;
    }
}
