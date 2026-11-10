package in.gov.fci.payroll.integration.orchestration;

import static in.gov.fci.payroll.ScriptedJdbc.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.ScriptedJdbc;
import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.employee.api.HrmsChanges;
import in.gov.fci.payroll.general.api.ExceptionWorklist;
import in.gov.fci.payroll.integration.api.InterfaceAdapter;
import in.gov.fci.payroll.integration.api.InterfaceSettings;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.time.Clock;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * X-03 Integration Monitor (SRS-INT-16, SRS-INT-31): blank filters are none; an interface whose
 * breaker has a recorded state shows it, one without shows CLOSED.
 */
class MonitorServiceFiltersTest {

    private final ScriptedJdbc db = new ScriptedJdbc();
    private final InterfaceSettings settings = mock(InterfaceSettings.class);
    private final AccessContext context = mock(AccessContext.class);
    private final MonitorService service;

    @SuppressWarnings("unchecked")
    MonitorServiceFiltersTest() {
        IntegrationStore store = mock(IntegrationStore.class);
        when(store.jdbc()).thenReturn(db.jdbc);
        ObjectProvider<InterfaceAdapter> adapters = mock(ObjectProvider.class);
        when(adapters.orderedStream()).thenReturn(Stream.empty());
        when(settings.inForce(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Optional.empty());
        service =
                new MonitorService(
                        store,
                        settings,
                        mock(Authorization.class),
                        mock(AuditService.class),
                        mock(ExceptionWorklist.class),
                        mock(HrmsChanges.class),
                        adapters,
                        Clock.systemUTC());
    }

    @Test
    void SRS_INT_16_blankFiltersAreNoneAndARecordedBreakerStateIsShown() {
        db.rows(
                "open_exceptions, (SELECT MAX(g.completed_at)",
                row(
                        "interface_id", 4L,
                        "interface_code", "I-HRMS",
                        "interface_name", "HRMS",
                        "counterparty_system", "HRMS",
                        "direction", "INBOUND",
                        "transport", "REST",
                        "endpoint_object", null,
                        "staging_object", "intf_staging_txn",
                        "consuming_screen", "I-21",
                        "spec_signed_off", "N",
                        "scope_status", "IN_SCOPE",
                        "active_flag", "Y",
                        "breaker_state", "OPEN",
                        "open_exceptions", 2L,
                        "last_good", null));
        MonitorService.InterfaceRow row =
                service.interfaces(context, " ", new PageSpec(0, 20)).items().get(0);
        assertThat(row.breakerState()).isEqualTo("OPEN");
        assertThat(service.windows(context, " ", new PageSpec(0, 20)).items()).isEmpty();
        assertThat(service.queue(context, false, " ", new PageSpec(0, 20)).items()).isEmpty();
    }

    /** One envelope of the Integration Audit Trail export. */
    private void envelope() {
        db.value("SELECT COUNT(*) FROM interface_run", 1L);
        db.rows(
                "r.control_total, r.run_status",
                row(
                        "run_id",
                        9L,
                        "interface_code",
                        "HRMS_EMP_OTA",
                        "direction",
                        "INBOUND",
                        "batch_reference",
                        null,
                        "window_start",
                        null,
                        "window_end",
                        null,
                        "received_count",
                        3L,
                        "accepted_count",
                        2L,
                        "rejected_count",
                        1L,
                        "control_total",
                        null,
                        "run_status",
                        "PARTIAL",
                        "retry_attempt",
                        0,
                        "started_at",
                        java.sql.Timestamp.from(java.time.Instant.EPOCH),
                        "completed_at",
                        null,
                        "failure_detail",
                        null));
    }

    @Test
    void SRS_INT_32_theIntegrationAuditTrailExportsEachEnvelopeAndStopsOnAFailingFile()
            throws Exception {
        envelope();
        java.util.List<Object[]> rows = new java.util.ArrayList<>();
        in.gov.fci.payroll.platform.export.ExportSink sink =
                new in.gov.fci.payroll.platform.export.ExportSink() {
                    @Override
                    public void header(String... titles) {}

                    @Override
                    public void row(Object... cells) {
                        rows.add(cells);
                    }

                    @Override
                    public long rows() {
                        return rows.size();
                    }

                    @Override
                    public void close() {}
                };
        service.exportAuditTrail(context, " ", sink);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst()[1]).isEqualTo("HRMS_EMP_OTA");
        assertThat(rows.getFirst()[10]).isEqualTo("PARTIAL");
        assertThat(service.auditTrailSize(context, "HRMS_EMP_OTA")).isEqualTo(1L);
        in.gov.fci.payroll.platform.export.ExportSink failing =
                new in.gov.fci.payroll.platform.export.ExportSink() {
                    @Override
                    public void header(String... titles) {}

                    @Override
                    public void row(Object... cells) throws java.io.IOException {
                        throw new java.io.IOException("disk full");
                    }

                    @Override
                    public long rows() {
                        return 0;
                    }

                    @Override
                    public void close() {}
                };
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.exportAuditTrail(context, null, failing))
                .isInstanceOf(java.io.IOException.class)
                .hasMessage("disk full");
    }
}
