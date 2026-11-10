package in.gov.fci.payroll.workflow.orchestration;

import in.gov.fci.payroll.platform.maintenance.Housekeeping;
import in.gov.fci.payroll.workflow.business.model.WorkflowInboxItem;
import in.gov.fci.payroll.workflow.persistence.WorkflowRepositories;
import java.time.Instant;
import org.springframework.stereotype.Component;

/** Flags open inbox items past their SLA. */
@Component
public class InboxSlaHousekeeping implements Housekeeping {

    private final WorkflowRepositories.Inbox inbox;

    public InboxSlaHousekeeping(WorkflowRepositories.Inbox inbox) {
        this.inbox = inbox;
    }

    @Override
    public String name() {
        return "slaBreaches";
    }

    @Override
    public int run(Instant now) {
        int breached = 0;
        for (WorkflowInboxItem item : inbox.findByStatus(WorkflowInboxItem.Status.PENDING)) {
            if (item.flagIfBreached(now)) {
                inbox.save(item);
                breached++;
            }
        }
        return breached;
    }
}
