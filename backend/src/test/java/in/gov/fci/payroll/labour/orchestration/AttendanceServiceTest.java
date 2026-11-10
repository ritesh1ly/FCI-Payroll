package in.gov.fci.payroll.labour.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.labour.business.AttendanceRules.BatchState;
import in.gov.fci.payroll.labour.orchestration.AttendanceService.Mark;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * I-01 attendance refusals the screen never sends (plan 20 phase 20b; SRS-2-29, SRS-2-35,
 * SRS-2-38): a day without its marks, a gang not working that day, a closed batch, a payroll type
 * with no code set, a member marked twice; a month submitted early, for a gang outside the month,
 * or with member-days left unmarked (a member leaving the gang or retiring inside the month).
 */
class AttendanceServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 28);
    private static final LocalDate DAY = LocalDate.of(2026, 10, 5);

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final LabourGangs gangs = mock(LabourGangs.class);
    private final SystemParameters parameters = mock(SystemParameters.class);
    private final NamedParameterJdbcTemplate named = mock(NamedParameterJdbcTemplate.class);
    private final AccessContext context = mock(AccessContext.class);
    private final AttendanceService service =
            new AttendanceService(
                    jdbc,
                    named,
                    gangs,
                    mock(Authorization.class),
                    mock(MakerChecker.class),
                    parameters,
                    mock(AuditService.class),
                    Clock.fixed(Instant.parse("2026-10-28T06:00:00Z"), ZoneOffset.UTC),
                    mock(GangStrengthService.class));

    private static LabourGangs.Gang gang(String status, LocalDate from, LocalDate to) {
        return new LabourGangs.Gang(
                5L, "DP-DL01-G002", "Okhla Gang 1", 7L, "DP-DL01", "DEPT_LABOUR", status, from, to);
    }

    private static void defect(ThrowingCallable call, String expected) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> assertThat(String.valueOf(e.getMessageArgs()[0])).isEqualTo(expected));
    }

    @BeforeEach
    void wire() {
        when(parameters.today()).thenReturn(TODAY);
        when(gangs.workGang(5L)).thenReturn(gang("ACTIVE", LocalDate.of(2025, 7, 1), null));
        when(gangs.batch(anyString(), anyLong(), any()))
                .thenReturn(new LabourGangs.Batch(null, BatchState.OPEN));
    }

    /** The code set of the day holds one code, W. */
    private void codeW() throws Exception {
        codes("W");
    }

    /** The code set of the day: the given codes, each a full present day. */
    private void codes(String... set) throws Exception {
        List<ResultSet> rows = new ArrayList<>();
        long id = 1;
        for (String code : set) {
            ResultSet rs = mock(ResultSet.class);
            when(rs.getString("attendance_code")).thenReturn(code);
            when(rs.getLong("attendance_code_id")).thenReturn(id++);
            when(rs.getBigDecimal("present_day_weight")).thenReturn(BigDecimal.ONE);
            when(rs.getString("is_leave_code")).thenReturn("N");
            when(rs.getString("is_confirmed")).thenReturn("Y");
            rows.add(rs);
        }
        doAnswer(
                        a -> {
                            for (ResultSet rs : rows) {
                                ((RowCallbackHandler) a.getArgument(1)).processRow(rs);
                            }
                            return null;
                        })
                .when(jdbc)
                .query(
                        contains("attendance_code_master"),
                        any(RowCallbackHandler.class),
                        any(),
                        any(),
                        any());
    }

    @Test
    void SRS_2_35_aDayNeedsItsDateMarksAWorkingGangAnOpenBatchAndACodeSet() throws Exception {
        defect(() -> service.saveDay(context, 5L, null, List.of()), "DAY");
        defect(() -> service.saveDay(context, 5L, DAY, null), "DAY");
        when(gangs.workGang(5L)).thenReturn(gang("ACTIVE", DAY.plusDays(1), null));
        defect(() -> service.saveDay(context, 5L, DAY, List.of()), "GANG_NOT_ACTIVE");
        when(gangs.workGang(5L)).thenReturn(gang("ACTIVE", LocalDate.of(2025, 7, 1), null));
        when(gangs.batch(anyString(), anyLong(), any()))
                .thenReturn(new LabourGangs.Batch(41L, BatchState.APPROVED));
        assertThatThrownBy(() -> service.saveDay(context, 5L, DAY, List.of()))
                .isInstanceOf(FciException.class)
                .hasMessage("LBR-409-STATE");
        when(gangs.batch(anyString(), anyLong(), any()))
                .thenReturn(new LabourGangs.Batch(null, BatchState.RETURNED));
        assertThatThrownBy(() -> service.saveDay(context, 5L, DAY, List.of()))
                .as("a payroll type with no code set in force")
                .isInstanceOf(FciException.class)
                .hasMessage("LBR-422-NOT-CONFIGURED");
        codeW();
        defect(
                () ->
                        service.saveDay(
                                context, 5L, DAY, List.of(new Mark(11L, "W"), new Mark(11L, null))),
                "MARKED_TWICE 11");
    }

    @Test
    void SRS_2_29_aMonthIsSubmittedWhenOverOpenWithinTheGangAndFullyMarked() {
        defect(() -> service.submit(context, 5L, null), "MONTH_NOT_OVER");
        defect(() -> service.submit(context, 5L, YearMonth.from(TODAY)), "MONTH_NOT_OVER");
        when(gangs.batch(anyString(), anyLong(), any()))
                .thenReturn(new LabourGangs.Batch(41L, BatchState.SUBMITTED));
        assertThatThrownBy(() -> service.submit(context, 5L, YearMonth.of(2026, 9)))
                .isInstanceOf(FciException.class)
                .hasMessage("LBR-409-STATE");
        when(gangs.batch(anyString(), anyLong(), any()))
                .thenReturn(new LabourGangs.Batch(null, BatchState.OPEN));
        // A gang formed after the month, or ended before it.
        when(gangs.workGang(5L)).thenReturn(gang("ACTIVE", LocalDate.of(2026, 10, 1), null));
        defect(() -> service.submit(context, 5L, YearMonth.of(2026, 9)), "GANG_NOT_ACTIVE");
        when(gangs.workGang(5L))
                .thenReturn(gang("ACTIVE", LocalDate.of(2025, 1, 1), LocalDate.of(2026, 8, 31)));
        defect(() -> service.submit(context, 5L, YearMonth.of(2026, 9)), "GANG_NOT_ACTIVE");
        // A gang formed mid-month and ending mid-month; one member leaves the gang on the 12th, one
        // retires on the 10th: their member-days after those dates are not owed.
        when(gangs.workGang(5L))
                .thenReturn(gang("ACTIVE", LocalDate.of(2026, 9, 5), LocalDate.of(2026, 9, 20)));
        Map<String, Object> leaving =
                span(11L, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 12), null);
        Map<String, Object> retiring =
                span(12L, LocalDate.of(2026, 9, 8), null, LocalDate.of(2026, 9, 10));
        when(jdbc.queryForList(
                        contains("labour_gang_membership m JOIN employee"),
                        eq(5L),
                        any(Date.class),
                        any(Date.class)))
                .thenReturn(List.of(leaving, retiring));
        // 5th - 12th for the leaver (8 days) and 8th - 10th for the retiree (3 days), none marked.
        defect(() -> service.submit(context, 5L, YearMonth.of(2026, 9)), "UNMARKED_DAYS 11");
    }

    private static Map<String, Object> span(
            long id, LocalDate from, LocalDate to, LocalDate retire) {
        Map<String, Object> m = new HashMap<>();
        m.put("EMPLOYEE_ID", id);
        m.put("EFFECTIVE_FROM", Date.valueOf(from));
        m.put("EFFECTIVE_TO", to == null ? null : Date.valueOf(to));
        m.put("RETIREMENT_DATE", retire == null ? null : Date.valueOf(retire));
        return m;
    }

    /** The members of the gang on a day: id and retirement date (null: none). */
    private void members(Object... idAndRetirement) throws Exception {
        List<ResultSet> rows = new ArrayList<>();
        for (int i = 0; i < idAndRetirement.length; i += 2) {
            ResultSet rs = mock(ResultSet.class);
            long id = (Long) idAndRetirement[i];
            LocalDate retire = (LocalDate) idAndRetirement[i + 1];
            when(rs.getLong("employee_id")).thenReturn(id);
            when(rs.getString("labour_number")).thenReturn("L" + id);
            when(rs.getDate("retirement_date"))
                    .thenReturn(retire == null ? null : Date.valueOf(retire));
            rows.add(rs);
        }
        doAnswer(
                        a -> {
                            for (ResultSet rs : rows) {
                                ((RowCallbackHandler) a.getArgument(1)).processRow(rs);
                            }
                            return null;
                        })
                .when(jdbc)
                .query(
                        contains("p.labour_number, e.retirement_date FROM"),
                        any(RowCallbackHandler.class),
                        any(),
                        any(),
                        any());
    }

    private static Map<String, Object> mark(
            long employee, LocalDate day, String code, long gang, String slipFiled) {
        Map<String, Object> m = new HashMap<>();
        m.put("ATTENDANCE_ID", 900L + employee);
        m.put("EMPLOYEE_ID", employee);
        m.put("ATTENDANCE_DATE", Date.valueOf(day));
        m.put("ATTENDANCE_CODE", code);
        m.put("GANG_ID", gang);
        m.put("WORK_SLIP_FILED_FLAG", slipFiled);
        m.put("PRESENT_DAY_WEIGHT", BigDecimal.ONE);
        m.put("IS_LEAVE_CODE", "N");
        m.put("IS_CONFIRMED", "Y");
        return m;
    }

    private static LabourGangs.Gang dpsGang() {
        return new LabourGangs.Gang(
                5L,
                "DP-DL01-G009",
                "Okhla DPS Gang",
                7L,
                "DP-DL01",
                "DPS_LABOUR",
                "ACTIVE",
                LocalDate.of(2025, 7, 1),
                null);
    }

    /**
     * SRS-4-5: the split-week Sunday rule needs a DPS gang and an A code in the set; with no A code
     * nothing is re-marked, and with no member that day there is nothing to read or keep.
     */
    @Test
    void SRS_4_5_theSplitWeekSundayNeedsTheAbsentCodeAndAMember() throws Exception {
        LocalDate sunday = LocalDate.of(2026, 10, 4);
        when(gangs.workGang(5L)).thenReturn(dpsGang());
        codeW();
        assertThat(service.saveDay(context, 5L, sunday, List.of()).markedAbsent()).isEmpty();
        codes("W", "A");
        assertThat(service.saveDay(context, 5L, sunday, List.of()).marked()).isZero();
    }

    /**
     * SRS-4-5: on the split-week Sunday a member absent on all the previous month's days of the
     * week is marked A; a member already marked A, or not marked at all, is left as entered (the
     * day is then refused as incomplete). A member retiring later is still expected that day.
     */
    @Test
    void SRS_4_5_onlyAMarkedMemberAbsentThroughTheWeekIsReMarked() throws Exception {
        LocalDate sunday = LocalDate.of(2026, 10, 4);
        when(gangs.workGang(5L)).thenReturn(dpsGang());
        codes("W", "A");
        members(11L, LocalDate.of(2030, 1, 31), 12L, null, 13L, null);
        List<Map<String, Object>> week = new ArrayList<>();
        for (long e = 11; e <= 13; e++) {
            for (int d = 28; d <= 30; d++) {
                week.add(mark(e, LocalDate.of(2026, 9, d), "A", 5L, "N"));
            }
        }
        when(named.queryForList(contains("c.present_day_weight"), any(MapSqlParameterSource.class)))
                .thenReturn(week);
        defect(
                () ->
                        service.saveDay(
                                context,
                                5L,
                                sunday,
                                List.of(new Mark(11L, "W"), new Mark(13L, "A"))),
                "UNMARKED");
    }

    /**
     * SRS-2-3 / BR-LABM-005: over a filed work-slip the same code is saved again unchanged; only a
     * different code is refused.
     */
    @Test
    void SRS_2_3_theSameCodeOverAFiledWorkSlipIsKeptUnchanged() throws Exception {
        codeW();
        members(11L, null);
        when(named.queryForList(contains("g.gang_name"), any(MapSqlParameterSource.class)))
                .thenReturn(List.of(mark(11L, DAY, "W", 5L, "Y")));
        AttendanceService.Saved saved =
                service.saveDay(context, 5L, DAY, List.of(new Mark(11L, "W")));
        assertThat(saved.added()).isZero();
        assertThat(saved.changed()).isZero();
    }

    /**
     * SRS-2-38 (DDL chk_lbr_att_m_cap): a month whose counts pass the calendar (a code weighted
     * above one day) is refused; a gang ending after the month is checked to the month end.
     */
    @Test
    void SRS_2_38_aMonthCountedBeyondTheCalendarIsRefused() {
        when(gangs.workGang(5L))
                .thenReturn(gang("ACTIVE", LocalDate.of(2025, 1, 1), LocalDate.of(2027, 3, 31)));
        when(jdbc.queryForList(
                        contains("SELECT DISTINCT employee_id"),
                        eq(Long.class),
                        eq(5L),
                        any(Date.class),
                        any(Date.class)))
                .thenReturn(List.of(11L));
        Map<String, Object> heavy = mark(11L, LocalDate.of(2026, 9, 1), "W", 5L, "N");
        heavy.put("PRESENT_DAY_WEIGHT", BigDecimal.valueOf(31));
        when(named.queryForList(contains("c.present_day_weight"), any(MapSqlParameterSource.class)))
                .thenReturn(List.of(heavy));
        defect(() -> service.submit(context, 5L, YearMonth.of(2026, 9)), "CALENDAR_EXCEEDED 11");
    }

    /**
     * I-01 grid: a mark in another gang is flagged, a member without a last name (or a blank one)
     * shows the first name, the leaving and retirement dates show when set; a month not yet
     * markable reads the strength on its first day.
     */
    @Test
    void I_01_theGridFlagsOtherGangsMarksAndShowsNamesAndDates() throws Exception {
        codeW();
        Map<String, Object> plain = span(11L, LocalDate.of(2026, 1, 1), null, null);
        plain.put("FIRST_NAME", "Ramu");
        plain.put("LAST_NAME", null);
        Map<String, Object> blank =
                span(12L, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 15), null);
        blank.put("FIRST_NAME", "Bhola");
        blank.put("LAST_NAME", " ");
        Map<String, Object> full =
                span(13L, LocalDate.of(2026, 1, 1), null, LocalDate.of(2026, 12, 20));
        full.put("FIRST_NAME", "Shyam");
        full.put("LAST_NAME", "Lal");
        when(jdbc.queryForList(contains("e.first_name"), eq(5L), any(Date.class), any(Date.class)))
                .thenReturn(List.of(plain, blank, full));
        LocalDate day = LocalDate.of(2026, 12, 1);
        when(named.queryForList(contains("c.present_day_weight"), any(MapSqlParameterSource.class)))
                .thenReturn(
                        List.of(
                                mark(11L, day, "W", 5L, "N"),
                                mark(11L, day.plusDays(1), "W", 9L, "Y")));
        AttendanceService.Grid grid = service.grid(context, 5L, YearMonth.of(2026, 12));
        assertThat(grid.members())
                .extracting(AttendanceService.MemberGrid::name)
                .containsExactly("Ramu", "Bhola", "Shyam Lal");
        assertThat(grid.members().get(0).marks())
                .extracting(AttendanceService.DayMark::otherGang)
                .containsExactly(false, true);
        assertThat(grid.members().get(1).to()).isEqualTo(LocalDate.of(2026, 12, 15));
        assertThat(grid.members().get(2).retirementDate()).isEqualTo(LocalDate.of(2026, 12, 20));
        assertThat(service.grid(context, 5L, YearMonth.of(2026, 9)).members()).hasSize(3);
    }
}
