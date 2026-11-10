package in.gov.fci.payroll.general.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.employee.api.EmployeeDirectory;
import in.gov.fci.payroll.general.business.model.FallbackEnableScope;
import in.gov.fci.payroll.general.business.model.FallbackEnablement;
import in.gov.fci.payroll.general.business.model.FallbackUpload;
import in.gov.fci.payroll.general.config.FallbackFormatProperties;
import in.gov.fci.payroll.general.persistence.GeneralRepositories;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.payroll.api.PayrollRecords;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.export.ExportReader;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import tools.jackson.databind.json.JsonMapper;

/**
 * X-07 (SRS-INT-22, SRS-EH-11, BR-INT-011): Headquarters is offered every live enablement, an
 * office only those naming it; a submission with its own reason is sent with that reason.
 */
class FallbackServiceBranchGapsTest {

    private static final Instant NOW = Instant.parse("2026-10-24T06:00:00Z");

    private final GeneralRepositories.Enablements enablements =
            mock(GeneralRepositories.Enablements.class);
    private final GeneralRepositories.EnableScopes scopes =
            mock(GeneralRepositories.EnableScopes.class);
    private final GeneralRepositories.Uploads uploads = mock(GeneralRepositories.Uploads.class);
    private final GeneralRepositories.UploadLines lines =
            mock(GeneralRepositories.UploadLines.class);
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final MakerChecker approvals = mock(MakerChecker.class);
    private final Authorization policy = mock(Authorization.class);

    private final FallbackService service =
            new FallbackService(
                    mock(ExportReader.class),
                    enablements,
                    scopes,
                    uploads,
                    lines,
                    new FallbackFormatProperties(Map.of()),
                    mock(EmployeeDirectory.class),
                    org,
                    mock(PayrollRecords.class),
                    mock(DocumentStore.class),
                    approvals,
                    policy,
                    mock(AuditService.class),
                    JsonMapper.builder().build(),
                    Clock.fixed(NOW, ZoneOffset.UTC));

    private final AccessContext office =
            new AccessContext(
                    3L,
                    "maker.delhi",
                    "Maker",
                    "s",
                    "P",
                    300L,
                    null,
                    List.of(),
                    Map.of(),
                    Set.of(),
                    Set.of(),
                    Set.of(300L));

    FallbackServiceBranchGapsTest() {
        when(org.cycleUnitOf(any())).thenReturn(Optional.empty());
        when(approvals.submit(any(), any(), any()))
                .thenReturn(new SubmittedApproval(77L, ApprovalStatus.PENDING_APPROVAL, null));
    }

    private static FallbackEnablement enablement(long id) {
        FallbackEnablement e = mock(FallbackEnablement.class);
        when(e.getId()).thenReturn(id);
        when(e.getType()).thenReturn(FallbackEnablement.Type.ATTENDANCE_LEAVE);
        when(e.getStatus()).thenReturn(FallbackEnablement.Status.OPEN);
        when(e.getPayrollYymm()).thenReturn("202610");
        return e;
    }

    private static FallbackEnableScope scope(Long unit) {
        FallbackEnableScope s = mock(FallbackEnableScope.class);
        when(s.getOrgUnitId()).thenReturn(unit);
        return s;
    }

    private FallbackUpload upload() {
        FallbackUpload u = mock(FallbackUpload.class);
        when(u.getId()).thenReturn(9L);
        when(u.getEnablementId()).thenReturn(4L);
        when(u.getType()).thenReturn(FallbackEnablement.Type.ATTENDANCE_LEAVE);
        when(u.getStatus()).thenReturn(FallbackUpload.Status.PENDING_APPROVAL);
        when(u.getReconStatus()).thenReturn(FallbackUpload.ReconStatus.NOT_DUE);
        when(u.getOrgUnitId()).thenReturn(300L);
        when(u.getApprovalId()).thenReturn(77L);
        return u;
    }

    @Test
    void OD_121_theUploadsOfOneEnablementComeOverTheScopeWithTheirApprovalAndReference() {
        FallbackUpload u = upload();
        FallbackEnablement e = enablement(4L);
        when(e.getReference()).thenReturn("FBE-2026-007");
        when(enablements.findById(4L)).thenReturn(Optional.of(e));
        when(uploads.findByEnablementIdAndOrgUnitIdInOrderByUploadedAtDescIdDesc(
                        eq(4L), eq(Set.of(300L)), any()))
                .thenReturn(new PageImpl<>(List.of(u)));

        FallbackService.UploadView view =
                service.uploads(office, new PageSpec(0, 20), 4L).items().get(0);

        assertThat(view.approvalId()).isEqualTo(77L);
        assertThat(view.enablementReference()).isEqualTo("FBE-2026-007");
        verify(policy).require(office, "X-07", "VIEW");
        verify(uploads, never()).findByOrgUnitIdInOrderByUploadedAtDescIdDesc(any(), any());
    }

    @Test
    void OD_121_withoutAnEnablementEveryUploadOfTheScopeComes() {
        FallbackUpload u = upload();
        when(uploads.findByOrgUnitIdInOrderByUploadedAtDescIdDesc(eq(Set.of(300L)), any()))
                .thenReturn(new PageImpl<>(List.of(u)));

        FallbackService.UploadView view =
                service.uploads(office, new PageSpec(0, 20)).items().get(0);

        assertThat(view.enablementReference()).isNull();
    }

