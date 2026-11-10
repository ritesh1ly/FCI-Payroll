package in.gov.fci.payroll.iam.business.model;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import org.hibernate.type.YesNoConverter;

/**
 * {@code app_role} (target DDL v4.0 S27). The role catalogue is DATA, not an enum: five of thirteen
 * roles are provisional. A MODULE role (Rule Approver, Super User …) maps to one of the seven
 * functional roles through {@code base_role_code}, as configuration; a READ_ONLY role has none.
 */
@Entity
@Table(name = "app_role")
public class AppRole {

    /** {@code app_role.role_type}. */
    public enum RoleType {
        FUNCTIONAL,
        MODULE,
        READ_ONLY
    }

    /** {@code app_role.base_role_code}: the seven functional roles of FRS V0.5 §3. */
    public enum BaseRole {
        MAKER,
        CHECKER,
        LDH,
        HR,
        FIN,
        ADMIN,
        EMP
    }

    /** {@code app_role.role_status}. */
    public enum Status {
        SETTLED,
        PROVISIONAL,
        RETIRED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "role_id")
    private Long id;

    @Column(name = "role_code", nullable = false, length = 30)
    private String code;

    @Column(name = "role_name", nullable = false, length = 150)
    private String name;

    @Column(name = "description", length = 500)
    private String description;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(name = "role_status", nullable = false, length = 15)
    private Status status;

    @Convert(converter = YesNoConverter.class)
    @Column(name = "is_approver", nullable = false, length = 1)
    private Boolean approver;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Enumerated(EnumType.STRING)
    @Column(name = "role_type", nullable = false, length = 10)
    private RoleType roleType;

    @Enumerated(EnumType.STRING)
    @Column(name = "base_role_code", length = 10)
    private BaseRole baseRole;

    protected AppRole() {}

    public static AppRole create(
            String code,
            String name,
            String description,
            boolean approver,
            RoleType roleType,
            BaseRole baseRole,
            LocalDate from) {
        if (roleType != RoleType.READ_ONLY && baseRole == null) {
            throw new IllegalArgumentException("A functional or module role needs its base role");
        }
        AppRole role = new AppRole();
        role.roleType = roleType;
        role.baseRole = roleType == RoleType.READ_ONLY ? null : baseRole;
        role.code = code;
        role.name = name;
        role.description = description;
        role.status = Status.PROVISIONAL;
        role.approver = approver;
        role.effectiveFrom = from;
        return role;
    }

    public void update(String name, String description, boolean approver) {
        this.name = name;
        this.description = description;
        this.approver = approver;
    }

    public void retire(LocalDate day) {
        this.status = Status.RETIRED;
        this.effectiveTo = day;
    }

    public boolean isActiveOn(LocalDate day) {
        return status != Status.RETIRED
                && !day.isBefore(effectiveFrom)
                && (effectiveTo == null || !day.isAfter(effectiveTo));
    }

    public RoleType getRoleType() {
        return roleType;
    }

    /** The functional role this role acts as ({@code base_role_code}); null for READ_ONLY. */
    public BaseRole getBaseRole() {
        return baseRole;
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public Status getStatus() {
        return status;
    }

    public boolean isApprover() {
        return Boolean.TRUE.equals(approver);
    }

    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    public LocalDate getEffectiveTo() {
        return effectiveTo;
    }
}
