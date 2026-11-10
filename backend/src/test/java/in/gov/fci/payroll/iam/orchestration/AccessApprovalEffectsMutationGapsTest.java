package in.gov.fci.payroll.iam.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.iam.business.model.AppSession;
import in.gov.fci.payroll.iam.business.model.AppUserAccessGroup;
import in.gov.fci.payroll.iam.persistence.AccessRepositories;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Mutation gaps of {@link AccessApprovalEffects}: SRS-22-23, SRS-SEC-14, Module 02 D-5. */
class AccessApprovalEffectsMutationGapsTest {

    private static final Instant NOW = Instant.parse("2026-11-06T06:00:00Z");

    private final AccessRepositories.Assignments assignments =
            mock(AccessRepositories.Assignments.class);
    private final AccessContextService contexts = mock(AccessContextService.class);
    private final SessionService sessions = mock(SessionService.class);
    private final AccessApprovalEffects effects =
            new AccessApprovalEffects(assignments, contexts, sessions);

    private static ApprovalDecided decision(long approval, String entity, ApprovalStatus outcome) {
        return new ApprovalDecided(
                approval,
                "A-04",
                entity,
                "maker@x|AG-DO",
                outcome,
                "ok",
                "{}",
                "checker@x",
                NOW,
                null);
    }

    @Test
    void SRS_22_23_subjectUserIdIsTheAssignmentHolderOnlyForAssignments() {
        AppUserAccessGroup a = mock(AppUserAccessGroup.class);
        when(a.getUserId()).thenReturn(12L);
        when(assignments.findByApprovalId(5L)).thenReturn(Optional.of(a));

        assertThat(effects.subjectUserId(AccessApprovalEffects.ASSIGNMENT, 5L)).contains(12L);
        assertThat(effects.subjectUserId(AccessApprovalEffects.ASSIGNMENT, 6L)).isEmpty();
        assertThat(effects.subjectUserId("access_group", 5L)).isEmpty();
        assertThat(effects.subjectUserId(null, 5L)).isEmpty();
    }

    @Test
    void SRS_SEC_14_anApprovedAssignmentEndsTheHoldersSessionsWithRoleChangeAndRefreshes() {
        AppUserAccessGroup a = mock(AppUserAccessGroup.class);
        when(a.getUserId()).thenReturn(12L);
        when(assignments.findByApprovalId(7L)).thenReturn(Optional.of(a));

        effects.on(decision(7L, AccessApprovalEffects.ASSIGNMENT, ApprovalStatus.APPROVED));

        verify(sessions).endAllSessions(12L, NOW, AppSession.EndReason.ROLE_CHANGE);
        verify(a, never()).revoke(any(), any(), any());
        verify(assignments, never()).save(any());
        verify(contexts, times(1)).evictAll();
    }

    @Test
    void SRS_SEC_14_anApprovedDecisionWithoutAnAssignmentEndsNoSessionButRefreshes() {
        when(assignments.findByApprovalId(8L)).thenReturn(Optional.empty());

        effects.on(decision(8L, AccessApprovalEffects.ASSIGNMENT, ApprovalStatus.APPROVED));

        verify(sessions, never()).endAllSessions(any(), any(), any());
        verify(contexts, times(1)).evictAll();
    }

    @Test
    void SRS_22_23_aRejectedAssignmentEndsNoSession() {
        AppUserAccessGroup a = mock(AppUserAccessGroup.class);
        when(a.getRevokedAt()).thenReturn(null);
        when(assignments.findByApprovalId(9L)).thenReturn(Optional.of(a));

        effects.on(decision(9L, AccessApprovalEffects.ASSIGNMENT, ApprovalStatus.REJECTED));

        verify(sessions, never()).endAllSessions(any(), any(), any());
        verify(contexts, times(1)).evictAll();
    }

    @Test
    void SRS_22_23_groupAndRolePermissionDecisionsRefreshTheCachesAnyOtherEntityDoesNot() {
        effects.on(decision(10L, "access_group", ApprovalStatus.APPROVED));
        verify(contexts, times(1)).evictAll();
        effects.on(decision(11L, "app_role_permission", ApprovalStatus.REJECTED));
        verify(contexts, times(2)).evictAll();
        effects.on(decision(12L, "employee_master", ApprovalStatus.APPROVED));
        verify(contexts, times(2)).evictAll();
    }
}
