package in.gov.fci.payroll.configuration.business.model;

import java.time.LocalDate;

/**
 * An A-07 system parameter as in force: a SETTING rule ({@link RuleDefinition}), its bounds ({@link
 * RuleSettingBound}) and its approved version for today ({@link RuleVersion}). A read model, not a
 * table: since V015 the parameters live in the rule engine of target DDL v4.0.
 */
public record SystemParameter(
        Long ruleId,
        Long versionId,
        String code,
        String group,
        RuleSettingBound.ValueType valueType,
        long value,
        long minValue,
        long maxValue,
        String unit,
        boolean enforced,
        int versionNo,
        LocalDate effectiveFrom,
        String changedBy) {

    public static SystemParameter of(
            RuleDefinition rule, RuleSettingBound bound, RuleVersion version) {
        return new SystemParameter(
                rule.getId(),
                version.getId(),
                rule.getKey(),
                bound.getGroup(),
                bound.getValueType(),
                version.getScalarValue() == null ? 0 : version.getScalarValue().longValueExact(),
                bound.getMinValue(),
                bound.getMaxValue(),
                bound.getUnit(),
                bound.isEnforced(),
                version.getVersionNo(),
                version.getEffectiveFrom(),
                version.getCreatedBy());
    }
}
