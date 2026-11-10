package in.gov.fci.payroll.workflow.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.web.Paging;
import in.gov.fci.payroll.workflow.business.WorkflowRules;
import in.gov.fci.payroll.workflow.business.model.WorkflowAction.OfficeLevel;
import in.gov.fci.payroll.workflow.orchestration.WorkflowMasterService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * A-08 proposals (SRS-22-19): a route without a screen, or with a blank one, names none; a named
 * screen is kept, and a missing bulk-approval flag is No (SRS-G-13).
 */
class WorkflowMasterControllerBranchGapsTest {

    private final WorkflowMasterService master = mock(WorkflowMasterService.class);
    private final WorkflowMasterController controller =
            new WorkflowMasterController(mock(CurrentAccess.class), master, mock(Paging.class));

    private static WorkflowMasterController.RouteRequest request(String screen, Boolean bulk) {
        return new WorkflowMasterController.RouteRequest(
                "C13_CREATE",
                "Batch job",
                "C-13",
                screen,
                OfficeLevel.DO,
                2,
                null,
                false,
                24,
                null,
                List.of(
                        new WorkflowMasterController.LevelRequest(
                                1, "PAYROLL_MAKER", OfficeLevel.DO, false)),
                bulk,
                "New route");
    }

    @Test
    void SRS_22_19_aMissingOrBlankScreenIsNoneAndANamedOneIsKept() {
        assertThat(controller.propose(request(null, null)).getStatusCode().value()).isEqualTo(202);
        controller.propose(request("   ", false));
        controller.propose(request("C-13", true));
        ArgumentCaptor<WorkflowRules.ProposedRoute> routes = ArgumentCaptor.captor();
        verify(master, times(3)).propose(any(), routes.capture(), eq("New route"));
        assertThat(routes.getAllValues())
                .extracting(WorkflowRules.ProposedRoute::screenCode)
                .containsExactly(null, null, "C-13");
        assertThat(routes.getAllValues())
                .extracting(WorkflowRules.ProposedRoute::bulkApprovalAllowed)
                .containsExactly(false, false, true);
        assertThat(routes.getValue().levels())
                .containsExactly(
                        new WorkflowRules.ProposedLevel(1, "PAYROLL_MAKER", OfficeLevel.DO, false));
    }
}
