package in.gov.fci.payroll.iam.orchestration;

import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.iam.business.model.AppSession;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * SRS-SEC-14: "idle logout after 15 minutes for payroll users and 10 minutes for eSeva members".
 * The one place that decides a session's idle timeout from its scope, at sign-in and after every
 * scope switch; the values are the A-07 settings SESSION_IDLE_TIMEOUT and
 * ESEVA_SESSION_IDLE_TIMEOUT (changed under maker-checker, SRS-G-36). Callers hand the session or
 * the scope, never a flag.
 */
@Component
public class SessionTimeouts {

    private final SystemParameters parameters;

    public SessionTimeouts(SystemParameters parameters) {
        this.parameters = parameters;
    }

    /** The idle timeout of a session that has just started in its scope. */
    public Duration idle(AppSession session) {
        return idle(session.isEsevaScope());
    }

    /** The idle timeout of a session that has just switched to {@code scope}. */
    public Duration idle(ScopeResolver.RoleScope scope) {
        return idle(scope.isEseva());
    }

    private Duration idle(boolean esevaMember) {
        return esevaMember ? parameters.esevaSessionIdleTimeout() : parameters.sessionIdleTimeout();
    }
}
