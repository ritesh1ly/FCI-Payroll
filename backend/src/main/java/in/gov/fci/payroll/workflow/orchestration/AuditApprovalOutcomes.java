package in.gov.fci.payroll.workflow.orchestration;

import in.gov.fci.payroll.audit.api.ApprovalOutcomes;
import in.gov.fci.payroll.workflow.business.model.ApprovalItem;
import in.gov.fci.payroll.workflow.persistence.WorkflowRepositories;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The approval behind audit rows, for X-04 (status, maker, checker; SRS-G-2): {@code audit_event}
 * keeps the approval id, the state lives here on {@code approval_queue}.
 */
@Component
public class AuditApprovalOutcomes implements ApprovalOutcomes {

    private final WorkflowRepositories.Approvals approvals;

    public AuditApprovalOutcomes(WorkflowRepositories.Approvals approvals) {
        this.approvals = approvals;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, Outcome> outcomes(Collection<Long> approvalIds) {
        Map<Long, Outcome> result = new LinkedHashMap<>();
        if (approvalIds == null || approvalIds.isEmpty()) {
            return result;
        }
        for (ApprovalItem item : approvals.findAllById(approvalIds)) {
            result.put(
                    item.getId(),
                    new Outcome(
                            item.getId(),
                            item.getStatus() == null ? null : item.getStatus().name(),
                            item.getSubmittedBy(),
                            item.getDecidedBy(),
                            item.getDecidedAt(),
                            item.getModuleCode()));
        }
        return result;
    }
}
