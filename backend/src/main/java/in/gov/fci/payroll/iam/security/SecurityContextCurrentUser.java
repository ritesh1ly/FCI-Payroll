package in.gov.fci.payroll.iam.security;

import in.gov.fci.payroll.platform.security.CurrentUser;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** Reads the caller from the security context: the {@link SessionUser} of a browser session. */
@Component
public class SecurityContextCurrentUser implements CurrentUser {

    @Override
    public Optional<String> userId() {
        return sessionUser().map(SessionUser::getName);
    }

    @Override
    public Optional<String> sessionId() {
        return sessionUser().map(SessionUser::sessionId);
    }

    private static Optional<SessionUser> sessionUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof SessionUser user) {
            return Optional.of(user);
        }
        return Optional.empty();
    }
}
