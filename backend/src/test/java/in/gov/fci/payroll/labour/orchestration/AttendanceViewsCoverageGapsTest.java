package in.gov.fci.payroll.labour.orchestration;

import static in.gov.fci.payroll.labour.orchestration.LabourCaptureDb.d;
import static in.gov.fci.payroll.labour.orchestration.LabourCaptureDb.ts;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.labour.business.LabourErrorCode;
import in.gov.fci.payroll.labour.orchestration.AttendanceViews.AnnaCounts;
import in.gov.fci.payroll.labour.orchestration.AttendanceViews.CodeOption;
import in.gov.fci.payroll.labour.orchestration.AttendanceViews.DayRow;
import in.gov.fci.payroll.labour.orchestration.AttendanceViews.Filter;
import in.gov.fci.payroll.labour.orchestration.AttendanceViews.GangOption;
import in.gov.fci.payroll.labour.orchestration.AttendanceViews.GangRow;
import in.gov.fci.payroll.labour.orchestration.AttendanceViews.LeaveRow;
import in.gov.fci.payroll.labour.orchestration.AttendanceViews.Overview;
import in.gov.fci.payroll.labour.orchestration.AttendanceViews.SourceRow;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.export.ExportSink;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * I-01 Labour Attendance Capture reads (OD-121; SRS-2-2, SRS-2-27, SRS-2-29, SRS-2-35, SRS-2-36,
 * SRS-2-37, SRS-2-38, SRS-2-57; SRS-G-13) run against an in-memory copy of the tables: the tiles
 * count only the approved batches for records and work-slips, each gang's month status follows the
 * first condition that holds (not marked, an exception of any kind, a work-slip missing, ready),
 * the day's rows show a mark held in another gang without filling the blank, and every list keeps
 * to the session's depots (a depot or its parent office) unless the session is at Headquarters.
 */
class AttendanceViewsCoverageGapsTest {

    private static final YearMonth OCT = YearMonth.of(2026, 10);
    private static final Filter DEPT = new Filter("DEPT_LABOUR", null, null);
    private static final PageSpec ALL = new PageSpec(0, 20);

    private final Authorization policy = mock(Authorization.class);
    private final SystemParameters parameters = mock(SystemParameters.class);
    private final AccessContext context = mock(AccessContext.class);
    private LabourCaptureDb db;
    private AttendanceViews views;

    @BeforeEach
    void seed() {
        when(parameters.today()).thenReturn(LocalDate.of(2026, 11, 7));
        when(context.locationId()).thenReturn(3L);
        when(context.dataScope()).thenReturn(Set.of(3L));
        when(context.groups()).thenReturn(List.of());
        db = new LabourCaptureDb();
        db.seedBase();
        october();
        november();
        views = new AttendanceViews(db.named, policy, parameters);
    }

    @AfterEach
    void close() {
        db.close();
    }

    private void mark(
            long employee,
            long gang,
            String day,
            String code,
            long codeId,
            String mode,
            String confirmed,
            String filed,
            long depot) {
        db.sql(
                "INSERT INTO labour_attendance VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                employee,
                gang,
                d(day),
                code,
                codeId,
                mode,
                confirmed,
                filed,
                depot);
    }

