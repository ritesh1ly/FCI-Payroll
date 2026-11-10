package in.gov.fci.payroll.platform.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.slf4j.event.KeyValuePair;

class SecurityAuditLoggerTest {

    private final SecurityAuditLogger auditLogger = new SecurityAuditLogger();

    @Test
    void writesFailureAtWarnWithStructuredKeyValues() {
        try (LogCapture logs = LogCapture.of(SecurityAuditLogger.LOGGER_NAME, Level.INFO)) {
            auditLogger.log(
                    SecurityEvent.failure("LOGIN_FAILURE", null, "BAD_CREDENTIALS")
                            .withTarget("USER", "u-1\r\nforged")
                            .withAttribute("clientIp", "10.0.0.1")
                            .withAttribute("password", "leak"));

            ILoggingEvent event = logs.events().get(0);
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage())
                    .isEqualTo("event=SECURITY_AUDIT type=LOGIN_FAILURE outcome=FAILURE");
            Map<String, Object> pairs = pairs(event);
            assertThat(pairs)
                    .containsEntry("audit.type", "LOGIN_FAILURE")
                    .containsEntry("audit.outcome", "FAILURE")
                    .containsEntry("audit.actor", "anonymous")
                    .containsEntry("audit.reason", "BAD_CREDENTIALS")
                    .containsEntry("audit.targetType", "USER")
                    .containsEntry("audit.targetId", "u-1\\r\\nforged")
                    .containsEntry("audit.clientIp", "10.0.0.1")
                    .containsEntry("audit.password", "***");
        }
    }

    @Test
    void writesSuccessAtInfo() {
        try (LogCapture logs = LogCapture.of(SecurityAuditLogger.LOGGER_NAME, Level.INFO)) {
            auditLogger.log(SecurityEvent.success("LOGOUT", "u-7"));
            assertThat(logs.events().get(0).getLevel()).isEqualTo(Level.INFO);
            assertThat(pairs(logs.events().get(0))).doesNotContainKey("audit.targetId");
        }
    }

    @Test
    void rejectsBadEventTypes() {
        assertThatThrownBy(() -> SecurityEvent.success("login ok", "u"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new SecurityEvent(
                                        null,
                                        SecurityEvent.Outcome.SUCCESS,
                                        "u",
                                        null,
                                        null,
                                        null,
                                        null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void attributesAreImmutableAndOrdered() {
        Map<String, String> source = new LinkedHashMap<>();
        source.put("b", "2");
        source.put("a", null);
        SecurityEvent event =
                new SecurityEvent(
                        "ROLE_CHANGED",
                        SecurityEvent.Outcome.SUCCESS,
                        "u",
                        null,
                        null,
                        null,
                        source);
        source.put("c", "3");

        assertThat(event.attributes()).containsOnlyKeys("b", "a");
        assertThat(event.attributes().keySet()).containsExactly("b", "a");
        assertThatThrownBy(() -> event.attributes().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static Map<String, Object> pairs(ILoggingEvent event) {
        return event.getKeyValuePairs().stream()
                .collect(Collectors.toMap(p -> p.key, (KeyValuePair p) -> p.value));
    }
}
