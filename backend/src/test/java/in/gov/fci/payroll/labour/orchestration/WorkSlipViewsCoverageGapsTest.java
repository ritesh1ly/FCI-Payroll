package in.gov.fci.payroll.labour.orchestration;

import static in.gov.fci.payroll.labour.orchestration.LabourCaptureDb.d;
import static in.gov.fci.payroll.labour.orchestration.LabourCaptureDb.ts;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.labour.orchestration.AttendanceViews.AnnaCounts;
import in.gov.fci.payroll.labour.orchestration.AttendanceViews.Filter;
import in.gov.fci.payroll.labour.orchestration.AttendanceViews.GangOption;
import in.gov.fci.payroll.labour.orchestration.WorkSlipViews.OvertimeRow;
import in.gov.fci.payroll.labour.orchestration.WorkSlipViews.Overview;
import in.gov.fci.payroll.labour.orchestration.WorkSlipViews.RegisterRow;
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
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * I-02 Work-Slip Register reads (OD-121; SRS-2-29, SRS-2-34, SRS-2-42, SRS-2-45; SRS-G-13) against
 * an in-memory copy of the tables: the import queue counts saved, refused and DPS slips still to be
 * booked; the register lists each row of work (a refused slip has none) newest first without the
 * amended versions; the overtime list joins the overtime of the month with the Present But No Work
 * rows of the participants; everything stays within the session's depots.
 */
class WorkSlipViewsCoverageGapsTest {

    private static final YearMonth OCT = YearMonth.of(2026, 10);
    private static final Filter DEPT = new Filter("DEPT_LABOUR", null, null);
    private static final PageSpec ALL = new PageSpec(0, 20);

    private final Authorization policy = mock(Authorization.class);
    private final SystemParameters parameters = mock(SystemParameters.class);
    private final AccessContext context = mock(AccessContext.class);
    private LabourCaptureDb db;
    private WorkSlipViews views;

    @BeforeEach
    void seed() {
        when(parameters.today()).thenReturn(LocalDate.of(2026, 11, 7));
        when(context.locationId()).thenReturn(3L);
        when(context.dataScope()).thenReturn(Set.of(3L));
        when(context.groups()).thenReturn(List.of());
        db = new LabourCaptureDb();
        db.seedBase();
        slips();
        overtime();
        views =
                new WorkSlipViews(
                        db.named, policy, new AttendanceViews(db.named, policy, parameters));
    }

    @AfterEach
    void close() {
        db.close();
    }

    private void slip(
            long id,
            long gang,
            String day,
            String status,
            String source,
            String engine,
            String go,
            String reason,
            Long approval,
            Long duplicateOf) {
        db.sql(
                "INSERT INTO work_slip VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'ldh1', ?, ?)",
                id,
                "WS-" + id,
                gang,
                d(day),
                status,
                source,
                engine,
                go,
                reason,
                approval,
                duplicateOf);
    }

    private void row(
            long id,
            long slip,
            int order,
            long op,
            Long bag,
            Integer bags,
            String start,
            String end,
            String norm,
            String pbnw) {
        db.sql(
                "INSERT INTO work_slip_row VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id,
                slip,
                order,
                op,
                bag,
                bags,
                start,
                end,
                norm,
                pbnw);
    }