    private void october() {
        db.sql(
                "INSERT INTO attendance_code_master VALUES (1, 'P', 'Present', 'DEPT_LABOUR',"
                        + " DATE '2026-01-01', NULL, 1, 'N', 'Y')");
        db.sql(
                "INSERT INTO attendance_code_master VALUES (2, 'L', 'Leave without pay',"
                        + " 'DEPT_LABOUR', DATE '2026-01-01', NULL, 0, 'Y', 'N')");
        db.sql(
                "INSERT INTO attendance_code_master VALUES (3, 'X', 'Old code', 'DEPT_LABOUR',"
                        + " DATE '2025-01-01', DATE '2026-09-30', 1, 'N', 'Y')");
        db.sql(
                "INSERT INTO attendance_code_master VALUES (4, 'P', 'Present DPS', 'DPS_LABOUR',"
                        + " DATE '2026-01-01', NULL, 1, 'N', 'Y')");
        mark(100, 10, "2026-10-29", "L", 2, "MANUAL", "N", "N", 3);
        mark(100, 10, "2026-10-30", "P", 1, "ANNA_DARPAN", "Y", "Y", 3);
        mark(100, 10, "2026-10-31", "P", 1, "ANNA_DARPAN", "N", "Y", 3);
        mark(102, 11, "2026-10-31", "P", 1, "MANUAL", "Y", "N", 5);
        mark(103, 15, "2026-10-30", "P", 1, "MANUAL", "N", "Y", 3);
        mark(104, 16, "2026-10-31", "P", 1, "MANUAL", "N", "Y", 3);
        mark(105, 17, "2026-10-31", "P", 1, "MANUAL", "N", "Y", 3);
        mark(107, 18, "2026-10-31", "P", 1, "MANUAL", "N", "Y", 3);
        mark(106, 12, "2026-10-31", "P", 1, "ANNA_DARPAN", "Y", "Y", 4);
        db.sql(
                "INSERT INTO attendance_punch_exception VALUES (104, DATE '2026-10-31', 'OPEN',"
                        + " 'MISSING_OUT')");
        db.sql(
                "INSERT INTO attendance_punch_exception VALUES (104, DATE '2026-10-30', 'CLOSED',"
                        + " 'MISSING_OUT')");
        db.sql(
                "INSERT INTO approval_queue VALUES (900, 'labour_attendance_batch', '10:202610',"
                        + " 'APPROVED', NULL, NULL)");
        db.sql(
                "INSERT INTO approval_queue VALUES (901, 'labour_attendance_batch', '11:202610',"
                        + " 'PENDING_APPROVAL', NULL, NULL)");
        db.sql(
                "INSERT INTO approval_queue VALUES (902, 'labour_attendance_batch', '16:202609',"
                        + " 'APPROVED', NULL, NULL)");
        db.sql(
                "INSERT INTO approval_queue VALUES (903, 'work_slip', '15:202610', 'APPROVED',"
                        + " NULL, NULL)");
        slip(500, 10, "2026-10-30", "APPROVED", "INTF");
        slip(501, 10, "2026-10-31", "REJECTED", "INTF");
        slip(502, 10, "2026-10-30", "APPROVED", "ENT");
        slip(503, 10, "2026-09-30", "REJECTED", "INTF");
        slip(504, 12, "2026-10-30", "REJECTED", "INTF");
    }

    private void slip(long id, long gang, String day, String status, String source) {
        db.sql(
                "INSERT INTO work_slip VALUES (?, ?, ?, ?, ?, ?, 'DEPT_LABOUR', 'N', NULL, 'ldh',"
                        + " NULL, NULL)",
                id,
                "WS-" + id,
                gang,
                d(day),
                status,
                source);
    }

    private void november() {
        mark(100, 10, "2026-11-05", "P", 1, "MANUAL", "Y", "Y", 3);
        mark(109, 16, "2026-11-05", "P", 1, "MANUAL", "N", "N", 3);
        mark(111, 11, "2026-11-05", "P", 1, "BIOMETRIC", "N", "N", 5);
        punch(100, "IN", "2026-11-05 08:05:00");
        punch(100, "IN", "2026-11-05 08:30:00");
        punch(100, "OUT", "2026-11-05 17:00:00");
        punch(100, "OUT", "2026-11-05 17:45:00");
        punch(100, "IN", "2026-11-04 07:00:00");
        punch(104, "OUT", "2026-11-05 18:10:00");
        db.sql(
                "INSERT INTO attendance_punch_exception VALUES (100, DATE '2026-11-05', 'OPEN',"
                        + " 'MISSING_OUT')");
        db.sql(
                "INSERT INTO attendance_punch_exception VALUES (100, DATE '2026-11-05', 'OPEN',"
                        + " 'LATE_IN')");
        db.sql(
                "INSERT INTO attendance_punch_exception VALUES (102, DATE '2026-11-05', 'CLOSED',"
                        + " 'LATE_IN')");
        source(3, "BIOMETRIC", "N", "ACTIVE", "2026-01-01", null, "N");
        source(3, "FACE", "N", "ACTIVE", "2025-01-01", "2025-12-31", "N");
        source(5, "BIOMETRIC", "N", "ACTIVE", "2026-01-01", null, "Y");
        source(6, "BIOMETRIC", "N", "SUSPENDED", "2026-01-01", null, "N");
        source(4, "ANNA_DARPAN", "Y", "ACTIVE", "2026-06-01", null, "N");
        enrol(100, 3, "2026-01-01", null);
        enrol(100, 3, "2026-02-01", null);
        enrol(109, 3, "2026-01-01", null);
        enrol(110, 3, "2026-01-01", "2026-10-31");
        enrol(104, 3, "2026-12-01", null);
        db.sql("INSERT INTO labour_leave_type VALUES (1, 'EL', 'DEPT_LABOUR')");
        db.sql("INSERT INTO labour_leave_type VALUES (2, 'CL', 'DEPT_LABOUR')");
        db.sql("INSERT INTO labour_leave_type VALUES (3, 'XL', 'DPS_LABOUR')");
        leave(1, 2, 100, null, "DEPOT", "N", "2026-11-01 10:00:00", "APPROVED");
        leave(2, 1, 100, 1L, "CONVERSION", "Y", "2026-11-04 09:00:00", "PENDING");
        leave(3, 3, 100, null, "DEPOT", "N", "2026-11-05 09:00:00", "APPROVED");
        leave(4, 1, 106, null, "DEPOT", "N", "2026-11-05 09:00:00", "APPROVED");
        leave(5, 1, 102, null, "DEPOT", "N", "2026-11-04 09:00:00", "APPROVED");
    }

