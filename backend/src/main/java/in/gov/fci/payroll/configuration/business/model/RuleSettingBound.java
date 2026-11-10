package in.gov.fci.payroll.configuration.business.model;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.type.YesNoConverter;

/**
 * {@code rule_setting_bound} (FCILekha extension, V015): the type, hard limits and unit of a
 * SETTING rule (A-07). The limits follow the security baseline and are checked before a change is
 * submitted.
 */
@Entity
@Table(name = "rule_setting_bound")
public class RuleSettingBound {

    public enum ValueType {
        INTEGER,
        BOOLEAN
    }

    @Id
    @Column(name = "rule_id")
    private Long ruleId;

    @Column(name = "setting_group", nullable = false, length = 20)
    private String group;

    @Enumerated(EnumType.STRING)
    @Column(name = "value_type", nullable = false, length = 10)
    private ValueType valueType;

    @Column(name = "min_value", nullable = false)
    private long minValue;

    @Column(name = "max_value", nullable = false)
    private long maxValue;

    @Column(name = "unit_label", length = 20)
    private String unit;

    @Convert(converter = YesNoConverter.class)
    @Column(name = "enforced_flag", nullable = false, length = 1)
    private Boolean enforced;

    protected RuleSettingBound() {}

    public boolean admits(long value) {
        return value >= minValue && value <= maxValue;
    }

    public Long getRuleId() {
        return ruleId;
    }

    public String getGroup() {
        return group;
    }

    public ValueType getValueType() {
        return valueType;
    }

    public long getMinValue() {
        return minValue;
    }

    public long getMaxValue() {
        return maxValue;
    }

    public String getUnit() {
        return unit;
    }

    public boolean isEnforced() {
        return Boolean.TRUE.equals(enforced);
    }
}
