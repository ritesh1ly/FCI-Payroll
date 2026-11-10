package in.gov.fci.payroll.payroll.web;

import static in.gov.fci.payroll.ControllerKit.from;
import static in.gov.fci.payroll.ControllerKit.pdfView;
import static in.gov.fci.payroll.ControllerKit.upload;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.ControllerKit;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.payroll.orchestration.CycleControlOverview;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.web.Paging;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.YearMonth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * C-01 override approval notes (SRS-G-32): the note goes to the period's instance, never to an
 * entity the client names (the entity is left for the service), with what the form sent (201); a
 * stored note downloads as an attachment from the caller's address; an unreadable upload stores
 * nothing.
 */
class CycleOverviewControllerCoverageGapsTest {

    private static final YearMonth OCT = YearMonth.of(2026, 10);

    private final CurrentAccess contexts = mock(CurrentAccess.class);
    private final AccessContext context = mock(AccessContext.class);
    private final CycleControlOverview overview = mock(CycleControlOverview.class);
    private final CycleOverviewController controller =
            new CycleOverviewController(contexts, overview, mock(Paging.class));

    @BeforeEach
    void session() {
        when(contexts.current()).thenReturn(context);
    }

    @Test
    void SRS_G_32_theNoteIsTheFormsAndThePeriodsAndDownloadsAsAnAttachment() throws Exception {
        DocumentStore.AttachmentView stored = pdfView();
        var file = upload((byte) 2);
        when(overview.attachOrder(eq(context), eq(OCT), any())).thenReturn(stored);
        var content = new DocumentStore.Content(stored, new byte[] {2});
        var request = from("10.0.0.2");
        when(overview.file(context, "10.0.0.2", OCT, 8L)).thenReturn(content);
        LocalDate day = LocalDate.of(2026, 10, 20);

        ResponseEntity<DocumentStore.AttachmentView> r =
                controller.order("2026-10", "OVERRIDE_NOTE", "N-1", day, "RO Delhi", file);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getBody()).isSameAs(stored);
        assertThat(ControllerKit.noStore(r)).isTrue();
        ArgumentCaptor<DocumentStore.NewAttachment> sent =
                ArgumentCaptor.forClass(DocumentStore.NewAttachment.class);
        verify(overview).attachOrder(eq(context), eq(OCT), sent.capture());
        DocumentStore.NewAttachment note = sent.getValue();
        assertThat(note.entityName()).isNull();
        assertThat(note.entityKey()).isNull();
        assertThat(note.documentTypeCode()).isEqualTo("OVERRIDE_NOTE");
        assertThat(note.referenceNumber()).isEqualTo("N-1");
        assertThat(note.orderDate()).isEqualTo(day);
        assertThat(note.issuingAuthority()).isEqualTo("RO Delhi");
        assertThat(note.fileName()).isEqualTo("order.pdf");
        assertThat(note.content()).containsExactly(2);
        assertThat(note.containsPii()).isFalse();
        ResponseEntity<byte[]> download = controller.file("2026-10", 8L, request);
        assertThat(download.getBody()).containsExactly(2);
        assertThat(ControllerKit.pdfAttachment(download)).isTrue();
    }

    @Test
    void SRS_G_32_anUnreadableUploadStoresNothing() throws Exception {
        var file = ControllerKit.unreadable();

        assertThatThrownBy(
                        () ->
                                controller.order(
                                        "2026-10",
                                        "OVERRIDE_NOTE",
                                        null,
                                        LocalDate.of(2026, 10, 20),
                                        null,
                                        file))
                .isInstanceOf(UncheckedIOException.class)
                .hasRootCauseMessage("disk");
        verify(overview, never()).attachOrder(any(), any(), any());
    }
}
