package in.gov.fci.payroll.arrear.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/** SRS-8-25 steps 5 to 8: the tiles and the DO-wise status report I-25 draws. */
class WageRevisionBoardServiceTest {

    private final NamedParameterJdbcTemplate named = mock(NamedParameterJdbcTemplate.class);
    private final Authorization policy = mock(Authorization.class);
    private final WageRevisionBoardService service = new WageRevisionBoardService(named, policy);

    private final AccessContext desk =
            new AccessContext(
                    1L, "u@x", "U", "s", "P", 7L, null, List.of(), Map.of(), Set.of(), Set.of(),
                    Set.of());

    private void events() {
        when(named.queryForMap(contains("FROM wage_revision"), any(SqlParameterSource.class)))
                .thenReturn(Map.of("N", 3L, "APPROVED", 1L, "SIMULATED", 2L));
    }

    @Test
    void SRS_8_25_withNoFixedRevisionOnlyTheEventsCount() {
        events();
        when(named.queryForList(contains("revision_status IN"), any(SqlParameterSource.class)))
                .thenReturn(List.of());
        assertThat(service.board(desk))
                .isEqualTo(
                        new WageRevisionBoardService.Board(
                                3, 1, 2, null, null, 0, 0, BigDecimal.ZERO, 0, 0));
        verify(policy).require(desk, "I-25", "VIEW");
    }

    @Test
    void SRS_8_25_theMemberFiguresAreThoseOfTheLatestFixedRevisionWithinTheOffices() {
        when(named.queryForMap(contains("COUNT(*) AS n"), any(SqlParameterSource.class)))
                .thenReturn(Map.of("N", 3L, "APPROVED", 1L, "SIMULATED", 2L));
        when(named.queryForList(contains("revision_status IN"), any(SqlParameterSource.class)))
                .thenReturn(
                        List.of(
                                Map.of(
                                        "WAGE_REVISION_ID",
                                        4L,
                                        "REVISION_CODE",
                                        "WR-2026-02",
                                        "REVISION_NAME",
                                        "IDA")));
        when(named.queryForMap(contains("AS exceptions"), any(SqlParameterSource.class)))
                .thenReturn(
                        Map.of("FIXED", 40L, "EXCEPTIONS", 5L, "PAID", new BigDecimal("1200.50")));
        ArgumentCaptor<SqlParameterSource> p = ArgumentCaptor.forClass(SqlParameterSource.class);
        when(named.queryForMap(contains("AS offices"), p.capture()))
                .thenReturn(Map.of("OFFICES", 4L, "DONE", 2L));
        assertThat(service.board(desk))
                .isEqualTo(
                        new WageRevisionBoardService.Board(
                                3,
                                1,
                                2,
                                "WR-2026-02",
                                "IDA",
                                40,
                                5,
                                new BigDecimal("1200.50"),
                                2,
                                4));
        assertThat(p.getValue().getValue("id")).isEqualTo(4L);
        assertThat(p.getValue().getValue("all")).isEqualTo("N");
    }

    @Test
    @SuppressWarnings("unchecked")
    void SRS_8_25_step8_theReportIsOnePageOfTheOffices() throws Exception {
        when(named.queryForObject(
                        contains("FROM wage_revision WHERE"),
                        any(SqlParameterSource.class),
                        eq(Long.class)))
                .thenReturn(1L);
        when(named.queryForObject(
                        contains("COUNT(DISTINCT x.office)"),
                        any(SqlParameterSource.class),
                        eq(Long.class)))
                .thenReturn(21L);
        ArgumentCaptor<RowMapper<WageRevisionBoardService.OfficeRow>> mapper =
                ArgumentCaptor.forClass(RowMapper.class);
        ArgumentCaptor<SqlParameterSource> p = ArgumentCaptor.forClass(SqlParameterSource.class);
        WageRevisionBoardService.OfficeRow row =
                new WageRevisionBoardService.OfficeRow("DO Karnal", 5, 4, 0, 1, 3);
        when(named.query(contains("GROUP BY x.office"), p.capture(), mapper.capture()))
                .thenReturn(List.of(row));
        Paged<WageRevisionBoardService.OfficeRow> page =
                service.offices(desk, 4, new PageSpec(1, 20));
        assertThat(page.items()).containsExactly(row);
        assertThat(p.getValue().getValue("offset")).isEqualTo(20L);
        assertThat(p.getValue().getValue("size")).isEqualTo(20);
        assertThat(p.getValue().getValue("id")).isEqualTo(4L);
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString(1)).thenReturn("DO Karnal");
        when(rs.getLong(2)).thenReturn(5L);
        when(rs.getLong(3)).thenReturn(4L);
        when(rs.getLong(4)).thenReturn(0L);
        when(rs.getLong(5)).thenReturn(1L);
        when(rs.getLong(6)).thenReturn(3L);
        assertThat(mapper.getValue().mapRow(rs, 0)).isEqualTo(row);
    }

    @Test
    void SRS_8_25_step8_anUnknownRevisionIsNotFound() {
        for (Long known : java.util.Arrays.asList(0L, null)) {
            when(named.queryForObject(anyString(), any(SqlParameterSource.class), eq(Long.class)))
                    .thenReturn(known);
            try {
                service.offices(desk, 9, new PageSpec(0, 20));
                throw new AssertionError("expected a refusal");
            } catch (FciException e) {
                assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.NOT_FOUND);
            }
        }
    }
}
