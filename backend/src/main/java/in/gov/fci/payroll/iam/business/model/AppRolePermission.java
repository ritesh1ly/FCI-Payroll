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
 * {@code app_role_permission} (target DDL S27), authoritative for what a role may do; effective
 * dated.
 */
@Entity
@Table(name = "app_role_permission")
public class AppRolePermission {

    /** Composite key (role, permission). */
    @Embeddable
    public static class Key implements Serializable {

        private static final long serialVersionUID = 1L;

        @Column(name = "role_id", nullable = false)
        private Long roleId;

        @Column(name = "permission_id", nullable = false)
        private Long permissionId;

        protected Key() {}

        public Key(Long roleId, Long permissionId) {
            this.roleId = roleId;
            this.permissionId = permissionId;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key
                    && Objects.equals(roleId, key.roleId)
                    && Objects.equals(permissionId, key.permissionId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(roleId, permissionId);
        }
    }

    @EmbeddedId private Key key;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    protected AppRolePermission() {}

    public AppRolePermission(Long roleId, Long permissionId, LocalDate from) {
        this.key = new Key(roleId, permissionId);
        this.effectiveFrom = from;
    }

    public boolean isActiveOn(LocalDate day) {
        return !day.isBefore(effectiveFrom) && (effectiveTo == null || !day.isAfter(effectiveTo));
    }

    /** Re-grants an end-dated permission from {@code day}. */
    public void grantFrom(LocalDate day) {
        this.effectiveFrom = day;
        this.effectiveTo = null;
    }

    /** Effective-dated revocation: the grant ends at the end of the day before {@code day}. */
    public void endBefore(LocalDate day) {
        this.effectiveTo = day.minusDays(1);
    }

    public Long getRoleId() {
        return key.roleId;
    }

    public Long getPermissionId() {
        return key.permissionId;
    }

    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    public LocalDate getEffectiveTo() {
        return effectiveTo;
    }
}
