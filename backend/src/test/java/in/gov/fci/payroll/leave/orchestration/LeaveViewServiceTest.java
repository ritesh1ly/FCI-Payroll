package in.gov.fci.payroll.leave.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.platform.export.ExportIncompleteException;
import in.gov.fci.payroll.platform.export.ExportSink;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.io.IOException;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/**
 * I-11 leave events (plan 19; SRS-LV-26, SRS I-11 field table): each event shows its member,
 * office, period and whether it adds or cancels leave; a member without a name, an office or dates
 * still shows; blank filters read every event.
 */
class LeaveViewServiceTest {

    private final NamedParameterJdbcTemplate named = mock(NamedParameterJdbcTemplate.class);
    private final OrgDirectory directory = mock(OrgDirectory.class);
    private final LeaveViewService service =
            new LeaveViewService(
                    named,
                    mock(Authorization.class),
                    mock(LeaveParameters.class),
                    directory,
                    Clock.fixed(Instant.parse("2026-10-27T05:00:00Z"), ZoneOffset.UTC));
    private final AccessContext context =
            new AccessContext(
                    1L,
                    "u@x",
                    "U",
                    "s",
                    "P",
                    10L,
                    null,
                    List.of(),
                    Map.of(),
                    Set.of(),
                    Set.of(),
                    Set.of(10L));

