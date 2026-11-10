package in.gov.fci.payroll.document.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.document.api.DocumentStore.NewAttachment;
import in.gov.fci.payroll.document.business.DocumentErrorCode;
import in.gov.fci.payroll.document.business.DocumentStorage;
import in.gov.fci.payroll.document.business.MalwareScanner;
import in.gov.fci.payroll.document.business.StorageLayout;
import in.gov.fci.payroll.document.business.model.DocumentAttachment;
import in.gov.fci.payroll.document.config.DocumentProperties;
import in.gov.fci.payroll.document.persistence.DocumentAccessLog;
import in.gov.fci.payroll.document.persistence.DocumentRepositories;
import in.gov.fci.payroll.platform.cache.CacheProperties;
import in.gov.fci.payroll.platform.cache.CacheRegions;
import in.gov.fci.payroll.platform.config.ApiProperties;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.ErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

/**
 * Document refusals before anything is stored (SRS-G-30, BR-SEC-005; KB OQ-B3 virus scanner): a
 * file the scanner finds infected, a scanner that cannot be reached (the upload is refused, never
 * stored unscanned), a replacement of a file no longer active, and an adoption of a key that is not
 * provisional or of a file not under it.
 */
class AttachmentServiceTest {

    private final DocumentRepositories.Attachments attachments =
            mock(DocumentRepositories.Attachments.class);
    private final DocumentStorage storage = mock(DocumentStorage.class);
    private final MalwareScanner scanner = mock(MalwareScanner.class);
    private final AttachmentService service;

    AttachmentServiceTest() {
        CacheRegions regions = mock(CacheRegions.class);
        ApiProperties api = mock(ApiProperties.class);
        when(api.businessZone()).thenReturn(java.time.ZoneId.of("Asia/Kolkata"));
        when(regions.properties())
                .thenReturn(new CacheProperties(Duration.ofSeconds(10), Duration.ofMinutes(15)));
        service =
                new AttachmentService(
                        attachments,
                        mock(DocumentRepositories.Types.class),
                        storage,
                        mock(StorageLayout.class),
                        scanner,
                        mock(DocumentAccessLog.class),
                        new DocumentProperties(
                                1_048_576,
                                10,
                                "clamd",
                                "127.0.0.1",
                                3310,
                                Duration.ofSeconds(30),
                                "fci-payroll",
                                "{prefix}/{entity}/{yyyy}/{mm}/{uuid}"),
                        mock(AuditService.class),
                        api,
                        Clock.systemUTC(),
                        regions);
    }

    private static final NewAttachment SOURCE =
            new NewAttachment(
                    "hrms_fallback_upload",
                    "7",
                    null,
                    null,
                    null,
                    null,
                    "export.csv",
                    "EIS,CODE\n104882,W\n".getBytes(StandardCharsets.US_ASCII),
                    true);

    private static void refused(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        FciException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    @Test
    void BR_SEC_005_anInfectedFileIsRefusedAndNeverStored() {
        when(scanner.scan(any())).thenReturn(MalwareScanner.Verdict.INFECTED);
        refused(() -> service.attach("maker", SOURCE), DocumentErrorCode.INFECTED);
        verify(attachments, never()).saveAndFlush(any());
        verify(storage, never()).write(any(), any());
    }

    @Test
    void BR_SEC_005_aFileIsNotStoredUnscannedWhenTheScannerIsUnavailable() {
        when(scanner.scan(any()))
                .thenThrow(new MalwareScanner.ScanUnavailableException("timeout", null));
        refused(() -> service.attach("maker", SOURCE), DocumentErrorCode.SCAN_UNAVAILABLE);
        verify(attachments, never()).saveAndFlush(any());
    }

    @Test
    void SRS_G_30_aFileNoLongerActiveIsNotReplaced() {
        DocumentAttachment superseded = mock(DocumentAttachment.class);
        when(superseded.belongsTo("hrms_fallback_upload", "7")).thenReturn(true);
        when(attachments.findById(3L)).thenReturn(Optional.of(superseded));
        refused(
                () -> service.replace("maker", "hrms_fallback_upload", "7", 3L, SOURCE),
                CommonErrorCode.NOT_FOUND);
        verify(scanner, never()).scan(any());
    }

    @Test
    void SRS_G_31_oneWrongFileIsCancelledNeverDeletedAndOnlyAnActiveFileOfTheRecord() {
        DocumentAttachment active = mock(DocumentAttachment.class);
        when(active.belongsTo("cpf_deputation_recovery", "7")).thenReturn(true);
        when(active.isActive()).thenReturn(true);
        when(active.getChecksumSha256()).thenReturn("0123456789abcdef0123");
        when(attachments.findById(4L)).thenReturn(Optional.of(active));
        service.cancel("maker", "cpf_deputation_recovery", "7", 4L, "Wrong advice uploaded");
        verify(active).cancel();
        verify(attachments).save(active);
        // Another record's file, a file gone, and a file already cancelled are not found.
        refused(
                () -> service.cancel("maker", "cpf_deputation_recovery", "8", 4L, "x"),
                CommonErrorCode.NOT_FOUND);
        when(attachments.findById(5L)).thenReturn(Optional.empty());
        refused(
                () -> service.cancel("maker", "cpf_deputation_recovery", "7", 5L, "x"),
                CommonErrorCode.NOT_FOUND);
        when(active.isActive()).thenReturn(false);
        refused(
                () -> service.cancel("maker", "cpf_deputation_recovery", "7", 4L, "x"),
                CommonErrorCode.NOT_FOUND);
    }

    @Test
    void SRS_G_30_onlyAFileUnderAProvisionalKeyIsAdopted() {
        assertThatThrownBy(() -> service.adopt("maker", "e", null, 3L, "9"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Not a provisional key");
        assertThatThrownBy(() -> service.adopt("maker", "e", "final-9", 3L, "9"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Not a provisional key");
        when(attachments.findById(3L)).thenReturn(Optional.empty());
        assertThatThrownBy(
                        () ->
                                service.adopt(
                                        "maker", "e", AttachmentService.PROVISIONAL + "x", 3L, "9"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("No such provisional file");
    }
}
