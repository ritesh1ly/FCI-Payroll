package in.gov.fci.payroll.general.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.export.ExportSink;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * C-08 (SRS-G-9, SRS l.1641-1678): the right is checked before any read, the run is the unit's and
 * then the scope's, a scope with no run is an empty list and never another's rows.
 */
class ComputationExceptionsTest {

    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final Authorization policy = mock(Authorization.class);
    private final ExceptionService worklist = mock(ExceptionService.class);
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final AccessContext context = mock(AccessContext.class);
    private final ComputationExceptions service =
            new ComputationExceptions(jdbc, policy, worklist, org);
    private final ComputationExceptions.Filter filter =
            new ComputationExceptions.Filter("202610", null, null);

    @Test
    void SRS_G_9_aRoleWithoutC08ReadsNothing() {
        doThrow(new FciException(CommonErrorCode.ACCESS_DENIED))
                .when(policy)
                .require(context, ComputationExceptions.SCREEN, "VIEW");
        assertThatThrownBy(() -> service.review(context, filter, new PageSpec(0, 20)))
                .isInstanceOf(FciException.class);
        assertThatThrownBy(() -> service.items(context, "202610", "C-02", "X", new PageSpec(0, 20)))
                .isInstanceOf(FciException.class);
        verifyNoInteractions(jdbc);
        verifyNoInteractions(worklist);
    }

    @Test
    void SRS_G_13_exportNeedsItsOwnRight() {
        doThrow(new FciException(CommonErrorCode.ACCESS_DENIED))
                .when(policy)
                .require(context, ComputationExceptions.SCREEN, "EXPORT");
        assertThatThrownBy(() -> service.exportSize(context, filter))
                .isInstanceOf(FciException.class);
        assertThatThrownBy(() -> service.export(context, filter, mock(ExportSink.class)))
                .isInstanceOf(FciException.class);
        verifyNoInteractions(jdbc);
    }

    @Test
    void SRS_G_9_noRunInScopeIsAnEmptyReview_neverAnotherOfficesRows() {
        when(context.dataScope()).thenReturn(Set.of(7L));
        ComputationExceptions.Review review = service.review(context, filter, new PageSpec(0, 20));
        assertThat(review.period()).isNull();
        assertThat(review.items()).isEmpty();
        assertThat(review.tiles().total()).isZero();
        assertThat(service.exportSize(context, filter)).isZero();
        assertThat(service.items(context, "202610", null, "X", new PageSpec(0, 20)).items())
                .isEmpty();
        verifyNoInteractions(worklist);
    }

    @Test
    void SRS_G_9_anEmptyDataScopeMatchesNoRow() {
        when(context.dataScope()).thenReturn(Set.of());
        service.review(context, filter, new PageSpec(0, 20));
        verify(jdbc)
                .queryForObject(
                        anyString(),
                        ArgumentMatchers.<MapSqlParameterSource>argThat(
                                p -> Set.of(-1L).equals(p.getValue("scope"))),
                        eq(String.class));
    }

    @Test
    void SRS_G_9_theSessionsOwnCycleUnitDecidesTheRunBeforeItsScope() {
        OrgLocation unit = mock(OrgLocation.class);
        when(unit.getId()).thenReturn(5L);
        when(context.locationId()).thenReturn(9L);
        when(context.dataScope()).thenReturn(Set.of(5L, 6L));
        when(org.cycleUnitOf(9L)).thenReturn(Optional.of(unit));
        service.review(context, filter, new PageSpec(0, 20));
        verify(jdbc)
                .queryForObject(
                        anyString(),
                        ArgumentMatchers.<MapSqlParameterSource>argThat(
                                p -> Set.of(5L).equals(p.getValue("scope"))),
                        eq(String.class));
        verify(jdbc)
                .queryForObject(
                        anyString(),
                        ArgumentMatchers.<MapSqlParameterSource>argThat(
                                p -> Set.of(5L, 6L).equals(p.getValue("scope"))),
                        eq(String.class));
    }

    @Test
    void SRS_l1678_theExportOfNoRunHasTheColumnsAndNoRow() throws Exception {
        when(context.dataScope()).thenReturn(Set.of(7L));
        ExportSink sink = mock(ExportSink.class);
        when(sink.rows()).thenReturn(0L);
        service.export(context, filter, sink);
        verify(sink).header(any(String[].class));
    }

    @Test
    void SRS_G_9_aSearchMatchesItsOwnWildcardsLiterally() {
        assertThat(ComputationExceptions.like("A_b%c!")).isEqualTo("a!_b!%c!!");
    }
}
