package in.gov.fci.payroll.workflow.orchestration;

import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.ApprovalStatuses;
import in.gov.fci.payroll.workflow.business.model.ApprovalItem;
import in.gov.fci.payroll.workflow.persistence.WorkflowRepositories;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Statuses of approval-queue items. A bean of its own, on the repository only: the access context
 * (which the authorisation policy, and so {@link ApprovalService}, depends on) reads it.
 */
@Component
public class ApprovalStatusReader implements ApprovalStatuses {

    private final WorkflowRepositories.Approvals approvals;

    public ApprovalStatusReader(WorkflowRepositories.Approvals approvals) {
        this.approvals = approvals;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, ApprovalStatus> of(Collection<Long> approvalIds) {
        Map<Long, ApprovalStatus> result = new HashMap<>();
        if (!approvalIds.isEmpty()) {
            approvals.findAllById(approvalIds).forEach(i -> result.put(i.getId(), i.getStatus()));
        }
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, String> deciders(Collection<Long> approvalIds) {
        Map<Long, String> result = new HashMap<>();
        if (!approvalIds.isEmpty()) {
            approvals
                    .findAllById(approvalIds)
                    .forEach(
                            i -> {
                                if (i.getDecidedBy() != null) {
                                    result.put(i.getId(), i.getDecidedBy());
                                }
                            });
        }
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Pending> pending(String entityName) {
        return approvals
                .findByEntityNameAndStatusOrderBySubmittedAtDesc(
                        entityName, ApprovalStatus.PENDING_APPROVAL)
                .stream()
                .map(ApprovalStatusReader::pendingOf)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Pending> pending(String entityName, String entityKey) {
        return approvals
                .findByEntityNameAndEntityKeyAndStatusOrderBySubmittedAtDescIdDesc(
                        entityName, entityKey, ApprovalStatus.PENDING_APPROVAL)
                .stream()
                .map(ApprovalStatusReader::pendingOf)
                .toList();
    }

    private static Pending pendingOf(ApprovalItem i) {
        return new Pending(
                i.getId(),
                i.getEntityKey(),
                i.getPayload(),
                i.getSubmittedBy(),
                i.getSubmittedAt());
    }
}
