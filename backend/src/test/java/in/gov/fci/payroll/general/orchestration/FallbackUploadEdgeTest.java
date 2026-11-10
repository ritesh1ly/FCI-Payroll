package in.gov.fci.payroll.general.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.employee.api.EmployeeDirectory;
import in.gov.fci.payroll.general.business.FallbackRules;
import in.gov.fci.payroll.general.business.GeneralErrorCode;
import in.gov.fci.payroll.general.business.model.FallbackEnableScope;
import in.gov.fci.payroll.general.business.model.FallbackEnablement;
import in.gov.fci.payroll.general.business.model.FallbackUpload;
import in.gov.fci.payroll.general.business.model.FallbackUploadLine;
import in.gov.fci.payroll.general.config.FallbackFormatProperties;
import in.gov.fci.payroll.general.persistence.GeneralRepositories;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.payroll.api.PayrollRecords;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.export.ExportReader;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
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
 * X-07 HRMS fallback upload edges (SRS-G-46, SRS-G-47, SRS-EH-11; BR-INT-011): an empty file is
 * refused as a bad layout; a file without a name is kept as upload.csv; a record without an
 * employee number is rejected, not matched; earlier uploads are superseded only while unsubmitted
 * (one returned is, a superseded one is left alone, an approved one refuses the upload); a
 * submission without a reason takes the batch as its reason; a fallback (b) line has no day counts.
 */
class FallbackUploadEdgeTest {

    private static final Instant NOW = Instant.parse("2026-10-24T06:00:00Z");
    private static final long OFFICE = 300L;

    private final GeneralRepositories.Enablements enablements =
            mock(GeneralRepositories.Enablements.class);
    private final GeneralRepositories.EnableScopes scopes =
            mock(GeneralRepositories.EnableScopes.class);
    private final GeneralRepositories.Uploads uploads = mock(GeneralRepositories.Uploads.class);
    private final GeneralRepositories.UploadLines lines =
            mock(GeneralRepositories.UploadLines.class);
    private final EmployeeDirectory employees = mock(EmployeeDirectory.class);
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final DocumentStore documents = mock(DocumentStore.class);
    private final MakerChecker approvals = mock(MakerChecker.class);
    private final PayrollRecords payroll = mock(PayrollRecords.class);

    private final FallbackService service =
            new FallbackService(
                    mock(ExportReader.class),
                    enablements,
                    scopes,
                    uploads,
                    lines,
                    new FallbackFormatProperties(
                            Map.of(
                                    FallbackEnablement.Type.ATTENDANCE_LEAVE,
                                    new FallbackFormatProperties.FormatConfig(
                                            Map.of(
                                                    FallbackRules.EMPLOYEE_NUMBER, "EMP_NO",
                                                    FallbackRules.PERIOD_FROM, "FROM_DATE",
                                                    FallbackRules.PERIOD_TO, "TO_DATE",
                                                    FallbackRules.PAY_DAYS, "PAY_DAYS",
                                                    FallbackRules.LWP_DAYS, "LWP_DAYS"),
                                            "dd-MM-yyyy"))),
                    employees,
                    org,
                    payroll,
                    documents,
                    approvals,
                    mock(Authorization.class),
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
                    OFFICE,
                    null,
                    List.of(),
                    Map.of(),
                    Set.of(),
                    Set.of(),
                    Set.of(OFFICE));

