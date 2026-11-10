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
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.workflow.business.model.ApprovalItem;
import in.gov.fci.payroll.workflow.business.model.WorkflowInboxItem;
import in.gov.fci.payroll.workflow.persistence.WorkflowRepositories;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Wave D review 9 (SRS-G-13, BR-APPR-002): the waiting item of a record its owner ended is
 * WITHDRAWN with its inbox task COMPLETED and audited; an item without an inbox task is still
 * withdrawn; a record with nothing waiting changes nothing; a decided item cannot be withdrawn.
 */
class ApprovalWithdrawalServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-02T05:00:00Z");

    private final WorkflowRepositories.Approvals approvals =
            mock(WorkflowRepositories.Approvals.class);
    private final WorkflowRepositories.Inbox inbox = mock(WorkflowRepositories.Inbox.class);
    private final AuditService audit = mock(AuditService.class);
    private final ApprovalWithdrawalService service =
            new ApprovalWithdrawalService(
                    approvals, inbox, audit, Clock.fixed(NOW, ZoneOffset.UTC));

    private static ApprovalItem item() {
        return ApprovalItem.submit(
                "I-21",
                1L,
                2,
                "hrms_change_task",
                "5",
                ActionType.UPDATE,
                "{}",
                null,
                "maker",
                "Maker's reason",
                null,
                null,
                NOW.minusSeconds(3600));
    }

    private void waiting(ApprovalStatus status, List<ApprovalItem> items) {
        when(approvals.findByEntityNameAndEntityKeyAndStatusOrderBySubmittedAtDescIdDesc(
                        "hrms_change_task", "5", status))
                .thenReturn(items);
    }

    @Test
    void SRS_G_13_aWaitingItemIsWithdrawnItsInboxTaskCompletedAndAudited() {
        ApprovalItem pending = item();
        waiting(ApprovalStatus.PENDING_APPROVAL, List.of(pending));
        waiting(ApprovalStatus.PENDING_ATTACHMENT, List.of());
        WorkflowInboxItem task = WorkflowInboxItem.open(1L, "PAYROLL_CHECKER", 10L, null);
        when(inbox.findByApprovalId(any())).thenReturn(Optional.of(task));

        assertThat(service.withdraw("hrms_change_task", "5", "system.hrms", "Superseded"))
                .isEqualTo(1);

        assertThat(pending.getStatus()).isEqualTo(ApprovalStatus.WITHDRAWN);
        assertThat(pending.getDecidedBy()).isEqualTo("system.hrms");
        assertThat(pending.getDecidedAt()).isEqualTo(NOW);
        assertThat(pending.getDecisionReason())
                .as("the Maker's text is kept")
                .isEqualTo("Maker's reason");
        assertThat(task.getStatus()).isEqualTo(WorkflowInboxItem.Status.COMPLETED);
        verify(approvals).save(pending);
        verify(inbox).save(task);
        ArgumentCaptor<AuditService.Entry> entry =
                ArgumentCaptor.forClass(AuditService.Entry.class);
        verify(audit).record(eq("system.hrms"), entry.capture());
        assertThat(entry.getValue().oldValue()).isEqualTo("PENDING_APPROVAL");
        assertThat(entry.getValue().newValue()).isEqualTo("WITHDRAWN");
        assertThat(entry.getValue().reason()).isEqualTo("Superseded");
        assertThat(entry.getValue().moduleCode()).isEqualTo("I-21");
    }

    @Test
    void SRS_G_13_anItemWithoutAnInboxTaskIsStillWithdrawn() {
        ApprovalItem pending = item();
        waiting(ApprovalStatus.PENDING_APPROVAL, List.of());
        waiting(ApprovalStatus.PENDING_ATTACHMENT, List.of(pending));
        when(inbox.findByApprovalId(any())).thenReturn(Optional.empty());

        assertThat(service.withdraw("hrms_change_task", "5", "u", "r")).isEqualTo(1);
        assertThat(pending.getStatus()).isEqualTo(ApprovalStatus.WITHDRAWN);
        verify(inbox, never()).save(any());
    }

    @Test
    void SRS_G_13_aRecordWithNothingWaitingChangesNothing() {
        waiting(ApprovalStatus.PENDING_APPROVAL, List.of());
        waiting(ApprovalStatus.PENDING_ATTACHMENT, List.of());

        assertThat(service.withdraw("hrms_change_task", "5", "u", "r")).isZero();
        verify(approvals, never()).save(any());
        verify(audit, never()).record(anyString(), any(AuditService.Entry.class));
    }

    @Test
    void SRS_G_13_aDecidedItemCannotBeWithdrawn() {
        ApprovalItem decided = item();
        decided.decide(ApprovalStatus.APPROVED, "checker", null, NOW);
        assertThatThrownBy(() -> decided.withdraw("u", NOW))
                .isInstanceOf(IllegalStateException.class);
        assertThat(decided.getStatus()).isEqualTo(ApprovalStatus.APPROVED);
    }
}
