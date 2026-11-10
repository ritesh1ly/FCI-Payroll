package in.gov.fci.payroll.platform.logging;

import in.gov.fci.payroll.platform.security.SecureTokens;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Runs immediately after the Spring Security filter chain and adds the authenticated user id and a
 * hashed session id to the MDC. The raw session id is never logged. {@link MdcFilter} clears the
 * MDC at the end of the request.
 */
public class MdcSecurityContextFilter extends OncePerRequestFilter {

    private static final int SESSION_HASH_LENGTH = 16;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken)) {
            MDC.put(MdcKeys.USER_ID, LogSanitizer.sanitize(authentication.getName(), 64));
        }
        HttpSession session = request.getSession(false);
        if (session != null) {
            MDC.put(MdcKeys.SESSION_ID, hashSessionId(session.getId()));
        }
        chain.doFilter(request, response);
    }

    /** One-way, truncated hash of a session id, suitable for log correlation only. */
    public static String hashSessionId(String sessionId) {
        return SecureTokens.sha256Hex(sessionId).substring(0, SESSION_HASH_LENGTH);
    }
}
