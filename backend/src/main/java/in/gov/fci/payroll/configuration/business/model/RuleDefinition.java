package in.gov.fci.payroll.configuration.business.model;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.type.YesNoConverter;

/**
 * {@code rule_definition} (target DDL v4.0 S06): one rule key of the rule engine; its values are
 * the effective-dated {@link RuleVersion}s. The A-07 system parameters are rules of type SETTING in
 * the SECURITY (FCILekha) or COMPUTATION domain, with their bounds in {@link RuleSettingBound}.
 */
@Entity
@Table(name = "rule_definition")
public class RuleDefinition {

    /** {@code rule_domain}. */
    public enum Domain {
        COMPUTATION,
        STATUTORY_OUTPUT,
        REPORT,
        /** FCILekha: security and session settings (A-07). */
        SECURITY
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "rule_id")
    private Long id;

    @Column(name = "rule_key", nullable = false, length = 100)
    private String key;

    @Column(name = "rule_type_code", nullable = false, length = 20)
    private String typeCode;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(name = "rule_domain", nullable = false, length = 20)
    private Domain domain;

    @Column(name = "rule_name", nullable = false, length = 200)
    private String name;

    @Column(name = "description", length = 2000)
    private String description;

    @Column(name = "payroll_type_scope", length = 100)
    private String payrollTypeScope;

    @Column(name = "org_scope_location_id")
    private Long orgScopeLocationId;

    @Column(name = "owning_module", length = 40)
    private String owningModule;

    @Convert(converter = YesNoConverter.class)
    @Column(name = "order_required", nullable = false, length = 1)
    private Boolean orderRequired;

    @Convert(converter = YesNoConverter.class)
    @Column(name = "active_flag", nullable = false, length = 1)
    private Boolean active;

    @Version
    @Column(name = "row_version", nullable = false)
    private Long rowVersion;

    protected RuleDefinition() {}

    public Long getId() {
        return id;
    }

    public String getKey() {
        return key;
    }

    public String getTypeCode() {
        return typeCode;
    }

    public Domain getDomain() {
        return domain;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getOwningModule() {
        return owningModule;
    }

    /** A new version must be linked to an ACTIVE circular (SRS-G-16). */
    public boolean isOrderRequired() {
        return Boolean.TRUE.equals(orderRequired);
    }

    /** Comma-separated payroll types the rule applies to; null = all engines (DDL). */
    public String getPayrollTypeScope() {
        return payrollTypeScope;
    }

    /**
     * The office the rule is scoped to (and the offices below it); null = every office (KB RE-10).
     */
    public Long getOrgScopeLocationId() {
        return orgScopeLocationId;
    }

    public boolean isActive() {
        return Boolean.TRUE.equals(active);
    }
}
