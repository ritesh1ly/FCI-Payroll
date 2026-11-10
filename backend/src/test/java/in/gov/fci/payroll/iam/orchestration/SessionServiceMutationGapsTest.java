package in.gov.fci.payroll.iam.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Mutation gaps of {@link SessionService}: SRS-SEC-14, SRS-G-48 ... 50 (sign-in, sign-out, lists).
 */
class SessionServiceMutationGapsTest {

    private static final Instant NOW = Instant.parse("2026-11-06T06:00:00Z");

    private final IdentityRepositories.Sessions sessions =
            mock(IdentityRepositories.Sessions.class);
    private final AccessRepositories.Users users = mock(AccessRepositories.Users.class);
    private final SystemParameters parameters = mock(SystemParameters.class);
    private final AccessContextService contexts = mock(AccessContextService.class);
    private final AuditService audit = mock(AuditService.class);
    private final SessionTimeouts timeouts = mock(SessionTimeouts.class);
    private final SessionService service =
            new SessionService(
                    sessions,
                    users,
                    contexts,
                    audit,
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    mock(ApiProperties.class),
                    parameters,
                    timeouts);

    SessionServiceMutationGapsTest() {
        when(parameters.sessionAbsoluteTimeout()).thenReturn(Duration.ofHours(8));
        when(parameters.passwordMaxAge()).thenReturn(Duration.ofDays(90));
    }

    private static AppSession openSession(Long userId, String hash) {
        AppSession s = mock(AppSession.class);
        when(s.getUserId()).thenReturn(userId);
        when(s.getTokenHash()).thenReturn(hash);
        when(s.isOpen()).thenReturn(true);
        return s;
    }

    @Test
    void SRS_G_48_singleSessionStartEndsOlderSessionsAsSupersededAndSavesTheNewOne() {
        when(parameters.singleSession()).thenReturn(true);
        AppUser user = mock(AppUser.class);
        when(user.getId()).thenReturn(7L);
        AppSession older = openSession(7L, "h-older");
        when(sessions.findByUserIdAndEndedAtIsNull(7L)).thenReturn(List.of(older));
        AppSession fresh = mock(AppSession.class);
        when(timeouts.idle(fresh)).thenReturn(Duration.ofMinutes(15));

        SessionService.StartedSession started =
                service.start(user, mock(AppUserCredential.class), fresh, "tok");

        verify(older).end(AppSession.EndReason.SUPERSEDED, NOW);
        verify(sessions).save(older);
        verify(sessions).save(fresh);
        verify(contexts).evictSession("h-older");
        assertThat(started.sessionId()).isEqualTo("tok");
        assertThat(started.userId()).isEqualTo(7L);
        assertThat(started.absoluteExpiresAt()).isEqualTo(NOW.plus(Duration.ofHours(8)));
        assertThat(started.idleTimeout()).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void SRS_G_49_signOutByTokenEndsTheOpenSessionWithLogoutAndAuditsWithLoginId() {
        AppSession session = openSession(7L, "h1");
        when(sessions.findByToken("tok")).thenReturn(Optional.of(session));
        AppUser user = mock(AppUser.class);
        when(user.getLoginId()).thenReturn("jsharma");
        when(users.findById(7L)).thenReturn(Optional.of(user));

        service.end("tok");

        verify(session).end(AppSession.EndReason.LOGOUT, NOW);
        verify(sessions).save(session);
        verify(contexts).evictSession("h1");
        verify(audit)
                .record(
                        "jsharma",
                        AuditService.Entry.change(
                                "app_user", "7", "LOGOUT", null, "SUCCESS", "User signed out"));
    }

    @Test
    void SRS_G_49_signOutOfAnUnknownUserAuditsAsUnknownAndTheGivenReasonIsKept() {
        AppSession session = openSession(8L, "h2");
        when(sessions.findByToken("tok2")).thenReturn(Optional.of(session));
        when(users.findById(8L)).thenReturn(Optional.empty());

        service.end("tok2", AppSession.EndReason.REVOCATION);

        verify(session).end(AppSession.EndReason.REVOCATION, NOW);
        verify(audit)
                .record(
                        "unknown",
                        AuditService.Entry.change(
                                "app_user", "8", "LOGOUT", null, "SUCCESS", "User signed out"));
    }

    @Test
    void SRS_G_49_aClosedOrUnknownSessionIsIgnored() {
        AppSession closed = mock(AppSession.class);
        when(closed.isOpen()).thenReturn(false);
        when(sessions.findByToken("closed")).thenReturn(Optional.of(closed));
        when(sessions.findByToken("none")).thenReturn(Optional.empty());

        service.end("closed");
        service.end("none");

        verify(closed, never()).end(any(), any());
        verify(sessions, never()).save(any());
        verify(audit, never()).record(any(), any());
    }

    @Test
    void SRS_G_49_endAllSessionsEndsEachOpenSessionOfTheUser() {
        AppSession a = openSession(9L, "ha");
        AppSession b = openSession(9L, "hb");
        when(sessions.findByUserIdAndEndedAtIsNull(9L)).thenReturn(List.of(a, b));

        service.endAllSessions(9L, NOW, AppSession.EndReason.LOGOUT);

        verify(a).end(AppSession.EndReason.LOGOUT, NOW);
        verify(b).end(AppSession.EndReason.LOGOUT, NOW);
        verify(sessions, times(2)).save(any(AppSession.class));
        verify(contexts).evictSession("ha");
        verify(contexts).evictSession("hb");
    }

    @Test
    void SRS_G_50_openCountAndRecentReturnTheRepositoryRows() {
        AppSession a = mock(AppSession.class);
        AppSession b = mock(AppSession.class);
        AppSession c = mock(AppSession.class);
        when(sessions.findByUserIdAndEndedAtIsNull(5L)).thenReturn(List.of(a, b, c));
        when(sessions.findTop20ByUserIdOrderByStartedAtDesc(5L)).thenReturn(List.of(c, b));

        assertThat(service.openCount(5L)).isEqualTo(3);
        assertThat(service.recent(5L)).containsExactly(c, b);
        assertThat(service.openCount(6L)).isZero();
        assertThat(service.recent(6L)).isEmpty();
    }
}
