package in.gov.fci.payroll.increment.web;

import static in.gov.fci.payroll.ControllerKit.from;
import static in.gov.fci.payroll.ControllerKit.pdfView;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.ControllerKit;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.increment.orchestration.IncrementReportService;
import in.gov.fci.payroll.increment.orchestration.IncrementRunService;
import in.gov.fci.payroll.increment.orchestration.IncrementWorklistService;
import in.gov.fci.payroll.increment.orchestration.PayFixationService;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.web.Paging;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * I-05 tiles, worklist and case orders (API-17-2; SRS-13-18, SRS-G-31): the month and page reach
 * the worklist unchanged and the answers are never cached; a case's order downloads as an
 * attachment from the caller's address.
 */
class IncrementControllerCoverageGapsTest {

    private static final YearMonth MONTH = YearMonth.of(2026, 7);
    private static final PageSpec PAGE = new PageSpec(0, 20);

    private final CurrentAccess contexts = mock(CurrentAccess.class);
    private final AccessContext context = mock(AccessContext.class);
    private final IncrementWorklistService worklist = mock(IncrementWorklistService.class);
    private final Paging paging = mock(Paging.class);
    private final IncrementController controller =
            new IncrementController(
                    contexts,
                    mock(IncrementRunService.class),
                    mock(PayFixationService.class),
                    mock(IncrementReportService.class),
                    worklist,
                    paging,
                    new ControllerKit().exports);

    @BeforeEach
    void session() {
        when(contexts.current()).thenReturn(context);
        when(paging.resolve(null, null)).thenReturn(PAGE);
    }

    @Test
    void SRS_13_18_theTilesWorklistAndCaseOrdersAreTheMonthsAndNeverCached() {
        IncrementWorklistService.Counts counts = mock(IncrementWorklistService.Counts.class);
        when(worklist.counts(context, MONTH)).thenReturn(counts);
        @SuppressWarnings("unchecked")
        Paged<IncrementWorklistService.Row> rows = mock(Paged.class);
        when(worklist.worklist(context, MONTH, PAGE)).thenReturn(rows);
        DocumentStore.AttachmentView stored = pdfView();
        when(worklist.orders(context, 61L)).thenReturn(List.of(stored));

        ResponseEntity<?> c = controller.counts(MONTH);
        ResponseEntity<?> w = controller.worklist(MONTH, null, null);
        ResponseEntity<List<DocumentStore.AttachmentView>> o = controller.caseOrders(61L);

        assertThat(c.getBody()).isSameAs(counts);
        assertThat(w.getBody()).isSameAs(rows);
        assertThat(o.getBody()).containsExactly(stored);
        assertThat(List.of(c, w, o)).allMatch(ControllerKit::noStore);
    }

    @Test
    void SRS_G_31_aCaseOrderDownloadsAsAnAttachment() {
        DocumentStore.AttachmentView stored = pdfView();
        var content = new DocumentStore.Content(stored, new byte[] {8});
        var request = from("10.0.0.4");
        when(worklist.orderFile(context, "10.0.0.4", 61L, 2L)).thenReturn(content);

        ResponseEntity<byte[]> r = controller.caseOrder(61L, 2L, request);

        assertThat(r.getBody()).containsExactly(8);
        assertThat(ControllerKit.pdfAttachment(r)).isTrue();
    }
}
