package in.gov.fci.payroll.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.gov.fci.payroll.employee.business.model.Employee;
import in.gov.fci.payroll.employee.business.model.RecordStatus;
import in.gov.fci.payroll.iam.business.model.AccessGroupScope;
import in.gov.fci.payroll.iam.business.model.AppPermission;
import in.gov.fci.payroll.iam.business.model.AppRole;
import in.gov.fci.payroll.iam.business.model.AppRolePermission;
import in.gov.fci.payroll.iam.business.model.AppUserRole;
import in.gov.fci.payroll.iam.business.model.OtpChallenge;
import in.gov.fci.payroll.iam.business.model.PasswordHistoryEntry;
import in.gov.fci.payroll.iam.business.model.UserPreference;
import in.gov.fci.payroll.platform.security.OtpGatedAction;
import in.gov.fci.payroll.workflow.business.model.WorkflowInboxItem;
import java.lang.reflect.Constructor;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** Value rules of the target-model entities. */
class EntityRulesTest {

    private static final Instant NOW = Instant.parse("2026-10-16T05:30:00Z");

    @Test
    void employeeStatusIsDerivedSetupFirstThenDataQuality() throws Exception {
        assertThat(employee(RecordStatus.ACTIVE, "ABCPS1234K", "C1", 5L, false).displayStatus())
                .isEqualTo("ACTIVE");
        assertThat(
                        employee(RecordStatus.PAYROLL_SETUP_PENDING, null, null, null, false)
                                .displayStatus())
                .isEqualTo("PAYROLL_SETUP_PENDING");
        assertThat(employee(RecordStatus.CHANGE_PENDING, null, null, null, false).displayStatus())
                .isEqualTo("CHANGE_PENDING");
        assertThat(employee(RecordStatus.ACTIVE, null, "C1", 5L, false).displayStatus())
                .isEqualTo("PAN_CPF_MISSING");
        assertThat(employee(RecordStatus.ACTIVE, "P", null, 5L, false).displayStatus())
                .isEqualTo("PAN_CPF_MISSING");
        assertThat(employee(RecordStatus.ACTIVE, "P", null, 5L, true).displayStatus())
                .isEqualTo("ACTIVE");
        assertThat(employee(RecordStatus.ACTIVE, "P", "C", null, false).displayStatus())
                .isEqualTo("BANK_INVALID");
    }

    @Test
    void aLegacyPendingChangeIsClearedOnceAndNothingElse() throws Exception {
        Employee e = employee(RecordStatus.CHANGE_PENDING, "P", "C", 5L, false);
        e.clearChangePending(NOW);
        assertThat(e.getRecordStatus()).isEqualTo(RecordStatus.ACTIVE);
        e.clearChangePending(NOW);
        assertThat(e.getRecordStatus()).isEqualTo(RecordStatus.ACTIVE);
    }

    private static OtpChallenge otp(OtpGatedAction action) {
        return OtpChallenge.issue(
                1L,
                "maker@fci-demo.test",
                action,
                "employee",
                "1",
                "h",
                "127.0.0.1",
                NOW,
                Duration.ofSeconds(600));
    }

    // SC-01-0148: the OTP is single-use and expires after its validity.
    @Test
    void SRS_G_3_otpsAreSingleUseExpireAndLockOutAfterTooManyAttempts() {
        OtpChallenge ok = otp(OtpGatedAction.AUTH_OWN_PASSWORD_CHANGE);
        assertThat(ok.verify("h", 5, NOW)).isTrue();
        assertThat(ok.getOutcome()).isEqualTo(OtpChallenge.Outcome.SUCCESS);
        assertThat(ok.verify("h", 5, NOW)).as("single use").isFalse();

        OtpChallenge late = otp(OtpGatedAction.AUTH_OWN_PASSWORD_CHANGE);
        assertThat(late.verify("h", 5, NOW.plusSeconds(600))).isFalse();
        assertThat(late.getOutcome()).isEqualTo(OtpChallenge.Outcome.EXPIRED);

        OtpChallenge guessed = otp(OtpGatedAction.SUPPLEMENTARY_POST_LOCK);
        assertThat(guessed.verify("x", 2, NOW)).isFalse();
        assertThat(guessed.verify("y", 2, NOW)).isFalse();
        assertThat(guessed.getOutcome()).isEqualTo(OtpChallenge.Outcome.LOCKED_OUT);
        assertThat(guessed.verify("h", 2, NOW)).as("no retry after lock-out").isFalse();

        OtpChallenge superseded = otp(OtpGatedAction.CYCLE_FREEZE_LOCK);
        superseded.abandon(NOW);
        assertThat(superseded.getOutcome()).isEqualTo(OtpChallenge.Outcome.ABANDONED);
        assertThat(superseded.verify("h", 5, NOW)).isFalse();
    }

