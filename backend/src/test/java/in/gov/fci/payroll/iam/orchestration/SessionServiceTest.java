package in.gov.fci.payroll.iam.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.iam.business.model.AppSession;
import in.gov.fci.payroll.iam.business.model.AppUser;
import in.gov.fci.payroll.iam.business.model.AppUserCredential;
import in.gov.fci.payroll.iam.persistence.AccessRepositories;
import in.gov.fci.payroll.iam.persistence.IdentityRepositories;
import in.gov.fci.payroll.platform.config.ApiProperties;
import in.gov.fci.payroll.platform.security.SecureTokens;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Sessions (SRS-SEC-14, SRS-G-48 … 50): with several sessions allowed a new one ends none; a
 * sign-in without a credential row, or with one that must change, asks for a new password; after a
 * password change every other session ends, and with no session to keep every one does.
 */
class SessionServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-29T06:00:00Z");

    private final IdentityRepositories.Sessions sessions =
            mock(IdentityRepositories.Sessions.class);
    private final SystemParameters parameters = mock(SystemParameters.class);
    private final AccessContextService contexts = mock(AccessContextService.class);
    private final SessionService service =
            new SessionService(
                    sessions,
                    mock(AccessRepositories.Users.class),
                    contexts,
                    mock(AuditService.class),
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    mock(ApiProperties.class),
                    parameters,
                    mock(SessionTimeouts.class));

    SessionServiceTest() {
        when(parameters.sessionAbsoluteTimeout()).thenReturn(Duration.ofHours(8));
        when(parameters.passwordMaxAge()).thenReturn(Duration.ofDays(90));
    }

    private static AppSession open(String token) {
        AppSession s = mock(AppSession.class);
        when(s.getTokenHash()).thenReturn(SecureTokens.sha256Hex(token));
        return s;
    }

    @Test
    void SRS_G_48_withSeveralSessionsAllowedANewOneEndsNoneAndAMissingCredentialMustChange() {
        when(parameters.singleSession()).thenReturn(false);
        AppUser user = mock(AppUser.class);
        when(user.getId()).thenReturn(7L);
        AppSession session = mock(AppSession.class);
        assertThat(service.start(user, null, session, "t").passwordChangeRequired()).isTrue();
        verify(sessions, never()).findByUserIdAndEndedAtIsNull(any());

        AppUserCredential mustChange = mock(AppUserCredential.class);
        when(mustChange.isMustChange()).thenReturn(true);
        assertThat(service.start(user, mustChange, session, "t").passwordChangeRequired()).isTrue();
        AppUserCredential fresh = mock(AppUserCredential.class);
        assertThat(service.start(user, fresh, session, "t").passwordChangeRequired()).isFalse();
    }

    @Test
    void SRS_G_50_aPasswordChangeEndsEveryOtherSessionOrAllWithNoneToKeep() {
        AppSession kept = open("keep");
        AppSession other = open("other");
        when(sessions.findByUserIdAndEndedAtIsNull(7L)).thenReturn(List.of(kept, other));
        service.endOtherSessions(7L, "keep", NOW);
        verify(kept, never()).end(any(), any());
        verify(other).end(AppSession.EndReason.REVOCATION, NOW);

        service.endOtherSessions(7L, null, NOW);
        verify(kept).end(AppSession.EndReason.REVOCATION, NOW);
    }
}
