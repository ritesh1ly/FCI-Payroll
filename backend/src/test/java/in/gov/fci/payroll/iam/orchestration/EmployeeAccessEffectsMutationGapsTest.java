package in.gov.fci.payroll.iam.orchestration;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.employee.api.EmployeeEvents;
import in.gov.fci.payroll.iam.business.model.AppUser;
import in.gov.fci.payroll.iam.business.model.AppUserAccessGroup;
import in.gov.fci.payroll.iam.persistence.AccessRepositories;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Mutation gaps of {@link EmployeeAccessEffects}: SRS-G-36, SRS-1-63 (audit lines, cache refresh).
 * Line 118 (reason length at most 500 against below 500) is equivalent: at exactly 500 both
 * branches yield the same string.
 */
class EmployeeAccessEffectsMutationGapsTest {

    private static final Instant NOW = Instant.parse("2026-11-06T06:00:00Z");

    private final AccessRepositories.Users users = mock(AccessRepositories.Users.class);
    private final AccessRepositories.Assignments assignments =
            mock(AccessRepositories.Assignments.class);
    private final AccessContextService contexts = mock(AccessContextService.class);
    private final AuditService audit = mock(AuditService.class);
    private final EmployeeAccessEffects effects =
            new EmployeeAccessEffects(
                    users,
                    assignments,
                    mock(SessionService.class),
                    contexts,
                    audit,
                    Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void SRS_G_36_aSeparationAuditsTheDeactivationAsAnI21Change() {
        AppUser live = mock(AppUser.class);
        when(live.getId()).thenReturn(2L);
        when(live.getStatus()).thenReturn(AppUser.Status.ACTIVE);
        when(users.findByEmployeeId(9L)).thenReturn(List.of(live));
        when(assignments.findByUserId(2L)).thenReturn(List.of());

        effects.on(
                new EmployeeEvents.EmployeeSeparated(
                        9L, 41L, "DISMISSAL", LocalDate.of(2026, 11, 1), "order 7", "checker.hq"));

        verify(audit)
                .record(
                        "checker.hq",
                        AuditService.Entry.change(
                                        "app_user",
                                        "2",
                                        "user_status",
                                        "ACTIVE",
                                        "DEACTIVATED",
                                        "DISMISSAL (life-cycle event 41, from HRMS)")
                                .module("I-21"));
        verify(contexts, times(1)).evictAll();
    }

    @Test
    void SRS_G_36_aSeparationOfAMemberWithNoAccountStillRefreshesTheContexts() {
        when(users.findByEmployeeId(3L)).thenReturn(List.of());
        effects.on(
                new EmployeeEvents.EmployeeSeparated(
                        3L, 1L, "SEPARATION", LocalDate.of(2026, 11, 1), "r", "c"));
        verify(contexts, times(1)).evictAll();
    }

    @Test
    void SRS_G_36_aTransferRefreshesTheContextsOnceEvenWhenNothingWasRevoked() {
        when(users.findByEmployeeId(4L)).thenReturn(List.of());
        effects.on(
                new EmployeeEvents.EmployeeTransferred(
                        4L, 5L, 300L, 301L, LocalDate.of(2026, 11, 1), "ORD/1", "c"));
        verify(contexts, times(1)).evictAll();
    }

    @Test
    void SRS_G_36_aTransferAuditsTheCountOfWithdrawnRightsAsM11() {
        AppUser u = mock(AppUser.class);
        when(u.getId()).thenReturn(6L);
        when(users.findByEmployeeId(4L)).thenReturn(List.of(u));
        AppUserAccessGroup a1 = mock(AppUserAccessGroup.class);
        AppUserAccessGroup a2 = mock(AppUserAccessGroup.class);
        when(assignments.findByUserId(6L)).thenReturn(List.of(a1, a2));

        effects.on(
                new EmployeeEvents.EmployeeTransferred(
                        4L, 55L, 300L, 301L, LocalDate.of(2026, 11, 1), "ORD/1", "chk"));

        verify(audit)
                .record(
                        "chk",
                        AuditService.Entry.change(
                                        "app_user",
                                        "6",
                                        "access_groups",
                                        "2 active",
                                        "0 (TRANSFER)",
                                        "Rights withdrawn on transfer (M-11 transfer 55)")
                                .module("M-11"));
        verify(contexts, times(1)).evictAll();
    }
}
