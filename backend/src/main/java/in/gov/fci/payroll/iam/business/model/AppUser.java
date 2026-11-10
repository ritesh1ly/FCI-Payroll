package in.gov.fci.payroll.iam.business.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Locale;

/**
 * {@code app_user} (target DDL S27). HLD AD-05: application-managed login; the login ID is the
 * official e-mail address; no SSO and no login-time MFA.
 */
@Entity
@Table(name = "app_user")
public class AppUser {

    /** {@code user_category}. */
    public enum Category {
        OPERATIONS,
        ESEVA,
        SYSTEM
    }

    /** {@code user_status}. */
    public enum Status {
        ACTIVE,
        LOCKED,
        SUSPENDED,
        DEACTIVATED
    }

    /** {@code deactivation_reason}. */
    public enum DeactivationReason {
        SEPARATION,
        ADMIN,
        BULK_SUSPEND,
        OTHER
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_id")
    private Long id;

    @Column(name = "login_id", nullable = false, length = 150)
    private String loginId;

    @Column(name = "employee_id")
    private Long employeeId;

    @Column(name = "display_name", length = 150)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "user_category", nullable = false, length = 15)
    private Category category;

    @Column(name = "registered_mobile", length = 20)
    private String registeredMobile;

    @Column(name = "registered_email", length = 150)
    private String registeredEmail;

    @Enumerated(EnumType.STRING)
    @Column(name = "user_status", nullable = false, length = 20)
    private Status status;

    @Column(name = "deactivated_at")
    private Instant deactivatedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "deactivation_reason", length = 30)
    private DeactivationReason deactivationReason;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    /** The I-17 SEPARATION / DISMISSAL that deactivated the account (target DDL v4.0, SRS-G-36). */
    @Column(name = "deactivation_event_id")
    private Long deactivationEventId;

    /** The unit shown on the Active User Report (target DDL v4.0). */
    @Column(name = "home_location_id")
    private Long homeLocationId;

    @Column(name = "created_by", length = 50, updatable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    @Column(name = "row_version", nullable = false)
    private Long rowVersion;

    protected AppUser() {}

    public static AppUser create(
            String loginId,
            Long employeeId,
            String displayName,
            Category category,
            String mobile,
            String email,
            Long homeLocationId,
            String createdBy,
            Instant now) {
        AppUser user = new AppUser();
        user.homeLocationId = homeLocationId;
        user.createdBy =
                createdBy == null || createdBy.length() <= 50
                        ? createdBy
                        : createdBy.substring(0, 50);
        user.createdAt = now;
        user.loginId = normaliseLogin(loginId);
        user.employeeId = employeeId;
        user.displayName = displayName;
        user.category = category;
        user.registeredMobile = mobile;
        user.registeredEmail = email == null ? null : email.toLowerCase(Locale.ROOT);
        user.status = Status.ACTIVE;
        return user;
    }

    /** Login IDs are e-mail addresses: compared trimmed and lower-case. */
    public static String normaliseLogin(String loginId) {
        return loginId == null ? null : loginId.strip().toLowerCase(Locale.ROOT);
    }

    public void updateContact(String displayName, String mobile, String email) {
        this.displayName = displayName;
        this.registeredMobile = mobile;
        this.registeredEmail = email == null ? null : email.toLowerCase(Locale.ROOT);
    }

    public void changeStatus(Status next, DeactivationReason reason, Instant now) {
        if (status == Status.DEACTIVATED) {
            throw new IllegalStateException("A deactivated user is never reactivated");
        }
        this.status = next;
        if (next == Status.DEACTIVATED) {
            this.deactivatedAt = now;
            this.deactivationReason = reason == null ? DeactivationReason.ADMIN : reason;
        }
    }

    /** SRS-G-36: deactivated in the same act that records the holder's separation (I-17 event). */
    public void deactivateForSeparation(Long lifecycleEventId, Instant now) {
        if (status == Status.DEACTIVATED) {
            return;
        }
        changeStatus(Status.DEACTIVATED, DeactivationReason.SEPARATION, now);
        this.deactivationEventId = lifecycleEventId;
    }

    public void recordLogin(Instant now) {
        this.lastLoginAt = now;
    }

    public Long getId() {
        return id;
    }

    public String getLoginId() {
        return loginId;
    }

    public Long getEmployeeId() {
        return employeeId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Category getCategory() {
        return category;
    }

    public String getRegisteredMobile() {
        return registeredMobile;
    }

    public String getRegisteredEmail() {
        return registeredEmail;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getDeactivatedAt() {
        return deactivatedAt;
    }

    public DeactivationReason getDeactivationReason() {
        return deactivationReason;
    }

    /** When the account was created (the Active User Report counts inactivity from here). */
    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    public Long getRowVersion() {
        return rowVersion;
    }

    @Override
    public String toString() {
        return "AppUser{id=" + id + ", status=" + status + "}";
    }
}
