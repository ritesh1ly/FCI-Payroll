package in.gov.fci.payroll.leave.web;

import static in.gov.fci.payroll.platform.web.Responses.noStore;

import in.gov.fci.payroll.leave.orchestration.LeaveArrearService;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.export.ExportFormat;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.validation.PrintableAscii;
import in.gov.fci.payroll.platform.web.ExportStreaming;
import in.gov.fci.payroll.platform.web.Paging;
import in.gov.fci.payroll.workflow.api.Submitted;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import org.springframework.context.annotation.Profile;
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
 * I-24 Leave Encashment Arrear (plan 13 phase 13d; SRS-8-23, SRS-8-24). The SRS names the screen,
 * not the API (PENDING API-13-1).
 *
 * <pre>
 * GET  /v1/leave-encashment-arrears              the records of the offices (paged)   I-24 VIEW
 * POST /v1/leave-encashment-arrears/runs         run the arrear (one EIS or the unit)  I-24 COMPUTE
 * POST /v1/leave-encashment-arrears/submissions  submit selected records (202)        I-24 SUBMIT
 * GET  /v1/leave-encashment-arrears/report       LE Arrear Invoice Report / sheets     I-24 EXPORT
 * </pre>
 */
@RestController
@Validated
@Profile("online")
public class LeaveArrearController {

    private static final String STATUS =
            "^(CALCULATED|SUBMITTED|APPROVED|INVOICED|PAID|CANCELLED|NOT_ELIGIBLE)$";
    private static final String EIS = "^[0-9A-Za-z]{1,20}$";

    public record RunBody(
            @Pattern(regexp = EIS) String eis,
            @NotNull Long circularId,
            @NotNull LocalDate revisionEffective,
            @NotNull @Size(min = 1, max = 300) @PrintableAscii String reason) {}

    public record SubmissionBody(
            @NotEmpty @Size(max = 500) List<@NotNull Long> ids,
            @NotNull @Size(max = 500) @PrintableAscii String remark) {}

    private final CurrentAccess contexts;
    private final LeaveArrearService arrears;
    private final Paging paging;
    private final ExportStreaming exports;

    public LeaveArrearController(
            CurrentAccess contexts,
            LeaveArrearService arrears,
            Paging paging,
            ExportStreaming exports) {
        this.contexts = contexts;
        this.arrears = arrears;
        this.paging = paging;
        this.exports = exports;
    }

    @GetMapping("/v1/leave-encashment-arrears")
    @PreAuthorize("@authz.gate('I-24','VIEW')")
    public ResponseEntity<Paged<LeaveArrearService.Row>> records(
            @RequestParam(required = false) @Pattern(regexp = STATUS) String status,
            @RequestParam(required = false) @Pattern(regexp = EIS) String eis,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(
                arrears.records(
                        contexts.current(),
                        new LeaveArrearService.Filter(status, eis),
                        paging.resolve(page, size)));
    }

    /** SRS-8-23: the arrear run; a member who cannot be computed is reported, not guessed. */
    @PostMapping("/v1/leave-encashment-arrears/runs")
    @PreAuthorize("@authz.gate('I-24','COMPUTE')")
    public ResponseEntity<LeaveArrearService.RunResult> run(@Valid @RequestBody RunBody body) {
        return noStore(
                arrears.run(
                        contexts.current(),
                        new LeaveArrearService.Run(
                                body.eis(),
                                body.circularId(),
                                body.revisionEffective(),
                                body.reason())));
    }

    /** SRS-8-24: one or many records submitted together for the Payroll Checker. */
    @PostMapping("/v1/leave-encashment-arrears/submissions")
    @PreAuthorize("@authz.gate('I-24','SUBMIT')")
    public ResponseEntity<Submitted> submit(@Valid @RequestBody SubmissionBody body) {
        SubmittedApproval item = arrears.submit(contexts.current(), body.ids(), body.remark());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new Submitted(item.id(), item.status().name()));
    }

    /** SRS-8-24 (Annexure A.11): the LE Arrear Invoice Report; with an EIS, its sheets. */
    @GetMapping("/v1/leave-encashment-arrears/report")
    @PreAuthorize("@authz.gate('I-24','EXPORT')")
    public ResponseEntity<StreamingResponseBody> report(
            @RequestParam(required = false) @Pattern(regexp = STATUS) String status,
            @RequestParam(required = false) @Pattern(regexp = EIS) String eis,
            @RequestParam(defaultValue = "XLSX") ExportFormat format) {
        AccessContext context = contexts.current();
        LeaveArrearService.Filter filter = new LeaveArrearService.Filter(status, eis);
        exports.checkSize(arrears.reportSize(context, filter));
        return exports.stream(
                "le-arrear-invoice-report", format, sink -> arrears.report(context, filter, sink));
    }
}
