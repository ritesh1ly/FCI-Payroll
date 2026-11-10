package in.gov.fci.payroll.iam.orchestration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.iam.business.model.AppUserAccessGroup;
import in.gov.fci.payroll.iam.persistence.AccessRepositories;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * SRS-22-23: a rejected or returned assignment is withdrawn once; one already revoked meanwhile is
 * left as it is, and the cached access contexts are refreshed either way.
 */
class AccessApprovalEffectsBranchGapsTest {

    private static final Instant NOW = Instant.parse("2026-10-24T06:00:00Z");

    private final AccessRepositories.Assignments assignments =
            mock(AccessRepositories.Assignments.class);
    private final AccessContextService contexts = mock(AccessContextService.class);
    private final AccessApprovalEffects effects =
            new AccessApprovalEffects(assignments, contexts, mock(SessionService.class));

    private static ApprovalDecided rejected(long approval) {
        return new ApprovalDecided(
                approval,
                "A-04",
                AccessApprovalEffects.ASSIGNMENT,
                "maker@x|AG-DO",
                ApprovalStatus.REJECTED,
                "not needed",
                "{}",
                "checker@x",
                NOW,
                null);
    }

    @Test
    void SRS_22_23_aRejectedAssignmentRevokedMeanwhileIsNotRevokedAgain() {
        AppUserAccessGroup revoked = mock(AppUserAccessGroup.class);
        when(revoked.getRevokedAt()).thenReturn(NOW.minusSeconds(60));
        when(assignments.findByApprovalId(80L)).thenReturn(Optional.of(revoked));
        effects.on(rejected(80L));
        verify(revoked, never()).revoke(any(), any(), any());
        verify(assignments, never()).save(any());
        verify(contexts).evictAll();
    }

    @Test
    void SRS_22_23_aRejectedOpenAssignmentIsWithdrawn() {
        AppUserAccessGroup open = mock(AppUserAccessGroup.class);
        when(open.getRevokedAt()).thenReturn(null);
        when(assignments.findByApprovalId(81L)).thenReturn(Optional.of(open));
        effects.on(rejected(81L));
        verify(open).revoke(AppUserAccessGroup.RevokeCause.ADMIN, "Approval rejected", NOW);
        verify(assignments).save(open);
    }
}
