package in.gov.fci.payroll.iam.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.employee.api.EmployeeDirectory;
import in.gov.fci.payroll.iam.business.model.AccessGroup;
import in.gov.fci.payroll.iam.business.model.AccessGroupScope;
import in.gov.fci.payroll.iam.business.model.AppUser;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.ErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.AccessMode;
import in.gov.fci.payroll.platform.security.ActiveGroup;
import in.gov.fci.payroll.platform.security.ScopeType;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The acting administrator's reach (SRS-22-13, SRS-22-19, SRS-22-23; OWASP ASVS V4.1.3 and V4.2.1:
 * least privilege, no horizontal escalation). A national administrator (at Headquarters or through
 * a GLOBAL group) reaches every user and group; a zone, region or division administrator reaches
 * only users whose employee record lies in the session's data scope and only groups whose whole
 * scope lies inside it; roles and group definitions are national only. Every refusal is recorded as
 * an out-of-scope security event.
 */
class AdminScopeTest {

    private static final Set<Long> NORTH = Set.of(10L, 11L, 12L);

    private final AccessPolicy policy = mock(AccessPolicy.class);
    private final EmployeeDirectory employees = mock(EmployeeDirectory.class);
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final AuditService audit = mock(AuditService.class);
    private final AdminScope scope = new AdminScope(policy, employees, org, audit);

    private final AccessContext regional = context(List.of());

    @BeforeEach
    void wire() {
        when(employees.locationOf(1L)).thenReturn(Optional.of(11L)); // inside the region
        when(employees.locationOf(2L)).thenReturn(Optional.of(99L)); // another region
        when(employees.locationOf(3L)).thenReturn(Optional.empty()); // no location on record
        when(org.subtree(10L)).thenReturn(Set.of(10L, 11L));
        when(org.subtree(12L)).thenReturn(Set.of(12L));
        when(org.subtree(99L)).thenReturn(Set.of(99L));
        when(org.inZone(5L)).thenReturn(Set.of(10L, 11L, 12L));
        when(org.inZone(6L)).thenReturn(Set.of(10L, 98L));
    }

    private static AccessContext context(List<ActiveGroup> groups) {
        return context(groups, 10L);
    }

    private static AccessContext context(List<ActiveGroup> groups, Long office) {
        return new AccessContext(
                1L,
                "admin.north@x",
                "Admin",
                "s",
                "P",
                office,
                null,
                groups,
                Map.of(),
                Set.of(),
                Set.of(),
                NORTH);
    }

    private static ActiveGroup group(ScopeType type) {
        return group(type, AccessMode.FULL);
    }

    private static ActiveGroup group(ScopeType type, AccessMode mode) {
        return new ActiveGroup(
                1L,
                "AG-1",
                "Group",
                2L,
                "SYSTEM_ADMINISTRATOR",
                "System Administrator",
                type,
                false,
                Set.of(),
                Set.of(),
                mode);
    }

    private static AppUser user(Long employeeId) {
        AppUser user = mock(AppUser.class);
        when(user.getId()).thenReturn(50L);
        when(user.getEmployeeId()).thenReturn(employeeId);
        return user;
    }

    private static AccessGroupScope row(Long zone, Long location) {
        AccessGroupScope row = mock(AccessGroupScope.class);
        when(row.getZoneId()).thenReturn(zone);
        when(row.getLocationId()).thenReturn(location);
        return row;
    }

    private static AccessGroup accessGroup(boolean consolidated) {
        AccessGroup group = mock(AccessGroup.class);
        when(group.getCode()).thenReturn("AG-NORTH");
        when(group.isConsolidatedView()).thenReturn(consolidated);
        return group;
    }

