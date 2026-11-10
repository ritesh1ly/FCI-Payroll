package in.gov.fci.payroll.platform.security;

import java.util.Optional;

/** The signed-in caller, as established by the session filters of Spring Security. */
public interface CurrentUser {

    /** The app_user id, or empty for anonymous calls and batch work. */
    Optional<String> userId();

    /** The {@code app_session} id of the browser session, if any. */
    Optional<String> sessionId();
}
