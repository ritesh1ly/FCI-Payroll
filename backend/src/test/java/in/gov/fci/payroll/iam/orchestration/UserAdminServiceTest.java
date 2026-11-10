package in.gov.fci.payroll.iam.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.employee.api.EmployeeDirectory;
import in.gov.fci.payroll.iam.business.model.AppUser;
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
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.LongStream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;

/**
 * A-01 refusals before anything is saved (SRS-G-49, SRS-22-13): an account needs a valid login
 * e-mail, category and display name; a suspension or deactivation needs its reason; a deactivated
 * account never changes again; an administrator never suspends, resets or reinstates itself; a
 * group suspension names 1 - 100 accounts.
 */
class UserAdminServiceTest {

    private final AccessRepositories.Users users = mock(AccessRepositories.Users.class);
    private final EmployeeDirectory employees = mock(EmployeeDirectory.class);
    private final PasswordService passwords = mock(PasswordService.class);
    private final SessionService sessions = mock(SessionService.class);
    private final AdminScope adminScope = mock(AdminScope.class);

    private final UserAdminService service =
            new UserAdminService(
                    users,
                    mock(IdentityRepositories.Credentials.class),
                    mock(IdentityRepositories.Preferences.class),
                    employees,
                    passwords,
                    sessions,
                    mock(AccessContextService.class),
                    mock(AccessPolicy.class),
                    mock(AuditService.class),
                    Clock.fixed(Instant.parse("2026-10-22T06:00:00Z"), ZoneOffset.UTC),
                    adminScope,
                    mock(AccountChangeAlerts.class),
                    mock(SecurityOffences.class));

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

    private static ErrorCode code(Throwable t) {
        return ((FciException) t).getErrorCode();
    }

