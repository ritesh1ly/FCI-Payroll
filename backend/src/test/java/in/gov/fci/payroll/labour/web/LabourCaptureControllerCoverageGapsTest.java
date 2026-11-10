package in.gov.fci.payroll.labour.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.ControllerKit;
import in.gov.fci.payroll.labour.orchestration.AttendanceViews;
import in.gov.fci.payroll.labour.orchestration.AttendanceViews.Filter;
import in.gov.fci.payroll.labour.orchestration.WorkSlipViews;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.export.ExportFormat;
import in.gov.fci.payroll.platform.export.ExportSink;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.web.Paging;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * I-01 and I-02 lists as drawn (OD-121; SRS-2-2, SRS-2-27, SRS-2-34, SRS-2-36, SRS-2-45, SRS-2-57;
 * SRS-G-13; API-20-2): each endpoint passes the session, the payroll type, gang, search, month or
 * day and the resolved page to its view unchanged, answers never-cached, and each export is
 * size-checked before a byte is sent and then carries what the view writes.
 */
class LabourCaptureControllerCoverageGapsTest {

    private static final YearMonth OCT = YearMonth.of(2026, 10);
    private static final PageSpec PAGE = new PageSpec(1, 50);

    private final CurrentAccess contexts = mock(CurrentAccess.class);
    private final AccessContext context = mock(AccessContext.class);
    private final AttendanceViews attendance = mock(AttendanceViews.class);
    private final WorkSlipViews slips = mock(WorkSlipViews.class);
    private final Paging paging = mock(Paging.class);
    private final ControllerKit kit = new ControllerKit();
    private final LabourCaptureController controller =
            new LabourCaptureController(contexts, attendance, slips, paging, kit.exports);

    @BeforeEach
    void session() {
        when(contexts.current()).thenReturn(context);
        when(paging.resolve(1, 50)).thenReturn(PAGE);
    }

    @SuppressWarnings("unchecked")
    private static <T> Paged<T> paged() {
        return mock(Paged.class);
    }

    @Test
    void SRS_2_27_theAttendanceOverviewIsTheMonthOfTheTypeAndGang() {
        AttendanceViews.Overview overview = mock(AttendanceViews.Overview.class);
        when(attendance.overview(context, new Filter("DEPT_LABOUR", 10L, null), OCT))
                .thenReturn(overview);

        ResponseEntity<AttendanceViews.Overview> r =
                controller.attendanceOverview("DEPT_LABOUR", "2026-10", 10L);

        assertThat(r.getBody()).isSameAs(overview);
        assertThat(ControllerKit.noStore(r)).isTrue();
    }

    @Test
    void SRS_2_35_theGangSummaryDayLeaveAndSourcesPassTheirCriteriaAndPage() {
        Paged<AttendanceViews.GangRow> gangs = paged();
        when(attendance.gangSummary(context, new Filter("DPS_LABOUR", 4L, "g01"), OCT, PAGE))
                .thenReturn(gangs);
        Paged<AttendanceViews.DayRow> days = paged();
        LocalDate day = LocalDate.of(2026, 10, 30);
        when(attendance.daily(context, new Filter("NWNP_LABOUR", null, "asha"), day, PAGE))
                .thenReturn(days);
        Paged<AttendanceViews.LeaveRow> leave = paged();
        when(attendance.leave(context, new Filter("DEPT_LABOUR", null, "ravi"), PAGE))
                .thenReturn(leave);
        Paged<AttendanceViews.SourceRow> sources = paged();
        when(attendance.sources(context, PAGE)).thenReturn(sources);

        var g = controller.gangSummaries("DPS_LABOUR", "2026-10", 4L, "g01", 1, 50);
        var d = controller.days("NWNP_LABOUR", day, null, "asha", 1, 50);
        var l = controller.leave("DEPT_LABOUR", "ravi", 1, 50);
        var s = controller.sources(1, 50);

        assertThat(g.getBody()).isSameAs(gangs);
        assertThat(d.getBody()).isSameAs(days);
        assertThat(l.getBody()).isSameAs(leave);
        assertThat(s.getBody()).isSameAs(sources);
        assertThat(List.of(g, d, l, s)).allMatch(ControllerKit::noStore);
    }

