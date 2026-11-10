package in.gov.fci.payroll.settlement.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.ControllerKit;
import in.gov.fci.payroll.platform.export.PdfRenderGate;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.web.Paging;
import in.gov.fci.payroll.settlement.orchestration.SettlementApprovals;
import in.gov.fci.payroll.settlement.orchestration.SettlementOrders;
import in.gov.fci.payroll.settlement.orchestration.SettlementViews;
import in.gov.fci.payroll.settlement.orchestration.SettlementWork;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * O-05 settlement statement (SRS-10-12; OD-144): the PDF is rendered through the render gate from
 * the same read as the detail and downloads under a safe name made of the case reference.
 */
class SettlementControllerCoverageGapsTest {

    @Test
    void SRS_10_12_theStatementIsTheCasesPdfUnderASafeName() {
        CurrentAccess contexts = mock(CurrentAccess.class);
        AccessContext context = mock(AccessContext.class);
        when(contexts.current()).thenReturn(context);
        SettlementViews views = mock(SettlementViews.class);
        SettlementViews.CaseRow row = mock(SettlementViews.CaseRow.class);
        when(row.caseReference()).thenReturn("FF/2026/07");
        SettlementViews.CaseDetail detail = mock(SettlementViews.CaseDetail.class);
        when(detail.summary()).thenReturn(row);
        when(views.detail(context, 12L)).thenReturn(detail);
        PdfRenderGate gate = mock(PdfRenderGate.class);
        when(gate.render(eq("settlement-statement"), any())).thenReturn(new byte[] {37, 80});
        ControllerKit kit = new ControllerKit();
        SettlementController controller =
                new SettlementController(
                        contexts,
                        views,
                        mock(SettlementWork.class),
                        mock(SettlementApprovals.class),
                        mock(SettlementOrders.class),
                        mock(Paging.class),
                        kit.exports,
                        gate);

        ResponseEntity<byte[]> r = controller.statement(12L);

        assertThat(r.getBody()).containsExactly(37, 80);
        assertThat(r.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
        assertThat(r.getHeaders().getContentDisposition().getFilename())
                .isEqualTo("settlement-statement-FF-2026-07.pdf");
        assertThat(kit.delivered).hasSize(1);
    }
}
