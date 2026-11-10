package in.gov.fci.payroll.workflow.api;

import in.gov.fci.payroll.workflow.persistence.WorkflowRepositories;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** The governed wireframe buttons ({@code screen_action_inventory}), in screen and action order. */
@Component
public class GovernedActions {

    /** One governed button and the message shown while it is disabled. */
    public record GovernedAction(
            String screenCode, String actionCode, String label, String disabledReasonMsg) {}

    private final WorkflowRepositories.ScreenActions inventory;

    public GovernedActions(WorkflowRepositories.ScreenActions inventory) {
        this.inventory = inventory;
    }

    @Transactional(readOnly = true)
    public List<GovernedAction> all() {
        return inventory.findAllByOrderByScreenCodeAscActionCodeAsc().stream()
                .map(
                        a ->
                                new GovernedAction(
                                        a.getScreenCode(),
                                        a.getActionCode(),
                                        a.getActionLabel(),
                                        a.getDisabledReasonMsg()))
                .toList();
    }
}
