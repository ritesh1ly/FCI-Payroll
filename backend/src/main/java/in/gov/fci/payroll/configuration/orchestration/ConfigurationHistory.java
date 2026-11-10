package in.gov.fci.payroll.configuration.orchestration;

import in.gov.fci.payroll.audit.api.RecordHistorySource;
import in.gov.fci.payroll.audit.api.RecordHistorySource.HistoryRecord;
import in.gov.fci.payroll.audit.api.RecordHistorySource.Version;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.configuration.business.DatedVersion;
import in.gov.fci.payroll.configuration.business.MasterImages;
import in.gov.fci.payroll.configuration.business.SlabBands;
import in.gov.fci.payroll.configuration.business.VersionChain;
import in.gov.fci.payroll.configuration.business.model.AllowanceCeilingNorm;
import in.gov.fci.payroll.configuration.business.model.GlAccountMapping;
import in.gov.fci.payroll.configuration.business.model.NetPayFloorRule;
import in.gov.fci.payroll.configuration.business.model.OperationMaster;
import in.gov.fci.payroll.configuration.business.model.OtherParameter;
import in.gov.fci.payroll.configuration.business.model.RateSet;
import in.gov.fci.payroll.configuration.business.model.RateSetLine;
import in.gov.fci.payroll.configuration.business.model.RuleSlabBand;
import in.gov.fci.payroll.configuration.business.model.RuleVersion;
import in.gov.fci.payroll.configuration.persistence.ConfigurationRepositories;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.ApprovalStatuses;
import in.gov.fci.payroll.platform.error.FciException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The configuration masters as X-04 / History-button records (SRS-20-3, SRS-20-6, SRS-20-29): A-07
 * settings, M-04 / M-22 rate sets, M-23 other parameters and M-24 GL mappings as approved
 * effective-dated versions; M-16 reference values as one row each, versioned in place. Values come
 * from {@link MasterImages}, the same fields the audit rows name.
 */
@Configuration(proxyBeanMethods = false)
public class ConfigurationHistory {

    /**
     * A rule version record, key = the A-07 setting's code or the M-08 rule's key (one source per
     * table: both screens keep their values in {@code rule_version}). The record names its screen,
     * whose VIEW the reader needs.
     */
    @Bean
    RecordHistorySource ruleVersionHistory(
            SystemParameters parameters,
            ConfigurationRepositories.Rules rules,
            ConfigurationRepositories.RuleVersions versions,
            ConfigurationRepositories.RuleSlabBands bands) {
        return RecordHistorySource.of(
                SystemParameterService.ENTITY,
                key ->
                        settingHistory(parameters, versions, key)
                                .or(() -> ruleHistory(rules, versions, bands, key)));
    }

    /** A-07: the setting's approved versions. */
    private static Optional<HistoryRecord> settingHistory(
            SystemParameters parameters,
            ConfigurationRepositories.RuleVersions versions,
            String code) {
        return parameters.definitions().stream()
                .filter(d -> d.code().equals(code))
                .findFirst()
                .map(
                        d ->
                                HistoryRecord.ofVersions(
                                        SystemParameterService.ENTITY,
                                        d.code(),
                                        SystemParameterService.MODULE,
                                        null,
                                        List.of(d.code()),
                                        approved(versions, d.ruleId()).stream()
                                                .map(
                                                        v ->
                                                                new Version(
                                                                        v.getVersionNo(),
                                                                        v.getEffectiveFrom(),
                                                                        v.getEffectiveTo(),
                                                                        v.getApprovalId(),
                                                                        MasterImages.ruleVersion(
                                                                                v)))
                                                .toList()));
    }

