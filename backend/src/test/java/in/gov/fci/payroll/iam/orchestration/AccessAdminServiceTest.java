package in.gov.fci.payroll.iam.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.employee.api.EmployeeDirectory;
import in.gov.fci.payroll.iam.business.model.AccessGroup;
import in.gov.fci.payroll.iam.business.model.AppRole;
import in.gov.fci.payroll.iam.business.model.AppUser;
import in.gov.fci.payroll.iam.business.model.AppUserAccessGroup;
import in.gov.fci.payroll.iam.persistence.AccessRepositories;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.LocationType;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.platform.approval.ApprovalStatuses;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.ErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.AccessMode;
import in.gov.fci.payroll.platform.security.ScopeType;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

/**
 * A-03 / A-04 refusals before anything is proposed (SRS-G-29, SRS-22-13, SRS-22-19): every invalid
 * group definition, end date, assignment and revocation is refused with its reason.
 */
class AccessAdminServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 22);

    private final AccessRepositories.Groups groups = mock(AccessRepositories.Groups.class);
    private final AccessRepositories.Roles roles = mock(AccessRepositories.Roles.class);
    private final AccessRepositories.Users users = mock(AccessRepositories.Users.class);
    private final AccessRepositories.Assignments assignments =
            mock(AccessRepositories.Assignments.class);
    private final ApprovalStatuses statuses = mock(ApprovalStatuses.class);
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final AccessContextService contexts = mock(AccessContextService.class);
    private final AdminScope adminScope = mock(AdminScope.class);

    private final AccessAdminService service =
            new AccessAdminService(
                    mock(AccessRepositories.Permissions.class),
                    mock(AccessRepositories.RolePermissions.class),
                    groups,
                    mock(AccessRepositories.GroupScopes.class),
                    roles,
                    users,
                    assignments,
                    statuses,
                    mock(MakerChecker.class),
                    org,
                    contexts,
                    mock(AccessPolicy.class),
                    mock(AuditService.class),
                    Clock.fixed(Instant.parse("2026-10-22T06:00:00Z"), ZoneOffset.UTC),
                    adminScope,
                    mock(SessionService.class),
                    mock(EmployeeDirectory.class));

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

    AccessAdminServiceTest() {
        when(contexts.today()).thenReturn(TODAY);
        when(roles.findById(5L)).thenReturn(Optional.of(mock(AppRole.class)));
    }

    private static void refused(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        FciException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    private static AccessAdminService.NewGroup group(
            String code,
            String name,
            Long roleId,
            ScopeType type,
            boolean consolidated,
            List<AccessAdminService.ScopeInput> scopes) {
        return new AccessAdminService.NewGroup(
                code, name, roleId, type, consolidated, AccessMode.FULL, null, scopes, "r");
    }

    private static List<AccessAdminService.ScopeInput> at(Long zone, Long location) {
        return List.of(new AccessAdminService.ScopeInput(zone, location));
    }

    @Test
    void SRS_G_29_aGroupNeedsAValidCodeANameAScopeTypeAndARole() {
        for (AccessAdminService.NewGroup bad :
                List.of(
                        group(null, "N", 5L, ScopeType.DIVISION, false, at(null, 7L)),
                        group("bad code!", "N", 5L, ScopeType.DIVISION, false, at(null, 7L)),
                        // access_group.group_code VARCHAR2(40): 41 characters are one too many
                        group("A".repeat(41), "N", 5L, ScopeType.DIVISION, false, at(null, 7L)),
                        group("AG", "N", 5L, ScopeType.DIVISION, false, at(null, 7L)),
                        group("AG-OK", null, 5L, ScopeType.DIVISION, false, at(null, 7L)),
                        group("AG-OK", " ", 5L, ScopeType.DIVISION, false, at(null, 7L)),
                        group("AG-OK", "N", null, ScopeType.DIVISION, false, at(null, 7L)),
                        group("AG-OK", "N", 5L, null, false, at(null, 7L)))) {
            refused(() -> service.createGroup(admin, bad, null), CommonErrorCode.VALIDATION_FAILED);
        }
        // An unknown role is a validation failure, a taken code a conflict.
        refused(
                () ->
                        service.createGroup(
                                admin,
                                group("AG-OK", "N", 9L, ScopeType.DIVISION, false, at(null, 7L)),
                                null),
                CommonErrorCode.VALIDATION_FAILED);
        when(groups.existsByCode("AG-TAKEN")).thenReturn(true);
        refused(
                () ->
                        service.createGroup(
                                admin,
                                group(
                                        " ag-taken ",
                                        "N",
                                        5L,
                                        ScopeType.DIVISION,
                                        false,
                                        at(null, 7L)),
                                null),
                CommonErrorCode.CONFLICT);
    }

    @Test
    void SRS_G_29_theScopeRowsMustFitTheScopeType() {
        OrgLocation division = mock(OrgLocation.class);
        when(division.getType()).thenReturn(LocationType.DO);
        when(org.location(7L)).thenReturn(Optional.of(division));
        // Consolidated view only on a Headquarters or national group.
        refused(
                () ->
                        service.createGroup(
                                admin,
                                group("AG-A", "N", 5L, ScopeType.DIVISION, true, at(null, 7L)),
                                null),
                ApiErrorCode.INVALID_SCOPE);
        // A consolidated Headquarters or national group still meets its own scope rules.
        refused(
                () ->
                        service.createGroup(
                                admin,
                                group("AG-A2", "N", 5L, ScopeType.HEADQUARTERS, true, at(null, 7L)),
                                null),
                ApiErrorCode.INVALID_SCOPE);
        refused(
                () ->
                        service.createGroup(
                                admin,
                                group("AG-A3", "N", 5L, ScopeType.GLOBAL, true, at(null, 7L)),
                                null),
                ApiErrorCode.INVALID_SCOPE);
        // A national group names no office; any other names at least one.
        refused(
                () ->
                        service.createGroup(
                                admin,
                                group("AG-B", "N", 5L, ScopeType.GLOBAL, false, at(null, 7L)),
                                null),
                ApiErrorCode.INVALID_SCOPE);
        refused(
                () ->
                        service.createGroup(
                                admin,
                                group("AG-C", "N", 5L, ScopeType.DIVISION, false, List.of()),
                                null),
                ApiErrorCode.INVALID_SCOPE);
        refused(
                () ->
                        service.createGroup(
                                admin,
                                group("AG-D", "N", 5L, ScopeType.DIVISION, false, null),
                                null),
                ApiErrorCode.INVALID_SCOPE);
        // Each row names a zone or an office, never both, never neither.
        refused(
                () ->
                        service.createGroup(
                                admin,
                                group("AG-E", "N", 5L, ScopeType.DIVISION, false, at(3L, 7L)),
                                null),
                ApiErrorCode.INVALID_SCOPE);
        refused(
                () ->
                        service.createGroup(
                                admin,
                                group("AG-F", "N", 5L, ScopeType.DIVISION, false, at(null, null)),
                                null),
                ApiErrorCode.INVALID_SCOPE);
        // A zone row must name a known zone; an office row a known office of the scope's type.
        refused(
                () ->
                        service.createGroup(
                                admin,
                                group("AG-G", "N", 5L, ScopeType.ZONE, false, at(3L, null)),
                                null),
                ApiErrorCode.INVALID_SCOPE);
        refused(
                () ->
                        service.createGroup(
                                admin,
                                group("AG-H", "N", 5L, ScopeType.ZONE, false, at(null, 7L)),
                                null),
                ApiErrorCode.INVALID_SCOPE);
        refused(
                () ->
                        service.createGroup(
                                admin,
                                group("AG-I", "N", 5L, ScopeType.REGION, false, at(null, 7L)),
                                null),
                ApiErrorCode.INVALID_SCOPE);
        refused(
                () ->
                        service.createGroup(
                                admin,
                                group("AG-J", "N", 5L, ScopeType.DEPOT, false, at(null, 8L)),
                                null),
                ApiErrorCode.INVALID_SCOPE);
        refused(
                () ->
                        service.createGroup(
                                admin,
                                group("AG-K", "N", 5L, ScopeType.HEADQUARTERS, false, at(null, 7L)),
                                null),
                ApiErrorCode.INVALID_SCOPE);
    }

    @Test
    void SRS_G_37_aGroupIsEndDatedOnlyIfItExistsAndNeverBeforeItStarted() {
        refused(() -> service.endGroup(admin, 99L, TODAY, "r"), CommonErrorCode.NOT_FOUND);
        AccessGroup started = mock(AccessGroup.class);
        when(started.getEffectiveFrom()).thenReturn(TODAY);
        when(groups.findById(4L)).thenReturn(Optional.of(started));
        refused(
                () -> service.endGroup(admin, 4L, TODAY.minusDays(1), "r"),
                CommonErrorCode.VALIDATION_FAILED);
    }

    @Test
    void SRS_G_29_anAssignmentNamesAUserAndAGroupNotTheAdministratorItselfAndAnActiveUser() {
        refused(
                () ->
                        service.propose(
                                admin,
                                new AccessAdminService.NewAssignment(null, 4L, null, null, "r"),
                                null),
                CommonErrorCode.VALIDATION_FAILED);
        refused(
                () ->
                        service.propose(
                                admin,
                                new AccessAdminService.NewAssignment(2L, null, null, null, "r"),
                                null),
                CommonErrorCode.VALIDATION_FAILED);
        refused(
                () ->
                        service.propose(
                                admin,
                                new AccessAdminService.NewAssignment(1L, 4L, null, null, "r"),
                                null),
                ApiErrorCode.SELF_ASSIGNMENT);
        refused(
                () ->
                        service.propose(
                                admin,
                                new AccessAdminService.NewAssignment(2L, 4L, null, null, "r"),
                                null),
                CommonErrorCode.NOT_FOUND);
        AppUser deactivated = mock(AppUser.class);
        when(deactivated.getStatus()).thenReturn(AppUser.Status.DEACTIVATED);
        when(users.findById(3L)).thenReturn(Optional.of(deactivated));
        refused(
                () ->
                        service.propose(
                                admin,
                                new AccessAdminService.NewAssignment(3L, 4L, null, null, "r"),
                                null),
                ApiErrorCode.INVALID_STATUS_CHANGE);
    }

    /**
     * X-01 User (SRS l.1121, US-GAP-125): "Must match an active user account" - an assignment to a
     * locked, suspended or deactivated (separated) account is refused before anything is submitted.
     */
    @Test
    void SRS_22_23_anAccessGroupIsAssignedOnlyToAnActiveUserAccount() {
        for (AppUser.Status status :
                new AppUser.Status[] {
                    AppUser.Status.LOCKED, AppUser.Status.SUSPENDED, AppUser.Status.DEACTIVATED
                }) {
            AppUser user = mock(AppUser.class);
            when(user.getStatus()).thenReturn(status);
            when(users.findById(3L)).thenReturn(Optional.of(user));
            refused(
                    () ->
                            service.propose(
                                    admin,
                                    new AccessAdminService.NewAssignment(3L, 4L, null, null, "r"),
                                    null),
                    ApiErrorCode.INVALID_STATUS_CHANGE);
        }
        verify(groups, never()).findById(4L);
    }

    /**
     * SRS-22-2 (Wave A review, A-04): the office scope is checked before the account status, so an
     * administrator outside the user's offices gets "not found" for a locked or suspended account
     * as for an active one, and cannot tell them apart.
     */
    @Test
    void SRS_22_2_theScopeIsCheckedBeforeTheAccountStatus() {
        for (AppUser.Status status :
                new AppUser.Status[] {
                    AppUser.Status.ACTIVE, AppUser.Status.LOCKED, AppUser.Status.SUSPENDED
                }) {
            AppUser user = mock(AppUser.class);
            when(user.getStatus()).thenReturn(status);
            when(users.findById(6L)).thenReturn(Optional.of(user));
            doThrow(new FciException(CommonErrorCode.NOT_FOUND))
                    .when(adminScope)
                    .requireUser(admin, user);
            refused(
                    () ->
                            service.propose(
                                    admin,
                                    new AccessAdminService.NewAssignment(6L, 4L, null, null, "r"),
                                    null),
                    CommonErrorCode.NOT_FOUND);
        }
        verify(groups, never()).findById(4L);
    }

    @Test
    void SRS_G_29_anAssignmentNeedsAnApprovedActiveGroupAndDatesFromTodayInOrder() {
        AppUser active = mock(AppUser.class);
        when(active.getStatus()).thenReturn(AppUser.Status.ACTIVE);
        when(users.findById(2L)).thenReturn(Optional.of(active));
        refused(
                () ->
                        service.propose(
                                admin,
                                new AccessAdminService.NewAssignment(2L, 4L, null, null, "r"),
                                null),
                CommonErrorCode.NOT_FOUND);
        AccessGroup unapproved = mock(AccessGroup.class);
        // Never submitted: no approval item (a mock would answer 0, not null).
        when(unapproved.getApprovalId()).thenReturn(null);
        when(groups.findById(4L)).thenReturn(Optional.of(unapproved));
        refused(
                () ->
                        service.propose(
                                admin,
                                new AccessAdminService.NewAssignment(2L, 4L, null, null, "r"),
                                null),
                ApiErrorCode.GROUP_NOT_APPROVED);
        AccessGroup approved = mock(AccessGroup.class);
        when(approved.getApprovalId()).thenReturn(70L);
        when(approved.isActiveOn(TODAY)).thenReturn(true);
        when(groups.findById(5L)).thenReturn(Optional.of(approved));
        when(statuses.approved(any())).thenReturn(Set.of(70L));
        refused(
                () ->
                        service.propose(
                                admin,
                                new AccessAdminService.NewAssignment(
                                        2L, 5L, TODAY.minusDays(1), null, "r"),
                                null),
                CommonErrorCode.VALIDATION_FAILED);
        refused(
                () ->
                        service.propose(
                                admin,
                                new AccessAdminService.NewAssignment(
                                        2L, 5L, TODAY.plusDays(5), TODAY.plusDays(4), "r"),
                                null),
                CommonErrorCode.VALIDATION_FAILED);
        // An approved group no longer in force is refused like an unapproved one.
        when(approved.isActiveOn(TODAY)).thenReturn(false);
        refused(
                () ->
                        service.propose(
                                admin,
                                new AccessAdminService.NewAssignment(2L, 5L, null, null, "r"),
                                null),
                ApiErrorCode.GROUP_NOT_APPROVED);
    }

    @Test
    void SRS_22_13_aRevocationNeedsAReasonAnExistingOpenAssignmentOfSomeoneElse() {
        refused(() -> service.revoke(admin, 8L, null, null), ApiErrorCode.REASON_REQUIRED);
        refused(() -> service.revoke(admin, 8L, null, "  "), ApiErrorCode.REASON_REQUIRED);
        refused(() -> service.revoke(admin, 8L, null, "moved"), CommonErrorCode.NOT_FOUND);
        AppUserAccessGroup own = mock(AppUserAccessGroup.class);
        when(own.getUserId()).thenReturn(1L);
        when(assignments.findById(9L)).thenReturn(Optional.of(own));
        refused(() -> service.revoke(admin, 9L, null, "moved"), ApiErrorCode.SELF_ASSIGNMENT);
        AppUserAccessGroup revoked = mock(AppUserAccessGroup.class);
        when(revoked.getUserId()).thenReturn(2L);
        when(revoked.getRevokedAt()).thenReturn(Instant.parse("2026-10-01T00:00:00Z"));
        when(assignments.findById(10L)).thenReturn(Optional.of(revoked));
        refused(
                () -> service.revoke(admin, 10L, null, "moved"),
                ApiErrorCode.INVALID_STATUS_CHANGE);
        AppUserAccessGroup open = mock(AppUserAccessGroup.class);
        when(open.getUserId()).thenReturn(2L);
        when(assignments.findById(11L)).thenReturn(Optional.of(open));
        when(users.findById(anyLong())).thenReturn(Optional.empty());
        refused(() -> service.revoke(admin, 11L, null, "moved"), CommonErrorCode.NOT_FOUND);
    }
}
