package in.gov.fci.payroll.general.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.export.ExportReader;
import in.gov.fci.payroll.platform.export.ExportSink;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** X-07 (SRS-G-47, BR-INT-011, Annexure A.11; API-01-1): the reconciliation table and the tile. */
class FallbackReconciliationServiceTest {

    private static final Set<Long> SCOPE = Set.of(300L);

    private final NamedParameterJdbcTemplate named = mock(NamedParameterJdbcTemplate.class);
    private final Authorization policy = mock(Authorization.class);
    private final ExportReader exports = mock(ExportReader.class);
    private final FallbackReconciliationService service =
            new FallbackReconciliationService(named, policy, exports);

    private final AccessContext office = context(SCOPE);

    private static AccessContext context(Set<Long> scope) {
        return new AccessContext(
                3L,
                "hr.delhi",
                "HR",
                "s",
                "P",
                300L,
                null,
                List.of(),
                Map.of(),
                Set.of(),
                Set.of(),
                scope);
    }

    private static ResultSet line() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("record_type")).thenReturn("ATTENDANCE_LEAVE");
        when(rs.getString("hrms_summary")).thenReturn("LWP_DAYS: 3");
        when(rs.getBigDecimal("lwp_days")).thenReturn(new BigDecimal("2.00"));
        when(rs.getString("reference_no")).thenReturn("FBE-2026-005");
        when(rs.getString("yymm")).thenReturn("202606");
        when(rs.getString("emp_no")).thenReturn("106004");
        when(rs.getString("first_name")).thenReturn("Prakash");
        when(rs.getString("last_name")).thenReturn("Nayak");
        when(rs.getString("effect")).thenReturn("RECOVERY");
        when(rs.getString("next_cycle")).thenReturn("202607");
        when(rs.getString("status")).thenReturn("DIFFERENCE");
        return rs;
    }

    @Test
    void SRS_G_47_aDifferenceComesBackLaidOutOverTheScope() throws Exception {
        ResultSet rs = line();
        when(named.queryForObject(
                        contains("COUNT(*)"), any(MapSqlParameterSource.class), eq(Long.class)))
                .thenReturn(21L);
        when(named.query(
                        contains("FETCH NEXT"),
                        any(MapSqlParameterSource.class),
                        org.mockito.ArgumentMatchers
                                .<RowMapper<FallbackReconciliationService.Row>>any()))
                .thenAnswer(
                        call -> {
                            RowMapper<FallbackReconciliationService.Row> mapper =
                                    call.getArgument(2);
                            return List.of(mapper.mapRow(rs, 0));
                        });

        Paged<FallbackReconciliationService.Row> page = service.page(office, new PageSpec(1, 20));

        verify(policy).require(office, "X-07", "VIEW");
        assertThat(page.total()).isEqualTo(21L);
        FallbackReconciliationService.Row row = page.items().get(0);
        assertThat(row.reference()).isEqualTo("FBE-2026-005");
        assertThat(row.employeeName()).isEqualTo("Prakash Nayak");
        assertThat(row.item()).isEqualTo("LWP_DAYS");
        assertThat(row.uploaded()).isEqualTo("2");
        assertThat(row.hrms()).isEqualTo("3");
        assertThat(row.difference()).isEqualTo("1");
        assertThat(row.effect()).isEqualTo("RECOVERY");
        assertThat(row.nextCycleYymm()).isEqualTo("202607");
        assertThat(row.status()).isEqualTo("DIFFERENCE");
        ArgumentCaptor<MapSqlParameterSource> params =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(named)
                .query(
                        anyString(),
                        params.capture(),
                        org.mockito.ArgumentMatchers
                                .<RowMapper<FallbackReconciliationService.Row>>any());
        assertThat(params.getValue().getValue("scope")).isEqualTo(SCOPE);
        assertThat(params.getValue().getValue("offset")).isEqualTo(20L);
        assertThat(params.getValue().getValue("size")).isEqualTo(20);
    }

    @Test
    void SRS_G_47_aFoldedRowHasNoNameAndAnEventKeepsItsDateAndAnEffectDefaultsToNone()
            throws Exception {
        ResultSet folded = mock(ResultSet.class);
        when(folded.getString("record_type")).thenReturn("ATTENDANCE_LEAVE");
        when(folded.getLong("others")).thenReturn(5L);
        when(folded.getString("effect")).thenReturn("NONE");
        when(folded.getString("status")).thenReturn("CLOSED");
        ResultSet event = mock(ResultSet.class);
        when(event.getString("record_type")).thenReturn("REVOCATION");
        when(event.getString("hrms_summary")).thenReturn("REVOCATION: 2026-07-01");
        when(event.getDate("event_date")).thenReturn(Date.valueOf(LocalDate.of(2026, 7, 1)));
        when(event.getString("first_name")).thenReturn("Ritu");
        when(event.getString("status")).thenReturn("MATCHED");
        when(named.queryForObject(
                        contains("COUNT(*)"), any(MapSqlParameterSource.class), eq(Long.class)))
                .thenReturn(2L);
        when(named.query(
                        contains("FETCH NEXT"),
                        any(MapSqlParameterSource.class),
                        org.mockito.ArgumentMatchers
                                .<RowMapper<FallbackReconciliationService.Row>>any()))
                .thenAnswer(
                        call -> {
                            RowMapper<FallbackReconciliationService.Row> mapper =
                                    call.getArgument(2);
                            return List.of(mapper.mapRow(folded, 0), mapper.mapRow(event, 1));
                        });

        List<FallbackReconciliationService.Row> rows =
                service.page(office, new PageSpec(0, 20)).items();

        assertThat(rows.get(0).others()).isEqualTo(5L);
        assertThat(rows.get(0).employeeName()).isNull();
        assertThat(rows.get(0).item()).isEqualTo("ALL");
        assertThat(rows.get(1).employeeName()).isEqualTo("Ritu");
        assertThat(rows.get(1).uploaded()).isEqualTo("2026-07-01");
        assertThat(rows.get(1).hrms()).isEqualTo("2026-07-01");
        assertThat(rows.get(1).effect()).isEqualTo("NONE");
    }

    @Test
    void SRS_G_47_anEmptyDataScopeReadsNoOffice() {
        when(named.queryForObject(
                        contains("COUNT(*)"), any(MapSqlParameterSource.class), eq(Long.class)))
                .thenReturn(0L);
        when(named.query(
                        contains("FETCH NEXT"),
                        any(MapSqlParameterSource.class),
                        org.mockito.ArgumentMatchers
                                .<RowMapper<FallbackReconciliationService.Row>>any()))
                .thenReturn(List.of());

        assertThat(service.page(context(Set.of()), new PageSpec(0, 20)).total()).isZero();

        ArgumentCaptor<MapSqlParameterSource> params =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(named).queryForObject(anyString(), params.capture(), eq(Long.class));
        assertThat(params.getValue().getValue("scope")).isEqualTo(Scopes.nothing());
    }

    @Test
    void R_RBAC_aRoleWithoutTheRightIsRefusedBeforeAnyRead() {
        FciException denied = new FciException(ApiErrorCode.OUT_OF_SCOPE);
        doThrow(denied).when(policy).require(office, "X-07", "VIEW");
        doThrow(denied).when(policy).require(office, "X-07", "EXPORT");

        assertThatThrownBy(() -> service.page(office, new PageSpec(0, 20))).isSameAs(denied);
        assertThatThrownBy(() -> service.interfaceStatus(office)).isSameAs(denied);
        assertThatThrownBy(() -> service.exportSize(office)).isSameAs(denied);
        assertThatThrownBy(() -> service.export(office, mock(ExportSink.class))).isSameAs(denied);
        org.mockito.Mockito.verifyNoInteractions(named);
    }

    @Test
    void SRS_G_13_theExportWritesEveryRowOfTheSnapshotAndChecksItsCount() throws IOException {
        when(named.queryForObject(
                        contains("COUNT(*)"), any(MapSqlParameterSource.class), eq(Long.class)))
                .thenReturn(1L);
        FallbackReconciliationService.Row row =
                new FallbackReconciliationService.Row(
                        "FBE-2026-005",
                        "202606",
                        "106004",
                        "Prakash Nayak",
                        0,
                        "LWP_DAYS",
                        "2",
                        "3",
                        "RECOVERY",
                        "1",
                        "202607",
                        "DIFFERENCE");
        when(named.query(
                        contains("FETCH NEXT"),
                        any(MapSqlParameterSource.class),
                        org.mockito.ArgumentMatchers
                                .<RowMapper<FallbackReconciliationService.Row>>any()))
                .thenReturn(List.of(row));
        when(exports.readAll(any(), any()))
                .thenAnswer(
                        call -> {
                            Function<Pageable, Page<FallbackReconciliationService.Row>> query =
                                    call.getArgument(0);
                            ExportReader.Batch<FallbackReconciliationService.Row> batch =
                                    call.getArgument(1);
                            Page<FallbackReconciliationService.Row> page =
                                    query.apply(PageRequest.of(0, 500));
                            batch.handle(page.getContent());
                            return page.getTotalElements();
                        });
        ExportSink sink = mock(ExportSink.class);
        when(sink.rows()).thenReturn(1L);

        assertThat(service.exportSize(office)).isEqualTo(1L);
        service.export(office, sink);

        verify(policy, org.mockito.Mockito.times(2)).require(office, "X-07", "EXPORT");
        verify(sink)
                .row(
                        "FBE-2026-005",
                        "202606",
                        "106004",
                        "Prakash Nayak",
                        0L,
                        "LWP_DAYS",
                        "2",
                        "3",
                        "RECOVERY",
                        "1",
                        "202607",
                        "DIFFERENCE");
    }

    private void since(Instant breaker, Instant declared) {
        when(named.queryForObject(
                        contains("interface_breaker_state"),
                        any(MapSqlParameterSource.class),
                        eq(Timestamp.class)))
                .thenReturn(breaker == null ? null : Timestamp.from(breaker));
        when(named.queryForObject(
                        contains("hrms_down_since"),
                        any(MapSqlParameterSource.class),
                        eq(Timestamp.class)))
                .thenReturn(declared == null ? null : Timestamp.from(declared));
    }

    @Test
    void BR_INT_011_hrmsIsUpWithNoOpenBreakerAndNoDeclaredOutage() {
        since(null, null);
        assertThat(service.interfaceStatus(office))
                .isEqualTo(new FallbackReconciliationService.InterfaceStatus(false, null));
    }

    @Test
    void BR_INT_011_hrmsIsDownSinceTheEarlierOfTheBreakerAndTheDeclaredOutage() {
        Instant early = Instant.parse("2026-07-27T16:40:00Z");
        Instant late = Instant.parse("2026-07-28T04:30:00Z");
        since(late, early);
        assertThat(service.interfaceStatus(office).since()).isEqualTo(early);
        since(early, late);
        assertThat(service.interfaceStatus(office).since()).isEqualTo(early);
        since(early, null);
        assertThat(service.interfaceStatus(office).down()).isTrue();
        since(null, late);
        assertThat(service.interfaceStatus(office).since()).isEqualTo(late);
    }
}
