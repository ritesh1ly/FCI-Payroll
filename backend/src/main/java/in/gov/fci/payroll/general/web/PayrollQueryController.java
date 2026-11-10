package in.gov.fci.payroll.general.web;

import static in.gov.fci.payroll.platform.web.Responses.noStore;

import in.gov.fci.payroll.general.business.model.PayrollQuery;
import in.gov.fci.payroll.general.orchestration.PayrollQueryService;
import in.gov.fci.payroll.platform.export.ExportFormat;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.validation.PrintableAscii;
import in.gov.fci.payroll.platform.web.ExportStreaming;
import in.gov.fci.payroll.platform.web.Paging;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
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
 * X-06 Payroll Query Management (SRS-G-11). The gate on each endpoint is the coarse check; the
 * service checks the member's record (own record for eSeva, data scope for staff) and the lifecycle
 * rules. Responses are never cached: queries carry personal data.
 *
 * <pre>
 * GET  /v1/queries[?status=]        list (own for eSeva, data scope for staff)
 * GET  /v1/queries/export          the list as CSV (by status)       X-06 EXPORT
 * GET  /v1/queries/{id}             one query
 * POST /v1/queries                  raise                    X-06 CREATE  (member, maker, LDH)
 * POST /v1/queries/{id}/respond     Maker response           X-06 EDIT
 * POST /v1/queries/{id}/resolve     resolve, system closes   X-06 APPROVE / EDIT (service decides)
 * </pre>
 *
 * There is no DELETE: a query is never deleted (SRS-G-37).
 */
@RestController
@Validated
@Profile("online")
public class PayrollQueryController {

    /** Largest Numeric (12) Payroll ID (X-06 field table, SRS v0.5 l.836). */
    static final long MAX_PAYROLL_ID = 999_999_999_999L;

    /**
     * Text fields are bounded here (4000, X-06 field table) and checked for ASCII in the service.
     * The Payroll ID is mandatory and Numeric (12) (SRS v0.5 X-06 field table, l.836): missing or
     * longer than 12 digits is a validation error before any lookup (BMAD TR-001).
     */
    public record RaiseRequest(
            @NotNull @Positive @Max(MAX_PAYROLL_ID) Long payrollId,
            @NotBlank @Size(max = 4000) @PrintableAscii String queryText,
            boolean concernsComputedAmount) {}

    public record RespondRequest(
            @NotBlank @Size(max = 4000) @PrintableAscii String response,
            boolean concernsComputedAmount) {}

    public record ResolveRequest(@NotBlank @Size(max = 4000) @PrintableAscii String resolution) {}

    private final CurrentAccess contexts;
    private final PayrollQueryService service;

    private final ExportStreaming exports;
    private final Paging paging;

    public PayrollQueryController(
            ExportStreaming exports,
            CurrentAccess contexts,
            PayrollQueryService service,
            Paging paging) {
        this.paging = paging;
        this.exports = exports;
        this.contexts = contexts;
        this.service = service;
    }

    @GetMapping("/v1/queries")
    @PreAuthorize("@authz.gate('X-06','VIEW')")
    public ResponseEntity<Paged<PayrollQueryService.QueryView>> list(
            @RequestParam(required = false) PayrollQuery.Status status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(service.list(contexts.current(), status, paging.resolve(page, size)));
    }

    /** X-06 tiles: the queries in scope by status. */
    @GetMapping("/v1/queries/counts")
    @PreAuthorize("@authz.gate('X-06','VIEW')")
    public ResponseEntity<java.util.Map<String, Long>> counts() {
        return noStore(service.counts(contexts.current()));
    }

    @GetMapping("/v1/queries/export")
    @PreAuthorize("@authz.gate('X-06','EXPORT')")
    public ResponseEntity<StreamingResponseBody> export(
            @RequestParam(required = false) PayrollQuery.Status status,
            @RequestParam(defaultValue = "CSV") ExportFormat format) {
        AccessContext context = contexts.current();
        exports.checkSize(service.exportSize(context, status));
        return exports.stream(
                "payroll-queries", format, sink -> service.export(context, status, sink));
    }

    @GetMapping("/v1/queries/{id}")
    @PreAuthorize("@authz.gate('X-06','VIEW')")
    public ResponseEntity<PayrollQueryService.QueryView> get(@PathVariable @Positive Long id) {
        return noStore(service.get(contexts.current(), id));
    }

    @PostMapping("/v1/queries")
    @PreAuthorize("@authz.gate('X-06','CREATE')")
    public ResponseEntity<PayrollQueryService.QueryView> raise(@Valid @RequestBody RaiseRequest r) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(
                        service.raise(
                                contexts.current(),
                                r.payrollId(),
                                r.queryText(),
                                r.concernsComputedAmount()));
    }

    @PostMapping("/v1/queries/{id}/respond")
    @PreAuthorize("@authz.gate('X-06','EDIT')")
    public ResponseEntity<PayrollQueryService.QueryView> respond(
            @PathVariable @Positive Long id, @Valid @RequestBody RespondRequest r) {
        return noStore(
                service.respond(contexts.current(), id, r.response(), r.concernsComputedAmount()));
    }

    @PostMapping("/v1/queries/{id}/resolve")
    @PreAuthorize("@authz.gate('X-06','VIEW')")
    public ResponseEntity<PayrollQueryService.QueryView> resolve(
            @PathVariable @Positive Long id, @Valid @RequestBody ResolveRequest r) {
        return noStore(service.resolve(contexts.current(), id, r.resolution()));
    }
}