    /** M-08: a listed rule's approved versions, with their bands (SRS-19-3, SRS-20-29). */
    private static Optional<HistoryRecord> ruleHistory(
            ConfigurationRepositories.Rules rules,
            ConfigurationRepositories.RuleVersions versions,
            ConfigurationRepositories.RuleSlabBands bands,
            String key) {
        return rules.findByKey(key)
                .filter(
                        r ->
                                RuleCatalogueService.DOMAINS.contains(r.getDomain())
                                        && !RuleCatalogueService.SETTING.equals(r.getTypeCode()))
                .map(
                        r -> {
                            List<RuleVersion> own = approved(versions, r.getId());
                            Map<Long, List<SlabBands.Band>> slab =
                                    own.isEmpty()
                                            ? Map.of()
                                            : bands
                                                    .findByRuleVersionIdIn(
                                                            own.stream()
                                                                    .map(RuleVersion::getId)
                                                                    .toList())
                                                    .stream()
                                                    .collect(
                                                            Collectors.groupingBy(
                                                                    RuleSlabBand::getRuleVersionId,
                                                                    Collectors.mapping(
                                                                            RuleSlabBand::band,
                                                                            Collectors.toList())));
                            return HistoryRecord.ofVersions(
                                    SystemParameterService.ENTITY,
                                    r.getKey(),
                                    RuleCatalogueService.MODULE,
                                    null,
                                    List.of(r.getKey()),
                                    own.stream()
                                            .map(
                                                    v ->
                                                            new Version(
                                                                    v.getVersionNo(),
                                                                    v.getEffectiveFrom(),
                                                                    v.getEffectiveTo(),
                                                                    v.getApprovalId(),
                                                                    MasterImages.ruleVersion(
                                                                            v,
                                                                            slab.getOrDefault(
                                                                                    v.getId(),
                                                                                    List.of()))))
                                            .toList());
                        });
    }

    private static List<RuleVersion> approved(
            ConfigurationRepositories.RuleVersions versions, Long ruleId) {
        return versions.findByRuleIdOrderByVersionNoDesc(ruleId).stream()
                .filter(v -> v.getState() == RuleVersion.State.APPROVED)
                .toList();
    }

    /** M-23 other parameter, key = its code. */
    @Bean
    RecordHistorySource otherParameterHistory(
            ConfigurationRepositories.OtherParameters parameters, ApprovalStatuses statuses) {
        return RecordHistorySource.of(
                OtherParameterService.ENTITY,
                code -> {
                    List<OtherParameter> rows =
                            parameters.findByParameterCodeOrderByVersionNoDesc(code);
                    if (rows.isEmpty()) {
                        return Optional.empty();
                    }
                    Map<Long, ApprovalStatus> status =
                            statuses.of(
                                    rows.stream()
                                            .map(OtherParameter::getApprovalId)
                                            .filter(Objects::nonNull)
                                            .toList());
                    return Optional.of(
                            HistoryRecord.ofVersions(
                                    OtherParameterService.ENTITY,
                                    code,
                                    OtherParameterService.SCREEN,
                                    null,
                                    List.of(code),
                                    rows.stream()
                                            .filter(
                                                    p ->
                                                            status.get(p.getApprovalId())
                                                                    == ApprovalStatus.APPROVED)
                                            .map(
                                                    p ->
                                                            new Version(
                                                                    p.getVersionNo(),
                                                                    p.getEffectiveFrom(),
                                                                    p.getEffectiveTo(),
                                                                    p.getApprovalId(),
                                                                    MasterImages.otherParameter(p)))
                                            .toList()));
                });
    }

    /** M-24 GL mapping of a pay code (the all-units mapping), key = the legacy pay code. */
    @Bean
    RecordHistorySource glMappingHistory(
            ConfigurationRepositories.GlMappings mappings,
            ConfigurationRepositories.PayComponents components,
            ApprovalStatuses statuses) {
        return RecordHistorySource.of(
                GlMappingService.ENTITY,
                key -> {
                    int payCode;
                    try {
                        payCode = Integer.parseInt(key);
                    } catch (NumberFormatException notACode) {
                        return Optional.empty();
                    }
                    return components
                            .findByLegacyPayCode(payCode)
                            .map(
                                    component -> {
                                        List<GlAccountMapping> rows =
                                                mappings
                                                        .findByComponentIdOrderByVersionNoDesc(
                                                                component.getComponentId())
                                                        .stream()
                                                        .filter(m -> m.getOrgUnitId() == null)
                                                        .toList();
                                        Map<Long, ApprovalStatus> status =
                                                statuses.of(
                                                        rows.stream()
                                                                .map(
                                                                        GlAccountMapping
                                                                                ::getApprovalId)
                                                                .filter(Objects::nonNull)
                                                                .toList());
                                        return HistoryRecord.ofVersions(
                                                GlMappingService.ENTITY,
                                                key,
                                                GlMappingService.SCREEN,
                                                null,
                                                List.of(key),
                                                rows.stream()
                                                        .filter(
                                                                m ->
                                                                        status.get(
                                                                                        m
                                                                                                .getApprovalId())
                                                                                == ApprovalStatus
                                                                                        .APPROVED)
                                                        .map(
                                                                m ->
                                                                        new Version(
                                                                                m.getVersionNo(),
                                                                                m
                                                                                        .getEffectiveFrom(),
                                                                                m.getEffectiveTo(),
                                                                                m.getApprovalId(),
                                                                                MasterImages
                                                                                        .glMapping(
                                                                                                m)))
                                                        .toList());
                                    });
                });
    }

