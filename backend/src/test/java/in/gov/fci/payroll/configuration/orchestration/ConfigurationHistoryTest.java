package in.gov.fci.payroll.configuration.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.configuration.business.model.GlAccountMapping;
import in.gov.fci.payroll.configuration.business.model.LabelMaster;
import in.gov.fci.payroll.configuration.business.model.OtherParameter;
import in.gov.fci.payroll.configuration.business.model.PayComponent;
import in.gov.fci.payroll.configuration.business.model.RateSet;
import in.gov.fci.payroll.configuration.business.model.RuleDefinition;
import in.gov.fci.payroll.configuration.business.model.RuleVersion;
import in.gov.fci.payroll.configuration.persistence.ConfigurationRepositories;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.ApprovalStatuses;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * X-04 History of the configuration masters beyond the integration path (SRS-20-3, SRS-20-6,
 * SRS-20-29): a rule outside the M-08 catalogue or an A-07 setting has no M-08 record; a rule
 * without approved versions lists none (no bands read); versions not approved are left out of M-23,
 * M-24 (and an office's own GL mapping); a rate set never approved lists no versions; an M-26 label
 * is one current row.
 */
class ConfigurationHistoryTest {

    private final ConfigurationHistory history = new ConfigurationHistory();
    private final ApprovalStatuses statuses = mock(ApprovalStatuses.class);

    private static RuleDefinition rule(RuleDefinition.Domain domain, String type) {
        RuleDefinition r = mock(RuleDefinition.class);
        when(r.getId()).thenReturn(8L);
        when(r.getKey()).thenReturn("K");
        when(r.getDomain()).thenReturn(domain);
        when(r.getTypeCode()).thenReturn(type);
        return r;
    }

    @Test
    void SRS_20_29_onlyACatalogueRuleHasAnM08RecordAndADraftVersionIsNotListed() {
        ConfigurationRepositories.Rules rules = mock(ConfigurationRepositories.Rules.class);
        ConfigurationRepositories.RuleVersions versions =
                mock(ConfigurationRepositories.RuleVersions.class);
        ConfigurationRepositories.RuleSlabBands bands =
                mock(ConfigurationRepositories.RuleSlabBands.class);
        var source =
                history.ruleVersionHistory(mock(SystemParameters.class), rules, versions, bands);

        RuleDefinition outside = rule(RuleDefinition.Domain.SECURITY, "FORMULA");
        when(rules.findByKey("K")).thenReturn(Optional.of(outside));
        assertThat(source.find("K")).isEmpty();
        RuleDefinition setting =
                rule(RuleDefinition.Domain.COMPUTATION, RuleCatalogueService.SETTING);
        when(rules.findByKey("K")).thenReturn(Optional.of(setting));
        assertThat(source.find("K")).isEmpty();

        RuleDefinition listed = rule(RuleDefinition.Domain.COMPUTATION, "FORMULA");
        when(rules.findByKey("K")).thenReturn(Optional.of(listed));
        RuleVersion draft = mock(RuleVersion.class);
        when(draft.getState()).thenReturn(RuleVersion.State.DRAFT);
        when(versions.findByRuleIdOrderByVersionNoDesc(8L)).thenReturn(List.of(draft));
        assertThat(source.find("K").orElseThrow().versions()).isEmpty();
        verify(bands, never()).findByRuleVersionIdIn(any());
    }

    @Test
    void SRS_20_6_aVersionNotApprovedIsLeftOutOfM23() {
        ConfigurationRepositories.OtherParameters parameters =
                mock(ConfigurationRepositories.OtherParameters.class);
        OtherParameter pending = mock(OtherParameter.class);
        when(pending.getApprovalId()).thenReturn(5L);
        when(parameters.findByParameterCodeOrderByVersionNoDesc("P")).thenReturn(List.of(pending));
        when(statuses.of(any())).thenReturn(Map.of(5L, ApprovalStatus.PENDING_APPROVAL));
        assertThat(
                        history.otherParameterHistory(parameters, statuses)
                                .find("P")
                                .orElseThrow()
                                .versions())
                .isEmpty();
    }

    @Test
    void SRS_20_6_anOfficesOwnGlMappingAndAPendingOneAreLeftOutOfM24() {
        ConfigurationRepositories.GlMappings mappings =
                mock(ConfigurationRepositories.GlMappings.class);
        ConfigurationRepositories.PayComponents components =
                mock(ConfigurationRepositories.PayComponents.class);
        PayComponent component = mock(PayComponent.class);
        when(component.getComponentId()).thenReturn(3L);
        when(components.findByLegacyPayCode(101)).thenReturn(Optional.of(component));
        GlAccountMapping office = mock(GlAccountMapping.class);
        when(office.getOrgUnitId()).thenReturn(30L);
        GlAccountMapping pending = mock(GlAccountMapping.class);
        when(pending.getApprovalId()).thenReturn(6L);
        when(mappings.findByComponentIdOrderByVersionNoDesc(3L))
                .thenReturn(List.of(office, pending));
        when(statuses.of(any())).thenReturn(Map.of(6L, ApprovalStatus.PENDING_APPROVAL));
        var source = history.glMappingHistory(mappings, components, statuses);
        assertThat(source.find("101").orElseThrow().versions()).isEmpty();
        assertThat(source.find("not-a-code")).isEmpty();
    }

    @Test
    void SRS_20_6_onlyTheApprovedHeadquartersGlMappingIsListedInM24() {
        ConfigurationRepositories.GlMappings mappings =
                mock(ConfigurationRepositories.GlMappings.class);
        ConfigurationRepositories.PayComponents components =
                mock(ConfigurationRepositories.PayComponents.class);
        PayComponent component = mock(PayComponent.class);
        when(component.getComponentId()).thenReturn(4L);
        when(components.findByLegacyPayCode(102)).thenReturn(Optional.of(component));
        GlAccountMapping approved = mock(GlAccountMapping.class);
        when(approved.getApprovalId()).thenReturn(7L);
        when(approved.getVersionNo()).thenReturn(2);
        // A Headquarters mapping names no office (a mocked Long would otherwise answer 0).
        when(approved.getOrgUnitId()).thenReturn(null);
        GlAccountMapping awaiting = mock(GlAccountMapping.class);
        when(awaiting.getApprovalId()).thenReturn(8L);
        when(awaiting.getOrgUnitId()).thenReturn(null);
        when(mappings.findByComponentIdOrderByVersionNoDesc(4L))
                .thenReturn(List.of(awaiting, approved));
        when(statuses.of(any()))
                .thenReturn(
                        Map.of(7L, ApprovalStatus.APPROVED, 8L, ApprovalStatus.PENDING_APPROVAL));
        var source = history.glMappingHistory(mappings, components, statuses);
        assertThat(source.find("102").orElseThrow().versions()).hasSize(1);
    }

    @Test
    void SRS_20_6_aRateSetNeverApprovedListsNoVersions() {
        ConfigurationRepositories.RateSets sets = mock(ConfigurationRepositories.RateSets.class);
        ConfigurationRepositories.RateSetLines lines =
                mock(ConfigurationRepositories.RateSetLines.class);
        RateSet draft = mock(RateSet.class);
        when(sets.findByRateSetCodeOrderByVersionNoDesc("R")).thenReturn(List.of(draft));
        assertThat(
                        history.rateSetHistory(sets, lines, mock(RateMasterService.class))
                                .find("R")
                                .orElseThrow()
                                .versions())
                .isEmpty();
        verify(lines, never()).findByRateSetIdIn(any());
    }

    @Test
    void SRS_20_3_aLabelIsOneCurrentRow() {
        ConfigurationRepositories.Labels labels = mock(ConfigurationRepositories.Labels.class);
        LabelMaster label = mock(LabelMaster.class);
        when(label.getScopeLocationId()).thenReturn(30L);
        when(labels.findById(4L)).thenReturn(Optional.of(label));
        var source = history.labelHistory(labels);
        assertThat(source.find("4").orElseThrow().locationId()).isEqualTo(30L);
        assertThat(source.find("x")).isEmpty();
    }
}
