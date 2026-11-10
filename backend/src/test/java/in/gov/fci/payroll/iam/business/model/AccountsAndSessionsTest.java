package in.gov.fci.payroll.iam.business.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * User accounts (login normalisation, contact, deactivation never reversed, SRS-G-36 separation),
 * roles (base role, retirement, active window), sessions (scope, end once, user agent cut) and the
 * persona-role key (A-01 / A-02 / A-03).
 */
class AccountsAndSessionsTest {

    private static final Instant NOW = Instant.parse("2026-10-21T05:00:00Z");
    private static final LocalDate D = LocalDate.of(2026, 4, 1);

    private static AppUser user() {
        return AppUser.create(
                "  Maker.Delhi@FCI-Demo.test ",
                5L,
                "Maker",
                AppUser.Category.OPERATIONS,
                "9000000001",
                "Maker@FCI.gov.in",
                3L,
                "admin",
                NOW);
    }

    @Test
    void A_01_aLoginIdIsTrimmedAndLowerCasedAndTheEmailLowerCased() {
        AppUser u = user();
        assertThat(u.getLoginId()).isEqualTo("maker.delhi@fci-demo.test");
        assertThat(u.getRegisteredEmail()).isEqualTo("maker@fci.gov.in");
        assertThat(u.getStatus()).isEqualTo(AppUser.Status.ACTIVE);
        assertThat(u.getCategory()).isEqualTo(AppUser.Category.OPERATIONS);
        assertThat(u.getEmployeeId()).isEqualTo(5L);
        assertThat(AppUser.normaliseLogin(null)).isNull();
        assertThat(
                        AppUser.create(
                                        "x",
                                        null,
                                        "X",
                                        AppUser.Category.SYSTEM,
                                        null,
                                        null,
                                        null,
                                        null,
                                        NOW)
                                .getRegisteredEmail())
                .isNull();
    }

    @Test
    void A_01_theCreatorIsCutTo50Characters() {
        String fifty = "a".repeat(50);
        assertThat(
                        ReflectionTestUtils.getField(
                                AppUser.create(
                                        "x",
                                        null,
                                        "X",
                                        AppUser.Category.SYSTEM,
                                        null,
                                        null,
                                        null,
                                        fifty,
                                        NOW),
                                "createdBy"))
                .isEqualTo(fifty);
        assertThat(
                        ReflectionTestUtils.getField(
                                AppUser.create(
                                        "x",
                                        null,
                                        "X",
                                        AppUser.Category.SYSTEM,
                                        null,
                                        null,
                                        null,
                                        fifty + "b",
                                        NOW),
                                "createdBy"))
                .isEqualTo(fifty);
        assertThat(
                        ReflectionTestUtils.getField(
                                AppUser.create(
                                        "x",
                                        null,
                                        "X",
                                        AppUser.Category.SYSTEM,
                                        null,
                                        null,
                                        null,
                                        null,
                                        NOW),
                                "createdBy"))
                .isNull();
    }

    @Test
    void A_01_contactChangesLowerCaseTheEmail() {
        AppUser u = user();
        u.updateContact("New", "9000000009", "New@X.IN");
        assertThat(u.getDisplayName()).isEqualTo("New");
        assertThat(u.getRegisteredMobile()).isEqualTo("9000000009");
        assertThat(u.getRegisteredEmail()).isEqualTo("new@x.in");
        u.updateContact("New", null, null);
        assertThat(u.getRegisteredEmail()).isNull();
        u.recordLogin(NOW);
        assertThat(u.getLastLoginAt()).isEqualTo(NOW);
        assertThat(u.toString()).isEqualTo("AppUser{id=null, status=ACTIVE}");
    }

    @Test
    void A_01_aDeactivationIsRecordedWithItsReasonAndNeverReversed() {
        AppUser locked = user();
        locked.changeStatus(AppUser.Status.LOCKED, null, NOW);
        assertThat(locked.getStatus()).isEqualTo(AppUser.Status.LOCKED);
        assertThat(locked.getDeactivatedAt()).isNull();
        assertThat(locked.getDeactivationReason()).isNull();
        locked.changeStatus(AppUser.Status.DEACTIVATED, null, NOW);
        assertThat(locked.getDeactivationReason())
                .as("default reason")
                .isEqualTo(AppUser.DeactivationReason.ADMIN);
        assertThat(locked.getDeactivatedAt()).isEqualTo(NOW);
        assertThatThrownBy(() -> locked.changeStatus(AppUser.Status.ACTIVE, null, NOW))
                .isInstanceOf(IllegalStateException.class);
        AppUser bulk = user();
        bulk.changeStatus(AppUser.Status.DEACTIVATED, AppUser.DeactivationReason.BULK_SUSPEND, NOW);
        assertThat(bulk.getDeactivationReason()).isEqualTo(AppUser.DeactivationReason.BULK_SUSPEND);
    }

    @Test
    void SRS_G_36_aSeparationDeactivatesOnceAndKeepsTheEvent() {
        AppUser u = user();
        u.deactivateForSeparation(88L, NOW);
        assertThat(u.getStatus()).isEqualTo(AppUser.Status.DEACTIVATED);
        assertThat(u.getDeactivationReason()).isEqualTo(AppUser.DeactivationReason.SEPARATION);
        assertThat(ReflectionTestUtils.getField(u, "deactivationEventId")).isEqualTo(88L);
        u.deactivateForSeparation(99L, NOW.plusSeconds(5));
        assertThat(ReflectionTestUtils.getField(u, "deactivationEventId"))
                .as("already deactivated")
                .isEqualTo(88L);
        assertThat(u.getDeactivatedAt()).isEqualTo(NOW);
    }

