package in.gov.fci.payroll.workflow.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.platform.approval.ActionType;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import in.gov.fci.payroll.workflow.business.model.ApprovalItem;
import in.gov.fci.payroll.workflow.business.model.ScreenAction;
import in.gov.fci.payroll.workflow.business.model.WorkflowAction;
import in.gov.fci.payroll.workflow.persistence.WorkflowRepositories;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Generic screen actions (SRS-G-1, SRS-G-21; R-MC): an action not in the inventory, an approval or
 * a route's own authorise permission is never performed here; an action without a route is checked
 * at the office and recorded; a routed one goes to the approval queue - a create, a delete (as a
 * deactivation) or an update, its own key or a generated one, a lock under the lock entity.
 */
class ActionServiceTest {

    private final WorkflowRepositories.ScreenActions inventory =
            mock(WorkflowRepositories.ScreenActions.class);
    private final Authorization policy = mock(Authorization.class);
    private final ApprovalService approvals = mock(ApprovalService.class);
    private final AuditService audit = mock(AuditService.class);
    private final ActionService service = new ActionService(inventory, policy, approvals, audit);
    private final AccessContext context = mock(AccessContext.class);

    private static ActionService.ActionRequest request(
            String action, Long location, String key, String reason) {
        return new ActionService.ActionRequest("M-07", action, location, key, reason, null);
    }

    private void listed(String action, boolean routed) {
        when(inventory.findByScreenCodeAndActionCode("M-07", action))
                .thenReturn(Optional.of(mock(ScreenAction.class)));
        when(approvals.actionInForce(Submission.workflowActionOf("M-07", action)))
                .thenReturn(routed ? Optional.of(mock(WorkflowAction.class)) : Optional.empty());
    }

    @Test
    void SRS_G_1_anUnlistedActionAnApprovalOrAnAuthoriseIsNeverPerformedHere() {
        when(inventory.findByScreenCodeAndActionCode(anyString(), anyString()))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.perform(context, request("CREATE", null, null, null)))
                .extracting(e -> ((FciException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.NOT_FOUND);
        for (String action : new String[] {"APPROVE", "M07_AUTHORISE"}) {
            listed(action, true);
            assertThatThrownBy(() -> service.perform(context, request(action, null, null, null)))
                    .extracting(e -> ((FciException) e).getErrorCode())
                    .isEqualTo(CommonErrorCode.NOT_FOUND);
        }
        verify(approvals, never()).submitItem(any(), any(), any());
    }

    @Test
    void SRS_G_21_anActionWithoutARouteIsCheckedAtTheOfficeAndRecorded() {
        when(context.locationId()).thenReturn(10L);
        listed("EXPORT", false);
        assertThat(service.perform(context, request("EXPORT", null, null, null)).outcome())
                .isEqualTo(ActionService.Outcome.RECORDED);
        verify(policy).requireAt(context, "M-07", "EXPORT", 10L);
        assertThat(
                        service.perform(context, request("EXPORT", 11L, null, "Monthly copy"))
                                .approvalId())
                .isNull();
        verify(policy).requireAt(context, "M-07", "EXPORT", 11L);
        verify(audit, org.mockito.Mockito.times(2)).record(any(), any());
    }

    private Submission submitted(String action, String key) {
        listed(action, true);
        ApprovalItem item = mock(ApprovalItem.class);
        when(item.getId()).thenReturn(77L);
        when(approvals.submitItem(eq(context), any(), any())).thenReturn(item);
        assertThat(service.perform(context, request(action, 10L, key, "Order")).approvalId())
                .isEqualTo(77L);
        ArgumentCaptor<Submission> sent = ArgumentCaptor.forClass(Submission.class);
        verify(approvals, org.mockito.Mockito.atLeastOnce())
                .submitItem(eq(context), sent.capture(), any());
        return sent.getValue();
    }

    @Test
    void SRS_G_1_aRoutedActionIsQueuedADeleteAsADeactivationAndALockUnderTheLockEntity() {
        assertThat(submitted("CREATE", " ").actionType()).isEqualTo(ActionType.CREATE);
        Submission delete = submitted("DELETE", "PC-140");
        assertThat(delete.actionType()).isEqualTo(ActionType.DEACTIVATE);
        assertThat(delete.entityKey()).isEqualTo("PC-140");
        Submission update = submitted("EDIT", null);
        assertThat(update.actionType()).isEqualTo(ActionType.UPDATE);
        assertThat(update.entityKey()).startsWith("M-07-");
        assertThat(update.entityName()).isEqualTo("screen:M-07");
        assertThat(submitted("LOCK", null).entityName()).isEqualTo(ApprovalService.LOCK_ENTITY);
    }
}
