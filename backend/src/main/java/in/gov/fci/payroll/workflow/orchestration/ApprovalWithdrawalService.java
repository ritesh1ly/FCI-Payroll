package in.gov.fci.payroll.workflow.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.ApprovalWithdrawals;
import in.gov.fci.payroll.workflow.business.model.ApprovalItem;
import in.gov.fci.payroll.workflow.persistence.WorkflowRepositories;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Wave D review 9 (SRS-G-13, BR-APPR-002; DDL {@code approval_queue}, {@code workflow_inbox_item}):
 * the item of a record its owner ended while it waited is WITHDRAWN and its inbox task COMPLETED in
 * the same call (the DDL keeps the two in step through one write path), audited with the reason. A
 * bean of its own, on the repositories only, so any owner may depend on the port.
 */
@Component
public class ApprovalWithdrawalService implements ApprovalWithdrawals {

    private final WorkflowRepositories.Approvals approvals;
    private final WorkflowRepositories.Inbox inbox;
    private final AuditService audit;
    private final Clock clock;

    public ApprovalWithdrawalService(
            WorkflowRepositories.Approvals approvals,
            WorkflowRepositories.Inbox inbox,
            AuditService audit,
            Clock clock) {
        this.approvals = approvals;
        this.inbox = inbox;
        this.audit = audit;
        this.clock = clock;
    }

    @Override
    @Transactional
    public int withdraw(String entityName, String entityKey, String actor, String reason) {
        List<ApprovalItem> open = new ArrayList<>();
        for (ApprovalStatus waiting :
                List.of(ApprovalStatus.PENDING_APPROVAL, ApprovalStatus.PENDING_ATTACHMENT)) {
            open.addAll(
                    approvals.findByEntityNameAndEntityKeyAndStatusOrderBySubmittedAtDescIdDesc(
                            entityName, entityKey, waiting));
        }
        Instant now = clock.instant();
        for (ApprovalItem item : open) {
            String before = item.getStatus().name();
            item.withdraw(actor, now);
            approvals.save(item);
            inbox.findByApprovalId(item.getId())
                    .ifPresent(
                            task -> {
                                task.close(false);
                                inbox.save(task);
                            });
            audit.record(
                    actor,
                    AuditService.Entry.change(
                                    item.getEntityName(),
                                    item.getEntityKey(),
                                    "DECISION",
                                    before,
                                    ApprovalStatus.WITHDRAWN.name(),
                                    reason)
                            .approval(item.getId())
                            .module(item.getModuleCode()));
        }
        return open.size();
    }
}
