package in.gov.fci.payroll.platform.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Highest-precedence filter that establishes the request's logging context: correlation id
 * (accepted from {@code X-Correlation-Id} only when well-formed, otherwise generated), client IP,
 * HTTP method, URI and application name. The correlation id is echoed in the response. The MDC is
 * cleared when the request completes.
 *
 * <p>The client IP is {@link HttpServletRequest#getRemoteAddr()}, which Tomcat's {@code
 * RemoteIpValve} resolves from the trusted proxy header; raw forwarding headers are never parsed
 * here. Trace and span ids are maintained by Micrometer Tracing.
 */
public class MdcFilter extends OncePerRequestFilter {

    private static final Pattern CORRELATION_ID = Pattern.compile("^[A-Za-z0-9-]{8,64}$");
    private static final Pattern IP_ADDRESS = Pattern.compile("^[0-9A-Fa-f:.%]{2,64}$");
    private static final int MAX_URI_LENGTH = 256;

    private final String appName;

    public MdcFilter(String appName) {
        this.appName = LogSanitizer.sanitize(appName, 32);
    }

    /** The correlation id of the current request, or {@code null} outside a request. */
    public static String currentCorrelationId() {
        return MDC.get(MdcKeys.CORRELATION_ID);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String correlationId = resolveCorrelationId(request);
        request.setAttribute(MdcKeys.CORRELATION_ATTRIBUTE, correlationId);
        response.setHeader(MdcKeys.CORRELATION_HEADER, correlationId);
        try {
            MDC.put(MdcKeys.CORRELATION_ID, correlationId);
            MDC.put(MdcKeys.APP, appName);
            MDC.put(MdcKeys.HTTP_METHOD, LogSanitizer.sanitize(request.getMethod(), 16));
            MDC.put(MdcKeys.URI, LogSanitizer.sanitize(request.getRequestURI(), MAX_URI_LENGTH));
            MDC.put(MdcKeys.CLIENT_IP, clientIp(request));
            chain.doFilter(request, response);
        } finally {
            MDC.clear();
        }
    }

    /** Also runs on error dispatches so that container error handling keeps the same context. */
    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    static boolean isValidCorrelationId(String value) {
        return value != null && CORRELATION_ID.matcher(value).matches();
    }

    private static String resolveCorrelationId(HttpServletRequest request) {
        Object existing = request.getAttribute(MdcKeys.CORRELATION_ATTRIBUTE);
        if (existing instanceof String id && isValidCorrelationId(id)) {
            return id;
        }
        String incoming = request.getHeader(MdcKeys.CORRELATION_HEADER);
        return isValidCorrelationId(incoming) ? incoming : UUID.randomUUID().toString();
    }

    private static String clientIp(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        return remote != null && IP_ADDRESS.matcher(remote).matches() ? remote : "unknown";
    }
}
