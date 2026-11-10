package in.gov.fci.payroll.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** SRS-22-29 / FRS 6.7.7.4: what each access mode of a group confers from its role. */
class AccessModeTest {

    @Test
    void SRS_22_29_fullConfersEveryPermissionOfTheRole() {
        assertThat(AccessMode.FULL.admits("M-01:EDIT")).isTrue();
        assertThat(AccessMode.FULL.admits("W-01:APPROVE")).isTrue();
        assertThat(AccessMode.FULL.admits("odd")).isTrue();
    }

    @Test
    void SRS_22_29_readOnlyConfersViewAndExportOnAnyScreenOnly() {
        assertThat(AccessMode.READ_ONLY.admits("M-01:VIEW")).isTrue();
        assertThat(AccessMode.READ_ONLY.admits("A-05:EXPORT")).isTrue();
        assertThat(AccessMode.READ_ONLY.admits("M-01:EDIT")).isFalse();
        assertThat(AccessMode.READ_ONLY.admits("W-01:APPROVE")).isFalse();
        // A permission without an action is never read-only.
        assertThat(AccessMode.READ_ONLY.admits("M-01")).isFalse();
        assertThat(AccessMode.READ_ONLY.admits("M-01:")).isFalse();
    }

    @Test
    void SRS_22_29_reportsOnlyConfersViewAndExportOnReportScreensOnly() {
        assertThat(AccessMode.REPORTS_ONLY.admits("R-01:VIEW")).isTrue();
        assertThat(AccessMode.REPORTS_ONLY.admits("R-07:EXPORT")).isTrue();
        assertThat(AccessMode.REPORTS_ONLY.admits("R-01:EDIT")).isFalse();
        assertThat(AccessMode.REPORTS_ONLY.admits("M-01:VIEW")).isFalse();
        assertThat(AccessMode.REPORTS_ONLY.admits("A-05:EXPORT")).isFalse();
        assertThat(AccessMode.REPORTS_ONLY.admits("R-01")).isFalse();
    }
}
