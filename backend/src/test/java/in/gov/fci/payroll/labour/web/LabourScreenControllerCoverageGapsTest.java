package in.gov.fci.payroll.labour.web;

import static in.gov.fci.payroll.ControllerKit.from;
import static in.gov.fci.payroll.ControllerKit.pdfView;
import static in.gov.fci.payroll.ControllerKit.upload;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.ControllerKit;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.labour.orchestration.LabourScreenService;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.web.Paging;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * M-02 labour orders and M-06 slabs (OD-121; SRS-G-30, SRS-2-2): an order copy is stored with what
 * the form sent (201), listed back and downloaded as an attachment from the caller's address; an
 * unreadable upload stores nothing; a norm's slabs are its own and never cached.
 */
class LabourScreenControllerCoverageGapsTest {

    private final CurrentAccess contexts = mock(CurrentAccess.class);
    private final AccessContext context = mock(AccessContext.class);
    private final LabourScreenService screens = mock(LabourScreenService.class);
    private final LabourScreenController controller =
            new LabourScreenController(
                    contexts, screens, mock(Paging.class), new ControllerKit().exports);

    @BeforeEach
    void session() {
        when(contexts.current()).thenReturn(context);
    }

    @Test
    void SRS_G_30_aLabourOrderIsStoredAsSentListedAndDownloaded() throws Exception {
        DocumentStore.AttachmentView stored = pdfView();
        var file = upload((byte) 4);
        when(screens.attachOrder(eq(context), eq(33L), any())).thenReturn(stored);
        when(screens.orders(context, 33L)).thenReturn(List.of(stored));
        var content = new DocumentStore.Content(stored, new byte[] {4});
        var request = from("10.0.0.7");
        when(screens.order(context, "10.0.0.7", 33L, 5L)).thenReturn(content);
        LocalDate day = LocalDate.of(2026, 11, 2);

        ResponseEntity<DocumentStore.AttachmentView> r =
                controller.attach(33L, "TRANSFER_ORDER", "T-1", day, "DO Delhi", file);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getBody()).isSameAs(stored);
        assertThat(ControllerKit.noStore(r)).isTrue();
        ArgumentCaptor<LabourScreenService.OrderCopy> sent =
                ArgumentCaptor.forClass(LabourScreenService.OrderCopy.class);
        verify(screens).attachOrder(eq(context), eq(33L), sent.capture());
        assertThat(sent.getValue().documentType()).isEqualTo("TRANSFER_ORDER");
        assertThat(sent.getValue().reference()).isEqualTo("T-1");
        assertThat(sent.getValue().orderDate()).isEqualTo(day);
        assertThat(sent.getValue().issuingAuthority()).isEqualTo("DO Delhi");
        assertThat(sent.getValue().fileName()).isEqualTo("order.pdf");
        assertThat(sent.getValue().content()).containsExactly(4);
        assertThat(controller.documents(33L).getBody()).containsExactly(stored);
        ResponseEntity<byte[]> download = controller.document(33L, 5L, request);
        assertThat(download.getBody()).containsExactly(4);
        assertThat(ControllerKit.pdfAttachment(download)).isTrue();
    }

    @Test
    void SRS_G_30_anUnreadableUploadStoresNothing() throws Exception {
        var file = ControllerKit.unreadable();

        assertThatThrownBy(
                        () ->
                                controller.attach(
                                        33L,
                                        "TRANSFER_ORDER",
                                        null,
                                        LocalDate.of(2026, 11, 2),
                                        null,
                                        file))
                .isInstanceOf(UncheckedIOException.class)
                .hasRootCauseMessage("disk");
        verify(screens, never()).attachOrder(any(), anyLong(), any());
    }

    @Test
    void SRS_2_2_aNormsSlabsAreItsOwnAndNeverCached() {
        List<LabourScreenService.SlabLine> slabs =
                List.of(mock(LabourScreenService.SlabLine.class));
        when(screens.slabs(context, 12L)).thenReturn(slabs);

        var r = controller.slabs(12L);

        assertThat(r.getBody()).isSameAs(slabs);
        assertThat(ControllerKit.noStore(r)).isTrue();
    }
}
