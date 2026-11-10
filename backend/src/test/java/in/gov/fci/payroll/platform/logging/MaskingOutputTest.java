package in.gov.fci.payroll.platform.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer;
import org.springframework.core.io.support.SpringFactoriesLoader;

class MaskingOutputTest {

    @Test
    void messageConverterMasksAndEncodesCrLf() {
        LoggerContext context = new LoggerContext();
        LoggingEvent event =
                new LoggingEvent(
                        "fqcn",
                        context.getLogger("test"),
                        Level.INFO,
                        "login {} password={}\nforged",
                        null,
                        new Object[] {"ritesh@fci.gov.in", "Hunter2"});
        MaskingMessageConverter converter = new MaskingMessageConverter();

        assertThat(converter.convert(event))
                .isEqualTo("login r***@f***.gov.in password=***\\nforged");
    }

    @Test
    void jsonCustomizerMasksEveryStringMember() {
        JsonWriter<Map<String, String>> writer =
                JsonWriter.of(
                        members -> {
                            members.add("message", m -> m.get("message"));
                            members.add("mdc")
                                    .usingMembers(
                                            nested -> nested.add("mobile", m -> m.get("mobile")));
                            applyCustomizer(members);
                        });

        String json =
                writer.writeToString(
                        Map.of(
                                "message",
                                "token=abc123 for 9876543210",
                                "mobile",
                                "+919876543210"));

        assertThat(json).doesNotContain("abc123", "9876543210").contains("token=***", "******3210");
    }

    @Test
    void customizerIsRegisteredForSpringBootStructuredLogging() {
        assertThat(
                        SpringFactoriesLoader.forDefaultResourceLocation(
                                        getClass().getClassLoader())
                                .load(StructuredLoggingJsonMembersCustomizer.class))
                .hasAtLeastOneElementOfType(MaskingJsonMembersCustomizer.class);
    }

    @SuppressWarnings("unchecked")
    private static <T> void applyCustomizer(JsonWriter.Members<T> members) {
        new MaskingJsonMembersCustomizer()
                .customize((JsonWriter.Members<Object>) (JsonWriter.Members<?>) members);
    }
}