    /**
     * SRS-G-21 (SRS v0.5 l.537, BMAD TR-003): exactly the eighteen listed actions, by name (the DDL
     * v4.0 {@code chk_otp_gated_action} list), so a swapped or extra action fails.
     */
    @Test
    void SRS_G_21_exactlyEighteenOtpGatedActions() {
        assertThat(OtpGatedAction.values())
                .extracting(Enum::name)
                .containsExactlyInAnyOrder(
                        "AUTH_LOGIN",
                        "AUTH_OWN_PASSWORD_CHANGE",
                        "CYCLE_FREEZE_LOCK",
                        "CYCLE_UNFREEZE_STUCK_RELEASE",
                        "INCREMENT_DATE_BASIC_CORRECTION",
                        "DISBURSEMENT_RELEASE",
                        "APPS_TRANSMISSION",
                        "YEAR_END_REMITTANCE",
                        "CPF_SANCTION_FINAL_PAYMENT",
                        "CPF_OPENING_BALANCE_CORRECTION",
                        "WAGE_REVISION_HQ",
                        "UNDO_TRANSACTION",
                        "FNF_APPROVAL",
                        "SUPPLEMENTARY_POST_LOCK",
                        "LOAN_FORECLOSURE",
                        "PLI_ADJUSTMENT",
                        "ROLE_ACCESS_GROUP_CHANGE",
                        "HRMS_FALLBACK_UPLOAD_APPROVAL");
    }

    @Test
    void inboxItemsFlagSlaBreachesOnce() {
        WorkflowInboxItem item = WorkflowInboxItem.open(1L, "PAYROLL_CHECKER", 10L, NOW);
        assertThat(item.flagIfBreached(NOW)).isFalse();
        assertThat(item.flagIfBreached(NOW.plusSeconds(1))).isTrue();
        assertThat(item.flagIfBreached(NOW.plusSeconds(2))).isFalse();
        assertThat(item.isBreached()).isTrue();
        item.close(true);
        assertThat(item.getStatus()).isEqualTo(WorkflowInboxItem.Status.RETURNED);
        assertThat(WorkflowInboxItem.open(2L, "X", 10L, null).flagIfBreached(NOW)).isFalse();
    }

    @Test
    void compositeKeysCompareByValue() {
        assertThat(new AppRolePermission.Key(1L, 2L))
                .isEqualTo(new AppRolePermission.Key(1L, 2L))
                .hasSameHashCodeAs(new AppRolePermission.Key(1L, 2L))
                .isNotEqualTo(new AppRolePermission.Key(1L, 3L))
                .isNotEqualTo(new AppRolePermission.Key(9L, 2L))
                .isNotEqualTo("x");
        assertThat(new AppUserRole.Key(1L, 2L))
                .isEqualTo(new AppUserRole.Key(1L, 2L))
                .hasSameHashCodeAs(new AppUserRole.Key(1L, 2L))
                .isNotEqualTo(new AppUserRole.Key(1L, 3L))
                .isNotEqualTo(new AppUserRole.Key(9L, 2L))
                .isNotEqualTo(null);
        assertThat(new PasswordHistoryEntry.Key(1L, NOW))
                .isEqualTo(new PasswordHistoryEntry.Key(1L, NOW))
                .hasSameHashCodeAs(new PasswordHistoryEntry.Key(1L, NOW))
                .isNotEqualTo(new PasswordHistoryEntry.Key(1L, NOW.plusSeconds(1)))
                .isNotEqualTo(new PasswordHistoryEntry.Key(2L, NOW));
        AppRolePermission.Key same = new AppRolePermission.Key(5L, 6L);
        assertThat(same).isEqualTo(same);
    }

    @Test
    void aPermissionIsNamedByItsScreenAndAction() {
        AppPermission p = org.springframework.beans.BeanUtils.instantiateClass(AppPermission.class);
        ReflectionTestUtils.setField(p, "screenCode", "M-01");
        ReflectionTestUtils.setField(p, "actionCode", "VIEW");
        assertThat(p.key()).isEqualTo("M-01:VIEW");
    }

