package in.gov.fci.payroll.integration.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.employee.api.HrmsChanges;
import in.gov.fci.payroll.general.api.ExceptionWorklist;
import in.gov.fci.payroll.integration.api.InterfaceAdapter;
import in.gov.fci.payroll.integration.api.InterfaceSettings;
import in.gov.fci.payroll.integration.business.IntegrationErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.ErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * X-03 Reprocess Failed for one exchange window (X-03 l.5172 "Feeds the reprocessing queue for
 * rejected or failed records. Reprocess Failed is Administrator only"; SRS-INT-33; PENDING G5-96):
 * every open item of the window through the interface's adapter under one reason, all or nothing;
 * the Administrator only, the reason mandatory, an unknown or settled window refused, no adapter or
 * an item without its staged record refused and audited, a failing item stopping the run and
 * audited with the window.
 */
class MonitorWindowReprocessTest {

    private static final String CODE = "HRMS_EMPLOYEE";

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final IntegrationStore store = mock(IntegrationStore.class);
    private final Authorization policy = mock(Authorization.class);
    private final AuditService audit = mock(AuditService.class);
    private final ExceptionWorklist worklist = mock(ExceptionWorklist.class);
    private final AccessContext context = mock(AccessContext.class);
    private final InterfaceAdapter adapter = mock(InterfaceAdapter.class);

    MonitorWindowReprocessTest() {
        when(store.jdbc()).thenReturn(jdbc);
        when(context.loginId()).thenReturn("admin");
        when(adapter.interfaceCode()).thenReturn(CODE);
    }

    @SuppressWarnings("unchecked")
    private MonitorService service(boolean withAdapter) {
        ObjectProvider<InterfaceAdapter> adapters = mock(ObjectProvider.class);
        when(adapters.orderedStream())
                .thenReturn(withAdapter ? Stream.of(adapter) : Stream.empty());
        return new MonitorService(
                store,
                mock(InterfaceSettings.class),
                policy,
                audit,
                worklist,
                mock(HrmsChanges.class),
                adapters,
                Clock.fixed(Instant.parse("2026-10-28T06:00:00Z"), ZoneOffset.UTC));
    }

    private void window(Long runId, String code) {
        when(jdbc.queryForList(contains("FROM interface_run r"), eq(String.class), eq(runId)))
                .thenReturn(code == null ? List.of() : List.of(code));
    }

    private static Map<String, Object> open(long id, Long staging) {
        Map<String, Object> row = new HashMap<>();
        row.put("EXCEPTION_ID", id);
        row.put("STAGING_ID", staging);
        row.put("REMEDIATION_STATUS", "OPEN");
        row.put("INTERFACE_CODE", CODE);
        row.put("TXN_TYPE", "EMPLOYEE_UPDATE");
        row.put("PAYLOAD", "{}");
        return row;
    }

    private void items(long runId, List<Map<String, Object>> rows) {
        when(jdbc.queryForList(contains("WHERE e.run_id = ?"), eq(runId))).thenReturn(rows);
    }

    private static void refused(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(
                        FciException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    private List<AuditService.Entry> independent() {
        ArgumentCaptor<AuditService.Entry> entries =
                ArgumentCaptor.forClass(AuditService.Entry.class);
        verify(audit).recordIndependently(eq("admin"), entries.capture());
        return entries.getAllValues();
    }

    @Test
    void SRS_INT_33_reprocessFailedTakesEveryOpenItemOfTheWindowWithOneReason() {
        MonitorService service = service(true);
        window(40L, CODE);
        items(40L, List.of(open(7L, 70L), open(8L, 80L)));
        when(jdbc.queryForObject(contains("remediation_status IN"), eq(Long.class), anyString()))
                .thenReturn(0L);

        MonitorService.WindowReprocess done =
                service.reprocessWindow(context, 40L, "  Feed corrected by HRMS  ");

        assertThat(done.runId()).isEqualTo(40L);
        assertThat(done.interfaceCode()).isEqualTo(CODE);
        assertThat(done.reprocessed()).containsExactly(7L, 8L);
        verify(policy).require(context, "X-03", "EDIT");
        verify(adapter).reprocess(70L, "EMPLOYEE_UPDATE", "{}");
        verify(adapter).reprocess(80L, "EMPLOYEE_UPDATE", "{}");
        verify(jdbc)
                .update(contains("SET remediation_status = 'REPROCESSED'"), eq(70L), any(), eq(7L));
        verify(jdbc)
                .update(contains("SET remediation_status = 'REPROCESSED'"), eq(80L), any(), eq(8L));
        ArgumentCaptor<AuditService.Entry> entries =
                ArgumentCaptor.forClass(AuditService.Entry.class);
        verify(audit, org.mockito.Mockito.times(3)).record(eq("admin"), entries.capture());
        List<AuditService.Entry> all = new ArrayList<>(entries.getAllValues());
        // one entry per item and one for the window, each with the one trimmed reason
        assertThat(all)
                .extracting(AuditService.Entry::reason)
                .containsOnly("Feed corrected by HRMS");
        AuditService.Entry windowEntry = all.getLast();
        assertThat(windowEntry.entityName()).isEqualTo("interface_run");
        assertThat(windowEntry.entityKey()).isEqualTo("40");
        assertThat(windowEntry.newValue()).isEqualTo("REPROCESSED 2");
        assertThat(windowEntry.moduleCode()).isEqualTo("X-03");
        // no open item left: the X-05 Integration Failure entry is settled once
        verify(worklist).clear(eq("admin"), any(), any(), any(), eq(CODE), anyString());
        verify(audit, never()).recordIndependently(anyString(), any());
    }

    @Test
    void SRS_INT_33_aWindowWithOneOpenItemIsReprocessedAndX05StaysWhileOthersAreOpen() {
        MonitorService service = service(true);
        window(41L, CODE);
        items(41L, List.of(open(9L, 90L)));
        when(jdbc.queryForObject(contains("remediation_status IN"), eq(Long.class), anyString()))
                .thenReturn(2L);
        assertThat(service.reprocessWindow(context, 41L, "x").reprocessed()).containsExactly(9L);
        verify(worklist, never()).clear(any(), any(), any(), any(), any(), any());
    }

    @Test
    void SRS_INT_33_reprocessFailedIsTheAdministratorsAlone() {
        MonitorService service = service(true);
        doThrow(new FciException(CommonErrorCode.ACCESS_DENIED))
                .when(policy)
                .require(context, "X-03", "EDIT");
        refused(
                () -> service.reprocessWindow(context, 40L, "Feed corrected"),
                CommonErrorCode.ACCESS_DENIED);
        verifyNoInteractions(jdbc, audit, worklist);
        verify(adapter, never()).reprocess(anyLong(), any(), any());
    }

    @Test
    void SRS_INT_33_theReasonIsMandatory() {
        MonitorService service = service(true);
        for (String blank : new String[] {null, "", "   ", "x".repeat(1001)}) {
            refused(
                    () -> service.reprocessWindow(context, 40L, blank),
                    IntegrationErrorCode.REASON);
        }
        verifyNoInteractions(jdbc, audit);
        verify(adapter, never()).reprocess(anyLong(), any(), any());
    }

    @Test
    void SRS_INT_33_anUnknownWindowOrOneWithNoOpenItemIsRefused() {
        MonitorService service = service(true);
        window(99L, null);
        refused(
                () -> service.reprocessWindow(context, 99L, "Feed corrected"),
                IntegrationErrorCode.WINDOW);
        window(42L, CODE);
        items(42L, List.of());
        refused(
                () -> service.reprocessWindow(context, 42L, "Feed corrected"),
                IntegrationErrorCode.WINDOW);
        verifyNoInteractions(audit);
        verify(adapter, never()).reprocess(anyLong(), any(), any());
    }

    @Test
    void SRS_INT_33_withoutAnAdapterTheWindowIsRefusedAndTheAttemptAudited() {
        MonitorService service = service(false);
        window(40L, CODE);
        items(40L, List.of(open(7L, 70L)));
        refused(
                () -> service.reprocessWindow(context, 40L, "Feed corrected"),
                IntegrationErrorCode.NO_ADAPTER);
        AuditService.Entry e = independent().getFirst();
        assertThat(e.entityName()).isEqualTo("interface_run");
        assertThat(e.newValue()).isEqualTo("NO_ADAPTER");
        assertThat(e.reason()).isEqualTo("Feed corrected");
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void SRS_INT_33_anItemWithoutItsStagedRecordRefusesTheWindowAndIsAudited() {
        MonitorService service = service(true);
        window(40L, CODE);
        items(40L, List.of(open(7L, 70L), open(8L, null)));
        refused(
                () -> service.reprocessWindow(context, 40L, "Feed corrected"),
                IntegrationErrorCode.NO_ADAPTER);
        assertThat(independent().getFirst().newValue()).isEqualTo("NO_RECORD");
        verify(adapter, never()).reprocess(anyLong(), any(), any());
    }

    @Test
    void SRS_INT_33_aFailingItemStopsTheWindowAndIsAuditedWithItAndTheItem() {
        MonitorService service = service(true);
        window(40L, CODE);
        items(40L, List.of(open(7L, 70L), open(8L, 80L), open(9L, 90L)));
        doThrow(new IllegalStateException("HRMS rejected the record"))
                .when(adapter)
                .reprocess(eq(80L), any(), any());
        refused(
                () -> service.reprocessWindow(context, 40L, "Feed corrected"),
                IntegrationErrorCode.CALL_FAILED);
        verify(adapter).reprocess(eq(70L), any(), any());
        verify(adapter, never()).reprocess(eq(90L), any(), any());
        ArgumentCaptor<AuditService.Entry> entries =
                ArgumentCaptor.forClass(AuditService.Entry.class);
        verify(audit, org.mockito.Mockito.times(2))
                .recordIndependently(eq("admin"), entries.capture());
        assertThat(entries.getAllValues())
                .extracting(AuditService.Entry::entityName, AuditService.Entry::entityKey)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("interface_exception", "8"),
                        org.assertj.core.groups.Tuple.tuple("interface_run", "40"));
        assertThat(entries.getAllValues().getLast().newValue()).isEqualTo("FAILED AT 8");
        verify(worklist, never()).clear(any(), any(), any(), any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void SRS_INT_33_thePerItemReprocessKeepsItsOwnPath() {
        MonitorService service = service(true);
        when(jdbc.queryForList(contains("s.payload FROM"), eq(7L)))
                .thenReturn(List.of(open(7L, 70L)));
        when(jdbc.queryForObject(contains("remediation_status IN"), eq(Long.class), anyString()))
                .thenReturn(0L);
        MonitorService.QueueItem item = mock(MonitorService.QueueItem.class);
        when(jdbc.query(
                        contains("WHERE e.exception_id = ?"),
                        any(org.springframework.jdbc.core.RowMapper.class),
                        eq(7L)))
                .thenReturn(List.of(item));
        assertThat(service.reprocess(context, 7L, "Feed corrected")).isSameAs(item);
        verify(adapter).reprocess(70L, "EMPLOYEE_UPDATE", "{}");
        verify(worklist).clear(eq("admin"), any(), any(), any(), eq(CODE), anyString());
    }
}
