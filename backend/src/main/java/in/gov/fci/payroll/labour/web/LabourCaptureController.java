package in.gov.fci.payroll.labour.web;

import static in.gov.fci.payroll.platform.web.Responses.noStore;

import in.gov.fci.payroll.labour.orchestration.AttendanceViews;
import in.gov.fci.payroll.labour.orchestration.WorkSlipViews;
import in.gov.fci.payroll.platform.export.ExportFormat;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.validation.PrintableAscii;
import in.gov.fci.payroll.platform.web.ExportStreaming;
import in.gov.fci.payroll.platform.web.Paging;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.YearMonth;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * The lists of I-01 and I-02 as Wireframes v6.1 draws them, one view per labour payroll (OD-121;
 * SRS-2-2, SRS-2-27, SRS-2-34, SRS-2-36, SRS-2-45, SRS-2-57; API-20-2):
 *
 * <pre>
 * GET /v1/labour/attendance/overview         tiles, codes, gangs of the type      I-01 VIEW
 * GET /v1/labour/attendance/gang-summaries   the gangs' month (paged)             I-01 VIEW
 * GET /v1/labour/attendance/gang-summaries/export                                 I-01 EXPORT
 * GET /v1/labour/attendance/days             the labour of a day (paged)          I-01 VIEW
 * GET /v1/labour/attendance/leave            labour leave actions (paged)         I-01 VIEW
 * GET /v1/labour/attendance/sources          Attendance Source master (paged)     I-01 VIEW
 * GET /v1/labour/work-slips/overview         import queue, gangs, dates           I-02 VIEW
 * GET /v1/labour/work-slips                  the register's rows (paged)          I-02 VIEW
 * GET /v1/labour/work-slips/export                                                I-02 EXPORT
 * GET /v1/labour/overtime                    overtime per person (paged)          I-02 VIEW
 * </pre>
 */
@RestController
@Validated
@Profile("online")
public class LabourCaptureController {

    private static final String TYPE = "^(DEPT_LABOUR|DPS_LABOUR|NWNP_LABOUR)$";
    private static final String MONTH = "^\\d{4}-(0[1-9]|1[0-2])$";

    private final CurrentAccess contexts;
    private final AttendanceViews attendance;
    private final WorkSlipViews slips;
    private final Paging paging;
    private final ExportStreaming exports;

    public LabourCaptureController(
            CurrentAccess contexts,
            AttendanceViews attendance,
            WorkSlipViews slips,
            Paging paging,
            ExportStreaming exports) {
        this.contexts = contexts;
        this.attendance = attendance;
        this.slips = slips;
        this.paging = paging;
        this.exports = exports;
    }

    @GetMapping("/v1/labour/attendance/overview")
    @PreAuthorize("@authz.gate('I-01','VIEW')")
    public ResponseEntity<AttendanceViews.Overview> attendanceOverview(
            @RequestParam @NotNull @Pattern(regexp = TYPE) String payrollType,
            @RequestParam @NotNull @Pattern(regexp = MONTH) String month,
            @RequestParam(required = false) @Positive Long gang) {
        return noStore(
                attendance.overview(
                        contexts.current(),
                        new AttendanceViews.Filter(payrollType, gang, null),
                        YearMonth.parse(month)));
    }

    @GetMapping("/v1/labour/attendance/gang-summaries")
    @PreAuthorize("@authz.gate('I-01','VIEW')")
    public ResponseEntity<Paged<AttendanceViews.GangRow>> gangSummaries(
            @RequestParam @NotNull @Pattern(regexp = TYPE) String payrollType,
            @RequestParam @NotNull @Pattern(regexp = MONTH) String month,
            @RequestParam(required = false) @Positive Long gang,
            @RequestParam(required = false) @Size(max = 150) @PrintableAscii String text,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(
                attendance.gangSummary(
                        contexts.current(),
                        new AttendanceViews.Filter(payrollType, gang, text),
                        YearMonth.parse(month),
                        paging.resolve(page, size)));
    }

    @GetMapping("/v1/labour/attendance/gang-summaries/export")
    @PreAuthorize("@authz.gate('I-01','EXPORT')")
    public ResponseEntity<StreamingResponseBody> exportGangSummaries(
            @RequestParam @NotNull @Pattern(regexp = TYPE) String payrollType,
            @RequestParam @NotNull @Pattern(regexp = MONTH) String month,
            @RequestParam(required = false) @Positive Long gang,
            @RequestParam(required = false) @Size(max = 150) @PrintableAscii String text,
            @RequestParam(defaultValue = "CSV") ExportFormat format) {
        AccessContext context = contexts.current();
        AttendanceViews.Filter filter = new AttendanceViews.Filter(payrollType, gang, text);
        YearMonth period = YearMonth.parse(month);
        exports.checkSize(attendance.exportSize(context, filter, period));
        return exports.stream(
                "gang-attendance-summary",
                format,
                sink -> attendance.export(context, filter, period, sink));
    }

