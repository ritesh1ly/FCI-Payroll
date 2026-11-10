package in.gov.fci.payroll.iam.business;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * SRS-G-48 / SRS-G-21, Module 02 decision D-2: a role is sensitive when it can decide other users'
 * changes (APPROVE anywhere) or change access and settings (anything but VIEW / EXPORT on an
 * administration screen A-nn); derived from the role's permissions, never from its code.
 */
class SensitiveRolesTest {

    @Test
    void SRS_G_48_anApproverIsSensitiveOnTheScreensItApprovesOn() {
        List<String> checker =
                List.of("M-01:VIEW", "M-01:APPROVE", "W-01:VIEW", "W-01:APPROVE", "R-01:VIEW");
        assertThat(SensitiveRoles.isSensitive(checker)).isTrue();
        assertThat(SensitiveRoles.sensitiveScreens(checker)).isEqualTo(Set.of("M-01", "W-01"));
    }

    @Test
    void SRS_G_48_changingAccessOrSettingsIsSensitiveReadingThemIsNot() {
        assertThat(SensitiveRoles.isSensitive(List.of("A-01:CREATE"))).isTrue();
        assertThat(SensitiveRoles.isSensitive(List.of("A-07:EDIT"))).isTrue();
        assertThat(SensitiveRoles.isSensitive(List.of("A-04:DELETE"))).isTrue();
        assertThat(SensitiveRoles.isSensitive(List.of("A-05:VIEW", "A-05:EXPORT"))).isFalse();
    }

    @Test
    void SRS_G_48_aMakerWhoRequestsALockIsNotSensitive() {
        // The Payroll Maker holds C-02 LOCK to REQUEST a lock; deciding it is the checker's.
        List<String> maker =
                List.of("C-02:VIEW", "C-02:CREATE", "C-02:SUBMIT", "C-02:LOCK", "M-01:VIEW");
        assertThat(SensitiveRoles.isSensitive(maker)).isFalse();
        assertThat(SensitiveRoles.sensitiveScreens(maker)).isEmpty();
    }

    @Test
    void malformedKeysAreNeverSensitive() {
        assertThat(SensitiveRoles.isSensitiveKey(null)).isFalse();
        assertThat(SensitiveRoles.isSensitiveKey("APPROVE")).isFalse();
        assertThat(SensitiveRoles.isSensitiveKey(":APPROVE")).isFalse();
        assertThat(SensitiveRoles.isSensitiveKey("X-01:APPROVE")).isTrue();
        assertThat(SensitiveRoles.isSensitive(List.of())).isFalse();
    }
}
