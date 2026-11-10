package in.gov.fci.payroll.general.web;

import static in.gov.fci.payroll.platform.web.Responses.noStore;

import in.gov.fci.payroll.general.orchestration.FallbackReconciliationService;
import in.gov.fci.payroll.platform.export.ExportFormat;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.web.ExportStreaming;
import in.gov.fci.payroll.platform.web.Paging;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * X-07 as Wireframes v6.1 draws it (OD-121; SRS-G-47, BR-INT-011, Annexure A.11; PENDING API-01-1).
 *
 * <pre>
 * GET /v1/hrms-fallback/interface-status        the tile "HRMS interface"     X-07 VIEW
 * GET /v1/hrms-fallback/reconciliation          the reconciliation table      X-07 VIEW
 * GET /v1/hrms-fallback/reconciliation/export   the table as CSV or Excel     X-07 EXPORT
 * </pre>
 */
@RestController
@Profile("online")
public class FallbackReconciliationController {

    private final CurrentAccess contexts;
    private final FallbackReconciliationService service;
    private final ExportStreaming exports;
    private final Paging paging;

    public FallbackReconciliationController(
            CurrentAccess contexts,
            FallbackReconciliationService service,
            ExportStreaming exports,
            Paging paging) {
        this.contexts = contexts;
        this.service = service;
        this.exports = exports;
        this.paging = paging;
    }

    @GetMapping("/v1/hrms-fallback/interface-status")
    @PreAuthorize("@authz.gate('X-07','VIEW')")
    public ResponseEntity<FallbackReconciliationService.InterfaceStatus> interfaceStatus() {
        return noStore(service.interfaceStatus(contexts.current()));
    }

    @GetMapping("/v1/hrms-fallback/reconciliation")
    @PreAuthorize("@authz.gate('X-07','VIEW')")
    public ResponseEntity<Paged<FallbackReconciliationService.Row>> reconciliation(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(service.page(contexts.current(), paging.resolve(page, size)));
    }

    @GetMapping("/v1/hrms-fallback/reconciliation/export")
    @PreAuthorize("@authz.gate('X-07','EXPORT')")
    public ResponseEntity<StreamingResponseBody> export(
            @RequestParam(defaultValue = "CSV") ExportFormat format) {
        AccessContext context = contexts.current();
        exports.checkSize(service.exportSize(context));
        return exports.stream(
                "hrms-fallback-reconciliation", format, sink -> service.export(context, sink));
    }
}
