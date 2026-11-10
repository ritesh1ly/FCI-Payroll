package in.gov.fci.payroll.reporting.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.web.ExportStreaming;
import in.gov.fci.payroll.platform.web.Paging;
import in.gov.fci.payroll.reporting.business.ReportModelRules;
import in.gov.fci.payroll.reporting.orchestration.ComponentSummaryService;
import in.gov.fci.payroll.reporting.orchestration.ReportCatalogueService;
import in.gov.fci.payroll.reporting.orchestration.ReportVersionService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * M-12 proposal of a report version (SRS-18-9; US-15-02): the parameter set, fields, control totals
 * and routing a request leaves out reach the service as empty lists; those given reach it one for
 * one, in order.
 */
class ReportControllerBranchGapsTest {

    private static final LocalDate FROM = LocalDate.of(2090, 4, 1);

    private final ReportVersionService versions = mock(ReportVersionService.class);
    private final ReportController controller =
            new ReportController(
                    mock(CurrentAccess.class),
                    mock(ReportCatalogueService.class),
                    mock(ComponentSummaryService.class),
                    versions,
                    mock(Paging.class),
                    mock(ExportStreaming.class));

    @Test
    void SRS_18_9_absentModelPartsAreEmptyAndGivenOnesArePassedOneForOne() {
        controller.propose(
                "COMPONENT_WISE_SUMMARY",
                new ReportController.VersionBody(
                        "CSV", true, "STORE", false, null, FROM, null, null, null, null, null));
        controller.propose(
                "COMPONENT_WISE_SUMMARY",
                new ReportController.VersionBody(
                        "EXCEL",
                        false,
                        "REGENERATE",
                        false,
                        null,
                        FROM,
                        "ROUND.RUPEE",
                        List.of(
                                new ReportController.ParameterBody(
                                        "YYMM", "Month", "MONTH", true, false, null, "Locked")),
                        List.of(
                                new ReportController.FieldBody(
                                        "Basic",
                                        ReportModelRules.Binding.COMPONENT,
                                        "101",
                                        "#,##0.00")),
                        List.of(
                                new ReportController.ControlBody(
                                        "Gross", "POSTED_PAYROLL", BigDecimal.ONE, true)),
                        List.of(
                                new ReportController.DistributionBody(
                                        "Finance", "INTERNAL_USER", "PORTAL", "Monthly"))));

        ArgumentCaptor<ReportVersionService.Proposal> sent =
                ArgumentCaptor.forClass(ReportVersionService.Proposal.class);
        verify(versions, times(2)).propose(any(), eq("COMPONENT_WISE_SUMMARY"), sent.capture());
        ReportVersionService.Proposal bare = sent.getAllValues().get(0);
        assertThat(bare.parameters()).isEmpty();
        assertThat(bare.fields()).isEmpty();
        assertThat(bare.controls()).isEmpty();
        assertThat(bare.distribution()).isEmpty();
        ReportVersionService.Proposal full = sent.getAllValues().get(1);
        assertThat(full.parameters())
                .containsExactly(
                        new ReportModelRules.Parameter(
                                "YYMM", "Month", "MONTH", true, false, null, "Locked"));
        assertThat(full.fields())
                .containsExactly(
                        new ReportModelRules.Field(
                                "Basic", ReportModelRules.Binding.COMPONENT, "101", "#,##0.00"));
        assertThat(full.controls())
                .containsExactly(
                        new ReportModelRules.Control(
                                "Gross", "POSTED_PAYROLL", BigDecimal.ONE, true));
        assertThat(full.distribution())
                .containsExactly(
                        new ReportModelRules.Distribution(
                                "Finance", "INTERNAL_USER", "PORTAL", "Monthly"));
        assertThat(full.roundingRuleKey()).isEqualTo("ROUND.RUPEE");
    }
}
