package in.gov.fci.payroll.iam.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.employee.api.EmployeeDirectory;
import in.gov.fci.payroll.iam.business.model.AppPermission;
import in.gov.fci.payroll.iam.business.model.AppRole;
import in.gov.fci.payroll.iam.business.model.AppRolePermission;
import in.gov.fci.payroll.iam.business.model.AppUser;
import in.gov.fci.payroll.iam.persistence.AccessRepositories;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.export.ExportReader;
import in.gov.fci.payroll.platform.security.AccessContext;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import tools.jackson.databind.json.JsonMapper;

/**
 * The reads X-01 adds (SRS-22-8, SRS-22-19, SRS-G-50): the Screen-Level Access Matrix from the
 * grants in force, behind A-08 VIEW; the Active User Report stays the administrators', in the
 * session's data scope and without the name only A-01 shows; a caller with neither right is
 * refused.
 */
class X01ReadsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 23);

    private final AccessPolicy policy = mock(AccessPolicy.class);
    private final AccessContextService contexts = mock(AccessContextService.class);
    private final AccessContext caller =
            new AccessContext(
                    1L,
                    "maker@x",
                    "Maker",
                    "s",
                    "P",
                    10L,
                    null,
                    List.of(),
                    Map.of(),
                    Set.of(),
                    Set.of(),
                    Set.of(10L));

    // ---------------------------------------------------------------- matrix

    private final AccessRepositories.Roles roles = mock(AccessRepositories.Roles.class);
    private final AccessRepositories.Permissions permissions =
            mock(AccessRepositories.Permissions.class);
    private final AccessRepositories.RolePermissions grants =
            mock(AccessRepositories.RolePermissions.class);
    private final RoleAdminService roleService =
            new RoleAdminService(
                    roles,
                    permissions,
                    grants,
                    contexts,
                    policy,
                    mock(AuditService.class),
                    mock(MakerChecker.class),
                    mock(AdminScope.class),
                    JsonMapper.builder().build());

    private static AppRole role(long id, String code) {
        AppRole r = mock(AppRole.class);
        when(r.getId()).thenReturn(id);
        when(r.getCode()).thenReturn(code);
        return r;
    }

    private static AppPermission permission(long id, String screen, String action) {
        AppPermission p = mock(AppPermission.class);
        when(p.getId()).thenReturn(id);
        when(p.getScreenCode()).thenReturn(screen);
        when(p.getActionCode()).thenReturn(action);
        return p;
    }

    private static AppRolePermission grant(long role, long permission, boolean active) {
        AppRolePermission g = mock(AppRolePermission.class);
        when(g.getRoleId()).thenReturn(role);
        when(g.getPermissionId()).thenReturn(permission);
        when(g.isActiveOn(TODAY)).thenReturn(active);
        return g;
    }

    @Test
    void SRS_22_8_theMatrixShowsTheGrantsInForceOfEveryScreen() {
        when(contexts.today()).thenReturn(TODAY);
        AppRole maker = role(1, "PAYROLL_MAKER");
        AppRole checker = role(2, "PAYROLL_CHECKER");
        when(roles.findAllByOrderByCode()).thenReturn(List.of(checker, maker));
        AppPermission view = permission(10, "A-08", "VIEW");
        AppPermission approve = permission(11, "A-08", "APPROVE");
        AppPermission other = permission(12, "A-01", "VIEW");
        when(permissions.findAllByOrderByScreenCodeAscActionCodeAsc())
                .thenReturn(List.of(view, approve, other));
        AppRolePermission makerView = grant(1, 10, true);
        AppRolePermission checkerView = grant(2, 10, true);
        AppRolePermission checkerApprove = grant(2, 11, true);
        AppRolePermission ended = grant(1, 12, false);
        AppRolePermission unknownPermission = grant(1, 99, true);
        AppRolePermission unknownRole = grant(9, 10, true);
        when(grants.findAll())
                .thenReturn(
                        List.of(
                                makerView,
                                checkerView,
                                checkerApprove,
                                ended,
                                unknownPermission,
                                unknownRole));

        List<RoleAdminService.ScreenAccess> rows = roleService.screenAccess(caller);

        assertThat(rows)
                .containsExactly(
                        new RoleAdminService.ScreenAccess("A-01", Map.of()),
                        new RoleAdminService.ScreenAccess(
                                "A-08", Map.of("PAYROLL_CHECKER", "A", "PAYROLL_MAKER", "V")));
        verify(policy).require(caller, "A-08", "VIEW");
    }

    @Test
    void SRS_22_8_aRoleWithoutViewHasNoLetterAndTheScreenStaysListed() {
        when(contexts.today()).thenReturn(TODAY);
        AppRole maker = role(1, "PAYROLL_MAKER");
        when(roles.findAllByOrderByCode()).thenReturn(List.of(maker));
        AppPermission edit = permission(10, "M-01", "EDIT");
        when(permissions.findAllByOrderByScreenCodeAscActionCodeAsc()).thenReturn(List.of(edit));
        AppRolePermission editOnly = grant(1, 10, true);
        when(grants.findAll()).thenReturn(List.of(editOnly));

        assertThat(roleService.screenAccess(caller))
                .containsExactly(new RoleAdminService.ScreenAccess("M-01", Map.of()));
    }

    @Test
    void SRS_22_8_withoutA08ViewTheMatrixIsRefusedAndNothingIsRead() {
        doThrow(new FciException(ApiErrorCode.ADMIN_HQ_ONLY))
                .when(policy)
                .require(caller, "A-08", "VIEW");

        assertThatThrownBy(() -> roleService.screenAccess(caller)).isInstanceOf(FciException.class);
        verify(grants, never()).findAll();
    }

    // ---------------------------------------------------------------- active users

    private final AccessRepositories.Users users = mock(AccessRepositories.Users.class);
    private final SystemParameters parameters = mock(SystemParameters.class);
    private final AdminScope adminScope = mock(AdminScope.class);
    private final ScopeResolver resolver = mock(ScopeResolver.class);
    private final UserReportService reports =
            new UserReportService(
                    mock(ExportReader.class),
                    users,
                    mock(AccessRepositories.Assignments.class),
                    mock(AccessRepositories.GroupScopes.class),
                    resolver,
                    mock(EmployeeDirectory.class),
                    mock(OrgDirectory.class),
                    parameters,
                    policy,
                    adminScope,
                    contexts,
                    Clock.fixed(Instant.parse("2026-10-23T06:00:00Z"), ZoneOffset.UTC));

    private void oneUser() {
        when(contexts.today()).thenReturn(TODAY);
        when(parameters.configured(SystemParameters.Code.INACTIVE_USER_DAYS))
                .thenReturn(OptionalLong.empty());
        when(resolver.activeGroupsOf(any(), any())).thenReturn(Map.of());
        AppUser user = mock(AppUser.class);
        when(user.getId()).thenReturn(5L);
        when(user.getLoginId()).thenReturn("hr.delhi@x");
        when(user.getDisplayName()).thenReturn("A Name");
        when(user.getStatus()).thenReturn(AppUser.Status.ACTIVE);
        when(users.reportAmong(any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(user)));
    }

    @Test
    void SRS_G_50_anAdministratorKeepsTheNames() {
        oneUser();
        when(policy.can(caller, "A-01", "VIEW")).thenReturn(true);

        UserReportService.ActiveUserReport report = reports.activeUsers(caller, null, false, 0, 20);

        assertThat(report.items())
                .singleElement()
                .satisfies(r -> assertThat(r.displayName()).isEqualTo("A Name"));
        verify(policy).require(caller, "A-01", "VIEW");
    }

    @Test
    void SRS_G_50_aCallerWithNeitherRightIsRefusedAsBefore() {
        doThrow(new FciException(ApiErrorCode.ADMIN_HQ_ONLY))
                .when(policy)
                .require(caller, "A-01", "VIEW");

        assertThatThrownBy(() -> reports.activeUsers(caller, null, false, 0, 20))
                .isInstanceOf(FciException.class);
        verify(users, never()).reportAmong(any(), any(), any(), any());
    }
}
