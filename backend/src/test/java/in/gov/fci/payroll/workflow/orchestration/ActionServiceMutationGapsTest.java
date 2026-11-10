package in.gov.fci.payroll.workflow.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import in.gov.fci.payroll.workflow.business.model.ScreenAction;
import in.gov.fci.payroll.workflow.persistence.WorkflowRepositories;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * SRS-G-21 mutation gap: the audit row of an action without a route carries the Maker's reason, or
 * "Action performed" when none was given.
 */
class ActionServiceMutationGapsTest {

    private final WorkflowRepositories.ScreenActions inventory =
            mock(WorkflowRepositories.ScreenActions.class);
    private final Authorization policy = mock(Authorization.class);
    private final ApprovalService approvals = mock(ApprovalService.class);
    private final AuditService audit = mock(AuditService.class);
    private final ActionService service = new ActionService(inventory, policy, approvals, audit);
    private final AccessContext context = mock(AccessContext.class);

    private List<AuditService.Entry> recordedReasons(String reason) {
        when(context.loginId()).thenReturn("ddo.delhi@fci.test");
        when(context.locationId()).thenReturn(10L);
        when(inventory.findByScreenCodeAndActionCode("M-07", "EXPORT"))
                .thenReturn(Optional.of(mock(ScreenAction.class)));
        when(approvals.actionInForce(Submission.workflowActionOf("M-07", "EXPORT")))
                .thenReturn(Optional.empty());
        service.perform(
                context,
                new ActionService.ActionRequest("M-07", "EXPORT", null, null, reason, null));
        ArgumentCaptor<AuditService.Entry> entry =
                ArgumentCaptor.forClass(AuditService.Entry.class);
        verify(audit, times(1)).record(eq("ddo.delhi@fci.test"), entry.capture());
        return entry.getAllValues();
    }

    @Test
    void SRS_G_21_aGivenReasonIsRecordedAsIs() {
        AuditService.Entry entry = recordedReasons("Monthly copy").get(0);
        assertThat(entry.reason()).isEqualTo("Monthly copy");
        assertThat(entry.entityName()).isEqualTo("screen_action");
        assertThat(entry.entityKey()).isEqualTo("M-07:EXPORT");
        assertThat(entry.newValue()).isEqualTo("RECORDED");
        assertThat(entry.moduleCode()).isEqualTo("M-07");
    }

    @Test
    void SRS_G_21_withoutAReasonTheRowSaysActionPerformed() {
        assertThat(recordedReasons(null).get(0).reason()).isEqualTo("Action performed");
    }
}
