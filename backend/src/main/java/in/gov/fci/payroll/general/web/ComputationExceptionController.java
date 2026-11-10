package in.gov.fci.payroll.general.web;

import static in.gov.fci.payroll.platform.web.Responses.noStore;

import in.gov.fci.payroll.general.business.model.ExceptionEntry;
import in.gov.fci.payroll.general.orchestration.ComputationExceptions;
import in.gov.fci.payroll.general.orchestration.ExceptionService;
import in.gov.fci.payroll.platform.export.ExportFormat;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.validation.PrintableAscii;
import in.gov.fci.payroll.platform.web.ExportStreaming;
import in.gov.fci.payroll.platform.web.Paging;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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
 * C-08 Payroll Computation - Exceptions Review (SRS-G-9, SRS l.1641-1678). The SRS names the screen
 * and its fields, not the API (PENDING API-05-21). Read only: accept and assign stay on {@link
 * ExceptionController}, under X-05 APPROVE and X-05 EDIT.
 *
 * <pre>
 * GET /v1/computation-exceptions?period=[&amp;severity=&amp;search=]  tiles + conditions   C-08 VIEW
 * GET /v1/computation-exceptions/items?period=&amp;code=[&amp;module=]    items of a condition  C-08 VIEW
 * GET /v1/computation-exceptions/export?period=[...]             the drawn columns     C-08 EXPORT
 * </pre>
 */
@RestController
@Validated
@Profile("online")
@RequestMapping("/v1/computation-exceptions")
public class ComputationExceptionController {

    private final CurrentAccess contexts;
    private final ComputationExceptions review;
    private final ExportStreaming exports;
    private final Paging paging;

    public ComputationExceptionController(
            CurrentAccess contexts,
            ComputationExceptions review,
            ExportStreaming exports,
            Paging paging) {
        this.contexts = contexts;
        this.review = review;
        this.exports = exports;
        this.paging = paging;
    }

    @GetMapping
    @PreAuthorize("@authz.gate('C-08','VIEW')")
    public ResponseEntity<ComputationExceptions.Review> review(
            @RequestParam @Pattern(regexp = ExceptionController.PERIOD) String period,
            @RequestParam(required = false) ExceptionEntry.Severity severity,
            @RequestParam(required = false) @Size(max = 60) @PrintableAscii String search,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(
                review.review(
                        contexts.current(),
                        filter(period, severity, search),
                        paging.resolve(page, size)));
    }

    @GetMapping("/items")
    @PreAuthorize("@authz.gate('C-08','VIEW')")
    public ResponseEntity<Paged<ExceptionService.ExceptionView>> items(
            @RequestParam @Pattern(regexp = ExceptionController.PERIOD) String period,
            @RequestParam @Pattern(regexp = ExceptionController.CODE) String code,
            @RequestParam(required = false) @Size(max = 40) String module,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(
                review.items(
                        contexts.current(),
                        period.replace("-", ""),
                        module,
                        code,
                        paging.resolve(page, size)));
    }

    @GetMapping("/export")
    @PreAuthorize("@authz.gate('C-08','EXPORT')")
    public ResponseEntity<StreamingResponseBody> export(
            @RequestParam @Pattern(regexp = ExceptionController.PERIOD) String period,
            @RequestParam(required = false) ExceptionEntry.Severity severity,
            @RequestParam(required = false) @Size(max = 60) @PrintableAscii String search,
            @RequestParam(defaultValue = "CSV") ExportFormat format) {
        AccessContext context = contexts.current();
        ComputationExceptions.Filter filter = filter(period, severity, search);
        exports.checkSize(review.exportSize(context, filter));
        return exports.stream(
                "computation-exceptions", format, sink -> review.export(context, filter, sink));
    }

    private static ComputationExceptions.Filter filter(
            String period, ExceptionEntry.Severity severity, String search) {
        return new ComputationExceptions.Filter(
                period.replace("-", ""),
                severity,
                search == null || search.isBlank() ? null : search.trim());
    }
}
