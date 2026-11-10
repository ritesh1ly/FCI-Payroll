package in.gov.fci.payroll.iam.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.employee.api.EmployeeDirectory;
import in.gov.fci.payroll.iam.business.model.AppUser;
import in.gov.fci.payroll.iam.business.model.AppUserCredential;
import in.gov.fci.payroll.iam.persistence.AccessRepositories;
import in.gov.fci.payroll.iam.persistence.IdentityRepositories;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * A-01 accounts beyond the refusals (SRS-22-13, SRS-G-49): an account without an employee, the
 * defaults a contact update and a reinstatement take, and what an account row shows of its
 * employee, lock and password state.
 */
class UserAdminServiceRowsTest {

    private static final Instant NOW = Instant.parse("2026-10-24T06:00:00Z");

    private final AccessRepositories.Users users = mock(AccessRepositories.Users.class);
    private final IdentityRepositories.Credentials credentials =
            mock(IdentityRepositories.Credentials.class);
    private final EmployeeDirectory employees = mock(EmployeeDirectory.class);
    private final PasswordService passwords = mock(PasswordService.class);
    private final AuditService audit = mock(AuditService.class);
    private final AccountChangeAlerts alerts = mock(AccountChangeAlerts.class);

    private final UserAdminService service =
            new UserAdminService(
                    users,
                    credentials,
                    mock(IdentityRepositories.Preferences.class),
                    employees,
                    passwords,
                    mock(SessionService.class),
                    mock(AccessContextService.class),
                    mock(AccessPolicy.class),
                    audit,
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    mock(AdminScope.class),
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

    private AppUser user(long id, AppUser.Status status) {
        AppUser user = mock(AppUser.class);
        when(user.getId()).thenReturn(id);
        when(user.getStatus()).thenReturn(status);
        when(user.getCategory()).thenReturn(AppUser.Category.OPERATIONS);
        when(user.getDisplayName()).thenReturn("Old Name");
        when(user.getRegisteredEmail()).thenReturn("u@x");
        when(users.findById(id)).thenReturn(Optional.of(user));
        return user;
    }

    @Test
    void SRS_22_13_anAccountWithABlankEmployeeNumberIsCreatedUnlinkedAndMustSetItsPassword() {
        when(users.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        UserAdminService.UserRow row =
                service.create(
                        admin,
                        new UserAdminService.NewUser(
                                "Ops@fci-demo.test",
                                " Ops User ",
                                AppUser.Category.OPERATIONS,
                                "   ",
                                null,
                                "Temp#Pass1"));

        verify(employees, never()).idOf(any());
        assertThat(row.loginId()).isEqualTo("ops@fci-demo.test");
        assertThat(row.displayName()).isEqualTo("Ops User");
        assertThat(row.employeeEis()).isNull();
        assertThat(row.locked()).isFalse();
        assertThat(row.mustChangePassword()).as("no credential of its own yet").isTrue();
        verify(passwords).setTemporary(any(), eq("Temp#Pass1"));
    }

    @Test
    void SRS_22_13_aContactUpdateWithoutANameKeepsTheCurrentOne() {
        AppUser user = user(2L, AppUser.Status.ACTIVE);

        service.updateContact(admin, 2L, null, "+919876543210");
        service.updateContact(admin, 2L, "   ", "+919876543210");
        service.updateContact(admin, 2L, " New Name ", null);

        verify(user, times(2)).updateContact("Old Name", "+919876543210", "u@x");
        verify(user).updateContact("New Name", null, "u@x");
    }

    @Test
    void SRS_20_2_aChangedMobileIsAuditedMaskedAndItsOwnerIsAlertedAtTheOldNumber() {
        AppUser user = user(2L, AppUser.Status.ACTIVE);
        // Before the change, after it; then unchanged (blank and absent are the same).
        when(user.getRegisteredMobile()).thenReturn("9811111122", "9822222233", " ", null);
        // (each update reads the mobile once before and once after the change)
        service.updateContact(admin, 2L, null, "9822222233");
        org.mockito.ArgumentCaptor<AuditService.Entry> entries =
                org.mockito.ArgumentCaptor.forClass(AuditService.Entry.class);
        verify(audit, times(2)).record(eq("admin@x"), entries.capture());
        AuditService.Entry mobile = entries.getAllValues().get(1);
        assertThat(mobile.fieldName()).isEqualTo("registered_mobile");
        assertThat(mobile.oldValue()).isEqualTo("********22");
        assertThat(mobile.newValue()).isEqualTo("********33");
        verify(alerts).changed(user, "9811111122", "registered mobile");

        service.updateContact(admin, 2L, "Name", null);
        verify(alerts, times(1)).changed(any(), any(), any());
        assertThat(UserAdminService.maskMobile("12")).isEqualTo("**");
        assertThat(UserAdminService.maskMobile("  ")).isNull();
    }

    @Test
    void SRS_G_49_aTemporaryPasswordSetByAnAdministratorAlertsTheOwner() {
        AppUser user = user(2L, AppUser.Status.ACTIVE);
        service.resetPassword(admin, 2L, "Temp#Pass1234");
        verify(alerts).changed(user, null, "password (a temporary password was set)");
    }

    @Test
    void SRS_G_49_aReinstatementUnlocksTheCredentialWithTheNoteOrADefault() {
        AppUserCredential credential = mock(AppUserCredential.class);
        when(credentials.findById(2L)).thenReturn(Optional.of(credential));
        for (String note : new String[] {null, "  ", "Back from leave"}) {
            AppUser user = user(2L, AppUser.Status.SUSPENDED);
            service.changeStatus(admin, 2L, AppUser.Status.ACTIVE, null, note);
            verify(user).changeStatus(AppUser.Status.ACTIVE, null, NOW);
        }

        verify(credential, times(2)).unlockByAdministrator("admin@x", "Reactivated", NOW);
        verify(credential).unlockByAdministrator("admin@x", "Back from leave", NOW);
        ArgumentCaptor<AuditService.Entry> entries = ArgumentCaptor.captor();
        verify(audit, times(3)).record(eq("admin@x"), entries.capture());
        assertThat(entries.getAllValues())
                .extracting(AuditService.Entry::reason)
                .containsExactly("Status changed", "Status changed", "Back from leave");
    }

    @Test
    void anAccountRowShowsItsEmployeeItsLockAndThePasswordState() {
        AppUser user = user(2L, AppUser.Status.ACTIVE);
        when(user.getEmployeeId()).thenReturn(9L);
        when(employees.eisOf(9L)).thenReturn(Optional.of("E100"));
        AppUserCredential credential = mock(AppUserCredential.class);
        when(credential.isLockedAt(NOW)).thenReturn(true);
        when(credentials.findById(2L)).thenReturn(Optional.of(credential));

        UserAdminService.UserRow row = service.get(admin, 2L);

        assertThat(row.employeeEis()).isEqualTo("E100");
        assertThat(row.status()).isEqualTo("ACTIVE");
        assertThat(row.locked()).isTrue();
        assertThat(row.mustChangePassword()).isFalse();
    }

    @Test
    void SRS_22_13_aScopedAdministratorWithoutOfficesListsNoAccount() {
        AccessContext noOffices =
                new AccessContext(
                        1L, "admin@x", "Admin", "s", "P", 10L, null, List.of(), Map.of(), Set.of(),
                        Set.of(), Set.of());
        assertThat(service.list(noOffices, null, null, 0, 20).total()).isZero();
        verify(users, never()).searchAmong(any(), any(), any(), any());
    }

    /** SRS-15-29: a stale version is refused naming who changed it last and when, if known. */
    @Test
    void SRS_15_29_aStaleContactUpdateNamesTheLastChangeOrADash() {
        AppUser user = user(2L, AppUser.Status.ACTIVE);
        when(user.getRowVersion()).thenReturn(4L);
        Instant changed = NOW.minusSeconds(60);
        when(audit.lastChange("app_user", "2"))
                .thenReturn(Optional.of(new AuditService.LastChange("checker@x", changed)));
        assertThatThrownBy(() -> service.updateContact(admin, 2L, "Name", null, 3L))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e ->
                                assertThat(e.getMessageArgs())
                                        .containsExactly("checker@x", changed.toString(), 4L));
        when(audit.lastChange("app_user", "2")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.updateContact(admin, 2L, "Name", null, 3L))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e -> assertThat(e.getMessageArgs()).containsExactly("-", "-", 4L));
        verify(user, never()).updateContact(any(), any(), any());
    }
}
