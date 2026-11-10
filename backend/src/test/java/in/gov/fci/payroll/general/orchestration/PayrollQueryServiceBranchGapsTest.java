package in.gov.fci.payroll.general.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.employee.api.EmployeeDirectory;
import in.gov.fci.payroll.general.business.model.PayrollQuery;
import in.gov.fci.payroll.general.persistence.GeneralRepositories;
import in.gov.fci.payroll.payroll.api.PayrollRecords;
import in.gov.fci.payroll.platform.export.ExportReader;
import in.gov.fci.payroll.platform.export.ExportSink;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/**
 * X-06 export of an eSeva member's own queries (SRS-G-10, SRS-G-13): each row says whether the
 * query concerns a computed amount (Y) or not (N).
 */
class PayrollQueryServiceBranchGapsTest {

    private static final Instant NOW = Instant.parse("2026-10-29T06:00:00Z");

    private final GeneralRepositories.Queries queries = mock(GeneralRepositories.Queries.class);
    private final AccessContext member = mock(AccessContext.class);
    private final PayrollQueryService service =
            new PayrollQueryService(
                    mock(ExportReader.class),
                    queries,
                    mock(PayrollRecords.class),
                    mock(EmployeeDirectory.class),
                    mock(Authorization.class),
                    mock(AuditService.class),
                    mock(ApplicationEventPublisher.class),
                    Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void SRS_G_10_theMembersExportFlagsTheQueriesOnAComputedAmount() throws Exception {
        when(member.isEseva()).thenReturn(true);
        when(member.employeeId()).thenReturn(7L);
        PayrollQuery onAmount =
                PayrollQuery.raise(
                        7L, 9L, 31L, PayrollQuery.Channel.ESEVA, "DA short", true, "m@x", NOW);
        PayrollQuery general =
                PayrollQuery.raise(
                        7L,
                        9L,
                        31L,
                        PayrollQuery.Channel.ESEVA,
                        "Bank changed?",
                        false,
                        "m@x",
                        NOW);
        when(queries.findByEmployeeIdOrderByRaisedAtDesc(7L))
                .thenReturn(List.of(onAmount, general));
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

        service.export(member, null, sink);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)[6]).isEqualTo("Y");
        assertThat(rows.get(1)[6]).isEqualTo("N");
        assertThat(rows.get(0)[10]).isEqualTo("DA short");
    }
}