    private void refused(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        FciException.class, e -> assertThat(e.getErrorCode()).isEqualTo(expected));
    }

    private void securityEvent(String entity, String key) {
        verify(audit)
                .securityEvent(
                        AuditService.Security.OUT_OF_SCOPE_ACCESS,
                        "admin.north@x",
                        entity,
                        key,
                        "admin-scope");
    }

    @Test
    void SRS_22_19_aNationalAdministratorActsAtHeadquartersOrThroughAGlobalGroup() {
        assertThat(scope.isNational(regional)).isFalse();
        assertThat(scope.isNational(context(List.of(group(ScopeType.GLOBAL))))).isTrue();
        assertThat(scope.isNational(context(List.of(group(ScopeType.ZONE))))).isFalse();
        AccessContext atHeadquarters = context(List.of(), 1L);
        when(policy.isAtHeadquarters(atHeadquarters)).thenReturn(true);
        assertThat(scope.isNational(atHeadquarters)).isTrue();

        // Roles, permissions and group definitions are national only.
        refused(() -> scope.requireNational(regional, "app_role"), ApiErrorCode.ADMIN_HQ_ONLY);
        securityEvent("national", "app_role");
        assertThatCode(() -> scope.requireNational(atHeadquarters, "app_role"))
                .doesNotThrowAnyException();
    }

    @Test
    void SRS_22_19_aReadOnlyGlobalGroupWidensWhatIsSeenNeverWhatIsChanged() {
        // Security review 2026-10-31: a READ_ONLY GLOBAL group beside a regional administrator
        // group made the session's every change national.
        AccessContext mixed =
                context(
                        List.of(
                                group(ScopeType.ZONE),
                                group(ScopeType.GLOBAL, AccessMode.READ_ONLY)));
        assertThat(scope.isNational(mixed)).isTrue();
        assertThat(scope.isNationalToChange(mixed)).isFalse();
        assertThat(scope.isNationalToChange(context(List.of(group(ScopeType.GLOBAL))))).isTrue();
        assertThat(
                        scope.isNationalToChange(
                                context(List.of(group(ScopeType.GLOBAL, AccessMode.REPORTS_ONLY)))))
                .isFalse();
        // A user of another region: seen, never changed.
        assertThatCode(() -> scope.requireUserVisible(mixed, user(2L))).doesNotThrowAnyException();
        refused(() -> scope.requireUser(mixed, user(2L)), CommonErrorCode.NOT_FOUND);
        refused(() -> scope.requireNational(mixed, "app_role"), ApiErrorCode.ADMIN_HQ_ONLY);
        refused(() -> scope.requireNewUser(mixed, 2L), CommonErrorCode.NOT_FOUND);
        // Its own region's user is still changed; outside any scope a user is not even seen.
        assertThatCode(() -> scope.requireUser(mixed, user(1L))).doesNotThrowAnyException();
        refused(() -> scope.requireUserVisible(regional, user(2L)), CommonErrorCode.NOT_FOUND);
    }

    @Test
    void SRS_22_13_aScopedAdministratorActsOnlyOnUsersWhoseEmployeeRecordIsInScope() {
        assertThat(scope.covers(regional, user(1L))).isTrue();
        assertThat(scope.covers(regional, user(2L))).as("another region").isFalse();
        assertThat(scope.covers(regional, user(3L))).as("no location on record").isFalse();
        assertThat(scope.covers(regional, user(null))).as("system accounts: national").isFalse();
        AccessContext national = context(List.of(group(ScopeType.GLOBAL)));
        assertThat(scope.covers(national, user(null))).isTrue();

        assertThatCode(() -> scope.requireUser(regional, user(1L))).doesNotThrowAnyException();
        refused(() -> scope.requireUser(regional, user(2L)), CommonErrorCode.NOT_FOUND);
        securityEvent("app_user", "50");
    }

    @Test
    void SRS_22_13_aNewUserMustBeForAnEmployeeInScopeAndASystemAccountIsNationalOnly() {
        assertThatCode(() -> scope.requireNewUser(regional, 1L)).doesNotThrowAnyException();
        refused(() -> scope.requireNewUser(regional, 2L), CommonErrorCode.NOT_FOUND);
        securityEvent("employee", "2");
        refused(() -> scope.requireNewUser(regional, 3L), CommonErrorCode.NOT_FOUND);
        refused(() -> scope.requireNewUser(regional, null), CommonErrorCode.NOT_FOUND);
        securityEvent("employee", "null");
        AccessContext national = context(List.of(group(ScopeType.GLOBAL)));
        assertThatCode(() -> scope.requireNewUser(national, null)).doesNotThrowAnyException();
        verify(employees, never()).locationOf(null);
    }

    @Test
    void OD_130_A04OffersOnlyGroupsInReachSoAClickCanNeverBeTheOffence() {
        AccessGroup offices = accessGroup(false);

        assertThat(scope.reaches(regional, offices, List.of(row(null, 10L)))).isTrue();
        assertThat(scope.reaches(regional, offices, List.of(row(null, 99L)))).isFalse();
        assertThat(scope.reaches(regional, offices, List.of())).isFalse();
        assertThat(scope.reaches(regional, accessGroup(true), List.of(row(null, 10L)))).isFalse();
        assertThatThrownBy(() -> scope.requireGroup(regional, offices, List.of(row(null, 99L))))
                .isInstanceOfSatisfying(
                        FciException.class, e -> assertThat(e.isAccessOffence()).isTrue());
    }

    @Test
    void SRS_22_23_aScopedAdministratorAssignsOnlyAGroupWhollyInsideTheScope() {
        AccessGroup offices = accessGroup(false);
        assertThatCode(
                        () ->
                                scope.requireGroup(
                                        regional, offices, List.of(row(null, 10L), row(null, 12L))))
                .doesNotThrowAnyException();
        assertThatCode(() -> scope.requireGroup(regional, offices, List.of(row(5L, null))))
                .as("a zone wholly inside")
                .doesNotThrowAnyException();

        // A national group (no scope rows), a consolidated view, an empty reach and a scope that
        // leaves the region are all refused.
        refused(() -> scope.requireGroup(regional, offices, List.of()), ApiErrorCode.OUT_OF_SCOPE);
        refused(
                () -> scope.requireGroup(regional, accessGroup(true), List.of(row(null, 10L))),
                ApiErrorCode.OUT_OF_SCOPE);
        refused(
                () -> scope.requireGroup(regional, offices, List.of(row(null, null))),
                ApiErrorCode.OUT_OF_SCOPE);
        refused(
                () ->
                        scope.requireGroup(
                                regional, offices, List.of(row(null, 10L), row(null, 99L))),
                ApiErrorCode.OUT_OF_SCOPE);
        refused(
                () -> scope.requireGroup(regional, offices, List.of(row(6L, null))),
                ApiErrorCode.OUT_OF_SCOPE);
        verify(audit, times(5))
                .securityEvent(
                        AuditService.Security.OUT_OF_SCOPE_ACCESS,
                        "admin.north@x",
                        "access_group",
                        "AG-NORTH",
                        "admin-scope");

        // A national administrator: no reach computed at all.
        clearInvocations(org);
        AccessContext national = context(List.of(group(ScopeType.GLOBAL)));
        assertThatCode(() -> scope.requireGroup(national, offices, List.of()))
                .doesNotThrowAnyException();
        verify(org, never()).inZone(any());
        verify(org, never()).subtree(any());
    }

    @Test
    void SRS_22_13_aReadOnlyGlobalAdministratorSeesASystemAccountButNeverChangesIt() {
        AccessContext readOnly = context(List.of(group(ScopeType.GLOBAL, AccessMode.READ_ONLY)));
        assertThatCode(() -> scope.requireUserVisible(readOnly, user(null)))
                .doesNotThrowAnyException();
        refused(() -> scope.requireUser(readOnly, user(null)), CommonErrorCode.NOT_FOUND);
        refused(() -> scope.requireNewUser(readOnly, null), CommonErrorCode.NOT_FOUND);
        assertThat(scope.covers(readOnly, user(null))).isFalse();
    }

    @Test
    void SRS_22_13_aScopedAdministratorDoesNotSeeASystemAccountOrAnUnlocatedEmployee() {
        refused(() -> scope.requireUserVisible(regional, user(null)), CommonErrorCode.NOT_FOUND);
        refused(() -> scope.requireUserVisible(regional, user(3L)), CommonErrorCode.NOT_FOUND);
        verify(audit, times(2))
                .securityEvent(
                        AuditService.Security.OUT_OF_SCOPE_ACCESS,
                        "admin.north@x",
                        "app_user",
                        "50",
                        "admin-scope");
    }

    @Test
    void SRS_22_23_aGroupWhoseZoneRowIsEmptyOrWhoseZoneLeavesTheScopeIsRefusedWithoutChange() {
        AccessGroup offices = accessGroup(false);
        refused(
                () -> scope.requireGroup(regional, offices, List.of(row(7L, null))),
                ApiErrorCode.OUT_OF_SCOPE);
        verify(org).inZone(7L);
    }
}