    /** M-04 / M-22 rate set, key = its code; the screen is the one whose catalogue holds it. */
    @Bean
    RecordHistorySource rateSetHistory(
            ConfigurationRepositories.RateSets sets,
            ConfigurationRepositories.RateSetLines lines,
            RateMasterService rates) {
        return RecordHistorySource.of(
                RateMasterService.ENTITY,
                code -> {
                    List<RateSet> rows = sets.findByRateSetCodeOrderByVersionNoDesc(code);
                    if (rows.isEmpty()) {
                        return Optional.empty();
                    }
                    List<RateSet> approved = rows.stream().filter(RateSet::isApproved).toList();
                    Map<Long, List<RateSetLine>> byVersion =
                            approved.isEmpty()
                                    ? Map.of()
                                    : lines
                                            .findByRateSetIdIn(
                                                    approved.stream()
                                                            .map(RateSet::getRateSetId)
                                                            .toList())
                                            .stream()
                                            .collect(
                                                    Collectors.groupingBy(
                                                            RateSetLine::getRateSetId));
                    String screen =
                            rates.screenOf(code)
                                    .map(RateMasterService.Screen::code)
                                    .orElse(RateMasterService.Screen.SPECIAL.code());
                    return Optional.of(
                            HistoryRecord.ofVersions(
                                    RateMasterService.ENTITY,
                                    code,
                                    screen,
                                    rows.get(0).getLocationId(),
                                    List.of(code),
                                    approved.stream()
                                            .map(
                                                    s ->
                                                            new Version(
                                                                    s.getVersionNo(),
                                                                    s.getEffectiveFrom(),
                                                                    s.getEffectiveTo(),
                                                                    s.getApprovalId(),
                                                                    MasterImages.rateSet(
                                                                            s,
                                                                            byVersion.getOrDefault(
                                                                                    s
                                                                                            .getRateSetId(),
                                                                                    List.of()))))
                                            .toList()));
                });
    }

    /**
     * M-16 reference value, key = {@code LIST.CODE}: one row versioned in place, so as-of is
     * rebuilt from its field-level audit rows.
     */
    @Bean
    RecordHistorySource referenceValueHistory(ReferenceListService lists) {
        return RecordHistorySource.of(
                ReferenceListService.ENTITY,
                key -> {
                    int dot = key.indexOf('.');
                    if (dot <= 0 || dot == key.length() - 1) {
                        return Optional.empty();
                    }
                    String list = key.substring(0, dot);
                    String code = key.substring(dot + 1);
                    ReferenceListService.ListHead head;
                    try {
                        head = lists.head(list);
                    } catch (FciException unknownList) {
                        return Optional.empty();
                    }
                    return lists.value(head.id(), code)
                            .map(
                                    v ->
                                            HistoryRecord.ofCurrent(
                                                    ReferenceListService.ENTITY,
                                                    list + "." + code,
                                                    ReferenceListService.SCREEN,
                                                    null,
                                                    MasterImages.referenceValue(
                                                            v.labelEn(),
                                                            v.sortOrder(),
                                                            v.effectiveTo(),
                                                            lists.hindiOf(v.valueId())),
                                                    v.effectiveFrom()));
                });
    }

    /**
     * The approved versions of a master without a state column (M-14, M-15, M-27), oldest first
     * numbered when the table has no version number.
     */
    private static <V extends DatedVersion> Optional<HistoryRecord> dated(
            String entity,
            String key,
            String screen,
            List<V> newestFirst,
            ApprovalStatuses statuses,
            java.util.function.ToIntFunction<V> number,
            java.util.function.Function<V, Map<String, String>> image) {
        if (newestFirst.isEmpty()) {
            return Optional.empty();
        }
        VersionChain<V> chain =
                new VersionChain<>(newestFirst, statuses.of(VersionChain.approvalIds(newestFirst)));
        return Optional.of(
                HistoryRecord.ofVersions(
                        entity,
                        key,
                        screen,
                        null,
                        List.of(key),
                        newestFirst.stream()
                                .filter(chain::isApproved)
                                .map(
                                        v ->
                                                new Version(
                                                        number.applyAsInt(v),
                                                        v.getEffectiveFrom(),
                                                        v.getEffectiveTo(),
                                                        v.getApprovalId(),
                                                        image.apply(v)))
                                .toList()));
    }

