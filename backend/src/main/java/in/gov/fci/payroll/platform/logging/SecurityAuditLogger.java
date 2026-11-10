package in.gov.fci.payroll.platform.logging;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;

/**
 * Writes {@link SecurityEvent}s to the dedicated {@value #LOGGER_NAME} logger, which the Logback
 * configuration routes to its own file with 180-day retention. Applications that persist audit
 * events (fci-api) call this in addition to writing the {@code audit_events} collection.
 */
public class SecurityAuditLogger {

    public static final String LOGGER_NAME = "SECURITY_AUDIT";

    private static final Logger AUDIT = LoggerFactory.getLogger(LOGGER_NAME);
    private static final int MAX_VALUE_LENGTH = 128;

    public void log(SecurityEvent event) {
        LoggingEventBuilder builder =
                event.outcome() == SecurityEvent.Outcome.FAILURE ? AUDIT.atWarn() : AUDIT.atInfo();
        builder =
                builder.addKeyValue("audit.type", event.type())
                        .addKeyValue("audit.outcome", event.outcome().name())
                        .addKeyValue("audit.actor", clean(event.actorId()));
        if (event.targetType() != null) {
            builder = builder.addKeyValue("audit.targetType", clean(event.targetType()));
        }
        if (event.targetId() != null) {
            builder = builder.addKeyValue("audit.targetId", clean(event.targetId()));
        }
        if (event.reason() != null) {
            builder = builder.addKeyValue("audit.reason", clean(event.reason()));
        }
        for (Map.Entry<String, String> attribute : event.attributes().entrySet()) {
            String key = attribute.getKey();
            String value =
                    LogSanitizer.isSensitiveName(key)
                            ? LogSanitizer.REDACTED
                            : clean(attribute.getValue());
            builder = builder.addKeyValue("audit." + clean(key), value);
        }
        builder.log("event=SECURITY_AUDIT type={} outcome={}", event.type(), event.outcome());
    }

    private static String clean(String value) {
        return LogSanitizer.sanitize(value, MAX_VALUE_LENGTH);
    }
}