    private void slips() {
        db.sql("INSERT INTO operation_master VALUES (1, 'UNL', 'Unloading', 'N')");
        db.sql("INSERT INTO operation_master VALUES (2, 'STK', 'Stacking', 'N')");
        db.sql("INSERT INTO operation_master VALUES (3, 'PBNW', 'Present but no work', 'Y')");
        db.sql("INSERT INTO bag_type VALUES (1, '50 kg jute')");
        db.sql(
                "INSERT INTO approval_queue VALUES (900, 'work_slip', '500', 'APPROVED', 'chk1',"
                        + " TIMESTAMP '2026-10-31 10:00:00')");
        slip(500, 10, "2026-10-30", "APPROVED", "INTF", "DEPT_LABOUR", "Y", null, 900L, null);
        slip(
                501,
                10,
                "2026-10-31",
                "REJECTED",
                "INTF",
                "DEPT_LABOUR",
                "N",
                "Duplicate",
                null,
                500L);
        slip(502, 10, "2026-10-31", "AMENDED", "ENT", "DEPT_LABOUR", "N", null, null, null);
        slip(503, 11, "2026-10-31", "DUPLICATE_HELD", "ENT", "DEPT_LABOUR", "N", null, null, null);
        slip(504, 12, "2026-10-30", "APPROVED", "ENT", "DEPT_LABOUR", "N", null, null, null);
        slip(505, 13, "2026-10-30", "DRAFT", "ENT", "DPS_LABOUR", "N", null, null, null);
        slip(506, 13, "2026-10-31", "SUBMITTED", "ENT", "DPS_LABOUR", "N", null, null, null);
        slip(507, 13, "2026-10-31", "APPROVED", "ENT", "DPS_LABOUR", "N", null, null, null);
        slip(508, 10, "2026-09-30", "APPROVED", "ENT", "DEPT_LABOUR", "N", null, null, null);
        row(800, 500, 1, 1, 1L, 120, "08:00", "12:00", "NORMAL", null);
        row(801, 500, 2, 2, null, null, "12:30", "14:00", "STACK", null);
        row(802, 500, 3, 3, null, null, null, null, null, "Rain");
        row(803, 503, 1, 1, 1L, 0, "09:00", "10:00", "NORMAL", null);
        row(804, 502, 1, 3, null, null, null, null, null, "Old");
        db.sql("INSERT INTO work_slip_participant VALUES (500, 100)");
        db.sql("INSERT INTO work_slip_participant VALUES (500, 109)");
        db.sql("INSERT INTO work_slip_participant VALUES (503, 102)");
        db.sql("INSERT INTO work_slip_participant VALUES (502, 100)");
    }

    private void ot(
            long id,
            long gang,
            long employee,
            long op,
            String start,
            String end,
            String normal,
            String dbl,
            Long bags,
            String met,
            String ancillary,
            String day,
            String period,
            String status) {
        db.sql(
                "INSERT INTO labour_overtime VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id,
                gang,
                employee,
                op,
                ts(start),
                ts(end),
                new BigDecimal(normal),
                new BigDecimal(dbl),
                bags,
                met,
                ancillary,
                d(day),
                d(period),
                status);
    }

    private void overtime() {
        ot(
                700,
                10,
                100,
                1,
                "2026-10-30 18:00:00",
                "2026-10-30 20:30:00",
                "1.50",
                "1.00",
                40L,
                "Y",
                "N",
                "2026-10-30",
                "2026-10-01",
                "APPROVED");
        ot(
                701,
                10,
                100,
                1,
                "2026-10-29 18:00:00",
                "2026-10-29 19:00:00",
                "1.00",
                "0",
                10L,
                "Y",
                "N",
                "2026-10-29",
                "2026-10-01",
                "CANCELLED");
        ot(
                702,
                11,
                102,
                2,
                "2026-10-31 18:00:00",
                "2026-10-31 19:00:00",
                "1.00",
                "0",
                null,
                null,
                "Y",
                "2026-10-31",
                "2026-10-01",
                "APPROVED");
        ot(
                703,
                10,
                100,
                1,
                "2026-09-30 18:00:00",
                "2026-09-30 19:00:00",
                "1.00",
                "0",
                5L,
                "N",
                "N",
                "2026-09-30",
                "2026-09-01",
                "APPROVED");
        ot(
                704,
                12,
                106,
                1,
                "2026-10-30 18:00:00",
                "2026-10-30 19:00:00",
                "1.00",
                "0",
                5L,
                "N",
                "N",
                "2026-10-30",
                "2026-10-01",
                "APPROVED");
    }

    // ------------------------------------------------------------------ overview

    @Test
    void SRS_2_29_theImportQueueCountsSavedAndRefusedSlipsAndTheMonthsDates() {
        Overview o = views.overview(context, DEPT, OCT);

        assertThat(o.month()).isEqualTo("2026-10");
        assertThat(o.saved()).isEqualTo(1);
        assertThat(o.rejected()).isEqualTo(1);
        assertThat(o.pendingBooking()).isZero();
        assertThat(o.anna()).isEqualTo(new AnnaCounts(2, 1, 1));
        assertThat(o.gangs()).extracting(GangOption::code).hasSize(7).startsWith("G01", "G02");
        assertThat(o.dates())
                .containsExactly(LocalDate.of(2026, 10, 31), LocalDate.of(2026, 10, 30));
    }

