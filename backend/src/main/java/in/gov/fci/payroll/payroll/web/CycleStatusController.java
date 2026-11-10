package in.gov.fci.payroll.payroll.web;

import static in.gov.fci.payroll.platform.web.Responses.noStore;

import in.gov.fci.payroll.payroll.orchestration.CycleStatusService;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.web.Paging;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * D-01 Home - Cycle Status (plan 05 phase 05i; SRS D-01): read only. The SRS names the screen, not
 * the API (PENDING API-05-4).
 */
@RestController
@Profile("online")
@RequestMapping("/v1/cycle-status")
public class CycleStatusController {

    private final CurrentAccess contexts;
    private final CycleStatusService status;
    private final Paging paging;

    public CycleStatusController(CurrentAccess contexts, CycleStatusService status, Paging paging) {
        this.contexts = contexts;
        this.status = status;
        this.paging = paging;
    }

    /** Scope, Cycle Status Summary, KPIs, Computation Status by Payroll Type, Blocking Panel. */
    @GetMapping
    @PreAuthorize("@authz.gate('D-01','VIEW')")
    public ResponseEntity<CycleStatusService.StatusView> status() {
        return noStore(status.status(contexts.current()));
    }

    /** The cycle units of the scope with their state (the roll-up, paged). */
    @GetMapping("/units")
    @PreAuthorize("@authz.gate('D-01','VIEW')")
    public ResponseEntity<Paged<CycleStatusService.UnitRow>> units(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(status.units(contexts.current(), paging.resolve(page, size)));
    }
}
