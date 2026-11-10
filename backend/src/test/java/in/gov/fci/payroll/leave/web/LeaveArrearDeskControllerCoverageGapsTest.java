package in.gov.fci.payroll.leave.web;

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
import in.gov.fci.payroll.leave.orchestration.LeaveArrearDeskService;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.workflow.api.Submitted;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

/**
 * I-24 Leave Encashment Arrear desk (OD-121; SRS-8-24, SRS-G-37, SRS-G-30; PENDING API-13-2): a
 * cancellation is only raised (202 with the approval, the Checker's approval cancels); an order
 * copy is stored with what the form sent and answers 201; a stored copy downloads as an attachment;
 * an unreadable upload stores nothing.
 */
class LeaveArrearDeskControllerCoverageGapsTest {

    private final CurrentAccess contexts = mock(CurrentAccess.class);
    private final AccessContext context = mock(AccessContext.class);
    private final LeaveArrearDeskService desk = mock(LeaveArrearDeskService.class);
    private final LeaveArrearDeskController controller =
            new LeaveArrearDeskController(contexts, desk);

    @BeforeEach
    void session() {
        when(contexts.current()).thenReturn(context);
    }

    private static DocumentStore.AttachmentView view() {
        DocumentStore.AttachmentView v = mock(DocumentStore.AttachmentView.class);
        when(v.fileName()).thenReturn("order.pdf");
        when(v.contentType()).thenReturn("application/pdf");
        return v;
    }

    @Test
    void SRS_G_37_aCancellationIsRaisedForTheCheckerAndAnswers202() {
        when(desk.cancel(context, 8L, "Wrong period"))
                .thenReturn(new SubmittedApproval(61L, ApprovalStatus.PENDING_APPROVAL, null));

        ResponseEntity<Submitted> r =
                controller.cancel(
                        8L, new LeaveArrearDeskController.CancellationBody("Wrong period"));

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(r.getBody()).isEqualTo(new Submitted(61L, "PENDING_APPROVAL"));
    }

    @Test
    void SRS_8_24_theSummaryIsTheSessionsAndNeverCached() {
        LeaveArrearDeskService.Summary summary = mock(LeaveArrearDeskService.Summary.class);
        when(desk.summary(context)).thenReturn(summary);

        ResponseEntity<LeaveArrearDeskService.Summary> r = controller.summary();

        assertThat(r.getBody()).isSameAs(summary);
        assertThat(ControllerKit.noStore(r)).isTrue();
    }

    @Test
    void SRS_G_30_anOrderCopyIsStoredAsSentAndListedBack() throws Exception {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getOriginalFilename()).thenReturn("order.pdf");
        when(file.getBytes()).thenReturn(new byte[] {3});
        DocumentStore.AttachmentView stored = view();
        when(desk.attachOrder(eq(context), eq(8L), any())).thenReturn(stored);
        when(desk.orders(context, 8L)).thenReturn(List.of(stored));
        LocalDate day = LocalDate.of(2026, 11, 1);

        ResponseEntity<DocumentStore.AttachmentView> r =
                controller.attachOrder(8L, "SANCTION_ORDER", "R-9", day, "RO Delhi", file);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getBody()).isSameAs(stored);
        assertThat(ControllerKit.noStore(r)).isTrue();
        ArgumentCaptor<LeaveArrearDeskService.NewOrder> sent =
                ArgumentCaptor.forClass(LeaveArrearDeskService.NewOrder.class);
        verify(desk).attachOrder(eq(context), eq(8L), sent.capture());
        LeaveArrearDeskService.NewOrder o = sent.getValue();
        assertThat(o.documentType()).isEqualTo("SANCTION_ORDER");
        assertThat(o.reference()).isEqualTo("R-9");
        assertThat(o.orderDate()).isEqualTo(day);
        assertThat(o.issuingOffice()).isEqualTo("RO Delhi");
        assertThat(o.fileName()).isEqualTo("order.pdf");
        assertThat(o.content()).containsExactly(3);
        assertThat(controller.orders(8L).getBody()).containsExactly(stored);
    }

    @Test
    void SRS_G_30_anUnreadableUploadStoresNothing() throws Exception {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getBytes()).thenThrow(new IOException("disk"));

        assertThatThrownBy(
                        () ->
                                controller.attachOrder(
                                        8L,
                                        "SANCTION_ORDER",
                                        null,
                                        LocalDate.of(2026, 11, 1),
                                        null,
                                        file))
                .isInstanceOf(UncheckedIOException.class)
                .hasRootCauseMessage("disk");
        verify(desk, never()).attachOrder(any(), anyLong(), any());
    }

    @Test
    void SRS_G_30_aStoredOrderDownloadsAsAnAttachmentFromTheCallersAddress() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("10.0.0.9");
        DocumentStore.Content content = new DocumentStore.Content(view(), new byte[] {1, 2});
        when(desk.order(context, "10.0.0.9", 8L, 5L)).thenReturn(content);

        ResponseEntity<byte[]> r = controller.order(8L, 5L, request);

        assertThat(r.getBody()).containsExactly(1, 2);
        assertThat(r.getHeaders().getContentDisposition().isAttachment()).isTrue();
        assertThat(r.getHeaders().getContentDisposition().getFilename()).isEqualTo("order.pdf");
        assertThat(r.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(r.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
        assertThat(ControllerKit.noStore(r)).isTrue();
    }
}
