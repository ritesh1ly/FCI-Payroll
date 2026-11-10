package in.gov.fci.payroll.general.orchestration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.general.business.model.FallbackEnableScope;
import in.gov.fci.payroll.general.business.model.FallbackEnablement;
import in.gov.fci.payroll.general.business.model.FallbackUpload;
import in.gov.fci.payroll.general.persistence.GeneralRepositories;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * X-07 decisions (BR-INT-011, SRS-G-47): a rejected enablement enables nothing; an approved one
 * opens it; an approved upload completes its enablement only once every enabled office holds an
 * approved upload (an office whose upload is still pending keeps it open).
 */
class FallbackEffectsBranchGapsTest {

    private static final Instant NOW = Instant.parse("2026-11-04T06:00:00Z");

    private final FallbackService service = mock(FallbackService.class);
    private final GeneralRepositories.Enablements enablements =
            mock(GeneralRepositories.Enablements.class);
    private final GeneralRepositories.EnableScopes scopes =
            mock(GeneralRepositories.EnableScopes.class);
    private final GeneralRepositories.Uploads uploads = mock(GeneralRepositories.Uploads.class);
    private final FallbackEffects effects =
            new FallbackEffects(service, enablements, scopes, uploads, mock(AuditService.class));

    private static ApprovalDecided decided(String entity, ApprovalStatus outcome, Long otp) {
        return new ApprovalDecided(
                61L, "X-07", entity, "4", outcome, null, "{}", "checker.hq", NOW, otp);
    }

    private static FallbackUpload upload(Long office, FallbackUpload.Status status) {
        FallbackUpload u = mock(FallbackUpload.class);
        when(u.getOrgUnitId()).thenReturn(office);
        when(u.getStatus()).thenReturn(status);
        when(u.getEnablementId()).thenReturn(4L);
        when(u.getBatchNo()).thenReturn("FBU-" + office);
        return u;
    }

    private static FallbackEnableScope scope(Long office) {
        FallbackEnableScope s = mock(FallbackEnableScope.class);
        when(s.getOrgUnitId()).thenReturn(office);
        return s;
    }

    @Test
    void BR_INT_011_aRejectedEnablementEnablesNothingAndAnApprovedOneOpensIt() {
        effects.on(decided(FallbackService.ENABLEMENT, ApprovalStatus.REJECTED, null));
        verifyNoInteractions(service, uploads);
        effects.on(decided(FallbackService.ENABLEMENT, ApprovalStatus.APPROVED, null));
        verify(service).openApproved("{}", 61L, "checker.hq");
    }

    @Test
    void SRS_G_47_anEnablementIsApprovedOnlyWhenEveryEnabledOfficeHasAnApprovedUpload() {
        FallbackUpload decided = upload(300L, FallbackUpload.Status.APPROVED);
        FallbackUpload pending = upload(301L, FallbackUpload.Status.PENDING_APPROVAL);
        when(uploads.findByApprovalId(61L)).thenReturn(Optional.of(decided));
        when(uploads.findByEnablementId(4L)).thenReturn(List.of(decided, pending));
        List<FallbackEnableScope> enabled = List.of(scope(300L), scope(301L));
        when(scopes.findByEnablementId(4L)).thenReturn(enabled);
        FallbackEnablement enablement = mock(FallbackEnablement.class);
        when(enablements.findById(4L)).thenReturn(Optional.of(enablement));

        effects.on(decided(FallbackService.UPLOAD, ApprovalStatus.APPROVED, 9L));
        verify(decided).approve("checker.hq", 9L, NOW);
        verify(enablement, never()).approved();

        // The other office's upload is approved too: the enablement is complete.
        when(pending.getStatus()).thenReturn(FallbackUpload.Status.APPROVED);
        effects.on(decided(FallbackService.UPLOAD, ApprovalStatus.APPROVED, 9L));
        verify(enablement).approved();
        verify(enablements).save(eq(enablement));
        verify(uploads, org.mockito.Mockito.times(2)).save(any());
    }
}
