package in.gov.fci.payroll.general.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.general.api.SupplementaryPayments;
import in.gov.fci.payroll.general.orchestration.FallbackService;
import in.gov.fci.payroll.general.orchestration.OffCycleService;
import in.gov.fci.payroll.general.orchestration.SupplementaryService;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.web.ExportStreaming;
import in.gov.fci.payroll.platform.web.Paging;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

/**
 * SRS-G-30 / SRS-G-31 (order copy of I-20 off-cycle, I-23 supplementary and X-07 fallback): a
 * multipart file whose bytes cannot be read is refused before the service runs, so no payment,
 * upload or attachment is created without its order.
 */
class GeneralUploadFailureTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Kolkata"));
    private static final byte[] PDF = {'%', 'P', 'D', 'F', '-', '1'};

    private final CurrentAccess contexts = mock(CurrentAccess.class);
    private final ExportStreaming exports = mock(ExportStreaming.class);
    private final Paging paging = mock(Paging.class);

    private static MultipartFile unreadable() throws IOException {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getOriginalFilename()).thenReturn("order.pdf");
        when(file.getBytes()).thenThrow(new IOException("temp file gone"));
        return file;
    }

    private static MultipartFile readable() throws IOException {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getOriginalFilename()).thenReturn("order.pdf");
        when(file.getBytes()).thenReturn(PDF);
        return file;
    }

    @Test
    void SRS_G_30_supplementaryCaptureWithUnreadableOrderIsRefusedBeforeTheServiceIsCalled()
            throws IOException {
        SupplementaryService service = mock(SupplementaryService.class);
        SupplementaryController controller =
                new SupplementaryController(exports, contexts, service, paging);
        MultipartFile file = unreadable();

        assertThatThrownBy(
                        () ->
                                controller.capture(
                                        "900001",
                                        "SALARY_WAGE",
                                        "202604",
                                        TODAY,
                                        null,
                                        null,
                                        "TRANSFER_ORDER",
                                        null,
                                        TODAY,
                                        "FCI HQ",
                                        file))
                .isInstanceOf(UncheckedIOException.class)
                .hasCauseInstanceOf(IOException.class)
                .hasRootCauseMessage("temp file gone");
        verifyNoInteractions(service);
    }

    @Test
    void SRS_G_30_supplementaryCapturePassesTheOrderBytesAndIsCreated() throws IOException {
        SupplementaryService service = mock(SupplementaryService.class);
        SupplementaryService.PaymentView view = mock(SupplementaryService.PaymentView.class);
        when(service.capture(
                        any(),
                        eq("900001"),
                        eq("SALARY_WAGE"),
                        eq("202604"),
                        any(),
                        any(),
                        any(),
                        any()))
                .thenReturn(view);
        SupplementaryController controller =
                new SupplementaryController(exports, contexts, service, paging);

        ResponseEntity<SupplementaryService.PaymentView> response =
                controller.capture(
                        "900001",
                        "SALARY_WAGE",
                        "202604",
                        TODAY,
                        TODAY,
                        "SO-1",
                        "TRANSFER_ORDER",
                        "R-1",
                        TODAY,
                        "FCI HQ",
                        readable());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isSameAs(view);
        ArgumentCaptor<SupplementaryPayments.Order> order =
                ArgumentCaptor.forClass(SupplementaryPayments.Order.class);
        verify(service)
                .capture(any(), any(), any(), any(), any(), eq(TODAY), eq("SO-1"), order.capture());
        assertThat(order.getValue().content()).isEqualTo(PDF);
        assertThat(order.getValue().fileName()).isEqualTo("order.pdf");
    }

    @Test
    void SRS_G_31_offCycleOrderWithUnreadableFileIsRefusedBeforeTheServiceIsCalled()
            throws IOException {
        OffCycleService service = mock(OffCycleService.class);
        OffCycleController controller = new OffCycleController(exports, contexts, service, paging);
        MultipartFile file = unreadable();

        assertThatThrownBy(
                        () -> controller.order(4L, "TRANSFER_ORDER", null, TODAY, "FCI HQ", file))
                .isInstanceOf(UncheckedIOException.class)
                .hasCauseInstanceOf(IOException.class);
        verifyNoInteractions(service);
    }

    @Test
    void SRS_G_31_offCycleOrderIsAttachedWithItsBytes() throws IOException {
        OffCycleService service = mock(OffCycleService.class);
        DocumentStore.AttachmentView view = mock(DocumentStore.AttachmentView.class);
        when(service.attachOrder(any(), eq(4L), any(), any(), any(), any(), any(), any()))
                .thenReturn(view);
        OffCycleController controller = new OffCycleController(exports, contexts, service, paging);

        ResponseEntity<DocumentStore.AttachmentView> response =
                controller.order(4L, "TRANSFER_ORDER", "R-2", TODAY, "FCI HQ", readable());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isSameAs(view);
        verify(service)
                .attachOrder(
                        any(),
                        eq(4L),
                        eq("TRANSFER_ORDER"),
                        eq("R-2"),
                        eq(TODAY),
                        eq("FCI HQ"),
                        eq("order.pdf"),
                        aryEq(PDF));
    }

    @Test
    void SRS_EH_11_fallbackUploadWithUnreadableFileIsRefusedBeforeTheServiceIsCalled()
            throws IOException {
        FallbackService service = mock(FallbackService.class);
        FallbackController controller = new FallbackController(exports, contexts, service, paging);
        MultipartFile file = unreadable();

        assertThatThrownBy(() -> controller.upload(3L, file))
                .isInstanceOf(UncheckedIOException.class)
                .hasCauseInstanceOf(IOException.class);
        verifyNoInteractions(service);
    }

    @Test
    void SRS_EH_11_fallbackUploadIsCreatedWithItsBytes() throws IOException {
        FallbackService service = mock(FallbackService.class);
        FallbackService.UploadView view = mock(FallbackService.UploadView.class);
        when(service.upload(any(), eq(3L), eq("order.pdf"), aryEq(PDF))).thenReturn(view);
        FallbackController controller = new FallbackController(exports, contexts, service, paging);

        ResponseEntity<FallbackService.UploadView> response = controller.upload(3L, readable());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isSameAs(view);
    }

    @Test
    void SRS_G_31_fallbackOrderWithUnreadableFileIsRefusedBeforeTheServiceIsCalled()
            throws IOException {
        FallbackService service = mock(FallbackService.class);
        FallbackController controller = new FallbackController(exports, contexts, service, paging);
        MultipartFile file = unreadable();

        assertThatThrownBy(
                        () -> controller.order(6L, "TRANSFER_ORDER", null, TODAY, "FCI HQ", file))
                .isInstanceOf(UncheckedIOException.class)
                .hasCauseInstanceOf(IOException.class);
        verifyNoInteractions(service);
    }

    @Test
    void SRS_G_31_fallbackOrderIsAttachedWithItsBytes() throws IOException {
        FallbackService service = mock(FallbackService.class);
        DocumentStore.AttachmentView view = mock(DocumentStore.AttachmentView.class);
        when(service.attachOrder(any(), eq(6L), any(), any(), any(), any(), any(), any()))
                .thenReturn(view);
        FallbackController controller = new FallbackController(exports, contexts, service, paging);

        ResponseEntity<DocumentStore.AttachmentView> response =
                controller.order(6L, "TRANSFER_ORDER", null, TODAY, "FCI HQ", readable());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isSameAs(view);
        verify(service)
                .attachOrder(
                        any(),
                        eq(6L),
                        eq("TRANSFER_ORDER"),
                        isNull(),
                        eq(TODAY),
                        eq("FCI HQ"),
                        eq("order.pdf"),
                        aryEq(PDF));
    }
}
