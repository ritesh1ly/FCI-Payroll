package in.gov.fci.payroll.integration.web;

import in.gov.fci.payroll.integration.orchestration.HrmsAccessService;
import in.gov.fci.payroll.platform.error.ErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.error.ProblemResponder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates a call to a published HRMS API (SRS-INT-4): the {@code api-key} header for the
 * token API, the {@code api-key} header and {@code Authorization: Bearer} token for every other.
 * Stateless: the authentication lives for this request only, no session is created. A refusal is a
 * problem response that does not say which part was wrong and is recorded for X-03 by the access
 * service.
 */
final class HrmsApiAuthFilter extends OncePerRequestFilter {

    static final String AUTHORITY = "HRMS_SYSTEM";
    static final String API_KEY_HEADER = "api-key";

    private final HrmsApiRoutes routes;
    private final HrmsAccessService access;
    private final ProblemResponder responder;

    HrmsApiAuthFilter(HrmsApiRoutes routes, HrmsAccessService access, ProblemResponder responder) {
        this.routes = routes;
        this.access = access;
        this.responder = responder;
    }

    /** The header's one value; a header sent twice has no single reading and counts as absent. */
    private static String single(HttpServletRequest request, String name) {
        java.util.List<String> values = java.util.Collections.list(request.getHeaders(name));
        return values.size() == 1 ? values.getFirst() : null;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            String remote = request.getRemoteAddr();
            if (routes.isToken(request)) {
                access.requireKey(single(request, API_KEY_HEADER), remote);
            } else {
                access.requireKeyAndToken(
                        single(request, API_KEY_HEADER), single(request, "Authorization"), remote);
            }
        } catch (FciException refused) {
            ErrorCode code = refused.getErrorCode();
            SecurityContextHolder.clearContext();
            responder.write(refused, code, request, response);
            return;
        }
        UsernamePasswordAuthenticationToken authentication =
                UsernamePasswordAuthenticationToken.authenticated(
                        "hrms", null, List.of(new SimpleGrantedAuthority(AUTHORITY)));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        chain.doFilter(request, response);
    }
}