    @Test
    void SRS_EH_11_theRejectionsOfAnUploadInScopeComeReasonByReason() {
        FallbackUpload u = upload();
        when(uploads.findById(9L)).thenReturn(Optional.of(u));
        when(lines.rejectionCounts(9L))
                .thenReturn(
                        List.<Object[]>of(
                                new Object[] {"EMPLOYEE_NOT_FOUND: not on the Payroll master", 9L},
                                new Object[] {"OVERLAP: leave dates overlap", 5}));

        assertThat(service.rejections(office, 9L))
                .containsExactly(
                        new FallbackService.Rejection(
                                "EMPLOYEE_NOT_FOUND: not on the Payroll master", 9),
                        new FallbackService.Rejection("OVERLAP: leave dates overlap", 5));
        verify(policy).requireRecordAt(office, "X-07", "VIEW", 300L);
    }

    @Test
    void OD_121_aReconciledEnablementNamesWhenHrmsCameBackAndItsOfficesByName() {
        FallbackEnablement e = enablement(4L);
        when(e.getStatus()).thenReturn(FallbackEnablement.Status.RECONCILED);
        when(e.getReference()).thenReturn("FBE-2026-005");
        when(enablements.findAllByOrderByEnabledAtDescIdDesc(any()))
                .thenReturn(new PageImpl<>(List.of(e)));
        List<FallbackEnableScope> names = List.of(scope(300L), scope(301L));
        when(scopes.findByEnablementId(4L)).thenReturn(names);
        OrgLocation delhi = mock(OrgLocation.class);
        when(delhi.getCode()).thenReturn("DH001");
        when(delhi.getName()).thenReturn("Delhi HQ");
        when(org.location(300L)).thenReturn(Optional.of(delhi));
        when(org.location(301L)).thenReturn(Optional.empty());
        Instant first = Instant.parse("2026-07-02T05:00:00Z");
        FallbackUpload restored = upload();
        when(restored.getHrmsRestoredAt()).thenReturn(first);
        FallbackUpload later = upload();
        when(later.getHrmsRestoredAt()).thenReturn(first.plusSeconds(60));
        FallbackUpload none = upload();
        when(uploads.findByEnablementId(4L)).thenReturn(List.of(restored, none, later));

        FallbackService.EnablementView view =
                service.enablements(office, new PageSpec(0, 20)).items().get(0);

        assertThat(view.offices()).containsExactly("DH001", "301");
        assertThat(view.officeNames()).containsExactly("DH001 - Delhi HQ", "301");
        assertThat(view.hrmsRestoredAt()).isEqualTo(first.plusSeconds(60));
    }

    @Test
    void OD_121_aReconciledEnablementWithoutARestoreDateAndAnOpenOneReadNoUpload() {
        FallbackEnablement reconciled = enablement(4L);
        when(reconciled.getStatus()).thenReturn(FallbackEnablement.Status.RECONCILED);
        FallbackEnablement open = enablement(5L);
        when(enablements.findAllByOrderByEnabledAtDescIdDesc(any()))
                .thenReturn(new PageImpl<>(List.of(reconciled, open)));
        when(uploads.findByEnablementId(4L)).thenReturn(List.of());

        assertThat(service.enablements(office, new PageSpec(0, 20)).items())
                .extracting(FallbackService.EnablementView::hrmsRestoredAt)
                .containsOnlyNulls();
        verify(uploads, never()).findByEnablementId(5L);
    }

    @Test
    void SRS_INT_22_headquartersSeesEveryLiveEnablementAndAnOfficeOnlyThoseNamingIt() {
        FallbackEnablement ours = enablement(4L);
        FallbackEnablement theirs = enablement(5L);
        when(enablements.findByStatusInOrderByEnabledAtDescIdDesc(any()))
                .thenReturn(List.of(ours, theirs));
        List<FallbackEnableScope> oursNames = List.of(scope(300L));
        List<FallbackEnableScope> theirsNames = List.of(scope(301L));
        when(scopes.findByEnablementId(4L)).thenReturn(oursNames);
        when(scopes.findByEnablementId(5L)).thenReturn(theirsNames);

        when(policy.isAtHeadquarters(office)).thenReturn(false);
        assertThat(service.liveEnablements(office))
                .extracting(FallbackService.EnablementView::id)
                .containsExactly(4L);

        when(policy.isAtHeadquarters(office)).thenReturn(true);
        assertThat(service.liveEnablements(office))
                .extracting(FallbackService.EnablementView::id)
                .containsExactly(4L, 5L);
    }

    @Test
    void SRS_EH_11_aSubmissionWithItsOwnReasonIsSentWithIt() {
        FallbackUpload mine = mock(FallbackUpload.class);
        when(mine.getId()).thenReturn(12L);
        when(mine.getOrgUnitId()).thenReturn(300L);
        when(mine.getStatus()).thenReturn(FallbackUpload.Status.VALIDATED);
        when(mine.getType()).thenReturn(FallbackEnablement.Type.ATTENDANCE_LEAVE);
        when(mine.getPayrollYymm()).thenReturn("202610");
        when(mine.getBatchNo()).thenReturn("FBU-12");
        when(mine.getReconStatus()).thenReturn(FallbackUpload.ReconStatus.NOT_DUE);
        when(uploads.findById(12L)).thenReturn(Optional.of(mine));

        service.submit(office, 12L, " Attendance checked with the depot ");

        ArgumentCaptor<Submission> submitted = ArgumentCaptor.captor();
        verify(approvals).submit(eq(office), submitted.capture(), isNull());
        assertThat(submitted.getValue().reason()).isEqualTo("Attendance checked with the depot");
        verify(mine).submit(77L);
    }
}
