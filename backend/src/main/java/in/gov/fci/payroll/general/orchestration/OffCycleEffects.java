package in.gov.fci.payroll.general.orchestration;

import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The Payroll Checker's decision on an I-20 case, applied inside the decision's transaction: a
 * failing duplicate check at approval throws and so rolls the approval back (SRS-G-10: "no role can
 * override the block").
 */
@Component
public class OffCycleEffects {

    private final OffCycleService service;

    public OffCycleEffects(OffCycleService service) {
        this.service = service;
    }

    @EventListener
    public void on(ApprovalDecided decision) {
        if (decision.concerns(OffCycleService.ENTITY)) {
            service.applyDecision(
                    decision.approvalId(), decision.approved(), decision.checkerLoginId());
        }
    }
}
