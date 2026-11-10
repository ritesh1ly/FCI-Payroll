package in.gov.fci.payroll.iam.business.model;

import in.gov.fci.payroll.platform.security.AccessMode;
import in.gov.fci.payroll.platform.security.ScopeType;
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
 * {@code access_group} (target DDL S27): THE PAIRING OF ONE ROLE WITH AN ORGANISATIONAL SCOPE
 * (SRS-22-2). Effective dated; assignable only once approved by a Payroll Checker (SRS-22-6). Only
 * a Headquarters-scoped group can carry the consolidated read-only view (SRS-22-3).
 */
@Entity
@Table(name = "access_group")
public class AccessGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "access_group_id")
    private Long id;

    @Column(name = "group_code", nullable = false, length = 40)
    private String code;

    @Column(name = "group_name", nullable = false, length = 200)
    private String name;

    @Column(name = "role_id", nullable = false)
    private Long roleId;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false, length = 15)
    private ScopeType scopeType;

    @Convert(converter = YesNoConverter.class)
    @Column(name = "consolidated_view", nullable = false, length = 1)
    private Boolean consolidatedView;

    @Column(name = "version_no", nullable = false)
    private Integer versionNo;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "approval_id")
    private Long approvalId;

    @Enumerated(EnumType.STRING)
    @Column(name = "access_mode", nullable = false, length = 15)
    private AccessMode accessMode;

    @Column(name = "otp_challenge_id")
    private Long otpChallengeId;

    protected AccessGroup() {}

    public static AccessGroup define(
            String code,
            String name,
            Long roleId,
            ScopeType scopeType,
            boolean consolidatedView,
            AccessMode accessMode,
            LocalDate from,
            Long approvalId,
            Long otpChallengeId) {
        if (consolidatedView
                && scopeType != ScopeType.HEADQUARTERS
                && scopeType != ScopeType.GLOBAL) {
            throw new IllegalArgumentException(
                    "Only a Headquarters or global group can be consolidated");
        }
        AccessGroup group = new AccessGroup();
        group.code = code;
        group.name = name;
        group.roleId = roleId;
        group.scopeType = scopeType;
        group.consolidatedView = consolidatedView;
        group.versionNo = 1;
        group.effectiveFrom = from;
        group.approvalId = approvalId;
        group.accessMode = accessMode == null ? AccessMode.FULL : accessMode;
        group.otpChallengeId = otpChallengeId;
        return group;
    }

    public boolean isActiveOn(LocalDate day) {
        return !day.isBefore(effectiveFrom) && (effectiveTo == null || !day.isAfter(effectiveTo));
    }

    public void endOn(LocalDate day) {
        this.effectiveTo = day;
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

    public Long getRoleId() {
        return roleId;
    }

    public ScopeType getScopeType() {
        return scopeType;
    }

    public boolean isConsolidatedView() {
        return Boolean.TRUE.equals(consolidatedView);
    }

    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    public LocalDate getEffectiveTo() {
        return effectiveTo;
    }

    public AccessMode getAccessMode() {
        return accessMode == null ? AccessMode.FULL : accessMode;
    }

    public Long getApprovalId() {
        return approvalId;
    }
}
