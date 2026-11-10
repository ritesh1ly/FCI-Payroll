package in.gov.fci.payroll.general.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import in.gov.fci.payroll.general.business.GeneralErrorCode;
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
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.ErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.export.ExportReader;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

/**
 * X-07 HRMS fallback beyond the happy path (BR-INT-011, SRS-EH-11, SRS-G-47): an enablement needs a
 * known type and at least one office; an enablement is withdrawn only while no upload of it is with
 * the Checker or approved; an upload acts only for its own office, and a submission without a
 * reason takes the batch as its reason.
 */
class FallbackServiceEdgeTest {

    private static final Instant NOW = Instant.parse("2026-10-24T06:00:00Z");

    private final GeneralRepositories.Enablements enablements =
            mock(GeneralRepositories.Enablements.class);
    private final GeneralRepositories.Uploads uploads = mock(GeneralRepositories.Uploads.class);
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final DocumentStore documents = mock(DocumentStore.class);
    private final MakerChecker approvals = mock(MakerChecker.class);
    private final Authorization policy = mock(Authorization.class);

    private final FallbackService service =
            new FallbackService(
                    mock(ExportReader.class),
                    enablements,
                    mock(GeneralRepositories.EnableScopes.class),
                    uploads,
                    mock(GeneralRepositories.UploadLines.class),
                    new FallbackFormatProperties(Map.of()),
                    mock(EmployeeDirectory.class),
                    org,
                    mock(PayrollRecords.class),
                    documents,
                    approvals,
                    policy,
                    mock(AuditService.class),
                    JsonMapper.builder().build(),
                    Clock.fixed(NOW, ZoneOffset.UTC));

    private final AccessContext maker =
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

    FallbackServiceEdgeTest() {
        // Enabling and withdrawing are Headquarters acts (SRS role table, System Administrator).
        when(policy.isAtHeadquarters(any())).thenReturn(true);
        when(org.cycleUnitOf(any())).thenReturn(Optional.empty());
        when(approvals.submit(any(), any(), any()))
                .thenReturn(new SubmittedApproval(77L, ApprovalStatus.PENDING_APPROVAL, null));
    }