    @Test
    void SRS_2_34_theWorkSlipOverviewRegisterAndOvertimePassTheirCriteriaAndPage() {
        WorkSlipViews.Overview overview = mock(WorkSlipViews.Overview.class);
        when(slips.overview(context, new Filter("DEPT_LABOUR", null, null), OCT))
                .thenReturn(overview);
        Paged<WorkSlipViews.RegisterRow> register = paged();
        LocalDate day = LocalDate.of(2026, 10, 31);
        when(slips.register(context, new Filter("DEPT_LABOUR", 10L, "ws"), OCT, day, PAGE))
                .thenReturn(register);
        Paged<WorkSlipViews.OvertimeRow> overtime = paged();
        when(slips.overtime(context, new Filter("DPS_LABOUR", 13L, "dl"), OCT, PAGE))
                .thenReturn(overtime);

        var o = controller.slipOverview("DEPT_LABOUR", "2026-10", null);
        var r = controller.register("DEPT_LABOUR", "2026-10", 10L, day, "ws", 1, 50);
        var t = controller.overtime("DPS_LABOUR", "2026-10", 13L, "dl", 1, 50);

        assertThat(o.getBody()).isSameAs(overview);
        assertThat(r.getBody()).isSameAs(register);
        assertThat(t.getBody()).isSameAs(overtime);
        assertThat(List.of(o, r, t)).allMatch(ControllerKit::noStore);
    }

    @Test
    void SRS_G_13_theGangSummaryExportIsTheViewsRowsAsCsv() throws Exception {
        Filter filter = new Filter("DEPT_LABOUR", null, "g");
        when(attendance.exportSize(context, filter, OCT)).thenReturn(1L);
        doAnswer(
                        call -> {
                            ExportSink sink = call.getArgument(3);
                            sink.header("Gang");
                            sink.row("G01");
                            return null;
                        })
                .when(attendance)
                .export(eq(context), eq(filter), eq(OCT), any());

        var response =
                controller.exportGangSummaries(
                        "DEPT_LABOUR", "2026-10", null, "g", ExportFormat.CSV);

        assertThat(response.getHeaders().getContentDisposition().getFilename())
                .isEqualTo("gang-attendance-summary.csv");
        assertThat(ControllerKit.body(response)).contains("Gang").contains("G01");
        assertThat(kit.delivered).hasSize(1);
    }

    @Test
    void SRS_G_13_theRegisterExportIsTheViewsRowsAndATooLargeOneIsRefusedFirst() throws Exception {
        Filter filter = new Filter("DEPT_LABOUR", 10L, null);
        LocalDate day = LocalDate.of(2026, 10, 31);
        when(slips.exportSize(context, filter, OCT, day)).thenReturn(2L);
        doAnswer(
                        call -> {
                            ExportSink sink = call.getArgument(4);
                            sink.header("Work-slip");
                            sink.row("WS-500");
                            return null;
                        })
                .when(slips)
                .export(eq(context), eq(filter), eq(OCT), eq(day), any());

        var response =
                controller.exportRegister(
                        "DEPT_LABOUR", "2026-10", 10L, day, null, ExportFormat.CSV);
        assertThat(response.getHeaders().getContentDisposition().getFilename())
                .isEqualTo("work-slip-register.csv");
        assertThat(ControllerKit.body(response)).contains("WS-500");

        when(slips.exportSize(context, filter, OCT, null))
                .thenReturn((long) ControllerKit.MAX_ROWS + 1);
        assertThatThrownBy(
                        () ->
                                controller.exportRegister(
                                        "DEPT_LABOUR", "2026-10", 10L, null, null, null))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e ->
                                assertThat(e.getErrorCode())
                                        .isEqualTo(CommonErrorCode.EXPORT_TOO_LARGE));
        verify(slips, never()).export(eq(context), eq(filter), eq(OCT), eq(null), any());
    }
}
