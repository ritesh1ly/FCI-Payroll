package in.gov.fci.payroll.leave.web;

import static in.gov.fci.payroll.platform.web.Responses.noStore;

import in.gov.fci.payroll.leave.orchestration.LeaveParameterService;
import in.gov.fci.payroll.leave.orchestration.LeaveRecoveryReportService;
import in.gov.fci.payroll.leave.orchestration.LeaveViewService;
import in.gov.fci.payroll.platform.export.ExportFormat;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.validation.PrintableAscii;
import in.gov.fci.payroll.platform.web.ExportStreaming;
import in.gov.fci.payroll.platform.web.Paging;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * The leave screens (plan 19 phases 19a / 19b; SRS M-13, M-20, I-11; SRS-LV-14, SRS-LV-24). No
 * endpoint writes a leave event, balance or type: they come from HRMS through {@code
 * leave.api.LeaveReceipts} (R-SRC, SRS-G-45). The SRS names the screens, not the API (PENDING
 * API-19-1).
 *
 * <pre>
 * GET  /v1/leave-types                 the leave types received from HRMS   M-13 VIEW
 * GET  /v1/leave-types/counts          the M-13 tiles                       M-13 VIEW
 * GET  /v1/leave-pay-parameters        every parameter version, paged       M-20 VIEW
 * POST /v1/leave-pay-parameters        propose a version (202, HQ only)     M-20 CREATE
 * GET  /v1/leave-events                the leave events of the offices      I-11 VIEW
 * GET  /v1/leave-events/counts         the I-11 tiles                       I-11 VIEW
 * GET  /v1/leave-events/recovery-report Leave Recovery Report (SRS-LV-23)    I-11 EXPORT
 * </pre>
 */
@RestController
@Validated
@Profile("online")
public class LeaveController {

    private static final String CODE = "^[A-Z0-9_]{1,40}$";
    private static final String TYPE = "^[A-Z0-9_]{1,15}$";
    private static final String STATUS = "^(PENDING|APPLIED|BACK_DATED|REJECTED)$";
    private static final String EIS = "^[A-Za-z0-9-]{1,20}$";
    private static final String MONTH = "^[0-9]{6}$";
    private static final String EVENT = "^(LWP|CANCELLATION|CONVERSION)$";
    private static final String REPORT_STATUS = "^(APPROVED|POSTED)$";

    /** A proposed M-20 version: a number or a text value, as the parameter takes. */
    public record NewVersionBody(
            @NotNull @Pattern(regexp = CODE) String code,
            @Pattern(regexp = TYPE) String leaveType,
            @Digits(integer = 14, fraction = 4) BigDecimal number,
            @Size(max = 200) @PrintableAscii String text,
            @NotNull LocalDate effectiveFrom,
            @NotNull @Size(max = 500) @PrintableAscii String reason,
            @Positive Long circularId) {}

    private final CurrentAccess contexts;
    private final LeaveViewService views;
    private final LeaveParameterService parameters;
    private final Paging paging;
    private final ExportStreaming exports;
    private final LeaveRecoveryReportService recoveries;

    public LeaveController(
            CurrentAccess contexts,
            LeaveViewService views,
            LeaveParameterService parameters,
            Paging paging,
            ExportStreaming exports,
            LeaveRecoveryReportService recoveries) {
        this.exports = exports;
        this.recoveries = recoveries;
        this.contexts = contexts;
        this.views = views;
        this.parameters = parameters;
        this.paging = paging;
    }

    @GetMapping("/v1/leave-types")
    @PreAuthorize("@authz.gate('M-13','VIEW')")
    public ResponseEntity<Paged<LeaveViewService.TypeRow>> types(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(views.types(contexts.current(), paging.resolve(page, size)));
    }

    /** M-13 Export: every leave type with its pay effect (SRS-G-13). */
    @GetMapping("/v1/leave-types/export")
    @PreAuthorize("@authz.gate('M-13','EXPORT')")
    public ResponseEntity<StreamingResponseBody> exportTypes(
            @RequestParam(defaultValue = "CSV") ExportFormat format) {
        AccessContext context = contexts.current();
        exports.checkSize(views.typesSize(context));
        return exports.stream("leave-types", format, sink -> views.exportTypes(context, sink));
    }