    private static void refused(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        FciException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    private static FallbackUpload upload(long id, Long office, FallbackUpload.Status status) {
        FallbackUpload u = mock(FallbackUpload.class);
        when(u.getId()).thenReturn(id);
        when(u.getOrgUnitId()).thenReturn(office);
        when(u.getStatus()).thenReturn(status);
        when(u.getType()).thenReturn(FallbackEnablement.Type.ATTENDANCE_LEAVE);
        when(u.getPayrollYymm()).thenReturn("202610");
        when(u.getBatchNo()).thenReturn("FBU-" + id);
        when(u.getReconStatus()).thenReturn(FallbackUpload.ReconStatus.NOT_DUE);
        return u;
    }

    @Test
    void BR_INT_011_anOfficeAwayFromHeadquartersCanNeitherEnableNorWithdraw() {
        // Security review 2026-10-31: an administrator of another office withdrew any window by id.
        when(policy.isAtHeadquarters(maker)).thenReturn(false);
        refused(
                () ->
                        service.proposeEnablement(
                                maker, "ATTENDANCE_LEAVE", "202610", List.of("DH001"), "r", null),
                ApiErrorCode.HQ_ONLY);
        refused(() -> service.withdraw(maker, 4L, "HRMS back"), ApiErrorCode.HQ_ONLY);
        verify(enablements, never()).findById(any());
        verify(approvals, never()).submit(any(), any(), any());
    }

    @Test
    void BR_INT_011_anEnablementNeedsAKnownTypeAndAtLeastOneOffice() {
        refused(
                () ->
                        service.proposeEnablement(
                                maker, "PAYSLIPS", "202610", List.of("DH001"), "r", null),
                GeneralErrorCode.FALLBACK_FORMAT);
        refused(
                () ->
                        service.proposeEnablement(
                                maker, "ATTENDANCE_LEAVE", "202610", null, "r", null),
                GeneralErrorCode.FALLBACK_OFFICES);
        refused(
                () ->
                        service.proposeEnablement(
                                maker, "ATTENDANCE_LEAVE", "202610", List.of(), "r", null),
                GeneralErrorCode.FALLBACK_OFFICES);
        when(org.byCode("XX999")).thenReturn(Optional.empty());
        refused(
                () ->
                        service.proposeEnablement(
                                maker, "ATTENDANCE_LEAVE", "202610", List.of("XX999"), "r", null),
                GeneralErrorCode.FALLBACK_OFFICES);
        verify(approvals, never()).submit(any(), any(), any());
    }

    /** SRS X-07 field table (l.876, l.5191): the enable reason is at most 500 characters. */
    @Test
    void SRS_G_47_anEnableReasonOfFiveHundredIsTakenAndOneMoreIsRefused() {
        OrgLocation office = mock(OrgLocation.class);
        when(office.getId()).thenReturn(300L);
        when(org.byCode("DH001")).thenReturn(Optional.of(office));
        refused(
                () ->
                        service.proposeEnablement(
                                maker,
                                "ATTENDANCE_LEAVE",
                                "202610",
                                List.of("DH001"),
                                "r".repeat(501),
                                null),
                GeneralErrorCode.QUERY_TEXT);
        verify(approvals, never()).submit(any(), any(), any());

        service.proposeEnablement(
                maker, "ATTENDANCE_LEAVE", "202610", List.of("DH001"), "r".repeat(500), null);
        verify(approvals).submit(any(), any(), any());
    }

    @Test
    void SRS_G_47_anEnablementIsWithdrawnOnlyWhileNoUploadOfItIsWithTheCheckerOrApproved() {
        FallbackEnablement open = mock(FallbackEnablement.class);
        when(open.getId()).thenReturn(4L);
        when(open.acceptsUploads()).thenReturn(true);
        when(open.getStatus()).thenReturn(FallbackEnablement.Status.UPLOADED);
        when(enablements.findById(4L)).thenReturn(Optional.of(open));
        FallbackEnablement closed = mock(FallbackEnablement.class);
        when(closed.getStatus()).thenReturn(FallbackEnablement.Status.CLOSED);
        when(enablements.findById(5L)).thenReturn(Optional.of(closed));

        refused(() -> service.withdraw(maker, 99L, "HRMS back"), CommonErrorCode.NOT_FOUND);
        refused(() -> service.withdraw(maker, 5L, "HRMS back"), GeneralErrorCode.FALLBACK_STATUS);
        FallbackUpload pending = upload(9L, 300L, FallbackUpload.Status.PENDING_APPROVAL);
        FallbackUpload approved = upload(10L, 300L, FallbackUpload.Status.APPROVED);
        for (FallbackUpload withChecker : List.of(pending, approved)) {
            when(uploads.findByEnablementId(4L)).thenReturn(List.of(withChecker));
            refused(
                    () -> service.withdraw(maker, 4L, "HRMS back"),
                    GeneralErrorCode.FALLBACK_STATUS);
        }
        verify(open, never()).withdraw(any());
    }

    @Test
    void SRS_EH_11_anUploadActsOnlyForItsOwnOfficeAndASubmissionWithoutAReasonNamesItsBatch() {
        FallbackUpload elsewhere = upload(11L, 301L, FallbackUpload.Status.VALIDATED);
        when(uploads.findById(11L)).thenReturn(Optional.of(elsewhere));
        refused(() -> service.submit(maker, 11L, "ready"), CommonErrorCode.NOT_FOUND);
        refused(() -> service.submit(maker, 404L, "ready"), CommonErrorCode.NOT_FOUND);

        FallbackUpload mine = upload(12L, 300L, FallbackUpload.Status.RETURNED);
        when(uploads.findById(12L)).thenReturn(Optional.of(mine));
        service.submit(maker, 12L, "   ");
        ArgumentCaptor<Submission> submitted = ArgumentCaptor.captor();
        verify(approvals).submit(eq(maker), submitted.capture(), isNull());
        assertThat(submitted.getValue().reason()).isEqualTo("HRMS fallback upload FBU-12");
        verify(mine).submit(77L);

        FallbackUpload decided = upload(13L, 300L, FallbackUpload.Status.APPROVED);
        when(uploads.findById(13L)).thenReturn(Optional.of(decided));
        refused(() -> service.submit(maker, 13L, "again"), GeneralErrorCode.FALLBACK_STATUS);
    }

    /**
     * SRS-G-47: an enablement whose uploads are only validated (not with the Checker) is withdrawn.
     */
    @Test
    void SRS_G_47_anEnablementWithOnlyAValidatedUploadIsWithdrawn() {
        FallbackEnablement open = mock(FallbackEnablement.class);
        when(open.getId()).thenReturn(4L);
        when(open.acceptsUploads()).thenReturn(true);
        when(open.getStatus()).thenReturn(FallbackEnablement.Status.UPLOADED);
        when(enablements.findById(4L)).thenReturn(Optional.of(open));
        when(open.getType()).thenReturn(FallbackEnablement.Type.ATTENDANCE_LEAVE);
        FallbackUpload validated = upload(9L, 300L, FallbackUpload.Status.VALIDATED);
        when(uploads.findByEnablementId(4L)).thenReturn(List.of(validated));
        service.withdraw(maker, 4L, "HRMS back");
        verify(open).withdraw(any());
    }

    /** BR-INT-011: an approved enablement whose stored proposal cannot be read is a fault. */
    @Test
    void BR_INT_011_anUnreadableEnablementProposalIsAFault() {
        assertThatThrownBy(() -> service.openApproved("not json", 1L, "checker"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Unreadable enablement proposal");
    }
}