    @Test
    void SRS_2_34_dpsSlipsStillDraftOrSubmittedArePendingBooking() {
        Overview o = views.overview(context, new Filter("DPS_LABOUR", null, null), OCT);

        assertThat(o.saved()).isEqualTo(3);
        assertThat(o.rejected()).isZero();
        assertThat(o.pendingBooking()).isEqualTo(2);
        assertThat(o.gangs()).extracting(GangOption::code).containsExactly("G04");
    }

    // ------------------------------------------------------------------ register

    @Test
    void SRS_2_34_theRegisterListsEachRowOfWorkNewestFirstWithoutAmendedVersions() {
        Paged<RegisterRow> page = views.register(context, DEPT, OCT, null, ALL);

        assertThat(page.total()).isEqualTo(5);
        assertThat(page.items())
                .extracting(r -> r.number() + "/" + r.rowOrder())
                .containsExactly("WS-501/1", "WS-503/1", "WS-500/1", "WS-500/2", "WS-500/3");
        RegisterRow refused = page.items().get(0);
        assertThat(refused.slipId()).isEqualTo(501);
        assertThat(refused.gangId()).isEqualTo(10);
        assertThat(refused.gangCode()).isEqualTo("G01");
        assertThat(refused.date()).isEqualTo(LocalDate.of(2026, 10, 31));
        assertThat(refused.bagType()).isNull();
        assertThat(refused.operationCode()).isNull();
        assertThat(refused.bags()).isNull();
        assertThat(refused.continueWork()).isFalse();
        assertThat(refused.labours()).isZero();
        assertThat(refused.status()).isEqualTo("REJECTED");
        assertThat(refused.source()).isEqualTo("INTF");
        assertThat(refused.duplicateOf()).isEqualTo("WS-500");
        assertThat(refused.reason()).isEqualTo("Duplicate");
        assertThat(refused.enteredBy()).isEqualTo("ldh1");
        assertThat(refused.decidedBy()).isNull();
        assertThat(refused.decidedAt()).isNull();
        assertThat(page.items().get(1).bags()).isZero();
        RegisterRow worked = page.items().get(2);
        assertThat(worked.bagType()).isEqualTo("50 kg jute");
        assertThat(worked.operationCode()).isEqualTo("UNL");
        assertThat(worked.operation()).isEqualTo("Unloading");
        assertThat(worked.bags()).isEqualTo(120);
        assertThat(worked.start()).isEqualTo("08:00");
        assertThat(worked.end()).isEqualTo("12:00");
        assertThat(worked.normType()).isEqualTo("NORMAL");
        assertThat(worked.continueWork()).isTrue();
        assertThat(worked.labours()).isEqualTo(2);
        assertThat(worked.duplicateOf()).isNull();
        assertThat(worked.decidedBy()).isEqualTo("chk1");
        assertThat(worked.decidedAt()).isEqualTo(ts("2026-10-31 10:00:00").toInstant());
        assertThat(page.items().get(3).bags()).isNull();
    }

    @Test
    void SRS_2_34_theRegisterNarrowsByDayGangAndSearchAndPages() {
        assertThat(views.register(context, DEPT, OCT, LocalDate.of(2026, 10, 30), ALL).total())
                .isEqualTo(3);
        assertThat(
                        views.register(
                                        context,
                                        new Filter("DEPT_LABOUR", 11L, null),
                                        OCT,
                                        null,
                                        ALL)
                                .items())
                .extracting(RegisterRow::slipId)
                .containsExactly(503L);
        assertThat(
                        views.register(
                                        context,
                                        new Filter("DEPT_LABOUR", null, "stack"),
                                        OCT,
                                        null,
                                        ALL)
                                .items())
                .extracting(RegisterRow::operationCode)
                .containsExactly("STK");
        Paged<RegisterRow> second = views.register(context, DEPT, OCT, null, new PageSpec(1, 2));
        assertThat(second.total()).isEqualTo(5);
        assertThat(second.items())
                .extracting(r -> r.number() + "/" + r.rowOrder())
                .containsExactly("WS-500/1", "WS-500/2");
    }

