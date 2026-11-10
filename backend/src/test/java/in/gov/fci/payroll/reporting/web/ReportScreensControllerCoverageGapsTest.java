package in.gov.fci.payroll.reporting.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.ControllerKit;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.web.Paging;
import in.gov.fci.payroll.reporting.orchestration.InvoiceOutputsService;
import in.gov.fci.payroll.reporting.orchestration.OutputWindowsService;
import in.gov.fci.payroll.reporting.orchestration.ReportCatalogueService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * O-07 months and O-06 invoice register and lines (OD-121; BR-CPF-036): the month, unit, type and
 * page reach the services unchanged and nothing is cached.
 */
class ReportScreensControllerCoverageGapsTest {

    private static final PageSpec PAGE = new PageSpec(1, 20);

    private final CurrentAccess contexts = mock(CurrentAccess.class);
    private final AccessContext context = mock(AccessContext.class);
    private final ReportCatalogueService catalogue = mock(ReportCatalogueService.class);
    private final InvoiceOutputsService invoices = mock(InvoiceOutputsService.class);
    private final Paging paging = mock(Paging.class);
    private final ReportScreensController controller =
            new ReportScreensController(
                    contexts, catalogue, invoices, mock(OutputWindowsService.class), paging);

    @BeforeEach
    void session() {
        when(contexts.current()).thenReturn(context);
        when(paging.resolve(1, 20)).thenReturn(PAGE);
    }

    @Test
    void BR_CPF_036_theMonthsRegisterAndLinesPassTheirCriteria() {
        ReportCatalogueService.CpfMonths months = mock(ReportCatalogueService.CpfMonths.class);
        when(catalogue.cpfMonths(context, "202610")).thenReturn(months);
        @SuppressWarnings("unchecked")
        Paged<InvoiceOutputsService.RegisterRow> register = mock(Paged.class);
        when(invoices.register(context, "202610", 300L, "LABOUR_BILL", PAGE)).thenReturn(register);
        @SuppressWarnings("unchecked")
        Paged<InvoiceOutputsService.InvoiceLine> lines = mock(Paged.class);
        when(invoices.lines(context, "202610", null, PAGE)).thenReturn(lines);

        List<ResponseEntity<?>> answers =
                List.of(
                        controller.cpfMonths("202610"),
                        controller.invoiceRegister("202610", 300L, "LABOUR_BILL", 1, 20),
                        controller.invoiceLines("202610", null, 1, 20));

        assertThat(answers)
                .extracting(r -> (Object) r.getBody())
                .containsExactly(months, register, lines);
        assertThat(answers).allMatch(ControllerKit::noStore);
    }
}