    /** M-14 net-pay floor of a payroll type, key = the type (STAFF, DEPT_LABOUR ...). */
    @Bean
    RecordHistorySource netPayFloorHistory(
            ConfigurationRepositories.NetPayFloors floors, ApprovalStatuses statuses) {
        return RecordHistorySource.of(
                NetPayFloorService.ENTITY,
                type -> {
                    List<NetPayFloorRule> rows =
                            floors.findByPayrollTypeOrderByEffectiveFromDesc(type);
                    return dated(
                            NetPayFloorService.ENTITY,
                            type,
                            NetPayFloorService.SCREEN,
                            rows,
                            statuses,
                            r -> rows.size() - rows.indexOf(r),
                            MasterImages::netPayFloor);
                });
    }

    /** M-15 ceiling norm of a claim type, key = the claim type. */
    @Bean
    RecordHistorySource ceilingNormHistory(
            ConfigurationRepositories.CeilingNorms norms, ApprovalStatuses statuses) {
        return RecordHistorySource.of(
                CeilingNormService.ENTITY,
                claimType ->
                        dated(
                                CeilingNormService.ENTITY,
                                claimType,
                                CeilingNormService.SCREEN,
                                norms.findByClaimTypeOrderByVersionNoDesc(claimType),
                                statuses,
                                AllowanceCeilingNorm::getVersionNo,
                                MasterImages::ceilingNorm));
    }

    /** M-27 operation, key = its code. */
    @Bean
    RecordHistorySource operationHistory(
            ConfigurationRepositories.Operations operations, ApprovalStatuses statuses) {
        return RecordHistorySource.of(
                OperationService.ENTITY,
                code ->
                        dated(
                                OperationService.ENTITY,
                                code,
                                OperationService.SCREEN,
                                operations.findByOperationCodeOrderByVersionNoDesc(code),
                                statuses,
                                OperationMaster::getVersionNo,
                                MasterImages::operation));
    }

    /**
     * M-07 pay component, key = the pay code: one row revised in place, its approved changes
     * audited as one image (field {@code component}), so as-of is rebuilt from those rows; not in
     * force before its effective-from (SRS-19-1, SRS-20-29).
     */
    @Bean
    RecordHistorySource payComponentHistory(ConfigurationRepositories.PayComponents components) {
        return RecordHistorySource.of(
                ComponentCatalogueService.ENTITY,
                key -> {
                    int payCode;
                    try {
                        payCode = Integer.parseInt(key);
                    } catch (NumberFormatException notACode) {
                        return Optional.empty();
                    }
                    return components
                            .findByLegacyPayCode(payCode)
                            .map(
                                    c ->
                                            HistoryRecord.ofCurrent(
                                                    ComponentCatalogueService.ENTITY,
                                                    key,
                                                    ComponentCatalogueService.SCREEN,
                                                    null,
                                                    Map.of(
                                                            "component",
                                                            ComponentCatalogueEffect.image(c)),
                                                    c.getEffectiveFrom()));
                });
    }

    /** M-25 circular, key = its id: one row, its status changed in place. */
    @Bean
    RecordHistorySource circularHistory(ConfigurationRepositories.Circulars circulars) {
        return RecordHistorySource.of(
                CircularService.ENTITY,
                key ->
                        id(key).flatMap(circulars::findById)
                                .map(
                                        c ->
                                                HistoryRecord.ofCurrent(
                                                        CircularService.ENTITY,
                                                        key,
                                                        CircularService.SCREEN,
                                                        c.getIssuingLocationId(),
                                                        MasterImages.circular(c))));
    }

    /** M-26 label version, key = its id: one row, its status changed in place. */
    @Bean
    RecordHistorySource labelHistory(ConfigurationRepositories.Labels labels) {
        return RecordHistorySource.of(
                LabelService.ENTITY,
                key ->
                        id(key).flatMap(labels::findById)
                                .map(
                                        l ->
                                                HistoryRecord.ofCurrent(
                                                        LabelService.ENTITY,
                                                        key,
                                                        LabelService.SCREEN,
                                                        l.getScopeLocationId(),
                                                        MasterImages.label(l))));
    }

    private static Optional<Long> id(String key) {
        try {
            return Optional.of(Long.valueOf(key));
        } catch (NumberFormatException notAnId) {
            return Optional.empty();
        }
    }
}
