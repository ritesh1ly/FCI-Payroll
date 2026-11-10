package in.gov.fci.payroll.payroll.orchestration;

import static in.gov.fci.payroll.platform.export.PdfText.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.jobs.api.BackgroundJobs;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.ErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.export.ExportFailed;
import in.gov.fci.payroll.platform.export.PdfRenderGate;
import in.gov.fci.payroll.platform.export.ZipBundle;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/**
 * OD-144, OD-149: every payslip PDF is rendered on demand from the locked data through
 * PayslipService (its own access and scope rules); a member's bulk is a ZIP of single-month PDFs
 * within the configured number of months; the O-01 bulk is a background job whose ZIP goes to its
 * requester only.
 */
class PayslipPdfServiceTest {

    private final PayslipService payslips = mock(PayslipService.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final BackgroundJobs jobs = mock(BackgroundJobs.class);
    private final DocumentStore documents = mock(DocumentStore.class);
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final Authorization policy = mock(Authorization.class);
    private final AccessContext context = mock(AccessContext.class);
    private final PdfRenderGate gate =
            new PdfRenderGate(4, Duration.ofSeconds(1), Duration.ofSeconds(5), events);

    private PayslipPdfService service(Integer max) {
        return new PayslipPdfService(payslips, gate, max, jobs, documents, org, policy);
    }

    private final PayslipPdfService service = service(12);

    PayslipPdfServiceTest() {
        OrgLocation unit = mock(OrgLocation.class);
        when(unit.getId()).thenReturn(50L);
        when(context.locationId()).thenReturn(7L);
        when(context.loginId()).thenReturn("maker@fci-demo.test");
        when(org.cycleUnitOf(7L)).thenReturn(Optional.of(unit));
    }

    private static void refused(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        FciException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    /** The entries of a ZIP in order, with their bytes. */
    static Map<String, byte[]> entries(byte[] zip) throws Exception {
        Map<String, byte[]> out = new java.util.LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                out.put(e.getName(), in.readAllBytes());
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- single month

    @Test
    void SRS_17_1_theOwnPayslipIsReadAsTheMembersOwnLockedMonth() {
        when(payslips.payslip(context, "202609")).thenReturn(PayslipPdfTest.slip(false));
        assertThat(text(service.mine(context, "202609"))).contains("104882");
        verify(payslips).payslip(context, "202609");
    }

    @Test
    void SRS_17_1_anotherMembersMonthOrAnUnknownMonthIsNotFound() {
        when(payslips.payslip(context, "209901"))
                .thenThrow(new FciException(CommonErrorCode.NOT_FOUND));
        refused(() -> service.mine(context, "209901"), CommonErrorCode.NOT_FOUND);
        when(payslips.ofStaff(context, "OTHER", "202609"))
                .thenThrow(new FciException(CommonErrorCode.NOT_FOUND));
        refused(() -> service.ofStaff(context, "OTHER", "202609"), CommonErrorCode.NOT_FOUND);
        when(payslips.ofPreview(context, 4L, "202609"))
                .thenThrow(new FciException(CommonErrorCode.NOT_FOUND));
        refused(() -> service.ofPreview(context, 4L, "202609"), CommonErrorCode.NOT_FOUND);
    }

    @Test
    void SRS_17_6_thePreviewAndTheO01ReadsUseTheirOwnScopedReads() {
        when(payslips.ofPreview(context, 4L, "202609")).thenReturn(PayslipPdfTest.slip(false));
        when(payslips.ofStaff(context, "104882", "202609")).thenReturn(PayslipPdfTest.slip(true));
        assertThat(text(service.ofPreview(context, 4L, "202609"))).contains("Ravi Sharma");
        assertThat(text(service.ofStaff(context, "104882", "202609"))).contains("PROVISIONAL");
    }

    @Test
    void SRS_O01_l3392_theSameMonthRendersToTheSameBytes() {
        when(payslips.payslip(context, "202609")).thenReturn(PayslipPdfTest.slip(false));
        assertThat(service.mine(context, "202609")).isEqualTo(service.mine(context, "202609"));
    }

    @Test
    void OD_144_aRenderFailureIsAuditedAndAnsweredAsAnErrorWithNoFile() {
        PayslipService.Payslip broken = mock(PayslipService.Payslip.class);
        when(broken.yymm()).thenReturn("not-a-month");
        when(payslips.payslip(context, "202609")).thenReturn(broken);
        refused(() -> service.mine(context, "202609"), CommonErrorCode.INTERNAL_ERROR);
        verify(events)
                .publishEvent(
                        argThat(
                                (Object e) ->
                                        e instanceof ExportFailed f
                                                && f.name().equals("payslip-202609")));
    }

    // ---------------------------------------------------------------- member bulk (E-01)

    @Test
    void OD_149_theMemberBulkIsRefusedWhileItsSettingIsNotMade() {
        PayslipPdfService unset = service(null);
        assertThat(unset.memberBulkMax()).isEmpty();
        refused(
                () -> unset.memberMonths(context, List.of("202601", "202603")),
                ApiErrorCode.PDF_BULK_NOT_CONFIGURED);
        assertThat(service(0).memberBulkMax()).as("zero is not a setting").isEmpty();
        assertThat(service.memberBulkMax()).contains(12);
    }

    private static List<String> months(int from, int count) {
        List<String> out = new ArrayList<>();
        java.time.YearMonth m = java.time.YearMonth.of(from / 100, from % 100);
        for (int i = 0; i < count; i++) {
            out.add(String.format("%04d%02d", m.getYear(), m.getMonthValue()));
            m = m.plusMonths(1);
        }
        return out;
    }

    @Test
    void OD_149_oneMonthAndExactlyTheMaxAreAllowed_maxPlusOneIsRefusedWithBothNumbers() {
        when(payslips.lockedMonths(any(), anyString(), anyString())).thenReturn(months(202501, 13));
        assertThat(service.memberMonths(context, List.of("202601"))).containsExactly("202601");
        assertThat(service.memberMonths(context, months(202501, 12))).hasSize(12);
        assertThatThrownBy(() -> service.memberMonths(context, months(202501, 13)))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> {
                            assertThat(e.getErrorCode()).isEqualTo(ApiErrorCode.PDF_BULK_TOO_MANY);
                            assertThat(e.getMessageArgs()).containsExactly(13, 12);
                        });
    }

    @Test
    void OD_149_duplicatesCountOnceAndTheMonthsComeBackOldestFirst() {
        when(payslips.lockedMonths(context, "202601", "202603"))
                .thenReturn(List.of("202601", "202602", "202603"));
        assertThat(service.memberMonths(context, List.of("202603", "202601", "202603")))
                .containsExactly("202601", "202603");
        verify(payslips).lockedMonths(context, "202601", "202603");
    }

    @Test
    void OD_149_anOpenOrAnotherMembersMonthMakesTheWholeRequestNotFound() {
        when(payslips.lockedMonths(context, "202601", "202603")).thenReturn(List.of("202601"));
        refused(
                () -> service.memberMonths(context, List.of("202601", "202603")),
                CommonErrorCode.NOT_FOUND);
        when(payslips.lockedMonths(context, "202605", "202605")).thenReturn(List.of());
        refused(() -> service.memberMonths(context, List.of("202605")), CommonErrorCode.NOT_FOUND);
    }

    @Test
    void OD_149_noMonthOrABadMonthIsInvalid() {
        refused(() -> service.memberMonths(context, List.of()), CommonErrorCode.VALIDATION_FAILED);
        refused(() -> service.memberMonths(context, null), CommonErrorCode.VALIDATION_FAILED);
        refused(
                () -> service.memberMonths(context, List.of("202613")),
                CommonErrorCode.VALIDATION_FAILED);
        refused(
                () -> service.memberMonths(context, List.of("2026-1")),
                CommonErrorCode.VALIDATION_FAILED);
    }

    private byte[] memberZip(List<String> months) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipBundle z = new ZipBundle(out, PayslipPdfService.zipTime(months))) {
            service.writeMemberZip(context, months, z);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    @Test
    void OD_149_theMemberZipHasOnePdfPerLockedMonthInOrderAndRepeatsByteForByte() throws Exception {
        when(payslips.payslip(eq(context), anyString())).thenReturn(PayslipPdfTest.slip(false));
        List<String> months = List.of("202601", "202602", "202603");
        byte[] first = memberZip(months);
        assertThat(entries(first).keySet())
                .containsExactly("payslip-202601.pdf", "payslip-202602.pdf", "payslip-202603.pdf");
        assertThat(memberZip(months)).as("the same request, the same bytes").isEqualTo(first);
        assertThat(PayslipPdfService.zipTime(months)).isEqualTo(LocalDateTime.of(2026, 3, 1, 0, 0));
        assertThat(PayslipPdfService.zipTime(List.of()))
                .isEqualTo(LocalDateTime.of(2000, 1, 1, 0, 0));
    }

    @Test
    void OD_149_aMonthThatFailsIsLeftOutAndNamedInTheManifest() throws Exception {
        when(payslips.payslip(context, "202601")).thenReturn(PayslipPdfTest.slip(false));
        when(payslips.payslip(context, "202602")).thenThrow(new IllegalStateException("x"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipBundle z = new ZipBundle(out, LocalDateTime.of(2026, 2, 1, 0, 0))) {
            service.writeMemberZip(context, List.of("202601", "202602"), z);
            assertThat(z.added()).isEqualTo(1);
            assertThat(z.leftOut()).isEqualTo(1);
        }
        Map<String, byte[]> e = entries(out.toByteArray());
        assertThat(e.keySet()).containsExactly("payslip-202601.pdf", ZipBundle.MANIFEST);
        assertThat(
                        new String(
                                e.get(ZipBundle.MANIFEST),
                                java.nio.charset.StandardCharsets.US_ASCII))
                .contains("payslip-202602.pdf", "IllegalStateException")
                .doesNotContain("Ravi");
    }

    @Test
    void OD_149_aFullBulkheadStopsTheZipWithBusyRatherThanLeavingMonthsOut() {
        PdfRenderGate none = mock(PdfRenderGate.class);
        when(none.render(anyString(), any())).thenThrow(new PdfRenderGate.Busy(5));
        PayslipPdfService busy =
                new PayslipPdfService(payslips, none, 12, jobs, documents, org, policy);
        when(payslips.payslip(eq(context), anyString())).thenReturn(PayslipPdfTest.slip(false));
        assertThatThrownBy(
                        () ->
                                busy.writeMemberZip(
                                        context,
                                        List.of("202601"),
                                        new ZipBundle(
                                                new ByteArrayOutputStream(),
                                                LocalDateTime.of(2026, 1, 1, 0, 0))))
                .isInstanceOf(PdfRenderGate.Busy.class);
    }

    // ---------------------------------------------------------------- O-01 bulk job

    @Test
    void SRS_NF_2_theO01BulkIsQueuedForTheUnitOfALockedMonthOnly() {
        when(payslips.monthLocked(50L, "202609")).thenReturn(true);
        when(jobs.enqueue(eq(context), eq(BackgroundJobs.Kind.PAYSLIP_BULK), eq(50L), any()))
                .thenReturn(91L);
        PayslipPdfService.BulkFile queued =
                service.requestBulk(context, "202609", PayslipRegisterService.Level.LABOUR_DEPOT);
        assertThat(queued.jobRequestId()).isEqualTo(91L);
        assertThat(queued.status()).isEqualTo("PENDING");
        verify(jobs)
                .enqueue(
                        context,
                        BackgroundJobs.Kind.PAYSLIP_BULK,
                        50L,
                        Map.of(
                                "locationId",
                                "50",
                                "payPeriod",
                                "2026-09",
                                "level",
                                "LABOUR_DEPOT"));
        when(payslips.monthLocked(50L, "202610")).thenReturn(false);
        refused(
                () -> service.requestBulk(context, "202610", null),
                ApiErrorCode.JOB_PARAMETER_INVALID);
    }

    @Test
    void SRS_NF_2_theBulkStatusIsTheUsersOwnNewestRequest() {
        when(jobs.latest(eq(BackgroundJobs.Kind.PAYSLIP_BULK), eq("maker@fci-demo.test"), any()))
                .thenReturn(Optional.of(job("RUNNING", "maker@fci-demo.test", 40, 2)));
        assertThat(service.bulkStatus(context, "202609", null))
                .contains(new PayslipPdfService.BulkFile(91L, "RUNNING", 40, 2));
        when(jobs.latest(eq(BackgroundJobs.Kind.PAYSLIP_BULK), eq("maker@fci-demo.test"), any()))
                .thenReturn(Optional.empty());
        assertThat(service.bulkStatus(context, "202609", null)).isEmpty();
    }

    private static BackgroundJobs.Job job(
            String status, String requester, long written, long skipped) {
        return new BackgroundJobs.Job(
                91L,
                BackgroundJobs.Kind.PAYSLIP_BULK,
                status,
                requester,
                50L,
                Map.of("payPeriod", "2026-09"),
                written,
                skipped,
                null);
    }

    @Test
    void OD_149_theBulkFileGoesToItsRequesterOnlyOnceCompleted() {
        DocumentStore.AttachmentView file = mock(DocumentStore.AttachmentView.class);
        when(file.id()).thenReturn(5L);
        when(documents.active(PayslipPdfService.OUTPUT_ENTITY, "91")).thenReturn(List.of(file));
        when(documents.read("maker@fci-demo.test", "ip", PayslipPdfService.OUTPUT_ENTITY, "91", 5L))
                .thenReturn(new DocumentStore.Content(file, new byte[] {'P', 'K', 3, 4}));
        when(jobs.find(BackgroundJobs.Kind.PAYSLIP_BULK, 91L))
                .thenReturn(Optional.of(job("COMPLETED", "maker@fci-demo.test", 3, 0)));
        PayslipPdfService.Download d = service.bulkFile(context, "ip", 91L);
        assertThat(d.name()).isEqualTo("payslips-202609");
        assertThat(d.files()).isEqualTo(3);
        // Another user's job reads as none (no existence leak).
        when(jobs.find(BackgroundJobs.Kind.PAYSLIP_BULK, 91L))
                .thenReturn(Optional.of(job("COMPLETED", "other@fci-demo.test", 3, 0)));
        refused(() -> service.bulkFile(context, "ip", 91L), CommonErrorCode.NOT_FOUND);
        // Not finished: 409 with its state.
        when(jobs.find(BackgroundJobs.Kind.PAYSLIP_BULK, 91L))
                .thenReturn(Optional.of(job("RUNNING", "maker@fci-demo.test", 1, 0)));
        refused(() -> service.bulkFile(context, "ip", 91L), ApiErrorCode.PDF_BULK_NOT_READY);
        // Another kind of job, or an unknown id.
        when(jobs.find(BackgroundJobs.Kind.PAYSLIP_BULK, 92L)).thenReturn(Optional.empty());
        refused(() -> service.bulkFile(context, "ip", 92L), CommonErrorCode.NOT_FOUND);
    }

    @Test
    void SRS_G_2_theRequesterWhoLostTheUnitIsRefused() {
        when(jobs.find(BackgroundJobs.Kind.PAYSLIP_BULK, 91L))
                .thenReturn(Optional.of(job("COMPLETED", "maker@fci-demo.test", 3, 0)));
        doThrow(new FciException(CommonErrorCode.NOT_FOUND))
                .when(policy)
                .requireRecordAt(context, "O-01", "VIEW", 50L);
        refused(() -> service.bulkFile(context, "ip", 91L), CommonErrorCode.NOT_FOUND);
        verify(documents, never()).read(any(), any(), any(), any(), any());
    }

    @Test
    void OD_149_aCompletedJobWithoutItsFileIsNotFound() {
        when(jobs.find(BackgroundJobs.Kind.PAYSLIP_BULK, 91L))
                .thenReturn(Optional.of(job("COMPLETED", "maker@fci-demo.test", 3, 0)));
        when(documents.active(PayslipPdfService.OUTPUT_ENTITY, "91")).thenReturn(new ArrayList<>());
        refused(() -> service.bulkFile(context, "ip", 91L), CommonErrorCode.NOT_FOUND);
    }
}
