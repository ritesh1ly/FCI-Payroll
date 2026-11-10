package in.gov.fci.payroll.configuration.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.configuration.api.ReferenceData;
import in.gov.fci.payroll.platform.approval.ApprovalStatuses;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * M-16 (SRS-16-15): the catalogue as the Administrator reads it from the database rows: a value
 * starting later or already ended is listed but not in force, an absent sort order, end date or
 * change time stays absent.
 */
class ReferenceListServiceRowsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 24);
    private static final Instant CHANGED = Instant.parse("2026-09-01T00:00:00Z");

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ReferenceData reference = mock(ReferenceData.class);
    private final ApprovalStatuses statuses = mock(ApprovalStatuses.class);
    private final ReferenceListService service =
            new ReferenceListService(
                    jdbc,
                    reference,
                    mock(MakerChecker.class),
                    statuses,
                    mock(Authorization.class),
                    JsonMapper.builder().build(),
                    Clock.fixed(TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC));

    private final AccessContext admin =
            new AccessContext(
                    1L,
                    "admin@x",
                    "Admin",
                    "s",
                    "P",
                    1L,
                    null,
                    List.of(),
                    Map.of(),
                    Set.of(),
                    Set.of(),
                    Set.of(1L));

    private static ResultSet head(String code) throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString(1)).thenReturn(code);
        when(rs.getString(2)).thenReturn("Leave reason");
        when(rs.getString(3)).thenReturn("M-16");
        when(rs.getString(4)).thenReturn("N");
        return rs;
    }

    private static ResultSet value(
            String code, Integer sort, LocalDate from, LocalDate to, Instant changedAt)
            throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString(1)).thenReturn("LEAVE_REASON");
        when(rs.getLong(2)).thenReturn(11L);
        when(rs.getString(3)).thenReturn(code);
        when(rs.getString(4)).thenReturn(code + " label");
        when(rs.getInt(5)).thenReturn(sort == null ? 0 : sort);
        when(rs.wasNull()).thenReturn(sort == null);
        when(rs.getDate(6)).thenReturn(Date.valueOf(from));
        when(rs.getDate(7)).thenReturn(to == null ? null : Date.valueOf(to));
        when(rs.getInt(8)).thenReturn(1);
        when(rs.getString(9)).thenReturn("admin@x");
        when(rs.getTimestamp(10)).thenReturn(changedAt == null ? null : Timestamp.from(changedAt));
        return rs;
    }

    private void database(ResultSet head, List<ResultSet> values) {
        doAnswer(
                        invocation -> {
                            RowCallbackHandler each = invocation.getArgument(1);
                            each.processRow(head);
                            return null;
                        })
                .when(jdbc)
                .query(startsWith("SELECT list_code"), any(RowCallbackHandler.class));
        doAnswer(
                        invocation -> {
                            RowCallbackHandler each = invocation.getArgument(1);
                            for (ResultSet row : values) {
                                each.processRow(row);
                            }
                            return null;
                        })
                .when(jdbc)
                .query(startsWith("SELECT l.list_code"), any(RowCallbackHandler.class));
    }

    @Test
    void SRS_16_15_theCatalogueListsEachValueWithItsWindowAndWhetherItIsInForceToday()
            throws SQLException {
        when(reference.today()).thenReturn(TODAY);
        when(statuses.pending(ReferenceListService.ENTITY)).thenReturn(List.of());
        database(
                head("LEAVE_REASON"),
                List.of(
                        value("SOON", null, TODAY.plusDays(1), null, null),
                        value("OLD", 2, TODAY.minusDays(30), TODAY.minusDays(1), CHANGED),
                        value("MED", 1, TODAY, null, null)));

        List<ReferenceListService.ListRow> lists = service.lists(admin);

        assertThat(lists).hasSize(1);
        assertThat(lists.get(0).values())
                .extracting(
                        ReferenceListService.ValueRow::code,
                        ReferenceListService.ValueRow::sortOrder,
                        ReferenceListService.ValueRow::effectiveTo,
                        ReferenceListService.ValueRow::active,
                        ReferenceListService.ValueRow::changedAt)
                .containsExactly(
                        tuple("SOON", null, null, false, null),
                        tuple("OLD", 2, TODAY.minusDays(1), false, CHANGED),
                        tuple("MED", 1, null, true, null));
    }

    @Test
    void SRS_16_15_aValueEndingTodayOrLaterIsInForceUntilItsLastDay() throws SQLException {
        when(reference.today()).thenReturn(TODAY);
        when(statuses.pending(ReferenceListService.ENTITY)).thenReturn(List.of());
        database(
                head("LEAVE_REASON"),
                List.of(
                        value("LAST", 1, TODAY.minusDays(5), TODAY, null),
                        value("LATER", 2, TODAY.minusDays(5), TODAY.plusDays(9), null)));

        assertThat(service.lists(admin).get(0).values())
                .extracting(
                        ReferenceListService.ValueRow::code, ReferenceListService.ValueRow::active)
                .containsExactly(tuple("LAST", true), tuple("LATER", true));
    }

    @Test
    void aValueRowWithoutASortOrderOrAnEndDateIsReadAsSuch() throws SQLException {
        ResultSet open = mock(ResultSet.class);
        when(open.getLong(1)).thenReturn(11L);
        when(open.getString(2)).thenReturn("Medical");
        when(open.wasNull()).thenReturn(true);
        when(open.getDate(4)).thenReturn(Date.valueOf(TODAY));
        ResultSet ended = mock(ResultSet.class);
        when(ended.getLong(1)).thenReturn(12L);
        when(ended.getString(2)).thenReturn("Casual");
        when(ended.getInt(3)).thenReturn(4);
        when(ended.getDate(4)).thenReturn(Date.valueOf(TODAY.minusDays(30)));
        when(ended.getDate(5)).thenReturn(Date.valueOf(TODAY.minusDays(1)));
        when(jdbc.query(
                        startsWith("SELECT value_id"),
                        ArgumentMatchers.<RowMapper<ReferenceListService.ValueState>>any(),
                        eq(3L),
                        eq("MED")))
                .thenAnswer(
                        invocation -> {
                            RowMapper<ReferenceListService.ValueState> mapper =
                                    invocation.getArgument(1);
                            return List.of(mapper.mapRow(open, 0), mapper.mapRow(ended, 1));
                        });

        assertThat(service.value(3L, "MED"))
                .hasValueSatisfying(
                        v -> {
                            assertThat(v.valueId()).isEqualTo(11L);
                            assertThat(v.sortOrder()).isNull();
                            assertThat(v.effectiveFrom()).isEqualTo(TODAY);
                            assertThat(v.effectiveTo()).isNull();
                        });
        when(jdbc.query(
                        startsWith("SELECT value_id"),
                        ArgumentMatchers.<RowMapper<ReferenceListService.ValueState>>any(),
                        eq(3L),
                        eq("CL")))
                .thenAnswer(
                        invocation -> {
                            RowMapper<ReferenceListService.ValueState> mapper =
                                    invocation.getArgument(1);
                            return List.of(mapper.mapRow(ended, 0));
                        });
        assertThat(service.value(3L, "CL"))
                .hasValueSatisfying(
                        v -> {
                            assertThat(v.sortOrder()).isEqualTo(4);
                            assertThat(v.effectiveTo()).isEqualTo(TODAY.minusDays(1));
                        });
    }
}
