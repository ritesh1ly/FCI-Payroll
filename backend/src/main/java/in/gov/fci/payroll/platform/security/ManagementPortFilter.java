package in.gov.fci.payroll.platform.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Separates the management (Actuator) port from the application port when the WAR runs in an
 * external Tomcat, where {@code management.server.port} has no effect (ADR-0016). Requests for
 * Actuator paths are served only on the management connector, and the management connector serves
 * nothing else. Anything else gets a bare 404 so that the endpoints' existence is not revealed.
 */
public class ManagementPortFilter extends OncePerRequestFilter {

    private final int managementPort;
    private final String actuatorBasePath;

    /**
     * @param managementPort the local port of the management connector; 0 or less disables the
     *     filter (for example with an embedded server in local development)
     * @param actuatorBasePath the Actuator base path, normally {@code /actuator}
     */
    public ManagementPortFilter(int managementPort, String actuatorBasePath) {
        this.managementPort = managementPort;
        this.actuatorBasePath =
                actuatorBasePath.endsWith("/")
                        ? actuatorBasePath.substring(0, actuatorBasePath.length() - 1)
                        : actuatorBasePath;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return managementPort <= 0;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        boolean onManagementPort = request.getLocalPort() == managementPort;
        boolean actuatorPath = isActuatorPath(applicationPath(request));
        if (onManagementPort != actuatorPath) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            response.setContentLength(0);
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean isActuatorPath(String path) {
        return path.equals(actuatorBasePath) || path.startsWith(actuatorBasePath + "/");
    }

    /**
     * The decoded, normalised path inside the web application, as the container resolved it. The
     * raw request URI is not used: {@code /act%75ator} or {@code /x/../actuator} would otherwise
     * dodge the check and still be routed to Actuator after decoding.
     */
    private static String applicationPath(HttpServletRequest request) {
        String servletPath = request.getServletPath();
        String pathInfo = request.getPathInfo();
        String path = (servletPath == null ? "" : servletPath) + (pathInfo == null ? "" : pathInfo);
        return path.isEmpty() ? "/" : path;
    }
}
