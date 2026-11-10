package in.gov.fci.payroll.iam.security;

import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.ProblemResponder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.Locale;
import java.util.Set;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Resource isolation of the browser API (OWASP CSRF Prevention Cheat Sheet, "Fetch Metadata" and
 * "Verifying Origin with standard headers"; ASVS V4.2.2 / V50.4.3): a state-changing request that a
 * browser marks as coming from another site is refused before the CSRF token is even looked at.
 *
 * <p>The CSRF double-submit token (SecurityConfig) stays the primary defence. This filter closes
 * the gap the double-submit pattern leaves open by design: whoever can plant a cookie on the parent
 * domain (a sibling sub-domain, a plain-HTTP response on a hostile network) can choose both the
 * cookie and the header and so pass the token check. A browser cannot be made to lie about where a
 * request comes from, so:
 *
 * <ul>
 *   <li>an unsafe method (anything but GET, HEAD, OPTIONS) with {@code Sec-Fetch-Site} other than
 *       {@code same-origin} or {@code none} (typed by the user) is refused: {@code cross-site} and
 *       also {@code same-site}, because a sibling sub-domain is exactly the cookie-planting case;
 *   <li>a browser too old to send Fetch Metadata still sends {@code Origin} on a cross-origin
 *       write: its host must be the request's own host, and the opaque {@code Origin: null} of a
 *       sandboxed frame or a cross-origin redirect is refused;
 *   <li>a request with neither header (the Next.js server rendering a page, scripts, tests) is left
 *       to the token check: no browser sends a cross-site write without one of them.
 * </ul>
 *
 * The refusal is the generic 403 COM-403-DENIED: it names no header, so it teaches an attacker
 * nothing. Security review 2026-11-04 (RBAC / tampering pass), OD-104.
 */
public class FetchMetadataFilter extends OncePerRequestFilter {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    private static final Set<String> SAME = Set.of("same-origin", "none");

    private final ProblemResponder responder;

    public FetchMetadataFilter(ProblemResponder responder) {
        this.responder = responder;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!SAFE_METHODS.contains(request.getMethod()) && crossSite(request)) {
            responder.write(null, CommonErrorCode.ACCESS_DENIED, request, response);
            return;
        }
        chain.doFilter(request, response);
    }

    /** Whether the browser says the request was started by another site. */
    static boolean crossSite(HttpServletRequest request) {
        String site = request.getHeader("Sec-Fetch-Site");
        if (site != null) {
            return !SAME.contains(site.trim().toLowerCase(Locale.ROOT));
        }
        String origin = request.getHeader("Origin");
        if (origin == null) {
            return false;
        }
        String host = hostOf(origin);
        return host == null || !host.equalsIgnoreCase(hostOnly(request.getServerName()));
    }

    /** The host of an Origin value, or null for {@code null} and anything unparsable. */
    private static String hostOf(String origin) {
        try {
            String host = URI.create(origin.trim()).getHost();
            return host == null ? null : hostOnly(host);
        } catch (IllegalArgumentException unparsable) {
            return null;
        }
    }

    private static String hostOnly(String host) {
        // An IPv6 literal keeps its brackets in a URI host and loses them in the server name.
        return host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1)
                : host;
    }
}
