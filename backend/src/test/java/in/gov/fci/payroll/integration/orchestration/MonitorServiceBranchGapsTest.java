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
 * Branch gaps of the X-03 Integration Monitor (SRS-INT-16): the exchange windows asked without an
 * interface filter at all (null, not blank) list every interface's windows.
 */
class MonitorServiceBranchGapsTest {

    private final ScriptedJdbc db = new ScriptedJdbc();
    private final MonitorService service;

    @SuppressWarnings("unchecked")
    MonitorServiceBranchGapsTest() {
        IntegrationStore store = mock(IntegrationStore.class);
        when(store.jdbc()).thenReturn(db.jdbc);
        ObjectProvider<InterfaceAdapter> adapters = mock(ObjectProvider.class);
        when(adapters.orderedStream()).thenReturn(Stream.empty());
        InterfaceSettings settings = mock(InterfaceSettings.class);
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
    void SRS_INT_16_windowsWithoutAnInterfaceFilterListEveryWindow() {
        db.value("SELECT COUNT(*) FROM interface_run r JOIN", 1L);
        db.rows(
                "r.failure_detail FROM interface_run r",
                row(
                        "run_id",
                        9L,
                        "interface_code",
                        "HRMS_EMP_OTA",
                        "direction",
                        "INBOUND",
                        "batch_reference",
                        "B-9",
                        "window_start",
                        null,
                        "window_end",
                        null,
                        "received_count",
                        3L,
                        "accepted_count",
                        3L,
                        "rejected_count",
                        0L,
                        "run_status",
                        "COMPLETED",
                        "retry_attempt",
                        0,
                        "started_at",
                        null,
                        "completed_at",
                        null,
                        "failure_detail",
                        null));
        var page = service.windows(mock(AccessContext.class), null, new PageSpec(0, 20));
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).received()).isEqualTo(3L);
        assertThat(page.items().get(0).interfaceCode()).isEqualTo("HRMS_EMP_OTA");
        assertThat(page.items().get(0).adapter()).isFalse();
    }
}
