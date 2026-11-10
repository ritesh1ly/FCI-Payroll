package in.gov.fci.payroll.configuration.business.model;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.type.YesNoConverter;

/**
 * {@code rule_type_reference}: Rule types of the rule engine, held as data (target DDL v4.0 S06:
 * FRS lists them two ways).
 */
@Entity
@Table(name = "rule_type_reference")
public class RuleTypeReference {

    @Id
    @Column(name = "rule_type_code", length = 20)
    private String ruleTypeCode;

    @Column(name = "rule_type_name", nullable = false, length = 100)
    private String ruleTypeName;

    @Convert(converter = YesNoConverter.class)
    @Column(name = "is_computation", nullable = false, length = 1)
    private Boolean isComputation;

    @Column(name = "notes", length = 500)
    private String notes;

    protected RuleTypeReference() {}

    public String getRuleTypeCode() {
        return ruleTypeCode;
    }

    public String getRuleTypeName() {
        return ruleTypeName;
    }

    public boolean isIsComputation() {
        return Boolean.TRUE.equals(isComputation);
    }

    public String getNotes() {
        return notes;
    }
}