    private void punch(long employee, String direction, String at) {
        db.sql(
                "INSERT INTO biometric_punch VALUES (?, ?, ?, DATE '2026-11-01')",
                employee,
                direction,
                ts(at));
    }

    private void source(
            long depot,
            String mode,
            String anna,
            String status,
            String from,
            String to,
            String fallback) {
        db.sql(
                "INSERT INTO attendance_source_master VALUES (?, ?, ?, ?, ?, ?, ?)",
                depot,
                mode,
                anna,
                status,
                d(from),
                to == null ? null : d(to),
                fallback);
    }

    private void enrol(long employee, long depot, String from, String to) {
        db.sql(
                "INSERT INTO biometric_enrolment VALUES (?, ?, ?, ?)",
                employee,
                depot,
                d(from),
                to == null ? null : d(to));
    }

    private void leave(
            long id,
            long type,
            long employee,
            Long from,
            String origin,
            String backDated,
            String created,
            String status) {
        db.sql(
                "INSERT INTO labour_leave_application VALUES (?, ?, ?, ?, ?, ?, DATE '2026-11-02',"
                        + " DATE '2026-11-03', ?, ?, ?)",
                id,
                type,
                employee + 1000,
                employee,
                from,
                origin,
                backDated,
                ts(created),
                status);
    }

    private static <T> Map<Long, T> byId(List<T> rows, Function<T, Long> id) {
        return rows.stream().collect(Collectors.toMap(id, r -> r));
    }

    // ------------------------------------------------------------------ tiles

    @Test
    void SRS_2_29_tilesCountApprovedBatchesOnlyAndEveryExceptionKindOnce() {
        Overview o = views.overview(context, DEPT, OCT);

        assertThat(o.month()).isEqualTo("2026-10");
        // G01's batch alone is approved: its three marks, two with a work-slip filed.
        assertThat(o.records()).isEqualTo(3);
        assertThat(o.withSlip()).isEqualTo(2);
        assertThat(o.lwpDays()).isEqualTo(1);
        // G06 past retirement, G07 an open punch, G08 a gap, G09 over the calendar.
        assertThat(o.exceptions()).isEqualTo(4);
        assertThat(o.pastRetirement()).isEqualTo(1);
        assertThat(o.overCalendar()).isEqualTo(1);
        // Anna Darpan: two marks (one confirmed) and two work-slips (one returned) in scope.
        assertThat(o.anna()).isEqualTo(new AnnaCounts(4, 2, 1));
        assertThat(o.codes())
                .containsExactly(
                        new CodeOption("L", "Leave without pay"), new CodeOption("P", "Present"));
        assertThat(o.gangs())
                .extracting(GangOption::code)
                .containsExactly("G01", "G02", "G06", "G07", "G08", "G09", "G10");
        assertThat(o.gangs().get(1))
                .isEqualTo(new GangOption(11, "G02", "Gang Two", "ANCILLARY_POOL"));
    }

