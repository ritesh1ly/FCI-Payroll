package in.gov.fci.payroll.general.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.employee.api.EmployeeDirectory;
import in.gov.fci.payroll.general.business.model.PayrollQuery;
import in.gov.fci.payroll.general.persistence.GeneralRepositories;
import in.gov.fci.payroll.payroll.api.PayrollRecords;
import in.gov.fci.payroll.platform.export.ExportReader;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/**
 * X-06 Payroll Query (SRS-G-10, SRS-20-2): a Maker response that repeats the recorded one, with the
 * flag unchanged, records only the status; a changed one records its old and new value; a query of
 * a member no longer in the directory shows no name.
 */
class PayrollQueryRespondTest {

    private static final Instant NOW = Instant.parse("2026-10-29T06:00:00Z");

    private final GeneralRepositories.Queries queries = mock(GeneralRepositories.Queries.class);
    private final AuditService audit = mock(AuditService.class);
    private final AccessContext context = mock(AccessContext.class);
    private final PayrollQueryService service =
            new PayrollQueryService(
                    mock(ExportReader.class),
                    queries,
                    mock(PayrollRecords.class),
                    mock(EmployeeDirectory.class),
                    mock(Authorization.class),
                    audit,
                    mock(ApplicationEventPublisher.class),
                    Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void SRS_20_2_aRepeatedResponseRecordsOnlyTheStatusAndAChangedOneItsValues() {
        when(context.loginId()).thenReturn("maker@x");
        PayrollQuery q =
                PayrollQuery.raise(
                        7L, 9L, 31L, PayrollQuery.Channel.STAFF, "DA short", false, "e@x", NOW);
        when(queries.findById(4L)).thenReturn(Optional.of(q));
        PayrollQueryService.QueryView first = service.respond(context, 4L, "Checked", false);
        assertThat(first.memberName()).isNull();
        verify(audit, times(2)).record(anyString(), any());

        service.respond(context, 4L, "Checked", false);
        // Only the status line again: the response and the flag are unchanged.
        verify(audit, times(3)).record(anyString(), any());

        service.respond(context, 4L, "Re-checked: arrear due", true);
        verify(audit)
                .record(
                        anyString(),
                        argThat(e -> "concerns_computed_amount".equals(e.fieldName())));
        verify(audit, times(6)).record(anyString(), any());
    }
}
