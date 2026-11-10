package in.gov.fci.payroll.iam.business;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.iam.business.UserGovernance.Readiness;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/** Module 02 F-3 / F-7 rules (SRS-G-50, SRS-22-11). */
class UserGovernanceTest {

    private static final Instant NOW = Instant.parse("2026-10-21T10:00:00Z");
    private static final OptionalLong NINETY = OptionalLong.of(90);

    private static Instant daysAgo(long days) {
        return NOW.minus(Duration.ofDays(days));
    }

    @Test
    void SRS_G_50_inactivityIsUnknownWhileTheThresholdIsNotConfigured() {
        assertThat(UserGovernance.inactive(daysAgo(400), daysAgo(500), OptionalLong.empty(), NOW))
                .isEmpty();
    }

    @Test
    void SRS_G_50_aUserIsInactiveOnlyBeyondTheThreshold() {
        assertThat(UserGovernance.inactive(daysAgo(89), daysAgo(500), NINETY, NOW)).contains(false);
        assertThat(UserGovernance.inactive(daysAgo(90), daysAgo(500), NINETY, NOW)).contains(false);
        assertThat(UserGovernance.inactive(daysAgo(90).minusSeconds(1), daysAgo(500), NINETY, NOW))
                .contains(true);
    }

    @Test
    void SRS_G_50_aUserWhoNeverSignedInCountsFromTheAccountsCreation() {
        assertThat(UserGovernance.inactive(null, daysAgo(10), NINETY, NOW)).contains(false);
        assertThat(UserGovernance.inactive(null, daysAgo(91), NINETY, NOW)).contains(true);
        assertThat(UserGovernance.inactive(null, null, NINETY, NOW)).contains(true);
    }

    @Test
    void SRS_G_50_daysSinceTheLastSignIn() {
        assertThat(UserGovernance.daysSince(daysAgo(3).minusSeconds(60), NOW)).isEqualTo(3);
        assertThat(UserGovernance.daysSince(null, NOW)).isNull();
    }

    @Test
    void SRS_G_50_oneUserHoldingBothRolesIsNotASeparatePair() {
        assertThat(UserGovernance.separatePair(List.of(1L), List.of(1L))).isFalse();
        assertThat(UserGovernance.separatePair(List.of(1L), List.of(1L, 2L))).isTrue();
        assertThat(UserGovernance.separatePair(List.of(1L, 2L), List.of(1L))).isTrue();
        assertThat(UserGovernance.separatePair(List.of(), List.of(1L))).isFalse();
        assertThat(UserGovernance.separatePair(List.of(1L), List.of())).isFalse();
    }

    @Test
    void SRS_22_11_readinessOfAUnit() {
        assertThat(UserGovernance.readiness(List.of(1L), List.of(2L), List.of(1L), List.of(2L)))
                .isEqualTo(Readiness.READY);
        // The unit's own Maker, the Checker only from the zone.
        assertThat(UserGovernance.readiness(List.of(1L), List.of(), List.of(1L), List.of(3L)))
                .isEqualTo(Readiness.COVERED_FROM_ABOVE);
        // One person with both roles at the unit and nobody above: not ready.
        assertThat(UserGovernance.readiness(List.of(1L), List.of(1L), List.of(1L), List.of(1L)))
                .isEqualTo(Readiness.NOT_READY);
        assertThat(UserGovernance.readiness(List.of(), List.of(), List.of(), List.of()))
                .isEqualTo(Readiness.NOT_READY);
    }

    @Test
    void SRS_22_11_theMinimumStaffIsUnknownUntilConfiguredThenAFloor() {
        assertThat(UserGovernance.meetsMinimum(5, OptionalLong.empty())).isEmpty();
        assertThat(UserGovernance.meetsMinimum(3, OptionalLong.of(3))).contains(true);
        assertThat(UserGovernance.meetsMinimum(2, OptionalLong.of(3))).contains(false);
    }
}
