package in.gov.fci.payroll.iam.security;

import in.gov.fci.payroll.platform.logging.MdcKeys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

/**
 * API logging inside the Spring Security chain (no separate API gateway): one line per request with
 * method, path (never the query string: it can carry search terms or e-mail addresses), status,
 * duration, user id and correlation id. Logger {@code FCI_API_ACCESS}.
 */
public class ApiAccessLogFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger("FCI_API_ACCESS");
    private static final UrlPathHelper PATHS = new UrlPathHelper();

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            long ms = (System.nanoTime() - start) / 1_000_000;
            String user =
                    WebSessions.user(request)
                            .map(u -> String.valueOf(u.userId()))
                            .orElse("anonymous");
            log.info(
                    "event=API_ACCESS method={} path={} status={} durationMs={} user={}"
                            + " correlationId={}",
                    request.getMethod(),
                    PATHS.getPathWithinApplication(request),
                    response.getStatus(),
                    ms,
                    user,
                    MDC.get(MdcKeys.CORRELATION_ID));
        }
    }
}