    @Test
    void SRS_2_29_aGangFilterNarrowsTheTilesButTheToolbarListsEveryGang() {
        Overview o = views.overview(context, new Filter("DEPT_LABOUR", 15L, null), OCT);

        assertThat(o.records()).isZero();
        assertThat(o.withSlip()).isZero();
        assertThat(o.lwpDays()).isZero();
        assertThat(o.pastRetirement()).isEqualTo(1);
        assertThat(o.exceptions()).isEqualTo(1);
        assertThat(o.overCalendar()).isZero();
        assertThat(o.anna()).isEqualTo(new AnnaCounts(0, 0, 0));
        assertThat(o.gangs()).hasSize(7);
    }

    @Test
    void SRS_2_40_inTheOpenMonthAGapCountsOnlyUpToYesterdayAndAnEndedMembershipToItsLastDay() {
        // Today is 7 November: the days to mark run to the 6th. G10 holds one labour for the whole
        // month (six days) and one from the 1st to the 3rd (three days); one mark is held.
        db.sql(
                "INSERT INTO labour_gang_membership VALUES (19, 107, DATE '2026-11-01', DATE"
                        + " '2026-11-03', 'LABOUR')");
        mark(108, 19, "2026-11-02", "P", 1, "MANUAL", "N", "Y", 3);

        Overview o =
                views.overview(context, new Filter("DEPT_LABOUR", 19L, null), OCT.plusMonths(1));

        assertThat(o.month()).isEqualTo("2026-11");
        assertThat(o.exceptions()).isEqualTo(8);
        assertThat(o.pastRetirement()).isZero();
        assertThat(o.overCalendar()).isZero();
        GangRow g10 =
                views.gangSummary(
                                context,
                                new Filter("DEPT_LABOUR", 19L, null),
                                OCT.plusMonths(1),
                                ALL)
                        .items()
                        .get(0);
        assertThat(g10.labour()).isEqualTo(2);
        assertThat(g10.calendarDays()).isEqualTo(30);
        assertThat(g10.status()).isEqualTo("EXCEPTION");
    }

    @Test
    void SRS_2_35_onTheRetirementDayItselfTheLabourIsNotPastRetirement() {
        Map<Long, DayRow> rows =
                byId(
                        views.daily(context, DEPT, LocalDate.of(2026, 11, 1), ALL).items(),
                        DayRow::employeeId);

        assertThat(rows.get(110L).pastRetirement()).isFalse();
        assertThat(rows.get(103L).pastRetirement()).isTrue();
        assertThat(rows.get(100L).pastRetirement()).isFalse();
    }

    @Test
    void SRS_2_2_headquartersSeesEveryDepotsGangsAndAnnaDarpanRecords() {
        when(policy.isAtHeadquarters(context)).thenReturn(true);

        Overview o = views.overview(context, DEPT, OCT);

        assertThat(o.gangs()).extracting(GangOption::code).contains("G03").hasSize(8);
        assertThat(o.anna()).isEqualTo(new AnnaCounts(6, 3, 2));
    }

    // ------------------------------------------------------------------ gang summary

