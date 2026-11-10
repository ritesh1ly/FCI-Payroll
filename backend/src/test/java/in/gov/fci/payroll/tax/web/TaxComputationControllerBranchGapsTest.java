package in.gov.fci.payroll.tax.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.employee.api.EmployeeDirectory.PanStatus;
import in.gov.fci.payroll.platform.export.ExportFormat;
import in.gov.fci.payroll.platform.export.ExportSink;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.web.ExportStreaming;
import in.gov.fci.payroll.tax.business.TaxCalculator;
import in.gov.fci.payroll.tax.business.model.ItRegimeDetail.Regime;
import in.gov.fci.payroll.tax.orchestration.TaxComputationService;
import in.gov.fci.payroll.tax.orchestration.TaxWhatIfService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The C-07 forecast download (SRS-7-27, SRS-7-13): a member without a regime reads "not chosen",
 * one with a regime names it, and the annual tax line says when the higher rate without a valid PAN
 * applied and is blank otherwise.
 */
class TaxComputationControllerBranchGapsTest {

    private final CurrentAccess contexts = mock(CurrentAccess.class);
    private final TaxComputationService service = mock(TaxComputationService.class);
    private final ExportStreaming exports = mock(ExportStreaming.class);
    private final TaxComputationController controller =
            new TaxComputationController(contexts, service, mock(TaxWhatIfService.class), exports);

    private static TaxComputationService.Forecast forecast(Regime regime, boolean higherRate) {
        return new TaxComputationService.Forecast(
                9L,
                "870001",
                "Member",
                "IN_SERVICE",
                "2025-26",
                "ACTUAL",
                new TaxComputationService.Inputs(
                        regime,
                        higherRate ? PanStatus.MISSING : PanStatus.VALID,
                        List.of(),
                        BigDecimal.ZERO,
                        List.of()),
                Optional.empty(),
                Optional.of(
                        new TaxCalculator.Result(
                                new BigDecimal("500000"),
                                new BigDecimal("12500"),
                                BigDecimal.ZERO,
                                new BigDecimal("500"),
                                new BigDecimal("13000"),
                                higherRate,
                                new BigDecimal("1084"))),
                Optional.empty(),
                List.of());
    }

    /** The rows the download writes, each as its cells. */
    private List<List<Object>> written(TaxComputationService.Forecast f) throws Exception {
        when(service.forecast(any(), eq("870001"), eq("2025-26"))).thenReturn(f);
        List<List<Object>> rows = new ArrayList<>();
        ExportSink sink = mock(ExportSink.class);
        doAnswer(
                        i -> {
                            rows.add(Arrays.asList(i.getArguments()));
                            return null;
                        })
                .when(sink)
                .row(any(Object[].class));
        when(exports.stream(anyString(), any(), any()))
                .thenAnswer(
                        i -> {
                            ExportStreaming.ExportJob job = i.getArgument(2);
                            job.write(sink);
                            return null;
                        });
        controller.export("870001", "2025-26", ExportFormat.CSV);
        return rows;
    }

    private static List<Object> row(List<List<Object>> rows, String item) {
        return rows.stream().filter(r -> item.equals(r.get(0))).findFirst().orElseThrow();
    }

    @Test
    void SRS_7_27_theRegimeNotChosenAndNoHigherRateAreShownAsSuch() throws Exception {
        List<List<Object>> rows = written(forecast(null, false));
        assertThat(row(rows, "Regime").get(1)).isEqualTo("not chosen");
        assertThat(row(rows, "Annual tax").get(1)).isEqualTo("");
    }

    @Test
    void SRS_7_13_theChosenRegimeAndTheHigherRateWithoutAValidPanAreNamed() throws Exception {
        List<List<Object>> rows = written(forecast(Regime.NEW, true));
        assertThat(row(rows, "Regime").get(1)).isEqualTo("NEW");
        assertThat(row(rows, "Annual tax").get(1)).isEqualTo("higher rate without a valid PAN");
        assertThat(row(rows, "Annual tax").get(2)).isEqualTo(new BigDecimal("13000"));
    }
}
