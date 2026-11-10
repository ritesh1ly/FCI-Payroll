package in.gov.fci.payroll.platform.error;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes a {@link ProblemDetail} directly to a servlet response, for use in filters and Spring
 * Security handlers that run outside Spring MVC. Extension properties are flattened to top-level
 * members exactly as Spring MVC renders them.
 */
public class ProblemDetailWriter {

    private final ObjectMapper jsonMapper;

    public ProblemDetailWriter(ObjectMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public void write(HttpServletResponse response, ProblemDetail problem) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.resetBuffer();
        response.setStatus(problem.getStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-store");
        jsonMapper.writeValue(response.getOutputStream(), toMap(problem));
    }

    /**
     * The RFC 9457 JSON members in order, with extension properties at top level. Rendering this
     * map (rather than the {@link ProblemDetail}) makes the output independent of whether the JSON
     * mapper has Spring's ProblemDetail mix-in registered.
     */
    public static Map<String, Object> toMap(ProblemDetail problem) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", String.valueOf(problem.getType()));
        body.put("title", problem.getTitle());
        body.put("status", problem.getStatus());
        if (problem.getDetail() != null) {
            body.put("detail", problem.getDetail());
        }
        java.net.URI instance = problem.getInstance();
        if (instance != null) {
            body.put("instance", instance.toString());
        }
        java.util.Map<String, Object> properties = problem.getProperties();
        if (properties != null) {
            body.putAll(properties);
        }
        return body;
    }
}
