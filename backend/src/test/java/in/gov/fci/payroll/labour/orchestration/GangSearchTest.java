package in.gov.fci.payroll.labour.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.sql.Date;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * The gang pickers' server search (I-01 l.4541, I-02 Gang Name, SRS-2-31, SRS-2-46) and a gang's
 * members on a day (I-02 l.4632, SRS-2-2): every criterion is bound (blank text is no criterion,
 * text matches in any case), and a dated read leaves out labour past retirement that day.
 */
class GangSearchTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 28);

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final NamedParameterJdbcTemplate named = mock(NamedParameterJdbcTemplate.class);
    private final Authorization policy = mock(Authorization.class);
    private final SystemParameters parameters = mock(SystemParameters.class);
    private final AccessContext context = mock(AccessContext.class);
    private final GangService service =
            new GangService(
                    jdbc,
                    named,
                    policy,
                    mock(OrgDirectory.class),
                    mock(MakerChecker.class),
                    parameters,
                    mock(AuditService.class),
                    JsonMapper.builder().build());

    @BeforeEach
    void wire() {
        when(parameters.today()).thenReturn(TODAY);
        when(context.locationId()).thenReturn(3L);
    }

    private MapSqlParameterSource searched(GangService.Search search) {
        when(named.queryForObject(
                        contains("SELECT COUNT(*)"),
                        any(MapSqlParameterSource.class),
                        eq(Long.class)))
                .thenReturn(0L);
        service.page(context, search, new PageSpec(0, 20));
        ArgumentCaptor<MapSqlParameterSource> p =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(named)
                .queryForObject(
                        contains("UPPER(g.gang_name) LIKE :text"), p.capture(), eq(Long.class));
        return p.getValue();
    }

    @Test
    void I_01_everyCriterionIsBoundAndTheTextMatchesTheCodeOrNameInAnyCase() {
        MapSqlParameterSource p =
                searched(
                        new GangService.Search(
                                7L, "DEPT_LABOUR", "ANCILLARY_POOL", "ACTIVE", " okhla gang "));
        assertThat(p.getValue("depot")).isEqualTo(7L);
        assertThat(p.getValue("type")).isEqualTo("DEPT_LABOUR");
        assertThat(p.getValue("gangType")).isEqualTo("ANCILLARY_POOL");
        assertThat(p.getValue("status")).isEqualTo("ACTIVE");
        assertThat(p.getValue("text")).isEqualTo("%OKHLA GANG%");
    }

    @Test
    void I_01_aBlankOrMissingCriterionIsNoCriterionAsTheOldDepotOnlyCall() {
        MapSqlParameterSource blank =
                searched(new GangService.Search(null, null, null, null, "   "));
        assertThat(blank.getValue("text")).isNull();
        assertThat(blank.getValue("depot")).isNull();
        assertThat(blank.getValue("type")).isNull();
        assertThat(blank.getValue("gangType")).isNull();
        assertThat(blank.getValue("status")).isNull();
    }

    @Test
    void I_01_theDepotOnlyPageIsTheSearchWithoutCriteria() {
        when(named.queryForObject(
                        contains("SELECT COUNT(*)"),
                        any(MapSqlParameterSource.class),
                        eq(Long.class)))
                .thenReturn(0L);
        service.page(context, 7L, new PageSpec(0, 20));
        ArgumentCaptor<MapSqlParameterSource> p =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(named).queryForObject(contains("SELECT COUNT(*)"), p.capture(), eq(Long.class));
        assertThat(p.getValue().getValue("depot")).isEqualTo(7L);
        assertThat(p.getValue().getValue("text")).isNull();
    }

    private void gang() {
        Map<String, Object> g = new HashMap<>();
        g.put("GANG_ID", 5L);
        g.put("DEPOT_LOCATION_ID", 7L);
        g.put("GANG_TYPE", "ANCILLARY_POOL");
        g.put("GANG_STATUS", "ACTIVE");
        g.put("PAYROLL_TYPE", "DEPT_LABOUR");
        when(jdbc.queryForList(contains("FROM labour_gang WHERE gang_id"), any(Object[].class)))
                .thenReturn(List.of(g));
    }

    @Test
    @SuppressWarnings("unchecked")
    void I_02_membersOnTheSlipDateLeaveOutTheRetiredAndTodayKeepsTheRoster() {
        gang();
        LocalDate slip = LocalDate.of(2026, 8, 14);
        service.members(context, 5L, slip);
        verify(jdbc)
                .query(
                        contains("e.retirement_date >= ?"),
                        any(RowMapper.class),
                        eq(5L),
                        eq(Date.valueOf(slip)),
                        eq(Date.valueOf(slip)),
                        eq("Y"),
                        eq(Date.valueOf(slip)));
        service.members(context, 5L);
        verify(jdbc)
                .query(
                        contains("e.retirement_date >= ?"),
                        any(RowMapper.class),
                        eq(5L),
                        eq(Date.valueOf(TODAY)),
                        eq(Date.valueOf(TODAY)),
                        eq("N"),
                        eq(Date.valueOf(TODAY)));
        verify(policy, org.mockito.Mockito.times(2)).requireRecordAt(context, "M-09", "VIEW", 7L);
    }

    // SRS-2-6 / OD-88: a transfer target is any office's active handling gang of the payroll type.
    @Test
    void SRS_2_6_transferTargetsSearchEveryOfficeForActiveHandlingGangsOfThePayrollType() {
        when(named.queryForObject(
                        contains("SELECT COUNT(*)"),
                        any(MapSqlParameterSource.class),
                        eq(Long.class)))
                .thenReturn(0L);
        service.transferTargets(context, "NWNP_LABOUR", "g5", new PageSpec(0, 20));
        ArgumentCaptor<MapSqlParameterSource> p =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(named).queryForObject(contains("SELECT COUNT(*)"), p.capture(), eq(Long.class));
        assertThat(p.getValue().getValue("all")).isEqualTo("Y");
        assertThat(p.getValue().getValue("type")).isEqualTo("NWNP_LABOUR");
        assertThat(p.getValue().getValue("gangType")).isEqualTo("HANDLING");
        assertThat(p.getValue().getValue("status")).isEqualTo("ACTIVE");
        assertThat(p.getValue().getValue("text")).isEqualTo("%G5%");
        verify(policy).require(context, "M-02", "EDIT");
        for (String wrong : new String[] {null, "STAFF"}) {
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () ->
                                    service.transferTargets(
                                            context, wrong, null, new PageSpec(0, 20)))
                    .isInstanceOf(in.gov.fci.payroll.platform.error.FciException.class);
        }
    }
}
