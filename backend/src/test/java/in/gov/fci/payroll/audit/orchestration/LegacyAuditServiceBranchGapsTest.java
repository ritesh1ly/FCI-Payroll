package in.gov.fci.payroll.audit.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.audit.api.RecordHistorySource.HistoryRecord;
import in.gov.fci.payroll.audit.persistence.AuditRepositories;
import in.gov.fci.payroll.platform.export.ExportReader;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;

/**
 * SRS-20-24: every Legacy Audit enquiry is logged with the requester's scope; the logged record key
 * fits the audit key column (100 characters) and a context without groups logs none.
 */
class LegacyAuditServiceBranchGapsTest {

    private final RecordHistoryService records = mock(RecordHistoryService.class);
    private final AuditRepositories.LegacyAudit legacy = mock(AuditRepositories.LegacyAudit.class);
    private final AuditService audit = mock(AuditService.class);
    private final LegacyAuditService service =
            new LegacyAuditService(
                    records, legacy, mock(Authorization.class), mock(ExportReader.class), audit);

    private static HistoryRecord record(String key) {
        return new HistoryRecord(
                "employee", key, "M-01", 10L, List.of(), false, List.of(), Map.of(), null);
    }

    @Test
    void SRS_20_24_aLongRecordKeyIsCutToTheAuditKeyColumnAndAShortOneKept() {
        AccessContext context = mock(AccessContext.class);
        when(context.loginId()).thenReturn("auditor@x");
        String longKey = "k".repeat(120);
        when(records.resolve(context, "employee", longKey, "VIEW")).thenReturn(record(longKey));
        when(records.resolve(context, "employee", "106620", "VIEW")).thenReturn(record("106620"));
        when(legacy.forRecord(any(), any(), anyBoolean(), any())).thenReturn(Page.empty());

        service.list(context, "employee", longKey, 0, 20);
        service.list(context, "employee", "106620", 0, 20);

        ArgumentCaptor<AuditService.Entry> logged = ArgumentCaptor.captor();
        verify(audit, org.mockito.Mockito.times(2))
                .recordIndependently(eq("auditor@x"), logged.capture());
        assertThat(logged.getAllValues().get(0).entityKey())
                .hasSize(100)
                .isEqualTo(("employee/" + longKey).substring(0, 100));
        assertThat(logged.getAllValues().get(1).entityKey()).isEqualTo("employee/106620");
    }

    @Test
    void SRS_20_24_theScopeNamesTheOfficeAndNoGroupsWhenTheContextHasNone() {
        AccessContext none = mock(AccessContext.class);
        when(none.groups()).thenReturn(null);
        when(none.locationId()).thenReturn(10L);
        assertThat(LegacyAuditService.scope(none)).isEqualTo("office=10;groups=");
    }
}