    @Test
    void SRS_2_35_eachGangsStatusFollowsTheFirstConditionThatHolds() {
        Paged<GangRow> page = views.gangSummary(context, DEPT, OCT, ALL);

        assertThat(page.total()).isEqualTo(7);
        Map<Long, GangRow> rows = byId(page.items(), GangRow::gangId);
        GangRow one = rows.get(10L);
        assertThat(one.gangCode()).isEqualTo("G01");
        assertThat(one.gangName()).isEqualTo("Gang One");
        assertThat(one.depot()).isEqualTo("D03 - Depot Three");
        assertThat(one.labour()).isEqualTo(1);
        assertThat(one.calendarDays()).isEqualTo(31);
        assertThat(one.counts()).containsEntry("P", 2L).containsEntry("L", 1L).hasSize(2);
        assertThat(one.presentDays()).isEqualByComparingTo("2");
        assertThat(one.overCalendar()).isFalse();
        assertThat(one.status()).isEqualTo("READY");
        assertThat(rows.get(11L).status()).isEqualTo("WORK_SLIP_MISSING");
        assertThat(rows.get(11L).depot()).isEqualTo("D05 - Depot Five");
        assertThat(rows.get(15L).status()).isEqualTo("EXCEPTION");
        assertThat(rows.get(16L).status()).isEqualTo("EXCEPTION");
        assertThat(rows.get(17L).status()).isEqualTo("EXCEPTION");
        GangRow over = rows.get(18L);
        assertThat(over.status()).isEqualTo("EXCEPTION");
        assertThat(over.overCalendar()).isTrue();
        assertThat(over.labour()).isZero();
        GangRow blank = rows.get(19L);
        assertThat(blank.status()).isEqualTo("NOT_MARKED");
        assertThat(blank.counts()).isEmpty();
        assertThat(blank.presentDays()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(blank.labour()).isEqualTo(1);
    }

    @Test
    void SRS_2_35_theSummaryPagesInGangCodeOrderAndCountsEveryGang() {
        Paged<GangRow> second = views.gangSummary(context, DEPT, OCT, new PageSpec(1, 3));

        assertThat(second.total()).isEqualTo(7);
        assertThat(second.items())
                .extracting(GangRow::gangCode)
                .containsExactly("G07", "G08", "G09");
    }

    @Test
    void SRS_2_35_theSearchMatchesTheGangNameAndAnEmptyPageHasNoRows() {
        Paged<GangRow> found =
                views.gangSummary(context, new Filter("DEPT_LABOUR", null, " gang one "), OCT, ALL);
        assertThat(found.items()).extracting(GangRow::gangCode).containsExactly("G01");
        assertThat(found.total()).isEqualTo(1);

        Paged<GangRow> blankText =
                views.gangSummary(context, new Filter("DEPT_LABOUR", null, "  "), OCT, ALL);
        assertThat(blankText.total()).isEqualTo(7);

        Paged<GangRow> none =
                views.gangSummary(context, new Filter("DEPT_LABOUR", null, "zz"), OCT, ALL);
        assertThat(none.items()).isEmpty();
        assertThat(none.total()).isZero();
    }

    @Test
    void SRS_G_13_theExportCarriesEveryGangOfTheFilterWithItsMarks() throws Exception {
        assertThat(views.exportSize(context, DEPT, OCT)).isEqualTo(7);
        Recorded sink = new Recorded();

        views.export(context, DEPT, OCT, sink);

        assertThat(sink.header)
                .containsExactly(
                        "Gang", "Gang name", "Depot", "Labour", "Calendar days", "Marks by code");
        assertThat(sink.rows).hasSize(7);
        Object[] first = sink.rows.get(0);
        assertThat(Arrays.asList(first).subList(0, 5))
                .containsExactly("G01", "Gang One", "D03 - Depot Three", 1, 31);
        assertThat((String) first[5]).contains("P=2").contains("L=1");
        assertThat(sink.rows.get(6)[0]).isEqualTo("G10");
        assertThat(sink.rows.get(6)[5]).isEqualTo("{}");
    }

    @Test
    void SRS_G_13_aCountTheDatabaseDoesNotReturnIsAnEmptyExport() {
        NamedParameterJdbcTemplate none = mock(NamedParameterJdbcTemplate.class);
        when(none.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class)))
                .thenReturn(null);

