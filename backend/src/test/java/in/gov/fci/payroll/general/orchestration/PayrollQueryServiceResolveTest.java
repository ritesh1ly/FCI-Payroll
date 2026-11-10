package in.gov.fci.payroll.general.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.employee.api.EmployeeDirectory;
import in.gov.fci.payroll.general.business.GeneralErrorCode;
import in.gov.fci.payroll.general.business.model.PayrollQuery;
import in.gov.fci.payroll.general.persistence.GeneralRepositories;
import in.gov.fci.payroll.payroll.api.PayrollRecords;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
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
 * X-06 resolving a query (SRS-G-11, SRS-22-5, OPEN-QUESTION Q-X06-1): the Payroll Checker resolves
 * and the system closes in the same act with both transitions audited; a Maker (EDIT only) resolves
 * a general query but is refused on a computed amount; a user with neither right, an eSeva member
 * and an unknown query change nothing.
 */
class PayrollQueryServiceResolveTest {

    private static final Instant NOW = Instant.parse("2026-10-29T06:00:00Z");

    private final GeneralRepositories.Queries queries = mock(GeneralRepositories.Queries.class);
    private final Authorization policy = mock(Authorization.class);
    private final AuditService audit = mock(AuditService.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final AccessContext staff = mock(AccessContext.class);
    private final PayrollQueryService service =
            new PayrollQueryService(
                    mock(ExportReader.class),
                    queries,
                    mock(PayrollRecords.class),
                    mock(EmployeeDirectory.class),
                    policy,
                    audit,
                    events,
                    Clock.fixed(NOW, ZoneOffset.UTC));

    PayrollQueryServiceResolveTest() {
        when(staff.loginId()).thenReturn("checker@x");
        when(staff.isEseva()).thenReturn(false);
    }

    private PayrollQuery inProgress(boolean computedAmount) {
        PayrollQuery query =
                PayrollQuery.raise(
                        7L,
                        9L,
                        31L,
                        PayrollQuery.Channel.ESEVA,
                        "DA short",
                        computedAmount,
                        "member@x",
                        NOW);
        query.respond("Checked, DA was paid in the supplementary", computedAmount, "maker@x", NOW);
        when(queries.findById(5L)).thenReturn(Optional.of(query));
        return query;
    }

    @Test
    void SRS_22_5_theCheckerResolvesAComputedAmountQueryAndTheSystemClosesIt() {
        PayrollQuery query = inProgress(true);
        when(policy.canAt(eq(staff), eq(PayrollQueryService.SCREEN), eq("APPROVE"), any()))
                .thenReturn(true);

        service.resolve(staff, 5L, "Confirmed against the supplementary run");

        assertThat(query.getStatus()).isEqualTo(PayrollQuery.Status.CLOSED);
        assertThat(query.getResolvedBy()).isEqualTo("checker@x");
        verify(audit, times(2)).record(eq("checker@x"), any(AuditService.Entry.class));
        verify(policy, never())
                .requireEmployeeRecord(any(), anyString(), anyString(), any(), any());
    }

    @Test
    void SRS_22_5_aMakerWithOnlyEditResolvesAGeneralQueryButNotAComputedAmountOne() {
        when(policy.canAt(eq(staff), eq(PayrollQueryService.SCREEN), eq("APPROVE"), any()))
                .thenReturn(false);
        when(policy.canAt(eq(staff), eq(PayrollQueryService.SCREEN), eq("EDIT"), any()))
                .thenReturn(true);

        PayrollQuery general = inProgress(false);
        service.resolve(staff, 5L, "Explained to the member");
        assertThat(general.getStatus()).isEqualTo(PayrollQuery.Status.CLOSED);

        PayrollQuery computed = inProgress(true);
        assertThatThrownBy(() -> service.resolve(staff, 5L, "Explained to the member"))
                .isInstanceOfSatisfying(
                        FciException.class,
                        ex ->
                                assertThat(ex.getErrorCode())
                                        .isEqualTo(GeneralErrorCode.QUERY_CHECKER_REQUIRED));
        assertThat(computed.getStatus()).isEqualTo(PayrollQuery.Status.IN_PROGRESS);
    }

    @Test
    void SRS_22_5_aUserWithNeitherRightIsRefusedWithThePreciseDenialAndNothingChanges() {
        PayrollQuery query = inProgress(true);
        when(policy.canAt(any(), anyString(), anyString(), any())).thenReturn(false);
        doThrow(new FciException(ApiErrorCode.PERMISSION_DENIED))
                .when(policy)
                .requireEmployeeRecord(
                        eq(staff), eq(PayrollQueryService.SCREEN), eq("APPROVE"), eq(7L), any());

        assertThatThrownBy(() -> service.resolve(staff, 5L, "Resolved"))
                .isInstanceOf(FciException.class);

        assertThat(query.getStatus()).isEqualTo(PayrollQuery.Status.IN_PROGRESS);
        verifyNoInteractions(audit, events);
        verify(queries, never()).save(any());
    }

    @Test
    void SRS_22_5_anEsevaMemberCannotResolveEvenWhenThePolicyWouldAllow() {
        PayrollQuery query = inProgress(false);
        AccessContext member = mock(AccessContext.class);
        when(member.isEseva()).thenReturn(true);
        when(policy.canAt(any(), anyString(), anyString(), any())).thenReturn(true);
        doThrow(new FciException(ApiErrorCode.PERMISSION_DENIED))
                .when(policy)
                .requireEmployeeRecord(
                        eq(member), eq(PayrollQueryService.SCREEN), eq("APPROVE"), eq(7L), any());

        assertThatThrownBy(() -> service.resolve(member, 5L, "Resolved"))
                .isInstanceOf(FciException.class);

        assertThat(query.getStatus()).isEqualTo(PayrollQuery.Status.IN_PROGRESS);
        verifyNoInteractions(audit, events);
    }

    @Test
    void SRS_22_5_resolvingAnUnknownQueryIsNotFound() {
        when(queries.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolve(staff, 99L, "Resolved"))
                .isInstanceOfSatisfying(
                        FciException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(CommonErrorCode.NOT_FOUND));
        verifyNoInteractions(audit, events);
    }
}
