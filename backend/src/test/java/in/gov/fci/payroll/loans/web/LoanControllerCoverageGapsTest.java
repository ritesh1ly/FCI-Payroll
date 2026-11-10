package in.gov.fci.payroll.loans.web;

import static in.gov.fci.payroll.ControllerKit.from;
import static in.gov.fci.payroll.ControllerKit.pdfView;
import static in.gov.fci.payroll.ControllerKit.upload;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.ControllerKit;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.loans.orchestration.LoanChangeService;
import in.gov.fci.payroll.loans.orchestration.LoanOrderService;
import in.gov.fci.payroll.loans.orchestration.LoanQueryService;
import in.gov.fci.payroll.loans.orchestration.LoanSanctionService;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.web.Paging;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * I-04 loan tiles and order copies (SRS-12-1, SRS-12-3, SRS-G-30): the tiles are the month's; an
 * order copy is stored with what the form sent (201), listed back and downloaded as an attachment
 * from the caller's address.
 */
class LoanControllerCoverageGapsTest {

    private final CurrentAccess contexts = mock(CurrentAccess.class);
    private final AccessContext context = mock(AccessContext.class);
    private final LoanQueryService loans = mock(LoanQueryService.class);
    private final LoanOrderService orders = mock(LoanOrderService.class);
    private final LoanController controller =
            new LoanController(
                    contexts,
                    mock(LoanSanctionService.class),
                    mock(LoanChangeService.class),
                    loans,
                    orders,
                    mock(Paging.class),
                    new ControllerKit().exports);

    @BeforeEach
    void session() {
        when(contexts.current()).thenReturn(context);
    }

    @Test
    void SRS_12_1_theTilesAreTheMonthsAndNeverCached() {
        LoanQueryService.Summary summary = mock(LoanQueryService.Summary.class);
        when(loans.summary(context, "202610")).thenReturn(summary);

        var r = controller.summary("202610");

        assertThat(r.getBody()).isSameAs(summary);
        assertThat(ControllerKit.noStore(r)).isTrue();
    }

    @Test
    void SRS_G_30_aLoanOrderIsStoredAsSentListedAndDownloaded() throws Exception {
        DocumentStore.AttachmentView stored = pdfView();
        var file = upload((byte) 6);
        when(orders.attach(eq(context), eq(21L), org.mockito.ArgumentMatchers.any()))
                .thenReturn(stored);
        when(orders.list(context, 21L)).thenReturn(List.of(stored));
        var content = new DocumentStore.Content(stored, new byte[] {6});
        var request = from("10.0.0.6");
        when(orders.file(context, "10.0.0.6", 21L, 4L)).thenReturn(content);
        LocalDate day = LocalDate.of(2026, 10, 15);

        ResponseEntity<DocumentStore.AttachmentView> r =
                controller.attachOrder(21L, "SANCTION_ORDER", "L-1", day, "RO Delhi", file);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getBody()).isSameAs(stored);
        ArgumentCaptor<LoanChangeService.Document> sent =
                ArgumentCaptor.forClass(LoanChangeService.Document.class);
        verify(orders).attach(eq(context), eq(21L), sent.capture());
        LoanChangeService.Document d = sent.getValue();
        assertThat(d.documentType()).isEqualTo("SANCTION_ORDER");
        assertThat(d.reference()).isEqualTo("L-1");
        assertThat(d.orderDate()).isEqualTo(day);
        assertThat(d.issuingAuthority()).isEqualTo("RO Delhi");
        assertThat(d.fileName()).isEqualTo("order.pdf");
        assertThat(d.content()).containsExactly(6);
        var listed = controller.orders(21L);
        assertThat(listed.getBody()).containsExactly(stored);
        assertThat(ControllerKit.noStore(listed)).isTrue();
        ResponseEntity<byte[]> download = controller.orderFile(21L, 4L, request);
        assertThat(download.getBody()).containsExactly(6);
        assertThat(ControllerKit.pdfAttachment(download)).isTrue();
    }
}
