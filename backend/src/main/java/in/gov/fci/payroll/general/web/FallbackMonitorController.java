package in.gov.fci.payroll.general.web;

import in.gov.fci.payroll.general.orchestration.FallbackMonitorService;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.web.Paging;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * X-03 HRMS Fallback Uploads (SRS-INT-22, BR-INT-011; US-GAP-63): the read-only list per cycle and
 * office; the uploads are worked on X-07.
 *
 * <pre>
 * GET /v1/integration/hrms-fallback-uploads   enabled uploads, their approval and reconciliation   X-03 VIEW
 * </pre>
 */
@RestController
public class FallbackMonitorController {

    private final CurrentAccess contexts;
    private final FallbackMonitorService service;
    private final Paging paging;

    public FallbackMonitorController(
            CurrentAccess contexts, FallbackMonitorService service, Paging paging) {
        this.contexts = contexts;
        this.service = service;
        this.paging = paging;
    }

    @GetMapping("/v1/integration/hrms-fallback-uploads")
    @PreAuthorize("@authz.gate('X-03','VIEW')")
    public ResponseEntity<Paged<FallbackMonitorService.Row>> uploads(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.page(contexts.current(), paging.resolve(page, size)));
    }
}
