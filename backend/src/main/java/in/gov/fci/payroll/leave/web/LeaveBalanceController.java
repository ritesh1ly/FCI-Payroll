package in.gov.fci.payroll.leave.web;

import static in.gov.fci.payroll.platform.web.Responses.noStore;

import in.gov.fci.payroll.leave.orchestration.LeaveBalanceService;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * E-01 leave balance (plan 14 phase 14e; SRS-LV-32). The SRS names the card, not the API (PENDING
 * API-14-1).
 *
 * <pre>
 * GET /v1/leave-balances/mine     the member's latest balance per leave type   E-01 VIEW
 * </pre>
 */
@RestController
@Profile("online")
public class LeaveBalanceController {

    private final CurrentAccess contexts;
    private final LeaveBalanceService balances;

    public LeaveBalanceController(CurrentAccess contexts, LeaveBalanceService balances) {
        this.contexts = contexts;
        this.balances = balances;
    }

    @GetMapping("/v1/leave-balances/mine")
    @PreAuthorize("@authz.gate('E-01','SELF')")
    public ResponseEntity<List<LeaveBalanceService.Balance>> mine() {
        return noStore(balances.mine(contexts.current()));
    }
}
