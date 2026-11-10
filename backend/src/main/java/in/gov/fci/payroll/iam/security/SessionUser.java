package in.gov.fci.payroll.iam.security;

import java.security.Principal;

/**
 * The principal of a signed-in browser session: the user, the {@code app_session} that holds the
 * active scope, and whether only the password change is allowed. Its name is the user id.
 */
public record SessionUser(Long userId, String sessionId, boolean passwordChangeRequired)
        implements Principal {

    @Override
    public String getName() {
        return String.valueOf(userId);
    }
}
