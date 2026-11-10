package in.gov.fci.payroll.iam.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.iam.business.model.AppSession;
import in.gov.fci.payroll.iam.business.model.AppUser;
import in.gov.fci.payroll.iam.persistence.AccessRepositories;
import in.gov.fci.payroll.iam.persistence.IdentityRepositories;
import in.gov.fci.payroll.platform.config.ApiProperties;
import java.time.Clock;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** OD-130: which ended session was ended by an offence. */
class SessionServiceOffenceTest {

    private static final String TOKEN = "token";
    private static final String ORIGIN =
            SecurityOffences.origin(SecurityOffences.sessionRef(TOKEN));

    private final IdentityRepositories.Sessions sessions =
            mock(IdentityRepositories.Sessions.class);
    private final AccessRepositories.Users users = mock(AccessRepositories.Users.class);
    private final AuditService audit = mock(AuditService.class);
    private final AppSession session = mock(AppSession.class);
    private final SessionService service =
            new SessionService(
                    sessions,
                    users,
                    mock(AccessContextService.class),
                    audit,
                    Clock.systemUTC(),
                    mock(ApiProperties.class),
                    mock(SystemParameters.class),
                    mock(SessionTimeouts.class));

    @BeforeEach
    void endedWithACountedOffence() {
        AppUser user = mock(AppUser.class);
        when(user.getLoginId()).thenReturn("u@x");
        when(users.findById(7L)).thenReturn(Optional.of(user));
        when(sessions.findByToken(TOKEN)).thenReturn(Optional.of(session));
        when(session.isOpen()).thenReturn(false);
        when(session.getEndReason()).thenReturn("REVOCATION");
        when(audit.hasSecurityEvent("u@x", AuditService.Security.UNAUTHORIZED_ACCESS, ORIGIN))
                .thenReturn(true);
    }

    @Test
    void OD_130_aRevokedSessionWithItsOffenceSaysSo() {
        assertThat(service.endedForOffence(7L, TOKEN)).isTrue();

        when(session.getEndReason()).thenReturn("ADMIN_REVOKE");
        assertThat(service.endedForOffence(7L, TOKEN)).isTrue();
    }

    @Test
    void OD_130_aSessionEndedOtherwiseDoesNot() {
        when(session.getEndReason()).thenReturn("TIMEOUT");
        assertThat(service.endedForOffence(7L, TOKEN)).isFalse();
    }

    @Test
    void OD_130_anOpenSessionOrOneWithoutAnOffenceDoesNot() {
        when(audit.hasSecurityEvent("u@x", AuditService.Security.UNAUTHORIZED_ACCESS, ORIGIN))
                .thenReturn(false);
        assertThat(service.endedForOffence(7L, TOKEN)).isFalse();

        when(session.isOpen()).thenReturn(true);
        assertThat(service.endedForOffence(7L, TOKEN)).isFalse();
    }

    @Test
    void OD_130_anUnknownSessionOrUserDoesNot() {
        when(users.findById(7L)).thenReturn(Optional.empty());
        assertThat(service.endedForOffence(7L, TOKEN)).isFalse();

        when(sessions.findByToken(TOKEN)).thenReturn(Optional.empty());
        assertThat(service.endedForOffence(7L, TOKEN)).isFalse();
    }
}
