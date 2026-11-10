package in.gov.fci.payroll.configuration.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.configuration.business.model.RuleDefinition;
import in.gov.fci.payroll.configuration.business.model.RuleSettingBound;
import in.gov.fci.payroll.configuration.business.model.RuleVersion;
import in.gov.fci.payroll.configuration.persistence.ConfigurationRepositories;
import in.gov.fci.payroll.platform.cache.CacheProperties;
import in.gov.fci.payroll.platform.cache.CacheRegions;
import in.gov.fci.payroll.platform.config.ApiProperties;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * SRS-G-22 (OD-67): once FCI sets the A-07 OTP_COOLING_OFF setting, the cooling-off period after
 * the OTP retry limit is that many minutes.
 */
class SystemParametersBranchGapsTest {

    @Test
    void SRS_G_22_aConfiguredCoolingOffIsThatManyMinutes() {
        ConfigurationRepositories.Rules rules = mock(ConfigurationRepositories.Rules.class);
        ConfigurationRepositories.SettingBounds bounds =
                mock(ConfigurationRepositories.SettingBounds.class);
        ConfigurationRepositories.RuleVersions versions =
                mock(ConfigurationRepositories.RuleVersions.class);
        ApiProperties properties = mock(ApiProperties.class);
        when(properties.businessZone()).thenReturn(ZoneId.of("Asia/Kolkata"));
        CacheRegions regions = mock(CacheRegions.class);
        when(regions.properties())
                .thenReturn(new CacheProperties(Duration.ofSeconds(10), Duration.ofMinutes(15)));

        RuleDefinition rule = mock(RuleDefinition.class);
        when(rule.getId()).thenReturn(31L);
        when(rule.getKey()).thenReturn(SystemParameters.Code.OTP_COOLING_OFF.name());
        when(rule.isActive()).thenReturn(true);
        RuleSettingBound bound = mock(RuleSettingBound.class);
        when(bound.getRuleId()).thenReturn(31L);
        RuleVersion version = mock(RuleVersion.class);
        when(version.getRuleId()).thenReturn(31L);
        when(version.isInForceOn(any())).thenReturn(true);
        when(version.getScalarValue()).thenReturn(new BigDecimal("30"));
        when(rules.findByTypeCodeOrderByKey(anyString())).thenReturn(List.of(rule));
        when(bounds.findByRuleIdIn(any())).thenReturn(List.of(bound));
        when(versions.findByRuleIdInAndState(any(), eq(RuleVersion.State.APPROVED)))
                .thenReturn(List.of(version));

        SystemParameters parameters =
                new SystemParameters(
                        rules,
                        bounds,
                        versions,
                        properties,
                        Clock.fixed(Instant.parse("2026-10-23T06:00:00Z"), ZoneOffset.UTC),
                        regions);
        assertThat(parameters.otpCoolingOff()).contains(Duration.ofMinutes(30));
    }
}
