package in.gov.fci.payroll.workflow.api;

import in.gov.fci.payroll.workflow.business.model.WorkflowAction;
import in.gov.fci.payroll.workflow.persistence.WorkflowRepositories;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The screens whose changes are proposed at Headquarters only: every approval route of the screen
 * in force on the day is maintained at HQ (the Approval Workflow Master, SRS-22-16 office level),
 * so a maker signed in at any other office is refused at submission (AUTHZ-403-HQ-ONLY). The
 * screens show such an action disabled with that reason instead of letting the user fill the form
 * first. Routes are configuration: this follows them, never a list in code.
 */
@Component
public class HeadquartersOnlyScreens {

    private final WorkflowRepositories.Actions actions;

    public HeadquartersOnlyScreens(WorkflowRepositories.Actions actions) {
        this.actions = actions;
    }

    /** The screen codes whose routes in force on {@code day} are all at Headquarters. */
    @Transactional(readOnly = true)
    public Set<String> on(LocalDate day) {
        return of(actions.findAll(), day);
    }

    static Set<String> of(List<WorkflowAction> routes, LocalDate day) {
        Map<String, List<WorkflowAction>> inForce =
                routes.stream()
                        .filter(a -> a.getScreenCode() != null && a.isActiveOn(day))
                        .collect(Collectors.groupingBy(WorkflowAction::getScreenCode));
        return inForce.entrySet().stream()
                .filter(e -> e.getValue().stream().allMatch(WorkflowAction::isHeadquartersOnly))
                .map(Map.Entry::getKey)
                .collect(Collectors.toCollection(TreeSet::new));
    }
}
