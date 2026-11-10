package in.gov.fci.payroll.iam.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.employee.api.EmployeeEvents;
import in.gov.fci.payroll.iam.business.model.AppSession;
import in.gov.fci.payroll.iam.business.model.AppUser;
import in.gov.fci.payroll.iam.business.model.AppUserAccessGroup;
import in.gov.fci.payroll.iam.persistence.AccessRepositories;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * SRS-22-13 / SRS-SEC-14: an approved separation or transfer withdraws the member's access. A
 * deactivated account is left alone; only open assignments are revoked, with the reason kept to the
 * column's 500 characters; a transfer that revoked nothing ends no session and writes no audit
 * line.
 */
class EmployeeAccessEffectsTest {

    private static final Instant NOW = Instant.parse("2026-10-24T06:00:00Z");

    private final AccessRepositories.Users users = mock(AccessRepositories.Users.class);
    private final AccessRepositories.Assignments assignments =
            mock(AccessRepositories.Assignments.class);
    private final SessionService sessions = mock(SessionService.class);
    private final AccessContextService contexts = mock(AccessContextService.class);
    private final AuditService audit = mock(AuditService.class);

    private final EmployeeAccessEffects effects =
            new EmployeeAccessEffects(
                    users,
                    assignments,
                    sessions,
                    contexts,
                    audit,
                    Clock.fixed(NOW, ZoneOffset.UTC));

    private static AppUser user(long id, AppUser.Status status) {
        AppUser user = mock(AppUser.class);
        when(user.getId()).thenReturn(id);
        when(user.getStatus()).thenReturn(status);
        return user;
    }

    private static AppUserAccessGroup assignment(Instant revokedAt) {
        AppUserAccessGroup a = mock(AppUserAccessGroup.class);
        when(a.getRevokedAt()).thenReturn(revokedAt);
        return a;
    }

    @Test
    void SRS_SEC_14_aSeparationDeactivatesOnlyLiveAccountsAndRevokesOnlyOpenAssignments() {
        AppUser gone = user(1L, AppUser.Status.DEACTIVATED);
        AppUser live = user(2L, AppUser.Status.ACTIVE);
        when(users.findByEmployeeId(9L)).thenReturn(List.of(gone, live));
        AppUserAccessGroup open = assignment(null);
        AppUserAccessGroup revoked = assignment(NOW.minusSeconds(3600));
        when(assignments.findByUserId(2L)).thenReturn(List.of(open, revoked));

        effects.on(
                new EmployeeEvents.EmployeeSeparated(
                        9L,
                        41L,
                        "SEPARATION",
                        LocalDate.of(2026, 10, 1),
                        "x".repeat(600),
                        "checker.hq"));

        verify(gone, never()).deactivateForSeparation(anyLong(), any());
        verify(live).deactivateForSeparation(41L, NOW);
        ArgumentCaptor<String> reason = ArgumentCaptor.captor();
        verify(open)
                .revoke(eq(AppUserAccessGroup.RevokeCause.SEPARATION), reason.capture(), eq(NOW));
        assertThat(reason.getValue()).hasSize(500).startsWith("SEPARATION: ");
        verify(revoked, never()).revoke(any(), anyString(), any());
        verify(sessions).endAllSessions(2L, NOW, AppSession.EndReason.REVOCATION);
        verify(sessions, never()).endAllSessions(eq(1L), any(), any());
        verify(contexts).evictAll();
    }

    @Test
    void SRS_22_13_aTransferWithoutAnOrderNamesNoneAndOneThatRevokedNothingLeavesTheSessions() {
        AppUser maker = user(2L, AppUser.Status.ACTIVE);
        AppUser idle = user(3L, AppUser.Status.ACTIVE);
        when(users.findByEmployeeId(9L)).thenReturn(List.of(maker, idle));
        AppUserAccessGroup open = assignment(null);
        when(assignments.findByUserId(2L)).thenReturn(List.of(open));
        when(assignments.findByUserId(3L)).thenReturn(List.of());

        effects.on(
                new EmployeeEvents.EmployeeTransferred(
                        9L, 7L, 300L, 301L, LocalDate.of(2026, 10, 1), null, "checker.delhi"));

        verify(open)
                .revoke(AppUserAccessGroup.RevokeCause.TRANSFER, "Transfer  from 2026-10-01", NOW);
        verify(sessions).endAllSessions(2L, NOW, AppSession.EndReason.REVOCATION);
        verify(sessions, never()).endAllSessions(eq(3L), any(), any());
        ArgumentCaptor<AuditService.Entry> entry = ArgumentCaptor.captor();
        verify(audit).record(eq("checker.delhi"), entry.capture());
        assertThat(entry.getValue().oldValue()).isEqualTo("1 active");

        // The same with an order reference names it; no account at all changes nothing.
        when(users.findByEmployeeId(10L)).thenReturn(List.of());
        effects.on(
                new EmployeeEvents.EmployeeTransferred(
                        10L, 8L, 300L, 301L, LocalDate.of(2026, 10, 1), "ORD/7", "checker.delhi"));
        AppUserAccessGroup second = assignment(null);
        when(assignments.findByUserId(2L)).thenReturn(List.of(second));
        effects.on(
                new EmployeeEvents.EmployeeTransferred(
                        9L, 9L, 300L, 301L, LocalDate.of(2026, 10, 1), "ORD/7", "checker.delhi"));
        verify(second)
                .revoke(
                        AppUserAccessGroup.RevokeCause.TRANSFER,
                        "Transfer ORD/7 from 2026-10-01",
                        NOW);
    }
}
