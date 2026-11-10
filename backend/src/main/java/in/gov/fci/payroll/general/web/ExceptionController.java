package in.gov.fci.payroll.general.web;

import static in.gov.fci.payroll.platform.web.Responses.noStore;

import in.gov.fci.payroll.general.business.model.ExceptionEntry;
import in.gov.fci.payroll.general.orchestration.ExceptionService;
import in.gov.fci.payroll.platform.export.ExportFormat;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.validation.PrintableAscii;
import in.gov.fci.payroll.platform.web.ExportStreaming;
import in.gov.fci.payroll.platform.web.Paging;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * X-05 Exceptions &amp; Holds Worklist (SRS-G-9). Table actions Filter, Assign, Export; screen
 * action Accept with reason (Payroll Checker, gate G2). There is no "resolve" and no DELETE: an
 * exception closes when the module that raised it finds the condition cleared.
 *
 * <pre>
 * GET  /v1/exceptions[?module=&amp;status=&amp;severity=&amp;cycle=&amp;period=&amp;office=&amp;code=]
 *                                                               worklist + tiles   X-05 VIEW
 * GET  /v1/exceptions/groups[same filters]                      by condition       X-05 VIEW
 * GET  /v1/exceptions/offices                                   office filter      X-05 VIEW
 * GET  /v1/exceptions/export[...]                               CSV               X-05 EXPORT
 * POST /v1/exceptions/{id}/assign  {owner}                      owner             X-05 EDIT
 * POST /v1/exceptions/{id}/accept  {reason}                     Accepted w/ reason X-05 APPROVE
 * </pre>
 */
@RestController
@Validated
@Profile("online")
public class ExceptionController {

    public record AssignRequest(@NotBlank @Pattern(regexp = "^[A-Z_]{3,30}$") String owner) {}

    public record AcceptRequest(@NotBlank @Size(max = 1000) @PrintableAscii String reason) {}

    private final CurrentAccess contexts;
    private final ExceptionService service;

    private final ExportStreaming exports;
    private final Paging paging;

    public ExceptionController(
            ExportStreaming exports,
            CurrentAccess contexts,
            ExceptionService service,
            Paging paging) {
        this.paging = paging;
        this.exports = exports;
        this.contexts = contexts;
        this.service = service;
    }

    /** A payroll month YYYY-MM (sent as the cycle's payroll_yymm YYYYMM). */
    static final String PERIOD = "^\\d{4}-(0[1-9]|1[0-2])$";

    /** An exception code as the producers raise it. */
    static final String CODE = "^[A-Za-z0-9_\\-]{1,40}$";

    @GetMapping("/v1/exceptions")
    @PreAuthorize("@authz.gate('X-05','VIEW')")
    public ResponseEntity<ExceptionService.Worklist> worklist(
            @RequestParam(required = false) @Size(max = 40) String module,
            @RequestParam(required = false) ExceptionEntry.Status status,
            @RequestParam(required = false) ExceptionEntry.Severity severity,
            @RequestParam(required = false) @Positive Long cycle,
            @RequestParam(required = false) @Pattern(regexp = PERIOD) String period,
            @RequestParam(required = false) @Positive Long office,
            @RequestParam(required = false) @Pattern(regexp = CODE) String code,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(
                service.worklist(
                        contexts.current(),
                        filter(module, status, severity, cycle, period, office, code),
                        paging.resolve(page, size)));
    }

    /**
     * SRS-G-9, SRS l.634: the worklist by condition with the members affected, counted by the
     * database over the whole filtered scope; one page of conditions.
     */
    @GetMapping("/v1/exceptions/groups")
    @PreAuthorize("@authz.gate('X-05','VIEW')")
    public ResponseEntity<ExceptionService.Groups> groups(
            @RequestParam(required = false) @Size(max = 40) String module,
            @RequestParam(required = false) ExceptionEntry.Status status,
            @RequestParam(required = false) ExceptionEntry.Severity severity,
            @RequestParam(required = false) @Positive Long cycle,
            @RequestParam(required = false) @Pattern(regexp = PERIOD) String period,
            @RequestParam(required = false) @Positive Long office,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(
                service.groups(
                        contexts.current(),
                        filter(module, status, severity, cycle, period, office, null),
                        paging.resolve(page, size)));
    }

    /** SRS l.604: the offices the worklist may be filtered to (the session's data scope). */
    @GetMapping("/v1/exceptions/offices")
    @PreAuthorize("@authz.gate('X-05','VIEW')")
    public ResponseEntity<java.util.List<ExceptionService.OfficeOption>> offices() {
        return noStore(service.offices(contexts.current()));
    }

    @GetMapping("/v1/exceptions/export")
    @PreAuthorize("@authz.gate('X-05','EXPORT')")
    public ResponseEntity<StreamingResponseBody> export(
            @RequestParam(required = false) @Size(max = 40) String module,
            @RequestParam(required = false) ExceptionEntry.Status status,
            @RequestParam(required = false) ExceptionEntry.Severity severity,
            @RequestParam(required = false) @Positive Long cycle,
            @RequestParam(required = false) @Pattern(regexp = PERIOD) String period,
            @RequestParam(required = false) @Positive Long office,
            @RequestParam(required = false) @Pattern(regexp = CODE) String code,
            @RequestParam(defaultValue = "CSV") ExportFormat format) {
        AccessContext context = contexts.current();
        ExceptionService.Filter filter =
                filter(module, status, severity, cycle, period, office, code);
        exports.checkSize(service.exportSize(context, filter));
        return exports.stream(
                "exceptions-worklist", format, sink -> service.export(context, filter, sink));
    }

    /** The filters as the service takes them; the month YYYY-MM as payroll_yymm YYYYMM. */
    private static ExceptionService.Filter filter(
            String module,
            ExceptionEntry.Status status,
            ExceptionEntry.Severity severity,
            Long cycle,
            String period,
            Long office,
            String code) {
        return new ExceptionService.Filter(
                module,
                status,
                severity,
                cycle,
                period == null || period.isBlank() ? null : period.replace("-", ""),
                office,
                code);
    }

    @PostMapping("/v1/exceptions/{id}/assign")
    @PreAuthorize("@authz.gate('X-05','EDIT')")
    public ResponseEntity<ExceptionService.ExceptionView> assign(
            @PathVariable @Positive Long id, @Valid @RequestBody AssignRequest r) {
        return noStore(service.assign(contexts.current(), id, r.owner()));
    }

    @PostMapping("/v1/exceptions/{id}/accept")
    @PreAuthorize("@authz.gate('X-05','APPROVE')")
    public ResponseEntity<ExceptionService.ExceptionView> accept(
            @PathVariable @Positive Long id, @Valid @RequestBody AcceptRequest r) {
        return noStore(service.accept(contexts.current(), id, r.reason()));
    }
}
