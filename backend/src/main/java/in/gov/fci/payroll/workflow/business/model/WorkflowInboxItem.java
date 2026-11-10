package in.gov.fci.payroll.workflow.business.model;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.type.YesNoConverter;

/**
 * {@code workflow_inbox_item} (target DDL v4.0 S26): the work item of one approval, assigned to the
 * role and office of its current level. Kept in step with {@code approval_queue} by the same
 * service call (one workflow event, one write path). An overdue item stays flagged until actioned;
 * the escalation level counts how often it was moved on.
 */
@Entity
@Table(name = "workflow_inbox_item")
public class WorkflowInboxItem {

    /** {@code item_status}. */
    public enum Status {
        PENDING,
        IN_PROGRESS,
        COMPLETED,
        RETURNED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "inbox_item_id")
    private Long id;

    @Column(name = "approval_id", nullable = false)
    private Long approvalId;

    @Column(name = "assigned_role_code", length = 30)
    private String assignedRoleCode;

    @Column(name = "assigned_user_id")
    private Long assignedUserId;

    @Column(name = "org_scope_id")
    private Long orgScopeId;

    @Column(name = "priority", nullable = false, length = 10)
    private String priority;

    @Column(name = "sla_due_at")
    private Instant slaDueAt;

    @Convert(converter = YesNoConverter.class)
    @Column(name = "sla_breached_flag", nullable = false, length = 1)
    private Boolean slaBreached;

    @Column(name = "escalation_level", nullable = false)
    private Integer escalationLevel;

    @Column(name = "escalated_at")
    private Instant escalatedAt;

    /** BR-APPR-007: nobody holds the approving role in scope; flagged, never auto-approved. */
    @Convert(converter = YesNoConverter.class)
    @Column(name = "no_approver_flag", nullable = false, length = 1)
    private Boolean noApprover;

    @Enumerated(EnumType.STRING)
    @Column(name = "item_status", nullable = false, length = 15)
    private Status status;

    protected WorkflowInboxItem() {}

    public static WorkflowInboxItem open(
            Long approvalId, String roleCode, Long orgScopeId, Instant slaDueAt) {
        WorkflowInboxItem item = new WorkflowInboxItem();
        item.approvalId = approvalId;
        item.assignedRoleCode = roleCode;
        item.orgScopeId = orgScopeId;
        item.priority = "NORMAL";
        item.slaDueAt = slaDueAt;
        item.slaBreached = false;
        item.escalationLevel = 0;
        item.noApprover = false;
        item.status = Status.PENDING;
        return item;
    }

    /**
     * Hands the item to the next level's role and office: a new turnaround target, a cleared breach
     * flag (the previous level actioned it) and one more escalation step.
     */
    public void moveTo(String roleCode, Long orgScopeId, Instant slaDueAt, Instant now) {
        this.assignedRoleCode = roleCode;
        this.orgScopeId = orgScopeId;
        this.slaDueAt = slaDueAt;
        this.slaBreached = false;
        this.escalationLevel = escalationLevel == null ? 1 : escalationLevel + 1;
        this.escalatedAt = now;
    }

    public void close(boolean returned) {
        this.status = returned ? Status.RETURNED : Status.COMPLETED;
    }

    /** Flags (never clears) an SLA breach. */
    public boolean flagIfBreached(Instant now) {
        if (status == Status.PENDING
                && slaDueAt != null
                && now.isAfter(slaDueAt)
                && !isBreached()) {
            slaBreached = true;
            return true;
        }
        return false;
    }

    public boolean isBreached() {
        return Boolean.TRUE.equals(slaBreached);
    }

    public Long getId() {
        return id;
    }

    public Long getApprovalId() {
        return approvalId;
    }

    public String getAssignedRoleCode() {
        return assignedRoleCode;
    }

    public Long getOrgScopeId() {
        return orgScopeId;
    }

    public String getPriority() {
        return priority;
    }

    public Instant getSlaDueAt() {
        return slaDueAt;
    }

    public int getEscalationLevel() {
        return escalationLevel == null ? 0 : escalationLevel;
    }

    public boolean isNoApprover() {
        return Boolean.TRUE.equals(noApprover);
    }

    /** BR-APPR-007: flagged until someone in scope can act (cleared when the item moves on). */
    public void flagNoApprover(boolean flag) {
        this.noApprover = flag;
    }

    public Status getStatus() {
        return status;
    }
}