    @Test
    void SRS_G_13_theRegisterExportCarriesEveryRowInRegisterOrder() throws Exception {
        assertThat(views.exportSize(context, DEPT, OCT, null)).isEqualTo(5);
        assertThat(views.exportSize(context, DEPT, OCT, LocalDate.of(2026, 10, 31))).isEqualTo(2);
        Recorded sink = new Recorded();

        views.export(context, DEPT, OCT, null, sink);

        assertThat(sink.header)
                .containsExactly(
                        "Work-slip",
                        "Gang",
                        "Date",
                        "Bag type",
                        "Operation",
                        "No. of bags",
                        "Start",
                        "End",
                        "Labours",
                        "Status");
        assertThat(sink.rows).hasSize(5);
        assertThat(Arrays.asList(sink.rows.get(0)))
                .containsExactly(
                        "WS-501",
                        "G01",
                        LocalDate.of(2026, 10, 31),
                        null,
                        null,
                        null,
                        null,
                        null,
                        0L,
                        "REJECTED");
        assertThat(Arrays.asList(sink.rows.get(2)))
                .containsExactly(
                        "WS-500",
                        "G01",
                        LocalDate.of(2026, 10, 30),
                        "50 kg jute",
                        "Unloading",
                        120,
                        "08:00",
                        "12:00",
                        2L,
                        "APPROVED");
    }

    @Test
    void SRS_G_13_aCountTheDatabaseDoesNotReturnIsAnEmptyExport() {
        NamedParameterJdbcTemplate none = mock(NamedParameterJdbcTemplate.class);
        when(none.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class)))
                .thenReturn(null);
        WorkSlipViews empty =
                new WorkSlipViews(none, policy, new AttendanceViews(none, policy, parameters));

        assertThat(empty.exportSize(context, DEPT, OCT, null)).isZero();
    }

    // ------------------------------------------------------------------ overtime

    @Test
    void SRS_2_45_overtimeAndPresentButNoWorkRowsOfTheMonthNewestFirst() {
        Paged<OvertimeRow> page = views.overtime(context, DEPT, OCT, ALL);

        assertThat(page.total()).isEqualTo(4);
        assertThat(page.items())
                .extracting(OvertimeRow::key)
                .containsExactly("OT-702", "OT-700", "PB-802-100", "PB-802-109");
        OvertimeRow pool = page.items().get(0);
        assertThat(pool.labourCode()).isEqualTo("DL0102");
        assertThat(pool.name()).isEqualTo("Ravi");
        assertThat(pool.gangCode()).isEqualTo("G02");
        assertThat(pool.pool()).isTrue();
        assertThat(pool.operationCode()).isEqualTo("STK");
        assertThat(pool.operation()).isEqualTo("Stacking");
        assertThat(pool.workingAs()).isEqualTo("LABOUR");
        assertThat(pool.start()).isEqualTo("18:00");
        assertThat(pool.end()).isEqualTo("19:00");
        assertThat(pool.hours()).isEqualByComparingTo("1");
        assertThat(pool.bags()).isNull();
        assertThat(pool.minimumMet()).isNull();
        assertThat(pool.ancillary()).isTrue();
        assertThat(pool.pbnwReason()).isNull();
        OvertimeRow gang = page.items().get(1);
        assertThat(gang.name()).isEqualTo("Asha Devi");
        assertThat(gang.pool()).isFalse();
        assertThat(gang.workingAs()).isEqualTo("MATE");
        assertThat(gang.end()).isEqualTo("20:30");
        assertThat(gang.hours()).isEqualByComparingTo("2.5");
        assertThat(gang.bags()).isEqualTo(40L);
        assertThat(gang.minimumMet()).isEqualTo("Y");
        assertThat(gang.ancillary()).isFalse();
    }

    @Test
    void SRS_2_42_presentButNoWorkRowsCarryTheReasonAndNoOvertime() {
        Paged<OvertimeRow> page = views.overtime(context, DEPT, OCT, new PageSpec(1, 2));

        assertThat(page.total()).isEqualTo(4);
        OvertimeRow pbnw = page.items().get(1);
        assertThat(pbnw.key()).isEqualTo("PB-802-109");
        assertThat(pbnw.operationCode()).isEqualTo("PBNW");
        assertThat(pbnw.workingAs()).isNull();
        assertThat(pbnw.start()).isNull();
        assertThat(pbnw.end()).isNull();
        assertThat(pbnw.hours()).isNull();
        assertThat(pbnw.bags()).isNull();
        assertThat(pbnw.minimumMet()).isNull();
        assertThat(pbnw.ancillary()).isFalse();
        assertThat(pbnw.pbnwReason()).isEqualTo("Rain");
        assertThat(
                        views.overtime(context, new Filter("DEPT_LABOUR", null, "dl0109"), OCT, ALL)
                                .items())
                .extracting(OvertimeRow::key)
                .containsExactly("PB-802-109");
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
