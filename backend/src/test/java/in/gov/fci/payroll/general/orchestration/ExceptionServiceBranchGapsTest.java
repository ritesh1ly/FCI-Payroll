package in.gov.fci.payroll.general.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.employee.api.EmployeeDirectory;
import in.gov.fci.payroll.general.business.model.ExceptionEntry;
import in.gov.fci.payroll.general.persistence.GeneralRepositories;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.platform.export.ExportReader;
import in.gov.fci.payroll.platform.export.ExportSink;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * X-05 export (SRS-G-9, SRS-G-13): each exception says whether it is resolvable in Payroll (Y) or
 * only at its source (N), the HRMS-owned field fixed in HRMS.
 */
class ExceptionServiceBranchGapsTest {

    private static final Instant NOW = Instant.parse("2026-10-29T06:00:00Z");

    private final ExportReader reader = mock(ExportReader.class);
    private final AccessContext context = mock(AccessContext.class);
    private final ExceptionService service =
            new ExceptionService(
                    reader,
                    mock(GeneralRepositories.Exceptions.class),
                    mock(EmployeeDirectory.class),
                    mock(OrgDirectory.class),
                    mock(Authorization.class),
                    mock(AuditService.class),
                    Clock.fixed(NOW, ZoneOffset.UTC));

    private static ExceptionEntry entry(long id, boolean resolvable) {
        ExceptionEntry e = mock(ExceptionEntry.class);
        when(e.getId()).thenReturn(id);
        when(e.getSeverity()).thenReturn(ExceptionEntry.Severity.WARNING);
        when(e.getStatus()).thenReturn(ExceptionEntry.Status.OPEN);
        when(e.getRaisedAt()).thenReturn(NOW.minusSeconds(86_400L * 3));
        when(e.isResolvableInPayroll()).thenReturn(resolvable);
        return e;
    }

    @Test
    @SuppressWarnings("unchecked")
    void SRS_G_9_theExportSaysWhetherEachExceptionIsResolvableInPayroll() throws Exception {
        when(context.dataScope()).thenReturn(Set.of(40L));
        when(reader.readAll(any(), any()))
                .thenAnswer(
                        i -> {
                            ExportReader.Batch<ExceptionEntry> batch = i.getArgument(1);
                            batch.handle(List.of(entry(1L, true), entry(2L, false)));
                            return 2L;
                        });
        ExportSink sink = mock(ExportSink.class);
        when(sink.rows()).thenReturn(2L);
        List<Object[]> rows = new ArrayList<>();
        doAnswer(
                        i -> {
                            rows.add(i.getArguments());
                            return null;
                        })
                .when(sink)
                .row(any(Object[].class));

        service.export(context, ExceptionService.Filter.none(), sink);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)[9]).isEqualTo("Y");
        assertThat(rows.get(1)[9]).isEqualTo("N");
        assertThat(rows.get(0)[14]).isEqualTo(3L);
    }
}
