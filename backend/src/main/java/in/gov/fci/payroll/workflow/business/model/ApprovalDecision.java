package in.gov.fci.payroll.workflow.business.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import org.hibernate.annotations.Immutable;

/**
 * {@code approval_decision} (target DDL v4.0 S05, X-02 decision trail): one decision at one level
 * of an item. A return or rejection always carries a reason; the decider is never the maker
 * (chk_appr_decision_sod). Written once.
 */
@Entity
@Immutable
@Table(name = "approval_decision")
public class ApprovalDecision {

    /** {@code decision}. */
    public enum Kind {
        APPROVE,
        RETURN,
        REJECT
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "decision_id")
    private Long id;

    @Column(name = "approval_id", nullable = false)
    private Long approvalId;

    @Column(name = "level_no", nullable = false)
    private Integer levelNo;

    @Column(name = "workflow_level_id")
    private Long workflowLevelId;

    @Column(name = "submitted_by", nullable = false, length = 50)
    private String submittedBy;

    @Column(name = "decided_by", nullable = false, length = 50)
    private String decidedBy;

    @Column(name = "acting_role_code", nullable = false, length = 30)
    private String actingRoleCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", nullable = false, length = 10)
    private Kind decision;

    @Column(name = "decision_reason", length = 1000)
    private String reason;

    @Column(name = "otp_challenge_id")
    private Long otpChallengeId;

    /** Sec 6.4, SRS-G-13 (US-GAP-21): the bulk approval this decision was taken in, if any. */
    @Column(name = "bulk_approval_ref", length = 40)
    private String bulkApprovalRef;

    @Column(name = "decided_at", nullable = false)
    private Instant decidedAt;

    protected ApprovalDecision() {}

    public static ApprovalDecision record(
            ApprovalItem item,
            WorkflowLevel level,
            String decidedBy,
            String actingRoleCode,
            Kind decision,
            String reason,
            Long otpChallengeId,
            Instant now) {
        return record(
                item,
                level,
                decidedBy,
                actingRoleCode,
                decision,
                reason,
                otpChallengeId,
                now,
                null);
    }

    /**
     * As above, taken in a bulk approval (SRS-G-13, UC-07 A2): {@code bulkApprovalRef} names it
     * (Sec 6.4); null for a decision taken on its own.
     */
    public static ApprovalDecision record(
            ApprovalItem item,
            WorkflowLevel level,
            String decidedBy,
            String actingRoleCode,
            Kind decision,
            String reason,
            Long otpChallengeId,
            Instant now,
            String bulkApprovalRef) {
        if (decidedBy.equals(item.getSubmittedBy())) {
            throw new IllegalArgumentException("Maker cannot decide");
        }
        if (decision != Kind.APPROVE && (reason == null || reason.isBlank())) {
            throw new IllegalArgumentException("A return or rejection needs a reason");
        }
        ApprovalDecision row = new ApprovalDecision();
        row.approvalId = Objects.requireNonNull(item.getId());
        row.levelNo = item.getCurrentLevel();
        row.workflowLevelId = level == null ? null : level.getId();
        row.submittedBy = item.getSubmittedBy();
        row.decidedBy = decidedBy;
        row.actingRoleCode = Objects.requireNonNull(actingRoleCode);
        row.decision = decision;
        row.reason = reason;
        row.otpChallengeId = otpChallengeId;
        row.bulkApprovalRef = bulkApprovalRef;
        row.decidedAt = now;
        return row;
    }

    public String getBulkApprovalRef() {
        return bulkApprovalRef;
    }

    public Long getId() {
        return id;
    }

    public Long getApprovalId() {
        return approvalId;
    }

    public int getLevelNo() {
        return levelNo;
    }

    public String getDecidedBy() {
        return decidedBy;
    }

    public String getActingRoleCode() {
        return actingRoleCode;
    }

    public Kind getDecision() {
        return decision;
    }

    public String getReason() {
        return reason;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }
}
