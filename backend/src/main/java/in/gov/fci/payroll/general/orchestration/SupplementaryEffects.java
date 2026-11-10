package in.gov.fci.payroll.general.orchestration;

import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The Payroll Checker's decision on an I-23 request, applied inside the decision's transaction: a
 * duplicate found at approval throws and so rolls the approval back (SRS-G-34: no override, the
 * Administrator included).
 */
@Component
public class SupplementaryEffects {

    private final SupplementaryService service;

    public SupplementaryEffects(SupplementaryService service) {
        this.service = service;
    }

    @EventListener
    public void on(ApprovalDecided decision) {
        if (decision.concerns(SupplementaryService.ENTITY)) {
            service.applyDecision(decision);
        }
    }
}