    FallbackUploadEdgeTest() {
        FallbackEnablement e = mock(FallbackEnablement.class);
        when(e.getId()).thenReturn(5L);
        when(e.acceptsUploads()).thenReturn(true);
        when(e.getType()).thenReturn(FallbackEnablement.Type.ATTENDANCE_LEAVE);
        when(e.getPayrollYymm()).thenReturn("202610");
        when(enablements.findById(5L)).thenReturn(Optional.of(e));
        FallbackEnableScope scope = mock(FallbackEnableScope.class);
        when(scope.getOrgUnitId()).thenReturn(OFFICE);
        when(scopes.findByEnablementId(5L)).thenReturn(List.of(scope));
        when(org.cycleUnitOf(any())).thenReturn(Optional.empty());
        when(org.subtree(OFFICE)).thenReturn(Set.of(OFFICE));
        when(employees.memberByEis(anyString())).thenReturn(Optional.empty());
        when(uploads.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        DocumentStore.AttachmentView source = mock(DocumentStore.AttachmentView.class);
        when(source.id()).thenReturn(41L);
        when(documents.attach(any(), any())).thenReturn(source);
        when(approvals.submit(any(), any(), any()))
                .thenReturn(new SubmittedApproval(77L, ApprovalStatus.PENDING_APPROVAL, null));
    }

    private static byte[] csv(String... records) {
        return ("EMP_NO,FROM_DATE,TO_DATE,PAY_DAYS,LWP_DAYS\n" + String.join("\n", records))
                .getBytes(StandardCharsets.US_ASCII);
    }

    private static FallbackUpload earlier(FallbackUpload.Status status) {
        FallbackUpload u = mock(FallbackUpload.class);
        when(u.getStatus()).thenReturn(status);
        return u;
    }

    /**
     * X-07 precondition (SRS l.849, US-GAP-120): "The payroll cycle for the period is not yet
     * locked" - an upload for an office whose cycle of the enabled month is locked is refused
     * before the file is read or stored.
     */
    @Test
    void SRS_G_46_anUploadForALockedCycleIsRefusedAndNothingIsStored() {
        in.gov.fci.payroll.organisation.business.model.OrgLocation unit =
                mock(in.gov.fci.payroll.organisation.business.model.OrgLocation.class);
        when(unit.getId()).thenReturn(OFFICE);
        when(org.cycleUnitOf(OFFICE)).thenReturn(Optional.of(unit));
        when(payroll.cycle(OFFICE, "202610"))
                .thenReturn(Optional.of(new PayrollRecords.Cycle(9L, OFFICE, "202610", "LOCKED")));
        assertThatThrownBy(() -> service.upload(maker, 5L, "att.csv", csv("101,01-10-2026")))
                .isInstanceOfSatisfying(
                        FciException.class,
                        ex ->
                                assertThat(ex.getErrorCode())
                                        .isEqualTo(GeneralErrorCode.FALLBACK_CYCLE_LOCKED));
        verify(uploads, never()).saveAndFlush(any());
        verify(documents, never()).attach(any(), any());
    }

    @Test
    void SRS_G_46_anEmptyFileIsRefusedAsALayoutError() {
        assertThatThrownBy(() -> service.upload(maker, 5L, "empty.csv", null))
                .isInstanceOfSatisfying(
                        FciException.class,
                        ex ->
                                assertThat(ex.getErrorCode())
                                        .isEqualTo(GeneralErrorCode.FALLBACK_FORMAT));
    }

    @Test
    void SRS_G_46_anUnnamedFileIsKeptAsUploadCsvAndANumberlessRecordIsRejected() {
        FallbackUpload returned = earlier(FallbackUpload.Status.RETURNED);
        FallbackUpload superseded = earlier(FallbackUpload.Status.SUPERSEDED);
        when(uploads.findByEnablementIdAndOrgUnitId(5L, OFFICE))
                .thenReturn(List.of(returned, superseded));

        service.upload(maker, 5L, null, csv(",01-10-2026,31-10-2026,31,0"));

        ArgumentCaptor<FallbackUpload> saved = ArgumentCaptor.captor();
        verify(uploads).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getSourceFileName()).isEqualTo("upload.csv");
        assertThat(saved.getValue().getAcceptedCount()).isZero();
        ArgumentCaptor<FallbackUploadLine> line = ArgumentCaptor.captor();
        verify(lines).save(line.capture());
        assertThat(line.getValue().getEmployeeId()).isNull();
        assertThat(line.getValue().getRejectionReason()).isNotNull();
        verify(employees, never()).memberByEis(eq(""));
        // A returned upload is superseded by the new one; a superseded one is left as it is.
        verify(returned).supersede();
        verify(superseded, never()).supersede();
    }

    @Test
    void SRS_G_47_anApprovedUploadOfTheOfficeRefusesANewOne() {
        FallbackUpload approved = earlier(FallbackUpload.Status.APPROVED);
        when(uploads.findByEnablementIdAndOrgUnitId(5L, OFFICE)).thenReturn(List.of(approved));
        assertThatThrownBy(
                        () ->
                                service.upload(
                                        maker,
                                        5L,
                                        "a.csv",
                                        csv("E1001,01-10-2026,31-10-2026,31,0")))
                .isInstanceOfSatisfying(
                        FciException.class,
                        ex ->
                                assertThat(ex.getErrorCode())
                                        .isEqualTo(GeneralErrorCode.FALLBACK_STATUS));
        verify(uploads, never()).saveAndFlush(any());
    }

    @Test
    void SRS_EH_11_aSubmissionWithoutAnyReasonNamesItsBatch() {
        FallbackUpload mine = mock(FallbackUpload.class);
        when(mine.getId()).thenReturn(12L);
        when(mine.getOrgUnitId()).thenReturn(OFFICE);
        when(mine.getStatus()).thenReturn(FallbackUpload.Status.VALIDATED);
        when(mine.getType()).thenReturn(FallbackEnablement.Type.ATTENDANCE_LEAVE);
        when(mine.getPayrollYymm()).thenReturn("202610");
        when(mine.getBatchNo()).thenReturn("FBU-12");
        when(mine.getReconStatus()).thenReturn(FallbackUpload.ReconStatus.NOT_DUE);
        when(uploads.findById(12L)).thenReturn(Optional.of(mine));
        service.submit(maker, 12L, null);
        ArgumentCaptor<Submission> submitted = ArgumentCaptor.captor();
        verify(approvals).submit(eq(maker), submitted.capture(), any());
        assertThat(submitted.getValue().reason()).isEqualTo("HRMS fallback upload FBU-12");
    }

    @Test
    void SRS_G_46_aLineWithoutDayCountsShowsNone() {
        FallbackUpload mine = mock(FallbackUpload.class);
        when(mine.getId()).thenReturn(12L);
        when(mine.getOrgUnitId()).thenReturn(OFFICE);
        when(uploads.findById(12L)).thenReturn(Optional.of(mine));
        FallbackUploadLine order = mock(FallbackUploadLine.class);
        when(order.getLineNo()).thenReturn(1);
        when(order.getPayDays()).thenReturn(null);
        when(order.getLwpDays()).thenReturn(new BigDecimal("1.5"));
        when(lines.findByUploadIdOrderByLineNo(anyLong(), any()))
                .thenReturn(new PageImpl<>(List.of(order)));
        FallbackService.LineView view =
                service.lines(maker, 12L, new PageSpec(0, 20)).items().get(0);
        assertThat(view.payDays()).isNull();
        assertThat(view.lwpDays()).isEqualTo("1.5");
    }
}
