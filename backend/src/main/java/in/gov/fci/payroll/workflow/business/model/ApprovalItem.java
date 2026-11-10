package in.gov.fci.payroll.workflow.business.model;

import in.gov.fci.payroll.platform.approval.ActionType;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * {@code approval_queue} (target DDL v4.0 S05): one maker submission, routed through the levels of
 * its workflow action (2 or 3; {@code current_level} starts at 2, the first checker). Only an
 * APPROVED row describes a change that reached the record. Separation of duties is on IDENTITY: the
 * decider can never be the submitter (here and {@code chk_approval_sod}); every level's decision is
 * in {@code approval_decision}.
 */
@Entity
@Table(name = "approval_queue")
public class ApprovalItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "approval_id")
    private Long id;

    @Column(name = "module_code", length = 40)
    private String moduleCode;

    @Column(name = "workflow_action_id")
    private Long workflowActionId;

    @Column(name = "level_count", nullable = false)
    private Integer levelCount;

    @Column(name = "current_level", nullable = false)
    private Integer currentLevel;

    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.CHAR)
    @Column(name = "no_approver_in_scope", nullable = false, length = 1)
    private String noApproverInScope;

    @Column(name = "flagged_to_hq_admin_at")
    private Instant flaggedToHqAdminAt;

    @Column(name = "entity_name", nullable = false, length = 100)
    private String entityName;

    @Column(name = "entity_key", nullable = false, length = 100)
    private String entityKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false, length = 20)
    private ActionType actionType;

    @Lob
    @Column(name = "payload", nullable = false)
    private String payload;

    @Column(name = "effective_date")
    private LocalDate effectiveDate;

    @Column(name = "impact_value", precision = 14, scale = 2)
    private BigDecimal impactValue;

    @Column(name = "submitted_by", nullable = false, length = 50)
    private String submittedBy;

    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ApprovalStatus status;

    @Column(name = "decided_by", length = 50)
    private String decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decision_reason", length = 1000)
    private String decisionReason;

    @Column(name = "return_reason", length = 1000)
    private String returnReason;

    @Column(name = "otp_challenge_id")
    private Long otpChallengeId;

    @Version
    @Column(name = "row_version", nullable = false)
    private Long rowVersion;

    protected ApprovalItem() {}

    public static ApprovalItem submit(
            String moduleCode,
            Long workflowActionId,
            int levelCount,
            String entityName,
            String entityKey,
            ActionType actionType,
            String payload,
            LocalDate effectiveDate,
            String submittedBy,
            String reason,
            BigDecimal impactValue,
            Long otpChallengeId,
            Instant now) {
        ApprovalItem item = new ApprovalItem();
        if (levelCount < 2 || levelCount > 3) {
            throw new IllegalArgumentException("An approval has two or three levels");
        }
        item.moduleCode = moduleCode;
        item.workflowActionId = workflowActionId;
        item.levelCount = levelCount;
        item.currentLevel = 2;
        item.noApproverInScope = "N";
        item.entityName = Objects.requireNonNull(entityName);
        item.entityKey = Objects.requireNonNull(entityKey);
        item.actionType = Objects.requireNonNull(actionType);
        item.payload = payload == null ? "{}" : payload;
        item.effectiveDate = effectiveDate;
        item.submittedBy = Objects.requireNonNull(submittedBy);
        item.submittedAt = now;
        item.status = ApprovalStatus.PENDING_APPROVAL;
        item.decisionReason = reason;
        item.impactValue = impactValue;
        item.otpChallengeId = otpChallengeId;
        return item;
    }

    /**
     * BR-APPR-007: nobody but the maker can decide the current level in scope; flagged to the HQ
     * Administrator (chk_approval_no_approver), never approved automatically.
     */
    public void flagNoApprover(Instant now) {
        noApproverInScope = "Y";
        if (flaggedToHqAdminAt == null) {
            flaggedToHqAdminAt = now;
        }
    }

    public boolean isNoApproverInScope() {
        return "Y".equals(noApproverInScope);
    }

    public Instant getFlaggedToHqAdminAt() {
        return flaggedToHqAdminAt;
    }

    /** Whether the current level is the last one (its approval is final). */
    public boolean isAtFinalLevel() {
        return currentLevel >= levelCount;
    }

    /** An approval below the final level moves the item to the next level; it stays pending. */
    public void advance() {
        if (status != ApprovalStatus.PENDING_APPROVAL || isAtFinalLevel()) {
            throw new IllegalStateException("No further level");
        }
        currentLevel = currentLevel + 1;
    }

    /** The final decision: approve at the last level, or reject / return at any level. */
    public void decide(ApprovalStatus outcome, String decider, String reason, Instant now) {
        if (status != ApprovalStatus.PENDING_APPROVAL) {
            throw new IllegalStateException("Not pending");
        }
        if (decider.equals(submittedBy)) {
            throw new IllegalArgumentException("Maker cannot be checker");
        }
        switch (outcome) {
            case APPROVED, REJECTED -> decisionReason = reason == null ? decisionReason : reason;
            case RETURNED -> {
                if (reason == null || reason.isBlank()) {
                    throw new IllegalArgumentException("A return needs a reason");
                }
                returnReason = reason;
            }
            default -> throw new IllegalArgumentException("Not a decision");
        }
        status = outcome;
        decidedBy = decider;
        decidedAt = now;
    }

    /**
     * Wave D review 9: the owner ended the record while the item still waited (superseded or
     * withdrawn), so the item is WITHDRAWN; who ended it and when are kept as its decision.
     */
    public void withdraw(String actor, Instant now) {
        if (status != ApprovalStatus.PENDING_APPROVAL
                && status != ApprovalStatus.PENDING_ATTACHMENT) {
            throw new IllegalStateException("Not pending");
        }
        status = ApprovalStatus.WITHDRAWN;
        decidedBy = actor;
        decidedAt = now;
    }

    public boolean isPending() {
        return status == ApprovalStatus.PENDING_APPROVAL;
    }

    public Long getId() {
        return id;
    }

    public String getModuleCode() {
        return moduleCode;
    }

    public Long getWorkflowActionId() {
        return workflowActionId;
    }

    public int getLevelCount() {
        return levelCount;
    }

    public int getCurrentLevel() {
        return currentLevel;
    }

    public String getEntityName() {
        return entityName;
    }

    public String getEntityKey() {
        return entityKey;
    }

    public ActionType getActionType() {
        return actionType;
    }

    public String getPayload() {
        return payload;
    }

    public LocalDate getEffectiveDate() {
        return effectiveDate;
    }

    public String getSubmittedBy() {
        return submittedBy;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public ApprovalStatus getStatus() {
        return status;
    }

    public String getDecidedBy() {
        return decidedBy;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public String getDecisionReason() {
        return decisionReason;
    }

    public String getReturnReason() {
        return returnReason;
    }

    public Long getOtpChallengeId() {
        return otpChallengeId;
    }

    public Long getRowVersion() {
        return rowVersion;
    }

    /** The value or impact shown to the checker (SRS-22-4); null if the change has none. */
    public BigDecimal getImpactValue() {
        return impactValue;
    }
}
