package in.gov.fci.payroll.iam.business.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;

/**
 * {@code app_user_role} (target DDL S27). Holds the eSeva role (no access group: its scope is the
 * user's own employee record) and mirrors the role of every access-group assignment.
 */
@Entity
@Table(name = "app_user_role")
public class AppUserRole {

    /** Composite key (user, role). */
    @Embeddable
    public static class Key implements Serializable {

        private static final long serialVersionUID = 1L;

        @Column(name = "user_id", nullable = false)
        private Long userId;

        @Column(name = "role_id", nullable = false)
        private Long roleId;

        protected Key() {}

        public Key(Long userId, Long roleId) {
            this.userId = userId;
            this.roleId = roleId;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key
                    && Objects.equals(userId, key.userId)
                    && Objects.equals(roleId, key.roleId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, roleId);
        }
    }

    @EmbeddedId private Key key;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "otp_challenge_id")
    private Long otpChallengeId;

    @Column(name = "approval_id")
    private Long approvalId;

    protected AppUserRole() {}

    public AppUserRole(Long userId, Long roleId, LocalDate from, Long approvalId, Long otpId) {
        this.key = new Key(userId, roleId);
        this.effectiveFrom = from;
        this.approvalId = approvalId;
        this.otpChallengeId = otpId;
    }

    public boolean isActiveOn(LocalDate day) {
        return !day.isBefore(effectiveFrom) && (effectiveTo == null || !day.isAfter(effectiveTo));
    }

    public void endOn(LocalDate day) {
        this.effectiveTo = day;
    }

    public Long getUserId() {
        return key.userId;
    }

    public Long getRoleId() {
        return key.roleId;
    }

    public Long getApprovalId() {
        return approvalId;
    }

    public LocalDate getEffectiveTo() {
        return effectiveTo;
    }
}
