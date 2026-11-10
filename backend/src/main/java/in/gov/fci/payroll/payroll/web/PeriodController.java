package in.gov.fci.payroll.payroll.web;

import static in.gov.fci.payroll.platform.web.Responses.noStore;

import in.gov.fci.payroll.payroll.orchestration.CycleService;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The payroll periods of the top-bar selector: the approved M-10 calendar with each cycle's state
 * at the session's paying unit. Any signed-in session may read it (months and states only); every
 * screen authorises its own data for the period chosen.
 */
@RestController
@Profile("online")
@PreAuthorize("isAuthenticated()")
public class PeriodController {

    private final CurrentAccess contexts;
    private final CycleService cycles;

    public PeriodController(CurrentAccess contexts, CycleService cycles) {
        this.contexts = contexts;
        this.cycles = cycles;
    }

    @GetMapping("/v1/periods")
    public ResponseEntity<List<CycleService.PeriodView>> periods() {
        return noStore(cycles.periods(contexts.current()));
    }
}
