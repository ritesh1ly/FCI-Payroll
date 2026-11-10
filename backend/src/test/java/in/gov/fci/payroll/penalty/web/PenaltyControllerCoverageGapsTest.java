package in.gov.fci.payroll.penalty.web;

import static in.gov.fci.payroll.ControllerKit.from;
import static in.gov.fci.payroll.ControllerKit.pdfView;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.ControllerKit;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.penalty.orchestration.OverAndAboveService;
import in.gov.fci.payroll.penalty.orchestration.PenaltyChangeService;
import in.gov.fci.payroll.penalty.orchestration.PenaltyOrderService;
import in.gov.fci.payroll.penalty.orchestration.PenaltyQueryService;
import in.gov.fci.payroll.penalty.orchestration.PenaltyRegisterService;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.web.Paging;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * I-06 Penalty orders (SRS-14-7, SRS-14-10, SRS-14-12, SRS-14-13, SRS-G-31): an order without
 * instalments previews with none (never an assumed count); the Min-Net Guard answers 204 when no
 * request waits; the order copies are the order's and download as an attachment.
 */
class PenaltyControllerCoverageGapsTest {

    private final CurrentAccess contexts = mock(CurrentAccess.class);
    private final AccessContext context = mock(AccessContext.class);
    private final PenaltyOrderService orders = mock(PenaltyOrderService.class);
    private final PenaltyRegisterService register = mock(PenaltyRegisterService.class);
    private final PenaltyController controller =
            new PenaltyController(
                    contexts,
                    orders,
                    mock(PenaltyQueryService.class),
                    mock(PenaltyChangeService.class),
                    mock(OverAndAboveService.class),
                    register,
                    mock(Paging.class),
                    new ControllerKit().exports);

    @BeforeEach
    void session() {
        when(contexts.current()).thenReturn(context);
    }

    @Test
    void SRS_14_13_anOrderWithoutInstalmentsPreviewsWithNone() {
        PenaltyOrderService.Preview preview = mock(PenaltyOrderService.Preview.class);
        when(orders.preview(eq(context), org.mockito.ArgumentMatchers.any())).thenReturn(preview);
        LocalDate effective = LocalDate.of(2026, 11, 1);

        var r =
                controller.preview(
                        new PenaltyController.OrderBody(
                                "104882",
                                "CENSURE",
                                "PEN/1",
                                effective,
                                null,
                                null,
                                null,
                                null,
                                LocalDate.of(2026, 12, 31)));

        assertThat(r.getBody()).isSameAs(preview);
        ArgumentCaptor<PenaltyOrderService.Request> sent =
                ArgumentCaptor.forClass(PenaltyOrderService.Request.class);
        verify(orders).preview(eq(context), sent.capture());
        PenaltyOrderService.Request request = sent.getValue();
        assertThat(request.instalments()).isZero();
        assertThat(request.eis()).isEqualTo("104882");
        assertThat(request.nature()).isEqualTo("CENSURE");
        assertThat(request.orderReference()).isEqualTo("PEN/1");
        assertThat(request.effectiveDate()).isEqualTo(effective);
        assertThat(request.amount()).isNull();
        assertThat(request.periodTo()).isEqualTo(LocalDate.of(2026, 12, 31));
    }

    @Test
    void SRS_14_7_givenInstalmentsAreKept() {
        controller.preview(
                new PenaltyController.OrderBody(
                        "104882",
                        "RECOVERY",
                        "PEN/2",
                        LocalDate.of(2026, 11, 1),
                        new BigDecimal("12000"),
                        12,
                        "202612",
                        Boolean.TRUE,
                        null));

        ArgumentCaptor<PenaltyOrderService.Request> sent =
                ArgumentCaptor.forClass(PenaltyOrderService.Request.class);
        verify(orders).preview(eq(context), sent.capture());
        assertThat(sent.getValue().instalments()).isEqualTo(12);
        assertThat(sent.getValue().startYymm()).isEqualTo("202612");
        assertThat(sent.getValue().deferToFnf()).isTrue();
    }

    @Test
    void SRS_14_10_noRequestWaitingIsNoContent() {
        when(register.guard(context, null)).thenReturn(Optional.empty());

        ResponseEntity<PenaltyRegisterService.MinNetGuard> r = controller.minNet(null);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(r.getBody()).isNull();
    }

    @Test
    void SRS_G_31_theOrderCopiesAreTheOrdersAndDownloadAsAnAttachment() {
        DocumentStore.AttachmentView stored = pdfView();
        when(register.orderCopies(context, 9L)).thenReturn(List.of(stored));
        var content = new DocumentStore.Content(stored, new byte[] {4});
        var request = from("10.0.0.9");
        when(register.orderCopy(context, "10.0.0.9", 9L, 2L)).thenReturn(content);

        var copies = controller.orderCopies(9L);
        ResponseEntity<byte[]> file = controller.orderCopy(9L, 2L, request);

        assertThat(copies.getBody()).containsExactly(stored);
        assertThat(ControllerKit.noStore(copies)).isTrue();
        assertThat(file.getBody()).containsExactly(4);
        assertThat(ControllerKit.pdfAttachment(file)).isTrue();
    }
}
