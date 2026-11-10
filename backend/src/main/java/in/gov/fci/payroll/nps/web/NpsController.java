package in.gov.fci.payroll.nps.web;

import static in.gov.fci.payroll.platform.web.Responses.noStore;

import in.gov.fci.payroll.nps.orchestration.NpsRegisterService;
import in.gov.fci.payroll.platform.export.ExportFormat;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.web.ExportStreaming;
import in.gov.fci.payroll.platform.web.Paging;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Screen I-14 NPS Deduction (from HRMS) (plan 18 phase 18c; SRS-9-1, SRS-9-1a, SRS-9-2, SRS-9-6).
 * View only: no endpoint writes an NPS value (R-SRC; the records come from HRMS through {@code
 * nps.api.NpsReceipts}). The SRS names the screen, not the API (PENDING API-18-1).
 *
 * <pre>
 * GET /v1/nps-deductions?yymm=          the month's records of the unit      I-14 VIEW
 * GET /v1/nps-deductions/counts?yymm=   the month's counts by status         I-14 VIEW
 * GET /v1/nps-deductions/summary?yymm=  the tiles of the month shown         I-14 VIEW
 * GET /v1/nps-deductions/export?yymm=   the register as a file               I-14 EXPORT
 * GET /v1/nps-deductions/report?from=&to=  the NPS Deduction Report           I-14 EXPORT
 * </pre>
 */
@RestController
@Validated
@Profile("online")
@RequestMapping("/v1/nps-deductions")
public class NpsController {

    private static final String YYMM = "^\\d{4}(0[1-9]|1[0-2])$";
    private static final String STATUS = "^[A-Z_]{1,30}$";
    private static final String EIS = "^[A-Za-z0-9-]{1,20}$";

    private final CurrentAccess contexts;
    private final NpsRegisterService register;
    private final Paging paging;
    private final ExportStreaming exports;

    public NpsController(
            CurrentAccess contexts,
            NpsRegisterService register,
            Paging paging,
            ExportStreaming exports) {
        this.contexts = contexts;
        this.register = register;
        this.paging = paging;
        this.exports = exports;
    }

    @GetMapping
    @PreAuthorize("@authz.gate('I-14','VIEW')")
    public ResponseEntity<Paged<NpsRegisterService.Row>> list(
            @RequestParam @Pattern(regexp = YYMM) String yymm,
            @RequestParam(required = false) @Pattern(regexp = STATUS) String status,
            @RequestParam(required = false) @Pattern(regexp = EIS) String eis,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(
                register.page(
                        contexts.current(),
                        new NpsRegisterService.Filter(yymm, status, eis),
                        paging.resolve(page, size)));
    }

    @GetMapping("/counts")
    @PreAuthorize("@authz.gate('I-14','VIEW')")
    public ResponseEntity<List<NpsRegisterService.Count>> counts(
            @RequestParam @Pattern(regexp = YYMM) String yymm) {
        return noStore(register.counts(contexts.current(), yymm));
    }

    /** SRS-9-1, SRS-9-1a: the tiles; the month shown falls back to the unit's latest records. */
    @GetMapping("/summary")
    @PreAuthorize("@authz.gate('I-14','VIEW')")
    public ResponseEntity<NpsRegisterService.Summary> summary(
            @RequestParam @Pattern(regexp = YYMM) String yymm) {
        return noStore(register.summary(contexts.current(), yymm));
    }

    @GetMapping("/export")
    @PreAuthorize("@authz.gate('I-14','EXPORT')")
    public ResponseEntity<StreamingResponseBody> export(
            @RequestParam @Pattern(regexp = YYMM) String yymm,
            @RequestParam(required = false) @Pattern(regexp = STATUS) String status,
            @RequestParam(required = false) @Pattern(regexp = EIS) String eis,
            @RequestParam(defaultValue = "XLSX") ExportFormat format) {
        // The session is read here, on the request thread: the rows are written on an export
        // thread.
        AccessContext context = contexts.current();
        NpsRegisterService.Filter filter = new NpsRegisterService.Filter(yymm, status, eis);
        exports.checkSize(register.exportSize(context, filter));
        return exports.stream(
                "nps-deductions-" + yymm, format, sink -> register.export(context, filter, sink));
    }

    /** SRS-9-6: the NPS Deduction Report for the months {@code from} to {@code to}. */
    @GetMapping("/report")
    @PreAuthorize("@authz.gate('I-14','EXPORT')")
    public ResponseEntity<StreamingResponseBody> report(
            @RequestParam @Pattern(regexp = YYMM) String from,
            @RequestParam @Pattern(regexp = YYMM) String to,
            @RequestParam(defaultValue = "XLSX") ExportFormat format) {
        AccessContext context = contexts.current();
        exports.checkSize(register.reportSize(context, from, to));
        return exports.stream(
                "nps-deduction-report-" + from + "-" + to,
                format,
                sink -> register.report(context, from, to, sink));
    }
}