    private static void refused(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call).satisfies(e -> assertThat(code(e)).isEqualTo(expected));
    }

    private AppUser user(long id, AppUser.Status status) {
        AppUser user = mock(AppUser.class);
        when(user.getId()).thenReturn(id);
        when(user.getStatus()).thenReturn(status);
        when(users.findById(id)).thenReturn(Optional.of(user));
        return user;
    }

    private static NewUser account(String login, String name, AppUser.Category category) {
        return new NewUser(login, name, category, null, null, "Temp#Pass1");
    }

    @Test
    void SRS_22_13_anAccountNeedsAValidLoginEmailACategoryAndADisplayName() {
        for (NewUser bad :
                List.of(
                        account(null, "Name", AppUser.Category.OPERATIONS),
                        account("not-an-email", "Name", AppUser.Category.OPERATIONS),
                        account("a@b", "Name", AppUser.Category.OPERATIONS),
                        account("ok@fci-demo.test", "Name", null),
                        account("ok@fci-demo.test", null, AppUser.Category.OPERATIONS),
                        account("ok@fci-demo.test", "  ", AppUser.Category.OPERATIONS))) {
            refused(() -> service.create(admin, bad), CommonErrorCode.VALIDATION_FAILED);
        }
        verify(users, never()).save(any());
    }

    @Test
    void SRS_22_13_aLoginAlreadyTakenOrAnUnknownEmployeeIsRefused() {
        when(users.existsByLoginId("taken@fci-demo.test")).thenReturn(true);
        refused(
                () ->
                        service.create(
                                admin,
                                account(" Taken@FCI-demo.test ", "N", AppUser.Category.OPERATIONS)),
                ApiErrorCode.LOGIN_TAKEN);
        when(employees.idOf("999999")).thenReturn(Optional.empty());
        refused(
                () ->
                        service.create(
                                admin,
                                new NewUser(
                                        "new@fci-demo.test",
                                        "N",
                                        AppUser.Category.OPERATIONS,
                                        "999999",
                                        null,
                                        "Temp#Pass1")),
                CommonErrorCode.VALIDATION_FAILED);
        verify(users, never()).save(any());
    }

    @Test
    void SRS_G_49_aSuspensionOrDeactivationNeedsItsReasonAndNeverTargetsTheAdministrator() {
        for (String missing : Arrays.asList(null, "", "  ")) {
            refused(
                    () -> service.changeStatus(admin, 2L, AppUser.Status.SUSPENDED, null, missing),
                    ApiErrorCode.REASON_REQUIRED);
            refused(
                    () ->
                            service.changeStatus(
                                    admin,
                                    2L,
                                    AppUser.Status.DEACTIVATED,
                                    AppUser.DeactivationReason.ADMIN,
                                    missing),
                    ApiErrorCode.REASON_REQUIRED);
            refused(() -> service.unlock(admin, 2L, missing), ApiErrorCode.REASON_REQUIRED);
            refused(
                    () -> service.suspend(admin, List.of(2L), missing),
                    ApiErrorCode.REASON_REQUIRED);
        }
        refused(
                () -> service.changeStatus(admin, 1L, AppUser.Status.SUSPENDED, null, "leave"),
                ApiErrorCode.SELF_ASSIGNMENT);
        // A reinstatement needs no note: the self check is reached.
        refused(
                () -> service.changeStatus(admin, 1L, AppUser.Status.ACTIVE, null, null),
                ApiErrorCode.SELF_ASSIGNMENT);
        refused(() -> service.resetPassword(admin, 1L, "Temp#Pass1"), ApiErrorCode.SELF_ASSIGNMENT);
        refused(
                () -> service.suspend(admin, List.of(1L, 2L), "leave"),
                ApiErrorCode.SELF_ASSIGNMENT);
        verify(users, never()).save(any());
        verify(passwords, never()).setTemporary(any(), anyString());
    }

    @Test
    void SRS_G_49_aDeactivatedAccountNeverChangesAndAStatusMustActuallyChange() {
        user(3L, AppUser.Status.DEACTIVATED);
        refused(
                () -> service.changeStatus(admin, 3L, AppUser.Status.ACTIVE, null, null),
                ApiErrorCode.INVALID_STATUS_CHANGE);
        AppUser active = user(4L, AppUser.Status.ACTIVE);
        refused(
                () -> service.changeStatus(admin, 4L, AppUser.Status.ACTIVE, null, null),
                ApiErrorCode.INVALID_STATUS_CHANGE);
        refused(
                () -> service.changeStatus(admin, 4L, null, null, "why"),
                ApiErrorCode.INVALID_STATUS_CHANGE);
        verify(active, never()).changeStatus(any(), any(), any());
        when(users.findById(5L)).thenReturn(Optional.empty());
        refused(
                () -> service.changeStatus(admin, 5L, AppUser.Status.SUSPENDED, null, "why"),
                CommonErrorCode.NOT_FOUND);
    }

    @Test
    void SRS_G_49_aGroupSuspensionNamesOneToAHundredExistingAccounts() {
        List<Long> tooMany =
                LongStream.rangeClosed(2, 2 + UserAdminService.MAX_SUSPEND).boxed().toList();
        for (List<Long> bad :
                Arrays.<List<Long>>asList(
                        null, List.of(), tooMany, Collections.singletonList((Long) null))) {
            refused(() -> service.suspend(admin, bad, "leave"), CommonErrorCode.VALIDATION_FAILED);
        }
        verify(sessions, never()).endAllSessions(any(), any(), any());
    }

    @Test
    void SRS_22_13_anAdministratorOutsideHeadquartersListsOnlyTheAccountsOfItsOwnOffices() {
        when(adminScope.isNational(admin)).thenReturn(false);
        // The scope is resolved in the database: the administrator's offices are bound, not the ids
        // of every employee in them (deep profile 2026-10-27).
        when(users.searchAmong(any(), any(), any(), any())).thenReturn(Page.empty());
        assertThat(service.list(admin, " ", null, -1, 0).total()).isZero();
        verify(users)
                .searchAmong(
                        any(), any(), org.mockito.ArgumentMatchers.eq(admin.dataScope()), any());
        verify(employees, never()).idsAt(any());

        when(adminScope.isNational(admin)).thenReturn(true);
        when(users.search(any(), any(), any())).thenReturn(Page.empty());
        assertThat(service.list(admin, " Ab% ", AppUser.Status.ACTIVE, 0, 1000).items()).isEmpty();
        verify(users).search(org.mockito.ArgumentMatchers.eq("%ab\\%%"), any(), any());
    }
}