    @GetMapping("/v1/labour/attendance/days")
    @PreAuthorize("@authz.gate('I-01','VIEW')")
    public ResponseEntity<Paged<AttendanceViews.DayRow>> days(
            @RequestParam @NotNull @Pattern(regexp = TYPE) String payrollType,
            @RequestParam(required = false) LocalDate date,
            @RequestParam(required = false) @Positive Long gang,
            @RequestParam(required = false) @Size(max = 150) @PrintableAscii String text,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(
                attendance.daily(
                        contexts.current(),
                        new AttendanceViews.Filter(payrollType, gang, text),
                        date,
                        paging.resolve(page, size)));
    }

    @GetMapping("/v1/labour/attendance/leave")
    @PreAuthorize("@authz.gate('I-01','VIEW')")
    public ResponseEntity<Paged<AttendanceViews.LeaveRow>> leave(
            @RequestParam @NotNull @Pattern(regexp = TYPE) String payrollType,
            @RequestParam(required = false) @Size(max = 150) @PrintableAscii String text,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(
                attendance.leave(
                        contexts.current(),
                        new AttendanceViews.Filter(payrollType, null, text),
                        paging.resolve(page, size)));
    }

    @GetMapping("/v1/labour/attendance/sources")
    @PreAuthorize("@authz.gate('I-01','VIEW')")
    public ResponseEntity<Paged<AttendanceViews.SourceRow>> sources(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(attendance.sources(contexts.current(), paging.resolve(page, size)));
    }

    @GetMapping("/v1/labour/work-slips/overview")
    @PreAuthorize("@authz.gate('I-02','VIEW')")
    public ResponseEntity<WorkSlipViews.Overview> slipOverview(
            @RequestParam @NotNull @Pattern(regexp = TYPE) String payrollType,
            @RequestParam @NotNull @Pattern(regexp = MONTH) String month,
            @RequestParam(required = false) @Positive Long gang) {
        return noStore(
                slips.overview(
                        contexts.current(),
                        new AttendanceViews.Filter(payrollType, gang, null),
                        YearMonth.parse(month)));
    }

    @GetMapping("/v1/labour/work-slips")
    @PreAuthorize("@authz.gate('I-02','VIEW')")
    public ResponseEntity<Paged<WorkSlipViews.RegisterRow>> register(
            @RequestParam @NotNull @Pattern(regexp = TYPE) String payrollType,
            @RequestParam @NotNull @Pattern(regexp = MONTH) String month,
            @RequestParam(required = false) @Positive Long gang,
            @RequestParam(required = false) LocalDate date,
            @RequestParam(required = false) @Size(max = 150) @PrintableAscii String text,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(
                slips.register(
                        contexts.current(),
                        new AttendanceViews.Filter(payrollType, gang, text),
                        YearMonth.parse(month),
                        date,
                        paging.resolve(page, size)));
    }

    @GetMapping("/v1/labour/work-slips/export")
    @PreAuthorize("@authz.gate('I-02','EXPORT')")
    public ResponseEntity<StreamingResponseBody> exportRegister(
            @RequestParam @NotNull @Pattern(regexp = TYPE) String payrollType,
            @RequestParam @NotNull @Pattern(regexp = MONTH) String month,
            @RequestParam(required = false) @Positive Long gang,
            @RequestParam(required = false) LocalDate date,
            @RequestParam(required = false) @Size(max = 150) @PrintableAscii String text,
            @RequestParam(defaultValue = "CSV") ExportFormat format) {
        AccessContext context = contexts.current();
        AttendanceViews.Filter filter = new AttendanceViews.Filter(payrollType, gang, text);
        YearMonth period = YearMonth.parse(month);
        exports.checkSize(slips.exportSize(context, filter, period, date));
        return exports.stream(
                "work-slip-register",
                format,
                sink -> slips.export(context, filter, period, date, sink));
    }

    @GetMapping("/v1/labour/overtime")
    @PreAuthorize("@authz.gate('I-02','VIEW')")
    public ResponseEntity<Paged<WorkSlipViews.OvertimeRow>> overtime(
            @RequestParam @NotNull @Pattern(regexp = TYPE) String payrollType,
            @RequestParam @NotNull @Pattern(regexp = MONTH) String month,
            @RequestParam(required = false) @Positive Long gang,
            @RequestParam(required = false) @Size(max = 150) @PrintableAscii String text,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(
                slips.overtime(
                        contexts.current(),
                        new AttendanceViews.Filter(payrollType, gang, text),
                        YearMonth.parse(month),
                        paging.resolve(page, size)));
    }
}
