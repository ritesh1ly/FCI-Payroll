package in.gov.fci.payroll.platform.logging;

import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer;

/**
 * Applies {@link SensitiveDataMasker} to every string value written by Spring Boot structured
 * logging (ECS): message, MDC values, key-value pairs and stack traces. Registered through {@code
 * META-INF/spring.factories}, so every application using {@code fci-common} gets it automatically.
 * JSON encoding already escapes CR/LF, which prevents log forging.
 */
public class MaskingJsonMembersCustomizer
        implements StructuredLoggingJsonMembersCustomizer<Object> {

    @Override
    public void customize(JsonWriter.Members<Object> members) {
        members.applyingValueProcessor(
                JsonWriter.ValueProcessor.of(String.class, SensitiveDataMasker::mask));
    }
}