        assertThat(new AttendanceViews(none, policy, parameters).exportSize(context, DEPT, OCT))
                .isZero();
    }

    // ------------------------------------------------------------------ the day

    @Test
    void SRS_2_38_theDayShowsOwnMarksPunchesSourcesAndAMarkHeldInAnotherGang() {
        Paged<DayRow> page = views.daily(context, DEPT, LocalDate.of(2026, 11, 5), ALL);

        assertThat(page.total()).isEqualTo(11);
        assertThat(page.items()).hasSize(11);
        Map<Long, DayRow> rows = byId(page.items(), DayRow::employeeId);
        DayRow asha = rows.get(100L);
        assertThat(asha.labourCode()).isEqualTo("DL0100");
        assertThat(asha.name()).isEqualTo("Asha Devi");
        assertThat(asha.gangId()).isEqualTo(10);
        assertThat(asha.gangCode()).isEqualTo("G01");
        assertThat(asha.code()).isEqualTo("P");
        assertThat(asha.captureMode()).isEqualTo("MANUAL");
        assertThat(asha.depotConfirmed()).isTrue();
        assertThat(asha.workSlipFiled()).isTrue();
        assertThat(asha.sourceMode()).isEqualTo("BIOMETRIC");
        assertThat(asha.punchIn()).isEqualTo("08:05");
        assertThat(asha.punchOut()).isEqualTo("17:45");
        assertThat(asha.exception()).isEqualTo("LATE_IN");
        assertThat(asha.otherCode()).isNull();
        assertThat(asha.otherGang()).isNull();
        assertThat(asha.otherDepot()).isNull();
        assertThat(asha.pastRetirement()).isFalse();

        DayRow elsewhere = rows.get(109L);
        assertThat(elsewhere.code()).isNull();
        assertThat(elsewhere.captureMode()).isNull();
        assertThat(elsewhere.depotConfirmed()).isFalse();
        assertThat(elsewhere.workSlipFiled()).isFalse();
        assertThat(elsewhere.otherCode()).isEqualTo("P");
        assertThat(elsewhere.otherGang()).isEqualTo("G07");
        assertThat(elsewhere.otherDepot()).isEqualTo("D03");

        DayRow retired = rows.get(110L);
        assertThat(retired.pastRetirement()).isTrue();
        assertThat(retired.code()).isNull();
        assertThat(retired.otherCode()).isNull();
        assertThat(retired.punchIn()).isNull();
        assertThat(retired.punchOut()).isNull();
        assertThat(retired.exception()).isNull();

        DayRow own = rows.get(111L);
        assertThat(own.code()).isEqualTo("P");
        assertThat(own.captureMode()).isEqualTo("BIOMETRIC");
        assertThat(own.depotConfirmed()).isFalse();
        assertThat(own.workSlipFiled()).isFalse();
        // D05 falls back to manual, D06 is suspended, D07 has no source in force.
        assertThat(own.sourceMode()).isEqualTo("MANUAL");
        assertThat(rows.get(112L).sourceMode()).isEqualTo("MANUAL");
        assertThat(rows.get(113L).sourceMode()).isEqualTo("MANUAL");
        assertThat(rows.get(102L).name()).isEqualTo("Ravi");
        assertThat(rows.get(102L).exception()).isNull();
        assertThat(rows.get(104L).punchIn()).isNull();
        assertThat(rows.get(104L).punchOut()).isEqualTo("18:10");
        assertThat(rows.get(103L).pastRetirement()).isTrue();
        assertThat(page.items().get(0).gangCode()).isEqualTo("G01");
        assertThat(page.items()).extracting(DayRow::labourCode).startsWith("DL0100", "DL0109");
    }

    @Test
    void SRS_2_35_withoutADateTheDayIsTodayAndABlankStaysBlank() {
        Paged<DayRow> page = views.daily(context, DEPT, null, ALL);

        assertThat(page.total()).isEqualTo(11);
        assertThat(page.items()).extracting(DayRow::code).containsOnlyNulls();
        assertThat(page.items()).extracting(DayRow::otherCode).containsOnlyNulls();
    }

    @Test
    void SRS_2_35_aSearchWithoutLabourAnswersAnEmptyPage() {
        Paged<DayRow> page =
                views.daily(
                        context,
                        new Filter("DEPT_LABOUR", null, "nobody"),
                        LocalDate.of(2026, 11, 5),
                        ALL);

        assertThat(page.items()).isEmpty();
        assertThat(page.total()).isZero();

        Paged<DayRow> byGang =
                views.daily(
                        context,
                        new Filter("DEPT_LABOUR", 11L, null),
                        LocalDate.of(2026, 11, 5),
                        ALL);
        assertThat(byGang.items()).extracting(DayRow::employeeId).containsExactly(102L, 111L);
    }

    // ------------------------------------------------------------------ leave and sources

    @Test
    void SRS_2_57_labourLeaveIsNewestFirstWithTheConvertedCodeAndScopedToTheDepots() {
        Paged<LeaveRow> page = views.leave(context, DEPT, ALL);

        assertThat(page.total()).isEqualTo(3);
        assertThat(page.items()).extracting(LeaveRow::id).containsExactly(5L, 2L, 1L);
        LeaveRow converted = page.items().get(1);
        assertThat(converted.labourCode()).isEqualTo("DL0100");
        assertThat(converted.name()).isEqualTo("Asha Devi");
        assertThat(converted.origin()).isEqualTo("CONVERSION");
        assertThat(converted.from()).isEqualTo(LocalDate.of(2026, 11, 2));
        assertThat(converted.to()).isEqualTo(LocalDate.of(2026, 11, 3));
        assertThat(converted.fromCode()).isEqualTo("CL");
        assertThat(converted.toCode()).isEqualTo("EL");
        assertThat(converted.backDated()).isTrue();
        assertThat(converted.raisedOn()).isEqualTo(LocalDate.of(2026, 11, 4));
        assertThat(converted.status()).isEqualTo("PENDING");
        LeaveRow first = page.items().get(2);
        assertThat(first.fromCode()).isNull();
        assertThat(first.backDated()).isFalse();
        assertThat(page.items().get(0).name()).isEqualTo("Ravi");

        Paged<LeaveRow> searched =
                views.leave(context, new Filter("DEPT_LABOUR", null, "devi"), ALL);
        assertThat(searched.items()).extracting(LeaveRow::id).containsExactly(2L, 1L);
    }

    @Test
    void SRS_2_36_theSourceMasterInForceIsReadEverywhereWithTheLabourEnrolledToday() {
        Paged<SourceRow> page = views.sources(context, ALL);

        assertThat(page.total()).isEqualTo(4);
        assertThat(page.items())
                .containsExactly(
                        new SourceRow(
                                "D03 - Depot Three",
                                "BIOMETRIC",
                                false,
                                "ACTIVE",
                                LocalDate.of(2026, 1, 1),
                                2),
                        new SourceRow(
                                "D04 - Depot Four",
                                "ANNA_DARPAN",
                                true,
                                "ACTIVE",
                                LocalDate.of(2026, 6, 1),
                                0),
                        new SourceRow(
                                "D05 - Depot Five",
                                "BIOMETRIC",
                                false,
                                "ACTIVE",
                                LocalDate.of(2026, 1, 1),
                                0),
                        new SourceRow(
                                "D06 - Depot Six",
                                "BIOMETRIC",
                                false,
                                "SUSPENDED",
                                LocalDate.of(2026, 1, 1),
                                0));
        assertThat(views.sources(context, new PageSpec(1, 3)).items()).hasSize(1);
    }

    // ------------------------------------------------------------------ helpers

    @Test
    void SRS_2_2_aMissingOrUnknownPayrollTypeIsRefusedBeforeAnyRead() {
        for (String type : new String[] {null, "BOGUS"}) {
            assertThatThrownBy(() -> views.leave(context, new Filter(type, null, null), ALL))
                    .isInstanceOfSatisfying(
                            FciException.class,
                            e -> {
                                assertThat(e.getErrorCode()).isEqualTo(LabourErrorCode.ATTENDANCE);
                                assertThat(e.getMessageArgs()).containsExactly("PAYROLL_TYPE");
                            });
        }
    }

    @Test
    void SRS_2_35_aNameIsTheFirstNameWhenTheLastIsMissingOrBlank() {
        assertThat(AttendanceViews.name("Ravi", null)).isEqualTo("Ravi");
        assertThat(AttendanceViews.name("Ravi", " ")).isEqualTo("Ravi");
        assertThat(AttendanceViews.name("Ravi", "Kumar")).isEqualTo("Ravi Kumar");
        assertThat(AttendanceViews.like(null)).isNull();
        assertThat(AttendanceViews.like(" ")).isNull();
        assertThat(AttendanceViews.like(" ab ")).isEqualTo("%AB%");
    }

    private static final class Recorded implements ExportSink {
        private final List<String> header = new ArrayList<>();
        private final List<Object[]> rows = new ArrayList<>();

        @Override
        public void header(String... titles) {
            header.addAll(List.of(titles));
        }

        @Override
        public void row(Object... cells) {
            rows.add(cells);
        }

        @Override
        public long rows() {
            return rows.size();
        }

        @Override
        public void close() {
            // Nothing to complete.
        }
    }
}
