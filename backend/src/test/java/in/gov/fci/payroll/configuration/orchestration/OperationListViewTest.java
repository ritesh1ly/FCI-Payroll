package in.gov.fci.payroll.configuration.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.configuration.api.Circulars;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.configuration.business.model.OperationMaster;
import in.gov.fci.payroll.configuration.persistence.ConfigurationRepositories;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.ApprovalStatuses;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.export.ExportSink;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The M-27 list as Wireframes v6.1 draws it (SRS M-27 field Circular, action Export): the number of
 * each row's governing circular, an operation without one, and the export.
 */
class OperationListViewTest {

    private static final LocalDate DAY = LocalDate.of(2026, 7, 15);

    private final ConfigurationRepositories.Operations operations =
            mock(ConfigurationRepositories.Operations.class);
    private final Circulars circulars = mock(Circulars.class);
    private final ApprovalStatuses statuses = mock(ApprovalStatuses.class);
    private final SystemParameters settings = mock(SystemParameters.class);
    private final Authorization policy = mock(Authorization.class);
    private final AccessContext context = mock(AccessContext.class);
    private final OperationService service =
            new OperationService(
                    operations,
                    mock(ConfigurationRepositories.PayComponents.class),
                    circulars,
                    statuses,
                    mock(MakerChecker.class),
                    policy,
                    settings,
                    mock(MasterAudit.class));

    private OperationMaster version(String code, Long approval, Long circular) {
        OperationMaster o = mock(OperationMaster.class);
        when(o.getOperationCode()).thenReturn(code);
        when(o.getOperationName()).thenReturn(code + " name");
        when(o.getVersionNo()).thenReturn(1);
        when(o.getEffectiveFrom()).thenReturn(DAY.minusDays(30));
        when(o.getApprovalId()).thenReturn(approval);
        when(o.getCircularOrderId()).thenReturn(circular);
        when(o.coversDay(any())).thenCallRealMethod();
        when(o.isAppliesDeptLabour()).thenReturn(true);
        when(o.isIsBagBased()).thenReturn(true);
        when(o.getNormType()).thenReturn("HANDLING");
        when(o.getRateNormLink()).thenReturn("NORM_SLAB");
        return o;
    }

    private void listed(OperationMaster... versions) {
        when(settings.today()).thenReturn(DAY);
        when(operations.findAllByOrderByOperationCodeAscVersionNoDesc())
                .thenReturn(List.of(versions));
        when(statuses.of(any()))
                .thenReturn(
                        Map.of(
                                1L, ApprovalStatus.APPROVED,
                                2L, ApprovalStatus.APPROVED,
                                3L, ApprovalStatus.PENDING_APPROVAL));
    }

    @Test
    void SRS_G_41_eachRowShowsItsCircularNumber_oneWithoutACircularShowsNone() {
        listed(version("DUST", 1L, null), version("W2P", 2L, 40L));
        when(circulars.numbers(List.of(40L))).thenReturn(Map.of(40L, "IR(L)/4/2026"));

        List<OperationService.OperationRow> rows = service.list(context);

        assertThat(rows.get(0).current().circularNumber()).isNull();
        assertThat(rows.get(1).current().circularNumber()).isEqualTo("IR(L)/4/2026");
    }

    @Test
    void SRS_G_41_theExportWritesTheVersionInForceOrThePendingOneOfANewCode() throws IOException {
        listed(version("NEW", 3L, 40L), version("W2P", 2L, 40L));
        when(circulars.numbers(List.of(40L))).thenReturn(Map.of(40L, "IR(L)/4/2026"));
        List<Object[]> rows = new ArrayList<>();
        ExportSink sink =
                new ExportSink() {
                    @Override
                    public void header(String... titles) {
                        assertThat(titles).contains("operation_code", "standard_name", "circular");
                    }

                    @Override
                    public void row(Object... cells) {
                        rows.add(cells);
                    }

                    @Override
                    public long rows() {
                        return rows.size();
                    }

                    @Override
                    public void close() {
                        // Nothing to complete.
                    }
                };

        service.export(context, null, null, sink);

        assertThat(rows).hasSize(2);
        assertThat(Arrays.asList(rows.get(0)))
                .startsWith("NEW", "NEW name", null, "Y", "N", "N", "Y", "HANDLING", "NORM_SLAB");
        assertThat(rows.get(1)[9]).isEqualTo("IR(L)/4/2026");
        verify(policy).require(context, OperationService.SCREEN, "EXPORT");
    }

    @Test
    void SRS_G_41_theExportSizeIsTheRowsOfTheList() {
        listed(version("W2P", 2L, null));
        assertThat(service.exportSize(context, null, null)).isEqualTo(1);
        verify(policy).require(context, OperationService.SCREEN, "EXPORT");
    }
}
