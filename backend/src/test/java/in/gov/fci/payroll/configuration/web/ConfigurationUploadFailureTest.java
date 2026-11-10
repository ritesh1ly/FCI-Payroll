package in.gov.fci.payroll.configuration.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.configuration.orchestration.CeilingNormService;
import in.gov.fci.payroll.configuration.orchestration.CircularService;
import in.gov.fci.payroll.configuration.orchestration.LabelService;
import in.gov.fci.payroll.configuration.orchestration.NetPayFloorService;
import in.gov.fci.payroll.configuration.orchestration.OperationService;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.web.ExportStreaming;
import in.gov.fci.payroll.platform.web.Paging;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

/**
 * SRS-G-39 (M-26 label bulk upload) and SRS-G-31 (M-25 circular order copy): a multipart file whose
 * bytes cannot be read is refused before any label or circular is proposed.
 */
class ConfigurationUploadFailureTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Kolkata"));

    private final CurrentAccess contexts = mock(CurrentAccess.class);
    private final LabelService labels = mock(LabelService.class);
    private final CircularService circulars = mock(CircularService.class);
    private final MdmController controller =
            new MdmController(
                    contexts,
                    mock(NetPayFloorService.class),
                    mock(CeilingNormService.class),
                    circulars,
                    labels,
                    mock(OperationService.class),
                    mock(ExportStreaming.class),
                    mock(Paging.class));

    private static MultipartFile unreadable() throws IOException {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getOriginalFilename()).thenReturn("labels.csv");
        when(file.getBytes()).thenThrow(new IOException("temp file gone"));
        return file;
    }

    private static MultipartFile content(String name, byte[] bytes) throws IOException {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getOriginalFilename()).thenReturn(name);
        when(file.getBytes()).thenReturn(bytes);
        return file;
    }

    @Test
    void SRS_G_39_labelUploadWithUnreadableFileIsRefusedBeforeAnyLabelIsProposed()
            throws IOException {
        MultipartFile file = unreadable();

        assertThatThrownBy(() -> controller.uploadLabels("Bulk load", file))
                .isInstanceOf(UncheckedIOException.class)
                .hasCauseInstanceOf(IOException.class)
                .hasRootCauseMessage("temp file gone");
        verifyNoInteractions(labels);
    }

    @Test
    void SRS_G_39_labelUploadOfAnEmptyFileIsRefusedAsValidationFailure() throws IOException {
        MultipartFile file = content("labels.csv", new byte[0]);

        assertThatThrownBy(() -> controller.uploadLabels("Bulk load", file))
                .isInstanceOf(FciException.class);
        verifyNoInteractions(labels);
    }

    @Test
    void SRS_G_39_labelUploadOfAReadableFileIsAcceptedAsProposals() throws IOException {
        String csv =
                "label_key,label_type,language,label_text,effective_from\r\n"
                        + "LBL.A,UI_LABEL,EN,Hello,"
                        + TODAY
                        + "\r\n";
        List<LabelService.LabelView> views = List.of(mock(LabelService.LabelView.class));
        when(labels.upload(any(), any(), eq("Bulk load"), eq(null))).thenReturn(views);

        ResponseEntity<List<LabelService.LabelView>> response =
                controller.uploadLabels(
                        "Bulk load", content("labels.csv", csv.getBytes(StandardCharsets.UTF_8)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isSameAs(views);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LabelService.ProposedLabel>> rows = ArgumentCaptor.forClass(List.class);
        verify(labels).upload(any(), rows.capture(), eq("Bulk load"), eq(null));
        assertThat(rows.getValue()).hasSize(1);
        assertThat(rows.getValue().get(0).key()).isEqualTo("LBL.A");
        assertThat(rows.getValue().get(0).effectiveFrom()).isEqualTo(TODAY);
    }

    @Test
    void SRS_G_31_circularWithUnreadableOrderCopyIsRefusedBeforeItIsRecorded() throws IOException {
        MdmController.CircularRequest request =
                new MdmController.CircularRequest(
                        "FCI/CIR/1",
                        TODAY,
                        "FCI_HQ",
                        "Revised DA",
                        "ALLOWANCE",
                        List.of("EMPLOYEE_IDA"),
                        true,
                        null,
                        TODAY,
                        null,
                        null,
                        "New circular",
                        null);
        MultipartFile file = unreadable();

        assertThatThrownBy(() -> controller.recordCircular(request, file))
                .isInstanceOf(UncheckedIOException.class)
                .hasCauseInstanceOf(IOException.class);
        verifyNoInteractions(circulars);
    }
}
