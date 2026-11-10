package in.gov.fci.payroll.integration.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * X-03 Enterprise Interfaces beyond the integration path (plan 23 phase 23b; SRS-INT-31,
 * SRS-INT-33; D23-5): a blank filter lists everything; the closed queue is read without the open
 * filter; a write-off needs a reason and an item still open (or in progress); the X-05 entry stays
 * while another item of the interface is open.
 */
class MonitorServiceTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ExceptionWorklist worklist = mock(ExceptionWorklist.class);
    private final AccessContext context = mock(AccessContext.class);
    private final AuditService audit = mock(AuditService.class);
    private final MonitorService service;

    @SuppressWarnings("unchecked")
    MonitorServiceTest() {
        IntegrationStore store = mock(IntegrationStore.class);
        when(store.jdbc()).thenReturn(jdbc);
        ObjectProvider<InterfaceAdapter> adapters = mock(ObjectProvider.class);
        when(adapters.orderedStream()).thenReturn(Stream.empty());
        service =
                new MonitorService(
                        store,
                        mock(InterfaceSettings.class),
                        mock(Authorization.class),
                        audit,
                        worklist,
                        mock(HrmsChanges.class),
                        adapters,
                        Clock.fixed(Instant.parse("2026-10-28T06:00:00Z"), ZoneOffset.UTC));
        when(context.loginId()).thenReturn("admin");
    }

    @Test
    void SRS_INT_33_aBlankFilterListsEveryInterfaceRunAndItem() {
        PageSpec page = new PageSpec(0, 20);
        assertThat(service.interfaces(context, "  ", page).items()).isEmpty();
        // Every filter blank: counterparty, scope (three binds), status, search (two binds each)
        // all null.
        verify(jdbc)
                .queryForObject(
                        contains("counterparty_system"),
                        eq(Long.class),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull());
        assertThat(service.windows(context, "", page).items()).isEmpty();
        verify(jdbc)
                .queryForObject(
                        contains("FROM interface_run r"), eq(Long.class), isNull(), isNull());
        assertThat(service.queue(context, false, null, page).items()).isEmpty();
        verify(jdbc)
                .queryForObject(
                        contains("FROM interface_exception"),
                        eq(Long.class),
                        isNull(),
                        isNull(),
                        isNull());
    }

    private void item(String status) {
        Map<String, Object> row =
                Map.of(
                        "EXCEPTION_ID",
                        7L,
                        "REMEDIATION_STATUS",
                        status,
                        "INTERFACE_CODE",
                        "HRMS_EMPLOYEE");
        when(jdbc.queryForList(contains("s.payload FROM"), eq(7L))).thenReturn(List.of(row));
    }

    @Test
    @SuppressWarnings("unchecked")
    void SRS_INT_31_aWriteOffNeedsAReasonAndAnOpenItemAndKeepsX05WhileOthersAreOpen() {
        assertThatThrownBy(() -> service.writeOff(context, 7L, null))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(IntegrationErrorCode.REASON));
        item("WRITTEN_OFF");
        assertThatThrownBy(() -> service.writeOff(context, 7L, "Duplicate of 6"))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(IntegrationErrorCode.STATE));

        item("IN_PROGRESS");
        when(jdbc.queryForObject(contains("remediation_status IN"), eq(Long.class), anyString()))
                .thenReturn(1L);
        MonitorService.QueueItem done = mock(MonitorService.QueueItem.class);
        when(jdbc.query(contains("WHERE e.exception_id = ?"), any(RowMapper.class), eq(7L)))
                .thenReturn(List.of(done));
        assertThat(service.writeOff(context, 7L, "Duplicate of 6")).isSameAs(done);
        verify(worklist, never()).clear(any(), any(), any(), any(), any(), any());
    }

    // BMAD TR-063 (SRS-INT-31 l.5154: only the Administrator resolves it, with a reason): a
    // reprocess without a reason, or with a blank one, is refused before the item is read; nothing
    // is changed and no adapter is called.
    @Test
    void SRS_INT_31_aReprocessWithoutAReasonIsRefusedBeforeAnythingIsRead() {
        for (String blank : new String[] {null, "", "   "}) {
            assertThatThrownBy(() -> service.reprocess(context, 7L, blank))
                    .isInstanceOfSatisfying(
                            FciException.class,
                            e ->
                                    assertThat(e.getErrorCode())
                                            .isEqualTo(IntegrationErrorCode.REASON));
        }
        verifyNoInteractions(jdbc, audit, worklist);
    }
}