    @Test
    void compositeKeysHashByEveryPartSoRowsOfOneRoleOrUserSpreadOverTheCache() {
        // The persistence context keys its entities by these values: equal keys hash alike, and
        // keys that differ in one part do not collide on one bucket.
        assertThat(new AppRolePermission.Key(1L, 2L).hashCode())
                .isEqualTo(java.util.Objects.hash(1L, 2L))
                .isNotEqualTo(new AppRolePermission.Key(1L, 3L).hashCode())
                .isNotEqualTo(new AppRolePermission.Key(9L, 2L).hashCode());
        assertThat(new PasswordHistoryEntry.Key(1L, NOW).hashCode())
                .isEqualTo(java.util.Objects.hash(1L, NOW))
                .isNotEqualTo(new PasswordHistoryEntry.Key(1L, NOW.plusSeconds(1)).hashCode())
                .isNotEqualTo(new PasswordHistoryEntry.Key(2L, NOW).hashCode());
        java.util.Map<PasswordHistoryEntry.Key, String> history = new java.util.HashMap<>();
        history.put(new PasswordHistoryEntry.Key(1L, NOW), "first");
        history.put(new PasswordHistoryEntry.Key(1L, NOW.plusSeconds(1)), "second");
        assertThat(history.get(new PasswordHistoryEntry.Key(1L, NOW))).isEqualTo("first");
        assertThat(history).hasSize(2);
    }

    @Test
    void rolesGrantsAndPreferencesAreEffectiveDated() {
        AppRole role =
                AppRole.create(
                        "LEAVE_ADMIN",
                        "Leave",
                        "d",
                        false,
                        AppRole.RoleType.MODULE,
                        AppRole.BaseRole.MAKER,
                        LocalDate.of(2026, 10, 1));
        assertThat(role.isActiveOn(LocalDate.of(2026, 9, 30))).isFalse();
        assertThat(role.isActiveOn(LocalDate.of(2026, 10, 1))).isTrue();
        role.update("Leave Admin", "d2", true);
        assertThat(role.isApprover()).isTrue();
        role.retire(LocalDate.of(2026, 12, 31));
        assertThat(role.isActiveOn(LocalDate.of(2026, 11, 1))).isFalse();

        AppRolePermission grant = new AppRolePermission(1L, 2L, LocalDate.of(2026, 1, 1));
        grant.endBefore(LocalDate.of(2026, 10, 16));
        assertThat(grant.isActiveOn(LocalDate.of(2026, 10, 15))).isTrue();
        assertThat(grant.isActiveOn(LocalDate.of(2026, 10, 16))).isFalse();
        grant.grantFrom(LocalDate.of(2026, 11, 1));
        assertThat(grant.isActiveOn(LocalDate.of(2026, 11, 1))).isTrue();

        AppUserRole direct = new AppUserRole(1L, 2L, LocalDate.of(2026, 1, 1), 3L, null);
        direct.endOn(LocalDate.of(2026, 6, 30));
        assertThat(direct.isActiveOn(LocalDate.of(2026, 7, 1))).isFalse();

        UserPreference preference = new UserPreference(1L, "hi", NOW);
        assertThat(preference.getPreferredLocale()).isEqualTo("hi");
        assertThatThrownBy(() -> preference.setLocale("fr", NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rolesNeedABaseRoleUnlessReadOnly() {
        assertThatThrownBy(
                        () ->
                                AppRole.create(
                                        "X",
                                        "X",
                                        "d",
                                        false,
                                        AppRole.RoleType.FUNCTIONAL,
                                        null,
                                        LocalDate.of(2026, 1, 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(
                        AppRole.create(
                                "RO",
                                "Read",
                                "d",
                                false,
                                AppRole.RoleType.READ_ONLY,
                                null,
                                LocalDate.of(2026, 1, 1)))
                .isNotNull();
    }

    @Test
    void aScopeRowNamesAtMostOneOfZoneOrLocation() {
        assertThat(AccessGroupScope.of(1L, 2L, null).getZoneId()).isEqualTo(2L);
        assertThat(AccessGroupScope.of(1L, null, 3L).getLocationId()).isEqualTo(3L);
        assertThatThrownBy(() -> AccessGroupScope.of(1L, 2L, 3L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Employee employee(
            RecordStatus status, String pan, String cpf, Long bankBranch, boolean labour)
            throws Exception {
        Constructor<Employee> constructor = Employee.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        Employee e = constructor.newInstance();
        ReflectionTestUtils.setField(e, "recordStatus", status);
        ReflectionTestUtils.setField(e, "panNumber", pan);
        ReflectionTestUtils.setField(e, "cpfCode", cpf);
        ReflectionTestUtils.setField(e, "bankBranchId", bankBranch);
        ReflectionTestUtils.setField(e, "labour", labour);
        return e;
    }
}
