package in.gov.fci.payroll.general.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.ControllerKit;
import in.gov.fci.payroll.general.business.model.ExceptionEntry;
import in.gov.fci.payroll.general.orchestration.ComputationExceptions;
import in.gov.fci.payroll.general.orchestration.ExceptionService;
import in.gov.fci.payroll.platform.export.ExportFormat;
import in.gov.fci.payroll.platform.export.ExportSink;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.web.Paging;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * C-08 Computation Exceptions (SRS-G-9, SRS-G-13; SRS l.1641-1678): the session's month is sent as
 * YYYYMM, a blank search is no search and a search is trimmed, the severity and page pass
 * unchanged, the items of a condition are its module's and code's, and the export carries what the
 * review writes.
 */
class ComputationExceptionControllerCoverageGapsTest {

    private static final PageSpec PAGE = new PageSpec(0, 50);

    private final CurrentAccess contexts = mock(CurrentAccess.class);
    private final AccessContext context = mock(AccessContext.class);
    private final ComputationExceptions review = mock(ComputationExceptions.class);
    private final Paging paging = mock(Paging.class);
    private final ControllerKit kit = new ControllerKit();
    private final ComputationExceptionController controller =
            new ComputationExceptionController(contexts, review, kit.exports, paging);

    @BeforeEach
    void session() {
        when(contexts.current()).thenReturn(context);
        when(paging.resolve(0, 50)).thenReturn(PAGE);
    }

    @Test
    void SRS_G_9_theMonthIsYyyymmAndTheSearchIsTrimmedOrDropped() {
        ComputationExceptions.Review trimmed = mock(ComputationExceptions.Review.class);
        ComputationExceptions.Review blank = mock(ComputationExceptions.Review.class);
        ComputationExceptions.Review none = mock(ComputationExceptions.Review.class);
        when(review.review(
                        context,
                        new ComputationExceptions.Filter(
                                "202610", ExceptionEntry.Severity.BLOCKING, "E01"),
                        PAGE))
                .thenReturn(trimmed);
        when(review.review(context, new ComputationExceptions.Filter("202610", null, null), PAGE))
                .thenReturn(blank, none);

        var t = controller.review("2026-10", ExceptionEntry.Severity.BLOCKING, "  E01 ", 0, 50);
        var b = controller.review("2026-10", null, "   ", 0, 50);
        var n = controller.review("2026-10", null, null, 0, 50);

        assertThat(t.getBody()).isSameAs(trimmed);
        assertThat(b.getBody()).isSameAs(blank);
        assertThat(n.getBody()).isSameAs(none);
        assertThat(ControllerKit.noStore(t)).isTrue();
    }

    @Test
    void SRS_G_9_theItemsAreTheConditionsOfTheMonth() {
        @SuppressWarnings("unchecked")
        Paged<ExceptionService.ExceptionView> items = mock(Paged.class);
        when(review.items(context, "202610", "C-02", "E01", PAGE)).thenReturn(items);

        var r = controller.items("2026-10", "E01", "C-02", 0, 50);

        assertThat(r.getBody()).isSameAs(items);
        assertThat(ControllerKit.noStore(r)).isTrue();
    }

    @Test
    void SRS_G_13_theExportIsSizeCheckedAndCarriesTheReviewsRows() throws Exception {
        ComputationExceptions.Filter filter =
                new ComputationExceptions.Filter("202610", null, "net");
        when(review.exportSize(context, filter)).thenReturn(1L);
        doAnswer(
                        call -> {
                            ExportSink sink = call.getArgument(2);
                            sink.header("Exception");
                            sink.row("E05: Net pay below floor");
                            return null;
                        })
                .when(review)
                .export(eq(context), eq(filter), any());

        var r = controller.export("2026-10", null, " net ", ExportFormat.CSV);

        assertThat(r.getHeaders().getContentDisposition().getFilename())
                .isEqualTo("computation-exceptions.csv");
        assertThat(ControllerKit.body(r)).contains("E05: Net pay below floor");
        assertThat(kit.delivered).hasSize(1);
    }
}
