package in.gov.fci.payroll.document.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.document.business.DocumentErrorCode;
import in.gov.fci.payroll.document.business.DocumentStorage;
import in.gov.fci.payroll.document.business.MalwareScanner;
import in.gov.fci.payroll.document.business.StorageLayout;
import in.gov.fci.payroll.document.business.model.DocumentAttachment;
import in.gov.fci.payroll.document.config.DocumentProperties;
import in.gov.fci.payroll.document.persistence.DocumentAccessLog;
import in.gov.fci.payroll.document.persistence.DocumentRepositories;
import in.gov.fci.payroll.platform.approval.AttachmentPresence;
import in.gov.fci.payroll.platform.cache.CacheProperties;
import in.gov.fci.payroll.platform.cache.CacheRegions;
import in.gov.fci.payroll.platform.config.ApiProperties;
import in.gov.fci.payroll.platform.error.FciException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * OD-149 system outputs (SRS-G-30, BR-SEC-005): a generated file is stored only when it is a PDF or
 * a ZIP (a ZIP with entries, or an empty one, by its signature); anything shorter than a signature
 * or of another type is refused before it is stored. The records of an entity list only those that
 * hold documents.
 */
class AttachmentServiceCoverageGapsTest {

    private final DocumentRepositories.Attachments attachments =
            mock(DocumentRepositories.Attachments.class);
    private final DocumentStorage storage = mock(DocumentStorage.class);
    private final AuditService audit = mock(AuditService.class);
    private final AttachmentService service;

    AttachmentServiceCoverageGapsTest() {
        CacheRegions regions = mock(CacheRegions.class);
        ApiProperties api = mock(ApiProperties.class);
        when(api.businessZone()).thenReturn(java.time.ZoneId.of("Asia/Kolkata"));
        when(regions.properties())
                .thenReturn(new CacheProperties(Duration.ofSeconds(10), Duration.ofMinutes(15)));
        when(attachments.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        service =
                new AttachmentService(
                        attachments,
                        mock(DocumentRepositories.Types.class),
                        storage,
                        mock(StorageLayout.class),
                        mock(MalwareScanner.class),
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
                        audit,
                        api,
                        Clock.systemUTC(),
                        regions);
    }

    private String storedType(byte[] content) {
        service.storeGenerated("job", "job_request", "81", "out.bin", content);
        ArgumentCaptor<DocumentAttachment> saved =
                ArgumentCaptor.forClass(DocumentAttachment.class);
        verify(attachments).saveAndFlush(saved.capture());
        verify(storage).write(saved.getValue(), content);
        return saved.getValue().getContentType();
    }

    @Test
    void OD_149_aZipWithEntriesIsStoredAsAZip() {
        assertThat(storedType(new byte[] {'P', 'K', 3, 4, 0, 0})).isEqualTo("application/zip");
    }

    @Test
    void OD_149_anEmptyZipIsStoredAsAZip() {
        assertThat(storedType(new byte[] {'P', 'K', 5, 6, 0, 0})).isEqualTo("application/zip");
    }

    @Test
    void OD_149_aPdfIsStoredAsAPdf() {
        assertThat(storedType("%PDF-1.7\n%".getBytes(StandardCharsets.US_ASCII)))
                .isEqualTo("application/pdf");
    }

    @Test
    void OD_149_anythingElseOrTooShortIsRefusedBeforeItIsStored() {
        for (byte[] content :
                new byte[][] {
                    {'P', 'K', 3, 5, 0},
                    {'P', 'K', 5, 4, 0},
                    {'P', 'K', 4, 4, 0},
                    {'P', 'X', 3, 4, 0},
                    {'Q', 'K', 3, 4, 0},
                    "plain text".getBytes(StandardCharsets.US_ASCII)
                }) {
            assertThatThrownBy(
                            () -> service.storeGenerated("job", "job_request", "81", "x", content))
                    .isInstanceOfSatisfying(
                            FciException.class,
                            e ->
                                    assertThat(e.getErrorCode())
                                            .isEqualTo(DocumentErrorCode.FILE_TYPE));
        }
        for (byte[] content : new byte[][] {null, {'P', 'K', 3}}) {
            assertThatThrownBy(
                            () -> service.storeGenerated("job", "job_request", "81", "x", content))
                    .isInstanceOfSatisfying(
                            FciException.class,
                            e ->
                                    assertThat(e.getErrorCode())
                                            .isEqualTo(DocumentErrorCode.EMPTY_FILE));
        }
        verify(attachments, never()).saveAndFlush(any());
        verify(storage, never()).write(any(), any());
    }

    @Test
    void SRS_G_30_onlyRecordsHoldingDocumentsAreListed() {
        AttachmentPresence.AttachedFile file = mock(AttachmentPresence.AttachedFile.class);
        AttachmentPresence presence =
                new AttachmentPresence() {
                    @Override
                    public int countActive(String entityName, String entityKey) {
                        return "7".equals(entityKey) ? 1 : 0;
                    }

                    @Override
                    public List<AttachedFile> files(String entityName, String entityKey) {
                        return "7".equals(entityKey) ? List.of(file) : List.of();
                    }

                    @Override
                    public FileContent content(
                            String actor,
                            String sourceIp,
                            String entityName,
                            String entityKey,
                            long attachmentId) {
                        return null;
                    }
                };

        Map<String, List<AttachmentPresence.AttachedFile>> found =
                presence.filesOf("loan_header", List.of("6", "7", "8"));

        assertThat(found).containsOnlyKeys("7");
        assertThat(found.get("7")).containsExactly(file);
    }
}
