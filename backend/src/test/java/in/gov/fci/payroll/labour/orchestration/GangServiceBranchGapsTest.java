package in.gov.fci.payroll.labour.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.sql.Date;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * M-09 gang list (SRS-2-31): a gang ended on a date shows that end date; one still running shows
 * none; a gang without a sanctioned strength in force shows no strength.
 */
class GangServiceBranchGapsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 28);

    private final NamedParameterJdbcTemplate named = mock(NamedParameterJdbcTemplate.class);
    private final SystemParameters parameters = mock(SystemParameters.class);
    private final GangService service =
            new GangService(
                    mock(JdbcTemplate.class),
                    named,
                    mock(Authorization.class),
                    mock(OrgDirectory.class),
                    mock(MakerChecker.class),
                    parameters,
                    mock(AuditService.class),
                    JsonMapper.builder().build());

    private static ResultSet gang(long id, LocalDate to, boolean strength) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong("gang_id")).thenReturn(id);
        when(rs.getString("gang_code")).thenReturn("DP-DL01-G00" + id);
        when(rs.getString("gang_name")).thenReturn("Okhla Gang " + id);
        when(rs.getString("location_code")).thenReturn("DP-DL01");
        when(rs.getString("location_name")).thenReturn("Okhla");
        when(rs.getString("payroll_type")).thenReturn("DEPT_LABOUR");
        when(rs.getString("gang_type")).thenReturn("HANDLING");
        when(rs.getString("gang_status")).thenReturn(to == null ? "ACTIVE" : "INACTIVE");
        when(rs.getInt("strength")).thenReturn(strength ? 25 : 0);
        when(rs.wasNull()).thenReturn(!strength);
        when(rs.getLong("members")).thenReturn(20L);
        when(rs.getDate("effective_from")).thenReturn(Date.valueOf(LocalDate.of(2025, 7, 1)));
        when(rs.getDate("effective_to")).thenReturn(to == null ? null : Date.valueOf(to));
        return rs;
    }

    @Test
    @SuppressWarnings("unchecked")
    void SRS_2_31_anEndedGangShowsItsEndDateAndARunningOneNone() throws Exception {
        when(parameters.today()).thenReturn(TODAY);
        AccessContext context = mock(AccessContext.class);
        when(context.locationId()).thenReturn(3L);
        when(named.queryForObject(
                        contains("SELECT COUNT(*)"),
                        any(MapSqlParameterSource.class),
                        eq(Long.class)))
                .thenReturn(2L);
        ResultSet ended = gang(1L, LocalDate.of(2026, 9, 30), false);
        ResultSet running = gang(2L, null, true);
        doAnswer(
                        inv -> {
                            RowMapper<GangService.Row> m = inv.getArgument(2);
                            return List.of(m.mapRow(ended, 0), m.mapRow(running, 1));
                        })
                .when(named)
                .query(
                        contains("ORDER BY l.location_code"),
                        any(MapSqlParameterSource.class),
                        any(RowMapper.class));

        var page = service.page(context, 7L, new PageSpec(0, 20));

        assertThat(page.items())
                .extracting(GangService.Row::effectiveTo)
                .containsExactly(LocalDate.of(2026, 9, 30), null);
        assertThat(page.items())
                .extracting(GangService.Row::sanctionedStrength)
                .containsExactly(null, 25);
        assertThat(page.items().get(0).depot()).isEqualTo("DP-DL01 - Okhla");
    }
}
