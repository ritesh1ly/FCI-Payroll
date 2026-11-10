package in.gov.fci.payroll.configuration.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.ReferenceData;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.configuration.business.model.CircularOrder;
import in.gov.fci.payroll.configuration.persistence.ConfigurationRepositories;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * M-25 Circulars beyond the happy path (SRS-G-38): a search without criteria or with blank ones
 * searches everything; the home page with no active circular reads no office scope, and a cyclic
 * office hierarchy ends; a withdrawal approved without a reason is recorded as "Approved"; a
 * circular without an issuing authority names the list it is not in.
 */
class CircularServiceEdgeTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 29);

    private final ConfigurationRepositories.Circulars circulars =
            mock(ConfigurationRepositories.Circulars.class);
    private final ConfigurationRepositories.CircularScopes scopes =
            mock(ConfigurationRepositories.CircularScopes.class);
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final SystemParameters settings = mock(SystemParameters.class);
    private final AccessContext context = mock(AccessContext.class);
    private final CircularService service =
            new CircularService(
                    circulars,
                    scopes,
                    mock(ConfigurationRepositories.CircularLinks.class),
                    mock(ConfigurationRepositories.Operations.class),
                    mock(ConfigurationRepositories.RateSets.class),
                    mock(ReferenceData.class),
                    org,
                    mock(DocumentStore.class),
                    mock(MakerChecker.class),
                    mock(Authorization.class),
                    settings,
                    mock(AuditService.class),
                    Clock.systemUTC());

    @Test
    void SRS_G_38_aSearchWithoutOrWithBlankCriteriaSearchesEverything() {
        assertThat(service.search(context, null)).isEmpty();
        assertThat(
                        service.search(
                                context,
                                new CircularService.SearchQuery(" ", " ", null, null, null)))
                .isEmpty();
        verify(circulars, times(2)).search(isNull(), isNull(), isNull(), isNull(), isNull());
    }

    @Test
    void SRS_G_38_withNoActiveCircularTheHomePageReadsNoScopeAndACyclicHierarchyEnds() {
        when(settings.today()).thenReturn(DAY);
        when(context.locationId()).thenReturn(5L);
        OrgLocation five = mock(OrgLocation.class);
        when(five.getParentId()).thenReturn(6L);
        OrgLocation six = mock(OrgLocation.class);
        when(six.getParentId()).thenReturn(5L);
        when(org.location(5L)).thenReturn(Optional.of(five));
        when(org.location(6L)).thenReturn(Optional.of(six));
        assertThat(service.current(context)).isEmpty();
        verify(scopes, never()).findByCircularOrderIdIn(any());
    }

    private static ApprovalDecided withdrawal(String reason) {
        return new ApprovalDecided(
                8L,
                "M-25",
                CircularService.WITHDRAWAL,
                "3",
                ApprovalStatus.APPROVED,
                reason,
                null,
                "checker",
                Instant.now());
    }

    @Test
    void SRS_G_38_aWithdrawalApprovedWithoutAReasonIsRecordedAsApproved() {
        CircularOrder active = mock(CircularOrder.class);
        when(active.isActive()).thenReturn(true);
        when(circulars.findById(3L)).thenReturn(Optional.of(active));
        service.on(withdrawal(null));
        service.on(withdrawal("  "));
        verify(active, times(2)).withdraw(eq("Approved"), eq("checker"), any());
    }

    @Test
    void SRS_G_38_aCircularWithoutAnIssuingAuthorityIsRefused() {
        assertThatThrownBy(
                        () ->
                                service.record(
                                        context,
                                        new CircularService.NewCircular(
                                                "CIR/2026/9",
                                                DAY,
                                                null,
                                                "Dusting allowance revised",
                                                "PAY",
                                                List.of("STAFF"),
                                                true,
                                                List.of(),
                                                DAY,
                                                null,
                                                null,
                                                "r"),
                                        null,
                                        null))
                .isInstanceOfSatisfying(
                        FciException.class,
                        ex -> {
                            assertThat(ex.getErrorCode())
                                    .isEqualTo(ApiErrorCode.MASTER_UNKNOWN_VALUE);
                            assertThat(ex.getMessageArgs())
                                    .containsExactly(CircularService.AUTHORITY, "");
                        });
    }
}
