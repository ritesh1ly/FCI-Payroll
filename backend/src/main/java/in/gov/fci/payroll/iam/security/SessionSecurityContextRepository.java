package in.gov.fci.payroll.iam.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.function.Supplier;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.DeferredSecurityContext;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpRequestResponseHolder;
import org.springframework.security.web.context.SecurityContextRepository;

/**
 * Rebuilds the authentication from the three session strings ({@link WebSessions}) on every
 * request, instead of storing a serialised {@code SecurityContext}. The only authority is {@code
 * ROLE_USER}: permissions come from {@code AccessPolicy} per request, never from here.
 */
public class SessionSecurityContextRepository implements SecurityContextRepository {

    @Override
    public DeferredSecurityContext loadDeferredContext(HttpServletRequest request) {
        Supplier<SecurityContext> supplier = () -> load(request);
        return new DeferredSecurityContext() {
            private SecurityContext context;

            @Override
            public SecurityContext get() {
                if (context == null) {
                    context = supplier.get();
                }
                return context;
            }

            @Override
            public boolean isGenerated() {
                return get().getAuthentication() == null;
            }
        };
    }

    @Override
    @SuppressWarnings("deprecation")
    public SecurityContext loadContext(HttpRequestResponseHolder holder) {
        return load(holder.getRequest());
    }

    /** The sign-in flow writes the session explicitly; nothing to save here. */
    @Override
    public void saveContext(
            SecurityContext context, HttpServletRequest request, HttpServletResponse response) {
        // Intentionally empty: see class comment.
    }

    @Override
    public boolean containsContext(HttpServletRequest request) {
        return WebSessions.user(request).isPresent();
    }

    private static SecurityContext load(HttpServletRequest request) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        WebSessions.user(request)
                .ifPresent(
                        user ->
                                context.setAuthentication(
                                        UsernamePasswordAuthenticationToken.authenticated(
                                                user,
                                                null,
                                                AuthorityUtils.createAuthorityList("ROLE_USER"))));
        return context;
    }
}
