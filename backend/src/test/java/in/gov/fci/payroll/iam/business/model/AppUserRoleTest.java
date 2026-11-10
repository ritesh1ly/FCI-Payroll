package in.gov.fci.payroll.iam.business.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * A role grant is dated (R-RBAC): in force from its start to its last day, keyed by user and role.
 */
class AppUserRoleTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 1);

    @Test
    void aGrantIsActiveFromItsStartToItsLastDay() {
        AppUserRole r = new AppUserRole(1L, 2L, DAY, 3L, null);
        assertThat(r.isActiveOn(DAY.minusDays(1))).isFalse();
        assertThat(r.isActiveOn(DAY)).isTrue();
        assertThat(r.isActiveOn(DAY.plusYears(3))).isTrue();
        r.endOn(DAY.plusDays(5));
        assertThat(r.isActiveOn(DAY.plusDays(5))).isTrue();
        assertThat(r.isActiveOn(DAY.plusDays(6))).isFalse();
    }

    @Test
    void keysOfDifferentGrantsDiffer() {
        AppUserRole.Key a = new AppUserRole.Key(1L, 2L);
        assertThat(a)
                .isEqualTo(new AppUserRole.Key(1L, 2L))
                .isNotEqualTo(new AppUserRole.Key(1L, 3L));
        assertThat(a.hashCode())
                .isEqualTo(new AppUserRole.Key(1L, 2L).hashCode())
                .isNotEqualTo(new AppUserRole.Key(2L, 7L).hashCode());
    }
}
