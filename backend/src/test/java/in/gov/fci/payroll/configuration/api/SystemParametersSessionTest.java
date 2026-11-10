package in.gov.fci.payroll.configuration.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.configuration.persistence.ConfigurationRepositories;
import in.gov.fci.payroll.platform.cache.CacheProperties;
import in.gov.fci.payroll.platform.cache.CacheRegions;
import in.gov.fci.payroll.platform.config.ApiProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * SRS-SEC-14: "idle logout after 15 minutes for payroll users and 10 minutes for eSeva members".
 * The session picks its A-07 setting by scope; with no setting in force (a deployment before
 * V033_2, or a retired setting) the configured defaults apply, so a session never runs without a
 * timeout. The seeded values themselves are tested end to end in NavigationAndSessionIT.
 */
class SystemParametersSessionTest {

    private final ConfigurationRepositories.Rules rules =
            mock(ConfigurationRepositories.Rules.class);
    private final ApiProperties properties = mock(ApiProperties.class);
    private final CacheRegions regions = mock(CacheRegions.class);

    private SystemParameters parameters(Duration idle, Duration esevaIdle) {
        when(properties.session())
                .thenReturn(
                        new ApiProperties.Session(
                                idle,
                                esevaIdle,
                                Duration.ofHours(8),
                                Duration.ofMinutes(15),
                                "FCISESSION",
                                true,
                                false));
        when(properties.businessZone()).thenReturn(ZoneId.of("Asia/Kolkata"));
        when(rules.findByTypeCodeOrderByKey(anyString())).thenReturn(List.of());
        when(regions.properties())
                .thenReturn(new CacheProperties(Duration.ofSeconds(10), Duration.ofMinutes(15)));
        return new SystemParameters(
                rules,
                mock(ConfigurationRepositories.SettingBounds.class),
                mock(ConfigurationRepositories.RuleVersions.class),
                properties,
                Clock.fixed(Instant.parse("2026-10-23T06:00:00Z"), ZoneOffset.UTC),
                regions);
    }

    @Test
    void SRS_SEC_14_withoutASettingInForceTheConfiguredDefaultsApply() {
        SystemParameters p = parameters(Duration.ofMinutes(15), Duration.ofMinutes(10));
        assertThat(p.sessionIdleTimeout()).isEqualTo(Duration.ofMinutes(15));
        assertThat(p.esevaSessionIdleTimeout()).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void SRS_SEC_14_theEsevaTimeoutIsItsOwnSettingNotTheStaffOne() {
        // Configured apart: changing one never moves the other.
        SystemParameters p = parameters(Duration.ofMinutes(20), Duration.ofMinutes(7));
        assertThat(p.esevaSessionIdleTimeout()).isEqualTo(Duration.ofMinutes(7));
        assertThat(p.sessionIdleTimeout()).isEqualTo(Duration.ofMinutes(20));
    }

    /** SRS-G-48: without the A-07 setting the single-session policy follows the configuration. */
    @Test
    void SRS_G_48_withoutASettingTheSingleSessionPolicyFollowsTheConfiguration() {
        SystemParameters p = parameters(Duration.ofMinutes(15), Duration.ofMinutes(10));
        when(properties.singleSession()).thenReturn(true);
        assertThat(p.singleSession()).isTrue();
        when(properties.singleSession()).thenReturn(false);
        assertThat(p.singleSession()).isFalse();
    }

    /**
     * NEW-G1-01, NEW-G1-08 (OD-67): without the A-07 settings the OTP retry limit is the deployment
     * value, no cooling-off period applies and a password mixes all four kinds of characters.
     */
    @Test
    void SRS_G_22_withoutTheSettingsTheRetryLimitIsConfiguredAndNoCoolingOffApplies() {
        SystemParameters p = parameters(Duration.ofMinutes(15), Duration.ofMinutes(10));
        when(properties.otp())
                .thenReturn(
                        new ApiProperties.Otp(
                                Duration.ofMinutes(10),
                                4,
                                5,
                                3,
                                ApiProperties.Verification.Y,
                                true,
                                true));
        assertThat(p.otpMaxAttempts()).isEqualTo(4);
        assertThat(p.otpCoolingOff()).isEmpty();
        assertThat(p.passwordCharacterClasses()).isEqualTo(4);
    }

    /**
     * SRS-8-6 / CLR-147: with no MAX_RETRO_PERIOD in force the back-date limit is empty (never a
     * guessed 60, never null), so every caller refuses "not configured".
     */
    @Test
    void SRS_8_6_withoutTheSettingTheBackDateLimitIsEmpty() {
        SystemParameters p = parameters(Duration.ofMinutes(15), Duration.ofMinutes(10));
        assertThat(p.maxRetroMonths()).isNotNull().isEmpty();
    }
}
