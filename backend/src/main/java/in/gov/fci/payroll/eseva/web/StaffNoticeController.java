package in.gov.fci.payroll.eseva.web;

import static in.gov.fci.payroll.platform.web.Responses.noStore;

import in.gov.fci.payroll.eseva.orchestration.StaffNotices;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.web.Paging;
import jakarta.validation.constraints.Min;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in user's own in-app notices (SRS-15-30, SRS-1-68; owner 2026-10-28). Any signed-in
 * session reads and marks only its own notices; another user's notice reads as unknown.
 *
 * <pre>
 * GET  /v1/notifications/mine             the user's notices, paged
 * POST /v1/notifications/mine/{id}/reads  a notice read (204)
 * </pre>
 */
@RestController
@Validated
@Profile("online")
public class StaffNoticeController {

    private final CurrentAccess contexts;
    private final StaffNotices notices;
    private final Paging paging;

    public StaffNoticeController(CurrentAccess contexts, StaffNotices notices, Paging paging) {
        this.contexts = contexts;
        this.notices = notices;
        this.paging = paging;
    }

    @GetMapping("/v1/notifications/mine")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Paged<StaffNotices.Notice>> mine(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return noStore(notices.mine(contexts.current(), paging.resolve(page, size)));
    }

    @PostMapping("/v1/notifications/mine/{id}/reads")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> read(@PathVariable @Min(1) long id) {
        notices.read(contexts.current(), id);
        return ResponseEntity.noContent().build();
    }
}
