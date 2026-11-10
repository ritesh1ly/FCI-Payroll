package in.gov.fci.payroll.iam.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.employee.api.EmployeeDirectory;
import in.gov.fci.payroll.iam.business.ClientDevices;
import in.gov.fci.payroll.iam.business.model.AppSession;
import in.gov.fci.payroll.iam.business.model.AppUser;
import in.gov.fci.payroll.iam.business.model.AppUserCredential;
import in.gov.fci.payroll.iam.business.model.UserPreference;
import in.gov.fci.payroll.iam.orchestration.UserAdminService.NewUser;
import in.gov.fci.payroll.iam.persistence.AccessRepositories;
import in.gov.fci.payroll.iam.persistence.IdentityRepositories;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.ErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/**
 * Mutation gaps of {@link UserAdminService}: A-01 screen permissions per action, Headquarters /
 * office scope checks, audited changes, session endings and the returned rows (SRS-G-49, SRS-G-50,
 * SRS-22-13, SRS-15-29).
 */
class UserAdminServiceMutationGapsTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Instant NOW = LocalDate.now(IST).atTime(11, 0).atZone(IST).toInstant();

    private final AccessRepositories.Users users = mock(AccessRepositories.Users.class);
    private final IdentityRepositories.Credentials credentials =
            mock(IdentityRepositories.Credentials.class);
    private final IdentityRepositories.Preferences preferences =
            mock(IdentityRepositories.Preferences.class);
    private final EmployeeDirectory employees = mock(EmployeeDirectory.class);
    private final PasswordService passwords = mock(PasswordService.class);
    private final SessionService sessions = mock(SessionService.class);
    private final AccessContextService contexts = mock(AccessContextService.class);
    private final AccessPolicy policy = mock(AccessPolicy.class);
    private final AuditService audit = mock(AuditService.class);
    private final AdminScope adminScope = mock(AdminScope.class);
    private final AccountChangeAlerts alerts = mock(AccountChangeAlerts.class);

    private final UserAdminService service =
            new UserAdminService(
                    users,
                    credentials,
                    preferences,
                    employees,
                    passwords,
                    sessions,
                    contexts,
                    policy,
                    audit,
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    adminScope,
                    alerts,
                    org.mockito.Mockito.mock(SecurityOffences.class));

    private final AccessContext admin =
            new AccessContext(
                    1L,
                    "admin@x",
                    "Admin",
                    "s",
                    "P",
                    10L,
                    null,
                    List.of(),
                    Map.of(),
                    Set.of(),
                    Set.of(),
                    Set.of(10L));

    private static void refused(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        FciException.class, e -> assertThat(e.getErrorCode()).isEqualTo(expected));
    }

    private AppUser user(long id, AppUser.Status status) {
        AppUser u = mock(AppUser.class);
        when(u.getId()).thenReturn(id);
        when(u.getLoginId()).thenReturn("user" + id + "@fci-demo.test");
        when(u.getDisplayName()).thenReturn("User " + id);
        when(u.getCategory()).thenReturn(AppUser.Category.OPERATIONS);
        when(u.getStatus()).thenReturn(status);
        when(users.findById(id)).thenReturn(Optional.of(u));
        return u;
    }

    // ------------------------------------------------------------------ list / get

    @Test
    void SRS_22_13_theListNeedsA01ViewAndShowsTheRowsOfTheNationalSearch() {
        AppUser u = user(5L, AppUser.Status.ACTIVE);
        PageRequest request = PageRequest.of(0, 20, Sort.by("loginId"));
        when(adminScope.isNational(admin)).thenReturn(true);
        when(users.search("%maker%", AppUser.Status.ACTIVE, request))
                .thenReturn(new PageImpl<>(List.of(u), request, 1));

        UserAdminService.PageView<UserAdminService.UserRow> page =
                service.list(admin, " Maker ", AppUser.Status.ACTIVE, 0, 20);

        verify(policy).require(admin, "A-01", "VIEW");
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).id()).isEqualTo(5L);
        assertThat(page.items().get(0).loginId()).isEqualTo("user5@fci-demo.test");
        assertThat(page.items().get(0).category()).isEqualTo("OPERATIONS");
        assertThat(page.total()).isEqualTo(1L);
        assertThat(page.size()).isEqualTo(20);
    }

    @Test
    void SRS_22_13_aScopedAdministratorSearchesOnlyAmongItsOfficesAndBlankTextIsNoFilter() {
        AppUser u = user(6L, AppUser.Status.ACTIVE);
        PageRequest request = PageRequest.of(0, 100, Sort.by("loginId"));
        when(adminScope.isNational(admin)).thenReturn(false);
        when(users.searchAmong(null, null, Set.of(10L), request))
                .thenReturn(new PageImpl<>(List.of(u), request, 1));

        UserAdminService.PageView<UserAdminService.UserRow> page =
                service.list(admin, "   ", null, -1, 1000);

        assertThat(page.items()).extracting(UserAdminService.UserRow::id).containsExactly(6L);
        verify(users, never()).search(any(), any(), any());
    }

    @Test
    void SRS_22_13_aScopedAdministratorWithoutOfficesSeesNoUsers() {
        AccessContext noScope =
                new AccessContext(
                        1L, "admin@x", "Admin", "s", "P", 10L, null, List.of(), Map.of(), Set.of(),
                        Set.of(), Set.of());
        when(adminScope.isNational(noScope)).thenReturn(false);

        assertThat(service.list(noScope, null, null, 0, 20).items()).isEmpty();
        verify(users, never()).searchAmong(any(), any(), any(), any());
    }

    @Test
    void SRS_22_13_getNeedsA01ViewAndTheUsersVisibilityAndAnUnknownIdIsNotFound() {
        AppUser u = user(5L, AppUser.Status.ACTIVE);

        UserAdminService.UserRow row = service.get(admin, 5L);

        verify(policy).require(admin, "A-01", "VIEW");
        verify(adminScope).requireUserVisible(admin, u);
        assertThat(row.id()).isEqualTo(5L);
        assertThat(row.mustChangePassword()).as("no credential on record").isTrue();
        assertThat(row.locked()).isFalse();

        when(users.findById(404L)).thenReturn(Optional.empty());
        refused(() -> service.get(admin, 404L), CommonErrorCode.NOT_FOUND);
    }

    // ------------------------------------------------------------------ create

    @Test
    void SRS_22_13_createNeedsA01CreateChecksTheEmployeeScopeAndAuditsTheNewAccount() {
        AppUser saved = user(9L, AppUser.Status.ACTIVE);
        when(users.save(any(AppUser.class))).thenReturn(saved);
        when(employees.idOf("EIS-100234")).thenReturn(Optional.of(55L));

        UserAdminService.UserRow row =
                service.create(
                        admin,
                        new NewUser(
                                " New.User@FCI-Demo.test ",
                                "New User",
                                AppUser.Category.OPERATIONS,
                                "EIS-100234",
                                null,
                                "Temp#Pass1"));

        verify(policy).require(admin, "A-01", "CREATE");
        verify(adminScope).requireNewUser(admin, 55L);
        verify(passwords).setTemporary(saved, "Temp#Pass1");
        verify(preferences).save(any(UserPreference.class));
        verify(audit)
                .record(
                        "admin@x",
                        AuditService.Entry.change(
                                        "app_user",
                                        "9",
                                        "CREATE",
                                        null,
                                        "new.user@fci-demo.test",
                                        "User created")
                                .module("A-01"));
        assertThat(row.id()).isEqualTo(9L);
    }

    @Test
    void SRS_22_13_createWithoutAnEmployeeChecksTheScopeForNoEmployee() {
        AppUser saved = user(9L, AppUser.Status.ACTIVE);
        when(users.save(any(AppUser.class))).thenReturn(saved);

        service.create(
                admin,
                new NewUser(
                        "ops.user@fci-demo.test",
                        "Ops User",
                        AppUser.Category.OPERATIONS,
                        "  ",
                        null,
                        "Temp#Pass1"));

        verify(adminScope).requireNewUser(admin, null);
        verify(employees, never()).idOf(any());
    }

    // ------------------------------------------------------------------ updateContact

    @Test
    void SRS_15_29_updateContactNeedsA01EditAndAnswersTheRowInBothForms() {
        user(5L, AppUser.Status.ACTIVE);

        assertThat(service.updateContact(admin, 5L, "New Name", "9876543210")).isNotNull();
        assertThat(service.updateContact(admin, 5L, "New Name", "9876543210", null)).isNotNull();

        verify(policy, org.mockito.Mockito.times(2)).require(admin, "A-01", "EDIT");
    }

    @Test
    void SRS_15_29_aChangedMobileIsAuditedMaskedAndTheOwnerIsToldAtTheOldNumber() {
        AppUser u = user(5L, AppUser.Status.ACTIVE);
        when(u.getDisplayName()).thenReturn("Old Name", "New Name");
        when(u.getRegisteredMobile()).thenReturn("9876543210", "9123456789");

        service.updateContact(admin, 5L, "New Name", "9123456789");

        verify(users).saveAndFlush(u);
        verify(audit)
                .record(
                        "admin@x",
                        AuditService.Entry.change(
                                        "app_user",
                                        "5",
                                        "display_name",
                                        "Old Name",
                                        "New Name",
                                        "Contact updated")
                                .module("A-01"));
        verify(audit)
                .record(
                        "admin@x",
                        AuditService.Entry.change(
                                        "app_user",
                                        "5",
                                        "registered_mobile",
                                        "********10",
                                        "********89",
                                        "Contact updated")
                                .module("A-01"));
        verify(alerts).changed(u, "9876543210", "registered mobile");
    }

    @Test
    void SRS_15_29_anUnchangedMobileIsNotAuditedAndNobodyIsTold() {
        AppUser u = user(5L, AppUser.Status.ACTIVE);
        when(u.getRegisteredMobile()).thenReturn("9876543210");

        service.updateContact(admin, 5L, "", "9876543210");

        verify(alerts, never()).changed(any(), any(), any());
    }

    @Test
    void SRS_15_29_aStaleVersionIsRefusedNamingWhoChangedIt() {
        AppUser u = user(5L, AppUser.Status.ACTIVE);
        when(u.getRowVersion()).thenReturn(4L);
        when(audit.lastChange("app_user", "5"))
                .thenReturn(Optional.of(new AuditService.LastChange("checker@x", NOW)));

        assertThatThrownBy(() -> service.updateContact(admin, 5L, "Name", "9876543210", 3L))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> {
                            assertThat(e.getErrorCode()).isEqualTo(ApiErrorCode.RECORD_CHANGED);
                            assertThat(e.getMessageArgs())
                                    .containsExactly("checker@x", NOW.toString(), 4L);
                        });
        verify(users, never()).saveAndFlush(any());

        assertThatCode(() -> service.updateContact(admin, 5L, "Name", "9876543210", 4L))
                .doesNotThrowAnyException();
    }

    // ------------------------------------------------------------------ changeStatus

    @Test
    void SRS_G_49_aSuspensionNeedsA01EditEndsTheSessionsEvictsAndAudits() {
        AppUser u = user(5L, AppUser.Status.ACTIVE);

        UserAdminService.UserRow row =
                service.changeStatus(admin, 5L, AppUser.Status.SUSPENDED, null, "Compromised");

        verify(policy).require(admin, "A-01", "EDIT");
        verify(u).changeStatus(AppUser.Status.SUSPENDED, null, NOW);
        verify(users).save(u);
        verify(sessions).endAllSessions(5L, NOW, AppSession.EndReason.ADMIN_REVOKE);
        verify(contexts).evictAll();
        verify(audit)
                .record(
                        "admin@x",
                        AuditService.Entry.change(
                                        "app_user",
                                        "5",
                                        "user_status",
                                        "ACTIVE",
                                        "SUSPENDED",
                                        "Compromised")
                                .module("A-01"));
        assertThat(row).isNotNull();
    }

    @Test
    void SRS_G_49_aDeactivationNeedsA01DeleteNotEdit() {
        user(5L, AppUser.Status.ACTIVE);

        service.changeStatus(
                admin, 5L, AppUser.Status.DEACTIVATED, AppUser.DeactivationReason.ADMIN, "Left");

        verify(policy).require(admin, "A-01", "DELETE");
        verify(policy, never()).require(admin, "A-01", "EDIT");
    }

    @Test
    void SRS_G_49_aReinstatementNeedsA01EditUnlocksTheCredentialAndEndsNoSession() {
        user(5L, AppUser.Status.SUSPENDED);
        AppUserCredential credential = mock(AppUserCredential.class);
        when(credentials.findById(5L)).thenReturn(Optional.of(credential));

        service.changeStatus(admin, 5L, AppUser.Status.ACTIVE, null, null);

        verify(policy).require(admin, "A-01", "EDIT");
        verify(credential).unlockByAdministrator("admin@x", "Reactivated", NOW);
        verify(credentials).save(credential);
        verify(sessions, never()).endAllSessions(any(), any(), any());
        verify(audit)
                .record(
                        "admin@x",
                        AuditService.Entry.change(
                                        "app_user",
                                        "5",
                                        "user_status",
                                        "SUSPENDED",
                                        "ACTIVE",
                                        "Status changed")
                                .module("A-01"));
    }

    // ------------------------------------------------------------------ unlock

    @Test
    void SRS_G_49_unlockNeedsA01EditClearsTheLockAndAuditsTheUnlock() {
        user(5L, AppUser.Status.ACTIVE);
        AppUserCredential credential = mock(AppUserCredential.class);
        when(credentials.findById(5L)).thenReturn(Optional.of(credential));

        UserAdminService.UserRow row = service.unlock(admin, 5L, " Verified by phone ");

        verify(policy).require(admin, "A-01", "EDIT");
        verify(credential).unlockByAdministrator("admin@x", "Verified by phone", NOW);
        verify(credentials).save(credential);
        verify(audit)
                .securityEvent(
                        AuditService.Security.ACCOUNT_UNLOCK,
                        "admin@x",
                        "app_user",
                        "5",
                        "Verified by phone");
        verify(audit)
                .record(
                        "admin@x",
                        AuditService.Entry.change(
                                        "app_user",
                                        "5",
                                        "LOCKOUT",
                                        "LOCKED",
                                        "UNLOCKED",
                                        "Verified by phone")
                                .module("A-01"));
        assertThat(row).isNotNull();
        assertThat(row.id()).isEqualTo(5L);
    }

    @Test
    void SRS_G_49_unlockOfAnAccountWithoutCredentialIsNotFoundAndNeedsAReason() {
        user(5L, AppUser.Status.ACTIVE);
        when(credentials.findById(5L)).thenReturn(Optional.empty());

        refused(() -> service.unlock(admin, 5L, "reason"), CommonErrorCode.NOT_FOUND);
        refused(() -> service.unlock(admin, 5L, " "), ApiErrorCode.REASON_REQUIRED);
        refused(() -> service.unlock(admin, 5L, null), ApiErrorCode.REASON_REQUIRED);
        verify(audit, never()).record(any(), any());
    }

    // ------------------------------------------------------------------ terminateSessions

    @Test
    void SRS_G_49_terminatingSessionsEndsThemAnswersTheCountAndAuditsTheTrimmedReason() {
        user(5L, AppUser.Status.ACTIVE);
        when(sessions.openCount(5L)).thenReturn(3);

        int open = service.terminateSessions(admin, 5L, "  Suspected misuse ");

        assertThat(open).isEqualTo(3);
        verify(policy).require(admin, "A-01", "EDIT");
        verify(sessions).endAllSessions(5L, NOW, AppSession.EndReason.ADMIN_REVOKE);
        verify(contexts).evictAll();
        verify(audit)
                .securityEvent(
                        AuditService.Security.SESSION_TERMINATED,
                        "admin@x",
                        "app_user",
                        "5",
                        "Suspected misuse");
        verify(audit)
                .record(
                        "admin@x",
                        AuditService.Entry.change(
                                        "app_user",
                                        "5",
                                        "SESSION",
                                        "3 open",
                                        "TERMINATED",
                                        "Suspected misuse")
                                .module("A-01"));
    }

    @Test
    void SRS_G_49_aReasonIsRequiredAndTheAdministratorsOwnSessionsAreNeverTerminated() {
        user(5L, AppUser.Status.ACTIVE);

        refused(() -> service.terminateSessions(admin, 5L, " "), ApiErrorCode.REASON_REQUIRED);
        refused(() -> service.terminateSessions(admin, 5L, null), ApiErrorCode.REASON_REQUIRED);
        refused(() -> service.terminateSessions(admin, 1L, "why"), ApiErrorCode.SELF_ASSIGNMENT);
        verify(sessions, never()).endAllSessions(any(), any(), any());
    }

    // ------------------------------------------------------------------ suspend

    @Test
    void SRS_G_49_aGroupSuspensionSuspendsTheActiveSkipsTheOthersAndAuditsEach() {
        AppUser active = user(5L, AppUser.Status.ACTIVE);
        AppUser locked = user(6L, AppUser.Status.SUSPENDED);

        UserAdminService.SuspendOutcome outcome =
                service.suspend(admin, List.of(5L, 6L, 5L), " Incident 42 ");

        verify(policy).require(admin, "A-01", "EDIT");
        assertThat(outcome.suspended()).containsExactly(5L);
        assertThat(outcome.skipped()).containsExactly(6L);
        verify(active).changeStatus(AppUser.Status.SUSPENDED, null, NOW);
        verify(locked, never()).changeStatus(any(), any(), any());
        verify(users).save(active);
        verify(users, never()).save(locked);
        verify(sessions).endAllSessions(5L, NOW, AppSession.EndReason.ADMIN_REVOKE);
        verify(sessions, never()).endAllSessions(org.mockito.ArgumentMatchers.eq(6L), any(), any());
        verify(audit)
                .securityEvent(
                        AuditService.Security.ACCOUNT_SUSPENDED,
                        "admin@x",
                        "app_user",
                        "5",
                        "Incident 42");
        verify(audit)
                .record(
                        "admin@x",
                        AuditService.Entry.change(
                                        "app_user",
                                        "5",
                                        "user_status",
                                        "ACTIVE",
                                        "SUSPENDED",
                                        "Incident 42")
                                .module("A-01"));
        verify(contexts).evictAll();
    }

    @Test
    void SRS_G_49_aGroupSuspensionOfExactlyTheMaximumIsAllowedOneMoreIsNot() {
        when(users.findById(anyLong()))
                .thenAnswer(
                        call -> {
                            AppUser u = mock(AppUser.class);
                            when(u.getId()).thenReturn(call.getArgument(0, Long.class));
                            when(u.getStatus()).thenReturn(AppUser.Status.SUSPENDED);
                            return Optional.of(u);
                        });
        List<Long> hundred = new ArrayList<>();
        for (long id = 2; id < 2 + UserAdminService.MAX_SUSPEND; id++) {
            hundred.add(id);
        }

        UserAdminService.SuspendOutcome outcome = service.suspend(admin, hundred, "Incident");
        assertThat(outcome.skipped()).hasSize(UserAdminService.MAX_SUSPEND);
        assertThat(outcome.suspended()).isEmpty();

        List<Long> tooMany = new ArrayList<>(hundred);
        tooMany.add(1000L);
        refused(
                () -> service.suspend(admin, tooMany, "Incident"),
                CommonErrorCode.VALIDATION_FAILED);
    }

    // ------------------------------------------------------------------ logins

    @Test
    void SRS_G_50_theLoginRecordNeedsA01ViewAndListsTheRecentSessions() {
        user(5L, AppUser.Status.ACTIVE);
        AppSession open = mock(AppSession.class);
        when(open.getId()).thenReturn(71L);
        when(open.getCreatedAt()).thenReturn(NOW);
        when(open.getEndedAt()).thenReturn(null);
        when(open.getEndReason()).thenReturn(null);
        when(open.getSourceIp()).thenReturn("10.0.0.7");
        when(open.getUserAgent()).thenReturn(null);
        when(open.isOpen()).thenReturn(true);
        AppSession ended = mock(AppSession.class);
        when(ended.getId()).thenReturn(70L);
        when(ended.getCreatedAt()).thenReturn(NOW.minusSeconds(3600));
        when(ended.getEndedAt()).thenReturn(NOW.minusSeconds(1800));
        when(ended.getEndReason()).thenReturn("LOGOUT");
        when(ended.getSourceIp()).thenReturn("10.0.0.8");
        when(ended.isOpen()).thenReturn(false);
        when(sessions.recent(5L)).thenReturn(List.of(open, ended));

        List<UserAdminService.LoginRecord> records = service.logins(admin, 5L);

        verify(policy).require(admin, "A-01", "VIEW");
        assertThat(records)
                .containsExactly(
                        new UserAdminService.LoginRecord(
                                71L,
                                NOW,
                                null,
                                null,
                                "10.0.0.7",
                                ClientDevices.describe(null),
                                true),
                        new UserAdminService.LoginRecord(
                                70L,
                                NOW.minusSeconds(3600),
                                NOW.minusSeconds(1800),
                                "LOGOUT",
                                "10.0.0.8",
                                ClientDevices.describe(null),
                                false));
    }

    // ------------------------------------------------------------------ resetPassword

    @Test
    void SRS_G_49_aPasswordResetNeedsA01EditSetsTheTemporaryEndsSessionsAndAudits() {
        AppUser u = user(5L, AppUser.Status.ACTIVE);

        service.resetPassword(admin, 5L, "Temp#Pass9");

        verify(policy).require(admin, "A-01", "EDIT");
        verify(adminScope).requireUser(admin, u);
        verify(passwords).setTemporary(u, "Temp#Pass9");
        verify(sessions).endAllSessions(5L, NOW, AppSession.EndReason.ADMIN_REVOKE);
        verify(audit)
                .record(
                        "admin@x",
                        AuditService.Entry.change(
                                        "app_user",
                                        "5",
                                        "PASSWORD_RESET",
                                        null,
                                        "TEMPORARY",
                                        "Reset by administrator")
                                .module("A-01"));
        verify(alerts).changed(u, null, "password (a temporary password was set)");
    }

    @Test
    void SRS_G_49_anAdministratorNeverResetsItsOwnPasswordThisWay() {
        refused(() -> service.resetPassword(admin, 1L, "Temp#Pass9"), ApiErrorCode.SELF_ASSIGNMENT);
        verify(passwords, never()).setTemporary(any(), any());
    }
}
