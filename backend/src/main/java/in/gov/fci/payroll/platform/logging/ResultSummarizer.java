package in.gov.fci.payroll.platform.logging;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.util.ClassUtils;

/**
 * Builds a PII-free summary of a method result for the ESER Return line: the type plus a size, a
 * total or an identifier, never the payload itself.
 */
public class ResultSummarizer {

    private static final boolean SPRING_DATA_PRESENT =
            ClassUtils.isPresent(
                    "org.springframework.data.domain.Page",
                    ResultSummarizer.class.getClassLoader());

    public String summarize(Object result) {
        if (result == null) {
            return "{type=null}";
        }
        if (result instanceof ResponseEntity<?> entity) {
            return "{type=ResponseEntity, status="
                    + entity.getStatusCode().value()
                    + ", body="
                    + summarize(entity.getBody())
                    + "}";
        }
        if (result instanceof Optional<?> optional) {
            return optional.map(value -> "{type=Optional, value=" + summarize(value) + "}")
                    .orElse("{type=Optional, empty=true}");
        }
        String type = result.getClass().getSimpleName();
        if (SPRING_DATA_PRESENT && SpringDataSummaries.supports(result)) {
            return SpringDataSummaries.summarize(type, result);
        }
        if (result instanceof Collection<?> collection) {
            return "{type=" + type + ", size=" + collection.size() + "}";
        }
        if (result instanceof Map<?, ?> map) {
            return "{type=" + type + ", size=" + map.size() + "}";
        }
        if (result.getClass().isArray()) {
            return "{type=" + type + ", length=" + java.lang.reflect.Array.getLength(result) + "}";
        }
        if (result instanceof Identifiable identifiable) {
            return "{type=" + type + ", id=" + LogSanitizer.sanitize(identifiable.id()) + "}";
        }
        if (result instanceof Boolean || result instanceof Number || result instanceof Enum<?>) {
            return "{type=" + type + ", value=" + result + "}";
        }
        return "{type=" + type + "}";
    }

    /** Isolated so that Spring Data classes are only loaded when present on the classpath. */
    private static final class SpringDataSummaries {

        static boolean supports(Object result) {
            return result instanceof org.springframework.data.domain.Slice<?>;
        }

        static String summarize(String type, Object result) {
            if (result instanceof org.springframework.data.domain.Page<?> page) {
                return "{type=Page, page="
                        + page.getNumber()
                        + ", size="
                        + page.getNumberOfElements()
                        + ", total="
                        + page.getTotalElements()
                        + "}";
            }
            org.springframework.data.domain.Slice<?> slice =
                    (org.springframework.data.domain.Slice<?>) result;
            return "{type="
                    + type
                    + ", page="
                    + slice.getNumber()
                    + ", size="
                    + slice.getNumberOfElements()
                    + "}";
        }
    }
}
