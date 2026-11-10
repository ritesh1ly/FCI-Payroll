package in.gov.fci.payroll.allowance.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.security.AccessContext;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/**
 * SRS-1-73, SRS l.2836-2838 (I-16 "for the financial year"): the selections of one financial year,
 * or of every year when none (or a blank one) is given.
 */
class CafeteriaQueryServiceBranchGapsTest {

    private final NamedParameterJdbcTemplate named = mock(NamedParameterJdbcTemplate.class);
    private final ClaimScope scope = mock(ClaimScope.class);
    private final SystemParameters settings = mock(SystemParameters.class);
    private final CafeteriaQueryService service = new CafeteriaQueryService(named, scope, settings);

    @Test
    void SRS_1_73_aBlankFinancialYearIsNoFilterAndAGivenOneFilters() {
        when(settings.today()).thenReturn(LocalDate.now(ZoneId.of("Asia/Kolkata")));
        when(scope.offices(any(), anyString(), anyString())).thenReturn(Optional.empty());
        when(named.queryForObject(
                        contains("SELECT COUNT(*)"), any(SqlParameterSource.class), eq(Long.class)))
                .thenReturn(0L);
        List<Object> sent = new ArrayList<>();
        for (String year : Arrays.asList(null, "  ", "2026-27")) {
            assertThat(
                            service.selections(
                                            mock(AccessContext.class),
                                            null,
                                            year,
                                            new PageSpec(0, 20))
                                    .items())
                    .isEmpty();
            ArgumentCaptor<MapSqlParameterSource> p =
                    ArgumentCaptor.forClass(MapSqlParameterSource.class);
            verify(named, atLeastOnce())
                    .query(anyString(), p.capture(), any(RowCallbackHandler.class));
            sent.add(p.getValue().getValue("finYear"));
        }
        assertThat(sent).containsExactly(null, null, "2026-27");
    }
}