    @GetMapping("/v1/leave-types/counts")
    @PreAuthorize("@authz.gate('M-13','VIEW')")
    public ResponseEntity<LeaveViewService.TypeCounts> typeCounts() {
        return noStore(views.typeCounts(contexts.current()));
    }

    @GetMapping("/v1/leave-pay-parameters")
    @PreAuthorize("@authz.gate('M-20','VIEW')")
    public ResponseEntity<Paged<LeaveParameterService.Version>> versions(
            @RequestParam(required = false) @Pattern(regexp = CODE) String code,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(parameters.versions(contexts.current(), code, paging.resolve(page, size)));
    }

    @PostMapping("/v1/leave-pay-parameters")
    @PreAuthorize("@authz.gate('M-20','CREATE')")
    public ResponseEntity<LeaveParameterService.Version> propose(
            @Valid @RequestBody NewVersionBody body) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .cacheControl(CacheControl.noStore())
                .body(
                        parameters.propose(
                                contexts.current(),
                                new LeaveParameterService.NewVersion(
                                        body.code(),
                                        body.leaveType(),
                                        body.number(),
                                        body.text(),
                                        body.effectiveFrom(),
                                        body.reason(),
                                        body.circularId())));
    }

    @GetMapping("/v1/leave-events")
    @PreAuthorize("@authz.gate('I-11','VIEW')")
    public ResponseEntity<Paged<LeaveViewService.EventRow>> events(
            @RequestParam(required = false) @Pattern(regexp = STATUS) String status,
            @RequestParam(required = false) @Pattern(regexp = EIS) String eis,
            @RequestParam(required = false) @Pattern(regexp = TYPE) String leaveType,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(
                views.events(
                        contexts.current(),
                        new LeaveViewService.Filter(status, eis, leaveType),
                        paging.resolve(page, size)));
    }

    /** I-11 Export: the leave events for the filters (SRS I-11 Export, SRS-G-13). */
    @GetMapping("/v1/leave-events/export")
    @PreAuthorize("@authz.gate('I-11','EXPORT')")
    public ResponseEntity<StreamingResponseBody> exportEvents(
            @RequestParam(required = false) @Pattern(regexp = STATUS) String status,
            @RequestParam(required = false) @Pattern(regexp = EIS) String eis,
            @RequestParam(required = false) @Pattern(regexp = TYPE) String leaveType,
            @RequestParam(defaultValue = "CSV") ExportFormat format) {
        AccessContext context = contexts.current();
        LeaveViewService.Filter filter = new LeaveViewService.Filter(status, eis, leaveType);
        exports.checkSize(views.eventsSize(context, filter));
        return exports.stream(
                "leave-events", format, sink -> views.exportEvents(context, filter, sink));
    }

    /** SRS-LV-23: the Leave Recovery Report from locked cycles (Excel or CSV). */
    @GetMapping("/v1/leave-events/recovery-report")
    @PreAuthorize("@authz.gate('I-11','EXPORT')")
    public ResponseEntity<StreamingResponseBody> recoveryReport(
            @RequestParam @Pattern(regexp = MONTH) String fromMonth,
            @RequestParam @Pattern(regexp = MONTH) String toMonth,
            @RequestParam(required = false) @Positive Long officeId,
            @RequestParam(required = false) @Pattern(regexp = EIS) String eis,
            @RequestParam(required = false) @Pattern(regexp = TYPE) String leaveType,
            @RequestParam(required = false) @Pattern(regexp = EVENT) String eventType,
            @RequestParam(required = false) @Pattern(regexp = REPORT_STATUS) String status,
            @RequestParam(defaultValue = "XLSX") ExportFormat format) {
        AccessContext context = contexts.current();
        LeaveRecoveryReportService.Filter filter =
                new LeaveRecoveryReportService.Filter(
                        fromMonth, toMonth, officeId, eis, leaveType, eventType, status);
        exports.checkSize(recoveries.size(context, filter));
        return exports.stream(
                "leave-recovery-report", format, sink -> recoveries.report(context, filter, sink));
    }

    @GetMapping("/v1/leave-events/counts")
    @PreAuthorize("@authz.gate('I-11','VIEW')")
    public ResponseEntity<LeaveViewService.EventCounts> eventCounts() {
        return noStore(views.eventCounts(contexts.current()));
    }
}