    private static ResultSet row(boolean full) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong("application_id")).thenReturn(1L);
        when(rs.getString("first_name")).thenReturn(full ? "Asha" : null);
        when(rs.getString("last_name")).thenReturn(full ? "Rao" : null);
        when(rs.getString("location_code")).thenReturn(full ? "DH001" : null);
        when(rs.getString("location_name")).thenReturn("Delhi");
        when(rs.getDate("applied_from"))
                .thenReturn(full ? Date.valueOf(LocalDate.of(2026, 10, 1)) : null);
        when(rs.getDate("applied_to"))
                .thenReturn(full ? Date.valueOf(LocalDate.of(2026, 10, 2)) : null);
        when(rs.getTimestamp("received_at"))
                .thenReturn(full ? Timestamp.from(Instant.parse("2026-10-03T05:00:00Z")) : null);
        when(rs.getString("decision")).thenReturn(full ? "CANCELLED" : "SANCTIONED");
        return rs;
    }

    @Test
    void SRS_LV_26_eachEventShowsItsMemberOfficePeriodAndWhetherItAddsOrCancels() throws Exception {
        when(named.queryForObject(
                        contains("SELECT COUNT(*)"), any(SqlParameterSource.class), eq(Long.class)))
                .thenReturn(2L);
        when(named.query(
                        contains("SELECT a.application_id"),
                        any(SqlParameterSource.class),
                        org.mockito.ArgumentMatchers.<RowMapper<LeaveViewService.EventRow>>any()))
                .thenAnswer(
                        call -> {
                            RowMapper<?> m = call.getArgument(2);
                            return List.of(m.mapRow(row(true), 0), m.mapRow(row(false), 1));
                        });
        var page =
                service.events(
                        context,
                        new LeaveViewService.Filter(" ", " 104882 ", null),
                        new PageSpec(0, 20));
        LeaveViewService.EventRow full = page.items().get(0);
        LeaveViewService.EventRow bare = page.items().get(1);
        assertThat(full.name()).isEqualTo("Asha Rao");
        assertThat(full.office()).isEqualTo("DH001 - Delhi");
        assertThat(full.eventType()).isEqualTo("CANCELLATION");
        assertThat(full.from()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(bare.name()).isEmpty();
        assertThat(bare.office()).isNull();
        assertThat(bare.eventType()).isEqualTo("ADDITION");
        assertThat(bare.from()).isNull();
        assertThat(bare.received()).isNull();
    }

    /** An applied event: its wage effect, back-dated or not, its period month and approval. */
    private static ResultSet applied(
            long id, Long location, String effect, boolean backDated, String approvedAt)
            throws Exception {
        ResultSet rs = row(true);
        when(rs.getLong("application_id")).thenReturn(id);
        when(rs.getLong("location_id")).thenReturn(location == null ? 0L : location);
        when(rs.wasNull()).thenReturn(location == null);
        when(rs.getString("wage_effect_code")).thenReturn(effect);
        when(rs.getString("is_back_dated")).thenReturn(backDated ? "Y" : "N");
        when(rs.getDate("period_date")).thenReturn(Date.valueOf(LocalDate.of(2026, 9, 1)));
        when(rs.getTimestamp("applied_at"))
                .thenReturn(approvedAt == null ? null : Timestamp.from(Instant.parse(approvedAt)));
        return rs;
    }

    /**
     * SRS I-11 Pay effect and Applied in (US-GAP-59; SRS-LV-33, BR-LEAVE-006, BR-LEAVE-007,
     * BR-LEAVE-010): an open month's event applies in its own month with the LWP rule; a back-dated
     * one in the open cycle at its approval, the month after when that month was locked first; an
     * event not yet applied shows neither.
     */
    @Test
    void SRS_LV_33_eachAppliedEventShowsTheRuleAndTheCycleItAppliesIn() throws Exception {
        OrgLocation unit = mock(OrgLocation.class);
        when(unit.getId()).thenReturn(50L);
        when(directory.cycleUnitOf(10L)).thenReturn(Optional.of(unit));
        when(directory.cycleUnitOf(11L)).thenReturn(Optional.empty());
        when(named.queryForObject(
                        contains("SELECT COUNT(*)"), any(SqlParameterSource.class), eq(Long.class)))
                .thenReturn(5L);
        when(named.query(
                        contains("SELECT a.application_id"),
                        any(SqlParameterSource.class),
                        org.mockito.ArgumentMatchers.<RowMapper<LeaveViewService.EventRow>>any()))
                .thenAnswer(
                        call -> {
                            RowMapper<?> m = call.getArgument(2);
                            return List.of(
                                    m.mapRow(applied(1, 10L, "FULL_DEDUCTION", false, null), 0),
                                    m.mapRow(
                                            applied(
                                                    2,
                                                    10L,
                                                    "HALF_PAY",
                                                    true,
                                                    "2026-10-20T06:00:00Z"),
                                            1),
                                    m.mapRow(
                                            applied(
                                                    3,
                                                    10L,
                                                    "FULL_DEDUCTION",
                                                    true,
                                                    "2026-11-05T06:00:00Z"),
                                            2),
                                    m.mapRow(
                                            applied(
                                                    4,
                                                    11L,
                                                    "FULL_DEDUCTION",
                                                    true,
                                                    "2026-10-20T06:00:00Z"),
                                            3),
                                    m.mapRow(row(true), 4));
                        });
        org.mockito.Mockito.doAnswer(
                        call -> {
                            RowCallbackHandler h = call.getArgument(2);
                            ResultSet lock = mock(ResultSet.class);
                            when(lock.getLong(1)).thenReturn(50L);
                            when(lock.getString(2)).thenReturn("202610");
                            when(lock.getTimestamp(3))
                                    .thenReturn(
                                            Timestamp.from(Instant.parse("2026-10-15T06:00:00Z")));
                            h.processRow(lock);
                            return null;
                        })
                .when(named)
                .query(
                        contains("FROM payroll_cycle_instance"),
                        any(SqlParameterSource.class),
                        any(RowCallbackHandler.class));
        var rows =
                service.events(
                                context,
                                new LeaveViewService.Filter(null, null, null),
                                new PageSpec(0, 20))
                        .items();
        assertThat(rows.get(0).appliedIn()).isEqualTo("2026-09");
        assertThat(rows.get(0).rule()).isEqualTo("BR-LEAVE-007");
        // October was locked on the 15th, before the approval on the 20th: November.
        assertThat(rows.get(1).appliedIn()).isEqualTo("2026-11");
        assertThat(rows.get(1).rule()).isEqualTo("BR-LEAVE-010");
        // November not locked: the approval month.
        assertThat(rows.get(2).appliedIn()).isEqualTo("2026-11");
        // A member whose unit is not known: the approval month.
        assertThat(rows.get(3).appliedIn()).isEqualTo("2026-10");
        assertThat(rows.get(4).appliedIn()).isNull();
        assertThat(rows.get(4).rule()).isNull();
    }

    /**
     * SRS I-11 Applied in (branch gaps; US-GAP-59, BR-LEAVE-006, BR-LEAVE-010): a back-dated event
     * of a member without an office applies in its approval month; an open-month event with no
     * period month and a back-dated one not yet approved show no month; two back-dated events of
     * one unit approved in one month ask its lock time once.
     */
    @Test
    void SRS_LV_33_appliedInWithoutAnOfficeAPeriodOrAnApprovalAndOneLockLookupPerUnitMonth()
            throws Exception {
        OrgLocation unit = mock(OrgLocation.class);
        when(unit.getId()).thenReturn(50L);
        when(directory.cycleUnitOf(10L)).thenReturn(Optional.of(unit));
        when(named.queryForObject(
                        contains("SELECT COUNT(*)"), any(SqlParameterSource.class), eq(Long.class)))
                .thenReturn(5L);
        when(named.query(
                        contains("SELECT a.application_id"),
                        any(SqlParameterSource.class),
                        org.mockito.ArgumentMatchers.<RowMapper<LeaveViewService.EventRow>>any()))
                .thenAnswer(
                        call -> {
                            RowMapper<?> m = call.getArgument(2);
                            ResultSet noPeriod = applied(2, 10L, "FULL_DEDUCTION", false, null);
                            when(noPeriod.getDate("period_date")).thenReturn(null);
                            return List.of(
                                    m.mapRow(
                                            applied(
                                                    1,
                                                    null,
                                                    "FULL_DEDUCTION",
                                                    true,
                                                    "2026-10-20T06:00:00Z"),
                                            0),
                                    m.mapRow(noPeriod, 1),
                                    m.mapRow(applied(3, 10L, "HALF_PAY", true, null), 2),
                                    m.mapRow(
                                            applied(
                                                    4,
                                                    10L,
                                                    "FULL_DEDUCTION",
                                                    true,
                                                    "2026-10-20T06:00:00Z"),
                                            3),
                                    m.mapRow(
                                            applied(
                                                    5,
                                                    10L,
                                                    "HALF_PAY",
                                                    true,
                                                    "2026-10-21T06:00:00Z"),
                                            4));
                        });
        org.mockito.Mockito.doAnswer(
                        call -> {
                            SqlParameterSource p = call.getArgument(1);
                            assertThat(
                                            new java.util.ArrayList<Object>(
                                                    (java.util.Collection<?>) p.getValue("units")))
                                    .containsExactly(50L);
                            assertThat(
                                            new java.util.ArrayList<Object>(
                                                    (java.util.Collection<?>) p.getValue("months")))
                                    .containsExactly("202610");
                            RowCallbackHandler h = call.getArgument(2);
                            ResultSet lock = mock(ResultSet.class);
                            when(lock.getLong(1)).thenReturn(50L);
                            when(lock.getString(2)).thenReturn("202610");
                            when(lock.getTimestamp(3))
                                    .thenReturn(
                                            Timestamp.from(Instant.parse("2026-10-15T06:00:00Z")));
                            h.processRow(lock);
                            return null;
                        })
                .when(named)
                .query(
                        contains("FROM payroll_cycle_instance"),
                        any(SqlParameterSource.class),
                        any(RowCallbackHandler.class));
        var rows =
                service.events(
                                context,
                                new LeaveViewService.Filter(null, null, null),
                                new PageSpec(0, 20))
                        .items();
        assertThat(rows.get(0).appliedIn())
                .as("no office: the approval month")
                .isEqualTo("2026-10");
        assertThat(rows.get(1).appliedIn()).as("no period month").isNull();
        assertThat(rows.get(1).rule()).isEqualTo("BR-LEAVE-007");
        assertThat(rows.get(2).appliedIn()).as("back-dated, not yet approved").isNull();
        assertThat(rows.get(3).appliedIn()).isEqualTo("2026-11");
        assertThat(rows.get(4).appliedIn()).isEqualTo("2026-11");
        org.mockito.Mockito.verify(named, org.mockito.Mockito.times(1))
                .query(
                        contains("FROM payroll_cycle_instance"),
                        any(SqlParameterSource.class),
                        any(RowCallbackHandler.class));
    }

    /** Records what an export writes; {@code fail} makes the file refuse a row (disk full). */
    private static final class Sink implements ExportSink {
        final List<List<Object>> rows = new ArrayList<>();
        boolean fail;

        @Override
        public void header(String... titles) {}

        @Override
        public void row(Object... cells) throws IOException {
            if (fail) {
                throw new IOException("disk full");
            }
            rows.add(Arrays.asList(cells));
        }

        @Override
        public long rows() {
            return rows.size();
        }

        @Override
        public void close() {}
    }

    private void typeRows(long count, int rowsServed) throws Exception {
        when(named.queryForObject(
                        contains("SELECT COUNT(*) FROM leave_type_reference"),
                        any(SqlParameterSource.class),
                        eq(Long.class)))
                .thenReturn(count);
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString(1)).thenReturn("EL");
        when(rs.getString(2)).thenReturn("Earned leave");
        when(rs.getString(3)).thenReturn("Y");
        when(rs.getString(4)).thenReturn("N");
        doAnswer(
                        call -> {
                            RowCallbackHandler h = call.getArgument(2);
                            for (int i = 0; i < rowsServed; i++) {
                                h.processRow(rs);
                            }
                            return null;
                        })
                .when(named)
                .query(
                        contains("FROM leave_type_reference ORDER BY"),
                        any(SqlParameterSource.class),
                        any(RowCallbackHandler.class));
    }

    /** SRS-G-13 / M-13 Export: a leave type row is written with its codes and no pay effect yet. */
    @Test
    void SRS_G_13_theLeaveTypeExportWritesEachTypeInCodeOrder() throws Exception {
        typeRows(1, 1);
        Sink sink = new Sink();

        service.exportTypes(context, sink);

        assertThat(sink.rows).hasSize(1);
        assertThat(sink.rows.get(0).subList(0, 4)).containsExactly("EL", "Earned leave", "Y", "N");
        assertThat(sink.rows.get(0).get(4)).as("pay effect not configured").isNull();
    }

    /** SRS-G-13: a file that refuses a row stops the export with the original IO failure. */
    @Test
    void SRS_G_13_aFailingFileStopsTheLeaveTypeExportWithTheOriginalIoException() throws Exception {
        typeRows(1, 1);
        Sink sink = new Sink();
        sink.fail = true;

        assertThatThrownBy(() -> service.exportTypes(context, sink))
                .isInstanceOf(IOException.class)
                .hasMessage("disk full");
        assertThat(sink.rows).isEmpty();
    }

    /** SRS-G-13: a snapshot that serves fewer rows than counted is an incomplete export. */
    @Test
    void SRS_G_13_aLeaveTypeExportShorterThanItsCountIsRefusedAsIncomplete() throws Exception {
        typeRows(2, 1);
        Sink sink = new Sink();

        assertThatThrownBy(() -> service.exportTypes(context, sink))
                .isInstanceOf(ExportIncompleteException.class);
    }

    /** SRS-G-13 / I-11 Export: a failing file stops the event export with the IO failure. */
    @Test
    void SRS_G_13_aFailingFileStopsTheLeaveEventExportWithTheOriginalIoException()
            throws Exception {
        when(named.queryForObject(
                        contains("SELECT COUNT(*)"), any(SqlParameterSource.class), eq(Long.class)))
                .thenReturn(1L);
        ResultSet rs = applied(1, 10L, "FULL_DEDUCTION", false, null);
        doAnswer(
                        call -> {
                            RowCallbackHandler h = call.getArgument(2);
                            h.processRow(rs);
                            return null;
                        })
                .when(named)
                .query(
                        contains("SELECT a.application_id"),
                        any(SqlParameterSource.class),
                        any(RowCallbackHandler.class));
        Sink sink = new Sink();
        sink.fail = true;

        assertThatThrownBy(
                        () ->
                                service.exportEvents(
                                        context,
                                        new LeaveViewService.Filter(null, null, null),
                                        sink))
                .isInstanceOf(IOException.class)
                .hasMessage("disk full");
        assertThat(sink.rows).isEmpty();
    }

    /** SRS-G-29: outside Headquarters the events are read for the session's offices only. */
    @Test
    void SRS_G_29_anOfficeSessionReadsOnlyItsOffices() {
        org.mockito.ArgumentCaptor<SqlParameterSource> params =
                org.mockito.ArgumentCaptor.forClass(SqlParameterSource.class);
        when(named.queryForObject(contains("SELECT COUNT(*)"), params.capture(), eq(Long.class)))
                .thenReturn(0L);
        service.events(context, new LeaveViewService.Filter(null, null, null), new PageSpec(0, 20));
        org.assertj.core.api.Assertions.assertThat(params.getValue().getValue("all"))
                .isEqualTo("N");
        org.assertj.core.api.Assertions.assertThat(params.getValue().getValue("locations"))
                .isEqualTo(Set.of(10L));
    }

    /** SRS-LV-14: a role without I-11 reaches nothing, whatever the filters. */
    @Test
    void SRS_LV_14_aRoleWithoutI11IsRefused() {
        Authorization refusing = mock(Authorization.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("refused"))
                .when(refusing)
                .require(any(), eq("I-11"), any());
        LeaveViewService guarded =
                new LeaveViewService(
                        named,
                        refusing,
                        mock(LeaveParameters.class),
                        directory,
                        Clock.fixed(Instant.parse("2026-10-27T05:00:00Z"), ZoneOffset.UTC));
        assertThatThrownBy(
                        () ->
                                guarded.events(
                                        context,
                                        new LeaveViewService.Filter(null, null, null),
                                        new PageSpec(0, 20)))
                .isInstanceOf(IllegalStateException.class);
    }
}
