package in.gov.fci.payroll.tax.orchestration;

import in.gov.fci.payroll.audit.api.RecordHistorySource;
import in.gov.fci.payroll.audit.api.RecordHistorySource.HistoryRecord;
import in.gov.fci.payroll.audit.api.RecordHistorySource.Version;
import in.gov.fci.payroll.tax.business.model.ItSection;
import in.gov.fci.payroll.tax.business.model.OutputControlTotal;
import in.gov.fci.payroll.tax.business.model.OutputField;
import in.gov.fci.payroll.tax.business.model.OutputVersion;
import in.gov.fci.payroll.tax.persistence.TaxRepositories;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The tax masters as X-04 / History-button records (SRS-20-29, SRS-20-3): an M-17 statutory output
 * and an M-18 income-tax section as their approved effective-dated versions, an M-18 PT state as
 * one row updated in place, its fields audited one by one (TaxConfigurationService). Each is
 * audited under its code (the output's report key, the section or state code). National records:
 * Headquarters keeps them.
 */
@Configuration(proxyBeanMethods = false)
public class TaxHistory {

    /** M-17 statutory output, key = its report key; a seeded output has no version yet. */
    @Bean
    RecordHistorySource outputDefinitionHistory(
            TaxRepositories.OutputDefinitions definitions,
            TaxRepositories.OutputVersions versions,
            TaxRepositories.OutputFields fields,
            TaxRepositories.OutputControls controls) {
        return RecordHistorySource.of(
                OutputDefinitionService.ENTITY,
                key ->
                        definitions
                                .findByReportKey(key)
                                .filter(
                                        d ->
                                                OutputDefinitionService.DOMAIN.equals(
                                                        d.getOutputDomain()))
                                .map(
                                        d -> {
                                            List<OutputVersion> oldestFirst =
                                                    new ArrayList<>(
                                                            versions
                                                                    .findByReportDefIdOrderByEffectiveFromDesc(
                                                                            d.getReportDefId()));
                                            Collections.reverse(oldestFirst);
                                            return HistoryRecord.ofVersions(
                                                    OutputDefinitionService.ENTITY,
                                                    key,
                                                    OutputDefinitionService.SCREEN,
                                                    null,
                                                    List.of(key),
                                                    oldestFirst.stream()
                                                            .filter(
                                                                    v ->
                                                                            "APPROVED"
                                                                                    .equals(
                                                                                            v
                                                                                                    .getApprovalState()))
                                                            .map(
                                                                    v ->
                                                                            outputVersion(
                                                                                    v, fields,
                                                                                    controls))
                                                            .toList());
                                        }));
    }

    /** What M-17 enters for a version: format, rounding, period lock, fields and control totals. */
    private static Version outputVersion(
            OutputVersion v,
            TaxRepositories.OutputFields fields,
            TaxRepositories.OutputControls controls) {
        Map<String, String> image = new LinkedHashMap<>();
        image.put("layout_format", v.getLayoutFormat());
        image.put("rounding_rule_key", v.getRoundingRuleKey());
        image.put("period_lock_required", v.isPeriodLockRequired() ? "Y" : "N");
        image.put(
                "fields",
                fields.findByReportVersionIdOrderByFieldOrder(v.getReportVersionId()).stream()
                        .map(OutputField::getFieldLabel)
                        .collect(Collectors.joining(", ")));
        image.put(
                "control_totals",
                controls.findByReportVersionId(v.getReportVersionId()).stream()
                        .map(OutputControlTotal::getControlLabel)
                        .collect(Collectors.joining(", ")));
        return new Version(
                v.getVersionNo(),
                v.getEffectiveFrom(),
                v.getEffectiveTo(),
                v.getApprovalId(),
                image);
    }

    /**
     * M-18 income-tax section, key = its code: every row is an approved version (nothing is written
     * before approval), numbered oldest first.
     */
    @Bean
    RecordHistorySource sectionHistory(TaxRepositories.Sections sections) {
        return RecordHistorySource.of(
                TaxConfigurationService.SECTION_ENTITY,
                code -> {
                    List<ItSection> oldestFirst =
                            new ArrayList<>(
                                    sections.findBySectionCodeOrderByEffectiveFromDesc(code));
                    if (oldestFirst.isEmpty()) {
                        return Optional.empty();
                    }
                    Collections.reverse(oldestFirst);
                    List<Version> out = new ArrayList<>();
                    for (int i = 0; i < oldestFirst.size(); i++) {
                        ItSection s = oldestFirst.get(i);
                        Map<String, String> image = new LinkedHashMap<>();
                        image.put("section_description", s.getSectionDescription());
                        image.put(
                                "max_deduction_amount",
                                s.getMaxDeductionAmount() == null
                                        ? null
                                        : s.getMaxDeductionAmount().toPlainString());
                        image.put("regime_applicability", s.getRegimeApplicability());
                        out.add(
                                new Version(
                                        i + 1,
                                        s.getEffectiveFrom(),
                                        s.getEffectiveTo(),
                                        null,
                                        image));
                    }
                    return Optional.of(
                            HistoryRecord.ofVersions(
                                    TaxConfigurationService.SECTION_ENTITY,
                                    code,
                                    TaxConfigurationService.SCREEN,
                                    null,
                                    List.of(code),
                                    out));
                });
    }

    /** M-18 PT state, key = its code: as-of is rebuilt from its field-level audit rows. */
    @Bean
    RecordHistorySource ptStateHistory(TaxRepositories.PtStates states) {
        return RecordHistorySource.of(
                TaxConfigurationService.STATE_ENTITY,
                code ->
                        states.findByStateCode(code)
                                .map(
                                        s ->
                                                HistoryRecord.ofCurrent(
                                                        TaxConfigurationService.STATE_ENTITY,
                                                        code,
                                                        TaxConfigurationService.SCREEN,
                                                        null,
                                                        s.image(),
                                                        s.getEffectiveFrom())));
    }
}