    @Test
    void A_02_aFunctionalRoleNeedsItsBaseRoleAReadOnlyRoleHasNone() {
        assertThatThrownBy(
                        () ->
                                AppRole.create(
                                        "R", "R", "d", false, AppRole.RoleType.FUNCTIONAL, null, D))
                .isInstanceOf(IllegalArgumentException.class);
        AppRole readOnly =
                AppRole.create(
                        "RO",
                        "RO",
                        "d",
                        false,
                        AppRole.RoleType.READ_ONLY,
                        AppRole.BaseRole.MAKER,
                        D);
        assertThat(ReflectionTestUtils.getField(readOnly, "baseRole")).isNull();
        AppRole module =
                AppRole.create(
                        "M", "M", "d", true, AppRole.RoleType.MODULE, AppRole.BaseRole.CHECKER, D);
        assertThat(ReflectionTestUtils.getField(module, "baseRole"))
                .isEqualTo(AppRole.BaseRole.CHECKER);
        assertThat(ReflectionTestUtils.getField(module, "status"))
                .isEqualTo(AppRole.Status.PROVISIONAL);
    }

    @Test
    void A_02_aRoleIsActiveInsideItsWindowUntilRetired() {
        AppRole role =
                AppRole.create(
                        "M", "M", "d", true, AppRole.RoleType.MODULE, AppRole.BaseRole.MAKER, D);
        assertThat(role.isActiveOn(D.minusDays(1))).isFalse();
        assertThat(role.isActiveOn(D)).isTrue();
        role.update("M2", "d2", false);
        assertThat(ReflectionTestUtils.getField(role, "name")).isEqualTo("M2");
        role.retire(D.plusDays(10));
        assertThat(role.isActiveOn(D.plusDays(5))).as("retired roles are never active").isFalse();
        AppRole windowed =
                AppRole.create(
                        "W", "W", "d", true, AppRole.RoleType.MODULE, AppRole.BaseRole.MAKER, D);
        ReflectionTestUtils.setField(windowed, "effectiveTo", D.plusDays(10));
        assertThat(windowed.isActiveOn(D.plusDays(10))).isTrue();
        assertThat(windowed.isActiveOn(D.plusDays(11))).isFalse();
    }

    @Test
    void aSessionKeepsItsSortedGroupsSwitchesScopeAndEndsOnce() {
        AppSession s =
                AppSession.start(
                        "hash",
                        1L,
                        "PAYROLL",
                        4L,
                        List.of(9L, 3L),
                        5L,
                        "10.0.0.1",
                        "u".repeat(401),
                        NOW);
        assertThat(s.activeGroupIdList()).containsExactly(3L, 9L);
        assertThat((String) ReflectionTestUtils.getField(s, "userAgent")).hasSize(400);
        assertThat(s.getCreatedAt()).isEqualTo(NOW);
        assertThat(s.isOpen()).isTrue();
        s.switchScope("FINANCE", 6L, List.of(), NOW.plusSeconds(1));
        assertThat(s.activeGroupIdList()).isEmpty();
        assertThat(ReflectionTestUtils.getField(s, "switchedAt")).isEqualTo(NOW.plusSeconds(1));
        s.end(AppSession.EndReason.LOGOUT, NOW.plusSeconds(2));
        s.end(AppSession.EndReason.TIMEOUT, NOW.plusSeconds(3));
        assertThat(s.isOpen()).isFalse();
        assertThat(s.getEndedAt()).isEqualTo(NOW.plusSeconds(2));
        assertThat(ReflectionTestUtils.getField(s, "endReason")).isEqualTo("LOGOUT");
        AppSession exact =
                AppSession.start("h", 1L, "P", null, null, null, null, "u".repeat(400), NOW);
        assertThat((String) ReflectionTestUtils.getField(exact, "userAgent")).hasSize(400);
        assertThat(exact.activeGroupIdList()).isEmpty();
        assertThatThrownBy(() -> AppSession.start(null, 1L, "P", null, null, null, null, null, NOW))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void SRS_SEC_14_aSessionWithoutAnOfficeIsInTheEsevaScopeAndFollowsAScopeSwitch() {
        AppSession staff =
                AppSession.start("h", 1L, "PAYROLL", 4L, List.of(3L), 5L, null, null, NOW);
        assertThat(staff.isEsevaScope()).isFalse();
        staff.switchScope("EMPLOYEE", null, List.of(), NOW.plusSeconds(1));
        assertThat(staff.isEsevaScope()).as("own record, no office").isTrue();
        staff.switchScope("PAYROLL", 4L, List.of(3L), NOW.plusSeconds(2));
        assertThat(staff.isEsevaScope()).isFalse();
        assertThat(
                        AppSession.start("h", 2L, "EMPLOYEE", null, List.of(), 5L, null, null, NOW)
                                .isEsevaScope())
                .isTrue();
    }

    @Test
    void A_03_aPersonaRoleKeyIsEqualOnPersonaAndRole() {
        LoginPersonaRole.Key a = key("PAYROLL", 1L);
        assertThat(a).isEqualTo(key("PAYROLL", 1L)).hasSameHashCodeAs(key("PAYROLL", 1L));
        assertThat(a).isNotEqualTo(key("PAYROLL", 2L));
        assertThat(a).isNotEqualTo(key("FINANCE", 1L));
        assertThat(a).isNotEqualTo("PAYROLL");
        assertThat(a.hashCode()).isNotZero();
    }

    private static LoginPersonaRole.Key key(String persona, Long role) {
        LoginPersonaRole.Key k = BeanUtils.instantiateClass(LoginPersonaRole.Key.class);
        ReflectionTestUtils.setField(k, "personaCode", persona);
        ReflectionTestUtils.setField(k, "roleId", role);
        return k;
    }
}
