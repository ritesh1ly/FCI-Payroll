package in.gov.fci.payroll.iam.business.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;

/**
 * {@code app_user_access_group} (target DDL S27): an effective-dated, revocable, audited
 * assignment. It confers access only when its {@code approval_id} names an APPROVED approval (HLD
 * §3.11.2, SRS-22-6).
 */
@Entity
@Table(name = "app_user_access_group")
public class AppUserAccessGroup {

    /** {@code revoke_cause} (target DDL v4.0 S27). */
    public enum RevokeCause {
        TRANSFER,
        SEPARATION,
        ROLE_CHANGE,
        ADMIN,
        EXPIRY
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "assignment_id")
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "access_group_id", nullable = false)
    private Long accessGroupId;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "assigned_by", nullable = false, length = 50)
    private String assignedBy;

    @Column(name = "otp_challenge_id")
    private Long otpChallengeId;

    @Column(name = "approval_id")
    private Long approvalId;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "revoke_cause", length = 15)
    private RevokeCause revokeCause;

    @Column(name = "revoke_reason", length = 500)
    private String revokeReason;

    protected AppUserAccessGroup() {}

    public static AppUserAccessGroup propose(
            Long userId,
            Long groupId,
            LocalDate from,
            LocalDate to,
            String assignedBy,
            Long otpChallengeId,
            Long approvalId) {
        if (to != null && to.isBefore(from)) {
            throw new IllegalArgumentException("effective_to before effective_from");
        }
        AppUserAccessGroup assignment = new AppUserAccessGroup();
        assignment.userId = userId;
        assignment.accessGroupId = groupId;
        assignment.effectiveFrom = from;
        assignment.effectiveTo = to;
        assignment.assignedBy = assignedBy;
        assignment.otpChallengeId = otpChallengeId;
        assignment.approvalId = approvalId;
        return assignment;
    }

    /** Within its dates and not revoked (the approval status is checked by the caller). */
    public boolean isCurrentOn(LocalDate day) {
        return revokedAt == null
                && !day.isBefore(effectiveFrom)
                && (effectiveTo == null || !day.isAfter(effectiveTo));
    }

    /**
     * Revocation takes effect immediately (ADR-0018 A10). The cause is recorded (target DDL v4.0
     * chk_user_access_group_revoke): a transfer or separation withdraws all rights (SRS-G-36).
     */
    public void revoke(RevokeCause cause, String reason, Instant now) {
        if (revokedAt != null) {
            throw new IllegalStateException("Already revoked");
        }
        this.revokedAt = now;
        this.revokeCause = java.util.Objects.requireNonNull(cause);
        this.revokeReason = reason;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getAccessGroupId() {
        return accessGroupId;
    }

    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    public LocalDate getEffectiveTo() {
        return effectiveTo;
    }

    public String getAssignedBy() {
        return assignedBy;
    }

    public Long getOtpChallengeId() {
        return otpChallengeId;
    }

    public Long getApprovalId() {
        return approvalId;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public RevokeCause getRevokeCause() {
        return revokeCause;
    }

    public String getRevokeReason() {
        return revokeReason;
    }
}
