package in.gov.fci.payroll.iam.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.employee.api.EmployeeDirectory;
import in.gov.fci.payroll.iam.business.model.AccessGroup;
import in.gov.fci.payroll.iam.business.model.AccessGroupScope;
import in.gov.fci.payroll.iam.business.model.AppPermission;
import in.gov.fci.payroll.iam.business.model.AppRole;
import in.gov.fci.payroll.iam.business.model.AppRolePermission;
import in.gov.fci.payroll.iam.business.model.AppSession;
import in.gov.fci.payroll.iam.business.model.AppUser;
import in.gov.fci.payroll.iam.business.model.AppUserAccessGroup;
import in.gov.fci.payroll.iam.persistence.AccessRepositories;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.LocationType;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.organisation.business.model.OrgZone;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.ApprovalStatuses;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.ErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.AccessMode;
import in.gov.fci.payroll.platform.security.ScopeType;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

/**
 * Mutation gaps of {@link AccessAdminService}: A-03 / A-04 screen and Headquarters checks, the
 * audited end of a group, the scope filters of a scoped administrator, the consolidated-view rule
 * (SRS-22-3) and the revocation (SRS-22-19, SRS-22-23, SRS-SEC-14).
 */
class AccessAdminServiceMutationGapsTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate TODAY = LocalDate.now(IST);
    private static final Instant NOW = TODAY.atTime(11, 0).atZone(IST).toInstant();

    private final AccessRepositories.Permissions permissions =
            mock(AccessRepositories.Permissions.class);
    private final AccessRepositories.RolePermissions rolePermissions =
            mock(AccessRepositories.RolePermissions.class);
    private final AccessRepositories.Groups groups = mock(AccessRepositories.Groups.class);
    private final AccessRepositories.GroupScopes scopes =
            mock(AccessRepositories.GroupScopes.class);
    private final AccessRepositories.Roles roles = mock(AccessRepositories.Roles.class);
    private final AccessRepositories.Users users = mock(AccessRepositories.Users.class);
    private final AccessRepositories.Assignments assignments =
            mock(AccessRepositories.Assignments.class);
    private final ApprovalStatuses statuses = mock(ApprovalStatuses.class);
    private final MakerChecker approvals = mock(MakerChecker.class);
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final AccessContextService contexts = mock(AccessContextService.class);
    private final AccessPolicy policy = mock(AccessPolicy.class);
    private final AuditService audit = mock(AuditService.class);
    private final AdminScope adminScope = mock(AdminScope.class);
    private final SessionService sessions = mock(SessionService.class);

    private final AccessAdminService service =
            new AccessAdminService(
                    permissions,
                    rolePermissions,
                    groups,
                    scopes,
                    roles,
                    users,
                    assignments,
                    statuses,
                    approvals,
                    org,
                    contexts,
                    policy,
                    audit,
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    adminScope,
                    sessions,
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
                    Set.of(10L, 11L));

    AccessAdminServiceMutationGapsTest() {
        when(contexts.today()).thenReturn(TODAY);
    }

    private static void refused(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        FciException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    private static AppRole role(long id, String code) {
        AppRole r = mock(AppRole.class);
        when(r.getId()).thenReturn(id);
        when(r.getCode()).thenReturn(code);
        return r;
    }

    private static AccessGroup group(long id, String code, long roleId, boolean consolidated) {
        AccessGroup g = mock(AccessGroup.class);
        when(g.getId()).thenReturn(id);
        when(g.getCode()).thenReturn(code);
        when(g.getName()).thenReturn("Group " + code);
        when(g.getRoleId()).thenReturn(roleId);
        when(g.getScopeType())
                .thenReturn(consolidated ? ScopeType.HEADQUARTERS : ScopeType.DIVISION);
        when(g.isConsolidatedView()).thenReturn(consolidated);
        when(g.getAccessMode()).thenReturn(AccessMode.FULL);
        when(g.getEffectiveFrom()).thenReturn(TODAY.minusDays(30));
        when(g.getApprovalId()).thenReturn(300L + id);
        when(g.isActiveOn(TODAY)).thenReturn(true);
        return g;
    }

    private static AccessGroupScope scopeRow(long groupId, Long zone, Long location) {
        AccessGroupScope s = mock(AccessGroupScope.class);
        when(s.getAccessGroupId()).thenReturn(groupId);
        when(s.getZoneId()).thenReturn(zone);
        when(s.getLocationId()).thenReturn(location);
        return s;
    }

    private static AccessAdminService.NewGroup newGroup(
            ScopeType type, boolean consolidated, List<AccessAdminService.ScopeInput> inputs) {
        return new AccessAdminService.NewGroup(
                "AG-NEW", "New group", 5L, type, consolidated, AccessMode.FULL, null, inputs, "r");
    }

    // ------------------------------------------------------------------ A-03 list

    @Test
    void SRS_22_19_theGroupListNeedsA03ViewAndShowsRoleScopesAndApprovalStatus() {
        AccessGroup g1 = group(1L, "AG-A", 5L, false);
        AccessGroup g2 = group(2L, "AG-B", 5L, false);
        when(g2.getApprovalId()).thenReturn(null);
        when(groups.findAllByOrderByCode()).thenReturn(List.of(g1, g2));
        AccessGroupScope atOffice = scopeRow(1L, null, 7L);
        AccessGroupScope inZone = scopeRow(1L, 3L, null);
        AccessGroupScope empty = scopeRow(1L, null, null);
        AccessGroupScope unknownOffice = scopeRow(2L, null, 99L);
        when(scopes.findByAccessGroupIdIn(any()))
                .thenReturn(List.of(atOffice, inZone, empty, unknownOffice));
        AppRole maker = role(5L, "PAYROLL_MAKER");
        when(roles.findAllByOrderByCode()).thenReturn(List.of(maker));
        OrgLocation office = mock(OrgLocation.class);
        when(office.getId()).thenReturn(7L);
        when(office.getCode()).thenReturn("DL01");
        when(office.displayLabel()).thenReturn("DL01 - Delhi Division");
        when(org.location(7L)).thenReturn(Optional.of(office));
        when(org.location(99L)).thenReturn(Optional.empty());
        OrgZone zone = mock(OrgZone.class);
        when(zone.getId()).thenReturn(3L);
        when(zone.getCode()).thenReturn("NZ");
        when(zone.getName()).thenReturn("North Zone");
        when(org.zone(3L)).thenReturn(Optional.of(zone));
        // A mutable map, as the repository's: asking it for a group without approval gives null.
        when(statuses.of(any()))
                .thenReturn(new java.util.HashMap<>(Map.of(301L, ApprovalStatus.APPROVED)));

        List<AccessAdminService.GroupView> views = service.groups(admin);

        verify(policy).require(admin, "A-03", "VIEW");
        assertThat(views).hasSize(2);
        AccessAdminService.GroupView first = views.get(0);
        assertThat(first.code()).isEqualTo("AG-A");
        assertThat(first.roleCode()).isEqualTo("PAYROLL_MAKER");
        assertThat(first.approvalStatus()).isEqualTo("APPROVED");
        assertThat(first.scopes())
                .containsExactly(
                        new AccessAdminService.ScopeRow(
                                "LOCATION", 7L, "DL01", "DL01 - Delhi Division"),
                        new AccessAdminService.ScopeRow("ZONE", 3L, "NZ", "North Zone"));
        AccessAdminService.GroupView second = views.get(1);
        assertThat(second.approvalStatus()).isEqualTo("NONE");
        assertThat(second.scopes()).isEmpty();
    }

    @Test
    void SRS_22_19_noGroupsGiveAnEmptyList() {
        when(groups.findAllByOrderByCode()).thenReturn(List.of());
        when(roles.findAllByOrderByCode()).thenReturn(List.of());
        assertThat(service.groups(admin)).isEmpty();
    }

    // ------------------------------------------------------------------ A-03 create

    @Test
    void SRS_22_19_aZoneGroupIsCreatedOnlyByHeadquartersWithAKnownZone() {
        AppRole maker = role(5L, "PAYROLL_MAKER");
        when(roles.findById(5L)).thenReturn(Optional.of(maker));
        OrgZone zone = mock(OrgZone.class);
        when(org.zone(3L)).thenReturn(Optional.of(zone));
        when(approvals.submit(any(), any(), any()))
                .thenReturn(new SubmittedApproval(900L, ApprovalStatus.PENDING_APPROVAL, 55L));
        AccessGroup saved = group(11L, "AG-NEW", 5L, false);
        when(groups.save(any(AccessGroup.class))).thenReturn(saved);
        AccessGroupScope savedRow = scopeRow(11L, 3L, null);
        when(scopes.save(any(AccessGroupScope.class))).thenReturn(savedRow);
        when(org.zone(3L)).thenReturn(Optional.of(zone));
        when(zone.getId()).thenReturn(3L);
        when(zone.getCode()).thenReturn("NZ");
        when(zone.getName()).thenReturn("North Zone");

        AccessAdminService.GroupView view =
                service.createGroup(
                        admin,
                        newGroup(
                                ScopeType.ZONE,
                                false,
                                List.of(new AccessAdminService.ScopeInput(3L, null))),
                        null);

        verify(adminScope).requireNational(admin, "access_group");
        assertThat(view.code()).isEqualTo("AG-NEW");
        assertThat(view.approvalStatus()).isEqualTo("PENDING_APPROVAL");
        assertThat(view.scopes()).hasSize(1);
        assertThat(view.scopes().get(0).type()).isEqualTo("ZONE");
    }

    @Test
    void SRS_22_19_aZoneGroupRefusesAnUnknownZoneAndAnOfficeRow() {
        AppRole maker = role(5L, "PAYROLL_MAKER");
        when(roles.findById(5L)).thenReturn(Optional.of(maker));
        when(org.zone(9L)).thenReturn(Optional.empty());

        refused(
                () ->
                        service.createGroup(
                                admin,
                                newGroup(
                                        ScopeType.ZONE,
                                        false,
                                        List.of(new AccessAdminService.ScopeInput(9L, null))),
                                null),
                ApiErrorCode.INVALID_SCOPE);
        refused(
                () ->
                        service.createGroup(
                                admin,
                                newGroup(
                                        ScopeType.ZONE,
                                        false,
                                        List.of(new AccessAdminService.ScopeInput(null, 7L))),
                                null),
                ApiErrorCode.INVALID_SCOPE);
        verify(approvals, never()).submit(any(), any(), any());
    }

    // ------------------------------------------------------------------ A-03 end

    @Test
    void SRS_22_19_endingAGroupNeedsA03EditAndHeadquartersEvictsAndAuditsTheDefaultReason() {
        AccessGroup g = group(11L, "AG-A", 5L, false);
        when(groups.findById(11L)).thenReturn(Optional.of(g));
        AppRole maker = role(5L, "PAYROLL_MAKER");
        when(roles.findById(5L)).thenReturn(Optional.of(maker));
        when(scopes.findByAccessGroupIdIn(List.of(11L))).thenReturn(List.of());

        service.endGroup(admin, 11L, null, null);

        verify(policy).require(admin, "A-03", "EDIT");
        verify(adminScope).requireNational(admin, "access_group");
        verify(g).endOn(TODAY);
        verify(groups).save(g);
        verify(contexts).evictAll();
        verify(audit)
                .record(
                        "admin@x",
                        AuditService.Entry.change(
                                        "access_group",
                                        "AG-A",
                                        "effective_to",
                                        null,
                                        TODAY.toString(),
                                        "Group end-dated")
                                .module("A-03"));
    }

    @Test
    void SRS_22_19_endingAGroupKeepsTheGivenReasonAndTheGivenLastDay() {
        AccessGroup g = group(11L, "AG-A", 5L, false);
        when(groups.findById(11L)).thenReturn(Optional.of(g));
        AppRole maker = role(5L, "PAYROLL_MAKER");
        when(roles.findById(5L)).thenReturn(Optional.of(maker));
        LocalDate last = TODAY.plusDays(10);

        service.endGroup(admin, 11L, last, "Scheme closed");

        verify(g).endOn(last);
        verify(audit)
                .record(
                        "admin@x",
                        AuditService.Entry.change(
                                        "access_group",
                                        "AG-A",
                                        "effective_to",
                                        null,
                                        last.toString(),
                                        "Scheme closed")
                                .module("A-03"));
    }

    @Test
    void SRS_22_19_aBlankReasonOnEndingAGroupGetsTheDefault() {
        AccessGroup g = group(11L, "AG-A", 5L, false);
        when(groups.findById(11L)).thenReturn(Optional.of(g));
        AppRole maker = role(5L, "PAYROLL_MAKER");
        when(roles.findById(5L)).thenReturn(Optional.of(maker));

        service.endGroup(admin, 11L, null, "   ");

        verify(audit)
                .record(
                        eq("admin@x"),
                        eq(
                                AuditService.Entry.change(
                                                "access_group",
                                                "AG-A",
                                                "effective_to",
                                                null,
                                                TODAY.toString(),
                                                "Group end-dated")
                                        .module("A-03")));
    }

    // ------------------------------------------------------------------ A-04 list

    @Test
    void SRS_22_2_theAssignmentListNeedsA04ViewAndClampsPageAndSize() {
        when(adminScope.isNational(admin)).thenReturn(true);
        when(assignments.findAllByOrderByIdDesc(any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 100), 0));

        AccessAdminService.PageView<AccessAdminService.AssignmentView> page =
                service.assignments(admin, null, -3, 500);

        verify(policy).require(admin, "A-04", "VIEW");
        verify(assignments).findAllByOrderByIdDesc(PageRequest.of(0, 100));
        assertThat(page.items()).isEmpty();
        assertThat(page.total()).isZero();

        service.assignments(admin, null, 2, 0);
        verify(assignments).findAllByOrderByIdDesc(PageRequest.of(2, 1));
    }

    @Test
    void SRS_22_2_aScopedAdministratorSeesOnlyAssignmentsOfUsersAtItsOffices() {
        when(adminScope.isNational(admin)).thenReturn(false);
        AppUser u1 = mock(AppUser.class);
        when(u1.getId()).thenReturn(21L);
        AppUser u2 = mock(AppUser.class);
        when(u2.getId()).thenReturn(22L);
        when(users.findAtLocations(Set.of(10L, 11L))).thenReturn(List.of(u1, u2));
        when(assignments.findByUserIdInOrderByIdDesc(any(), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        service.assignments(admin, null, 0, 20);
        verify(assignments).findByUserIdInOrderByIdDesc(Set.of(21L, 22L), PageRequest.of(0, 20));

        service.assignments(admin, 22L, 0, 20);
        verify(assignments).findByUserIdInOrderByIdDesc(Set.of(22L), PageRequest.of(0, 20));

        // A user outside the administrator's offices: nothing, and no query for it.
        assertThat(service.assignments(admin, 99L, 0, 20).items()).isEmpty();
        verify(assignments, never()).findByUserIdInOrderByIdDesc(eq(Set.of(99L)), any());
        verify(assignments, never()).findByUserIdOrderByIdDesc(any(), any());
    }

    @Test
    void SRS_22_2_aScopedAdministratorWithNoOfficeSeesNoAssignments() {
        AccessContext noScope =
                new AccessContext(
                        1L, "admin@x", "Admin", "s", "P", 10L, null, List.of(), Map.of(), Set.of(),
                        Set.of(), Set.of());
        when(adminScope.isNational(noScope)).thenReturn(false);

        assertThat(service.assignments(noScope, null, 0, 20).items()).isEmpty();

        verify(users, never()).findAtLocations(any());
    }

    // ------------------------------------------------------------------ A-04 propose

    private AppUser subject() {
        AppUser subject = mock(AppUser.class);
        when(subject.getId()).thenReturn(2L);
        when(subject.getLoginId()).thenReturn("maker.dl");
        when(subject.getStatus()).thenReturn(AppUser.Status.ACTIVE);
        when(users.findById(2L)).thenReturn(Optional.of(subject));
        return subject;
    }

    private AccessGroup approvedGroup(boolean consolidated) {
        AccessGroup g = group(11L, "AG-A", 5L, consolidated);
        when(groups.findById(11L)).thenReturn(Optional.of(g));
        when(statuses.approved(List.of(311L))).thenReturn(Set.of(311L));
        OrgLocation hq = mock(OrgLocation.class);
        when(hq.getId()).thenReturn(1L);
        when(org.headquarters()).thenReturn(Optional.of(hq));
        when(approvals.submit(any(), any(), any()))
                .thenReturn(new SubmittedApproval(901L, ApprovalStatus.PENDING_APPROVAL, 56L));
        AppUserAccessGroup saved = mock(AppUserAccessGroup.class);
        when(assignments.save(any(AppUserAccessGroup.class))).thenReturn(saved);
        AppRole maker = role(5L, "PAYROLL_MAKER");
        when(roles.findAllByOrderByCode()).thenReturn(List.of(maker));
        return g;
    }

    private static AccessAdminService.NewAssignment request() {
        return new AccessAdminService.NewAssignment(2L, 11L, null, null, "needed");
    }

    @Test
    void SRS_22_23_aProposalChecksTheGroupsScopeAgainstTheAdministrator() {
        subject();
        AccessGroup g = approvedGroup(false);
        AccessGroupScope row = scopeRow(11L, null, 7L);
        when(scopes.findByAccessGroupIdIn(List.of(11L))).thenReturn(List.of(row));
        OrgLocation zonal = mock(OrgLocation.class);
        when(zonal.getId()).thenReturn(4L);
        when(org.ancestorOrSelf(7L, LocationType.ZO)).thenReturn(Optional.of(zonal));

        AccessAdminService.AssignmentView view = service.propose(admin, request(), null);

        verify(adminScope).requireGroup(admin, g, List.of(row));
        assertThat(view.approvalStatus()).isEqualTo("PENDING_APPROVAL");
        assertThat(view.groupCode()).isEqualTo("AG-A");
        assertThat(view.roleCode()).isEqualTo("PAYROLL_MAKER");
    }

    @Test
    void SRS_22_3_aConsolidatedGroupIsAssignedOnlyWhenItsRoleHoldsMisView() {
        subject();
        approvedGroup(true);
        AppPermission mis = mock(AppPermission.class);
        when(mis.getId()).thenReturn(70L);

        // No R-01 VIEW permission is defined at all.
        when(permissions.findByScreenCodeAndActionCode("R-01", "VIEW"))
                .thenReturn(Optional.empty());
        refused(() -> service.propose(admin, request(), null), ApiErrorCode.CONSOLIDATED_NEEDS_MIS);

        // Defined, but the role never held it.
        when(permissions.findByScreenCodeAndActionCode("R-01", "VIEW"))
                .thenReturn(Optional.of(mis));
        when(rolePermissions.findById(new AppRolePermission.Key(5L, 70L)))
                .thenReturn(Optional.empty());
        refused(() -> service.propose(admin, request(), null), ApiErrorCode.CONSOLIDATED_NEEDS_MIS);

        // Held once, no longer in force.
        AppRolePermission ended = mock(AppRolePermission.class);
        when(ended.isActiveOn(TODAY)).thenReturn(false);
        when(rolePermissions.findById(new AppRolePermission.Key(5L, 70L)))
                .thenReturn(Optional.of(ended));
        refused(() -> service.propose(admin, request(), null), ApiErrorCode.CONSOLIDATED_NEEDS_MIS);
        verify(approvals, never()).submit(any(), any(), any());

        // In force today: assigned.
        AppRolePermission live = mock(AppRolePermission.class);
        when(live.isActiveOn(TODAY)).thenReturn(true);
        when(rolePermissions.findById(new AppRolePermission.Key(5L, 70L)))
                .thenReturn(Optional.of(live));
        assertThatCode(() -> service.propose(admin, request(), null)).doesNotThrowAnyException();
    }

    @Test
    void SRS_22_3_anOrdinaryGroupNeedsNoMisPermission() {
        subject();
        approvedGroup(false);

        assertThatCode(() -> service.propose(admin, request(), null)).doesNotThrowAnyException();

        verify(permissions, never()).findByScreenCodeAndActionCode(any(), any());
    }

    // ------------------------------------------------------------------ A-04 revoke

    @Test
    void SRS_SEC_14_aRevocationNeedsA04DeleteTheUsersScopeEndsSessionsAndAudits() {
        AppUser target = mock(AppUser.class);
        when(target.getLoginId()).thenReturn("maker.dl");
        when(users.findById(2L)).thenReturn(Optional.of(target));
        AccessGroup g = group(11L, "AG-A", 5L, false);
        when(groups.findById(11L)).thenReturn(Optional.of(g));
        AppRole maker = role(5L, "PAYROLL_MAKER");
        when(roles.findAllByOrderByCode()).thenReturn(List.of(maker));
        AppUserAccessGroup assignment = mock(AppUserAccessGroup.class);
        when(assignment.getUserId()).thenReturn(2L);
        when(assignment.getAccessGroupId()).thenReturn(11L);
        when(assignment.getRevokedAt()).thenReturn(null);
        when(assignment.getRevokeCause()).thenReturn(AppUserAccessGroup.RevokeCause.ADMIN);
        when(assignments.findById(8L)).thenReturn(Optional.of(assignment));

        service.revoke(admin, 8L, null, " Left the unit ");

        verify(policy).require(admin, "A-04", "DELETE");
        verify(adminScope).requireUser(admin, target);
        verify(assignment).revoke(AppUserAccessGroup.RevokeCause.ADMIN, "Left the unit", NOW);
        verify(assignments).save(assignment);
        verify(sessions).endAllSessions(2L, NOW, AppSession.EndReason.REVOCATION);
        verify(contexts).evictAll();
        verify(audit)
                .record(
                        "admin@x",
                        AuditService.Entry.change(
                                        "app_user_access_group",
                                        "maker.dl|AG-A",
                                        "revoked_at",
                                        null,
                                        "REVOKED ADMIN",
                                        "Left the unit")
                                .module("A-04"));
    }
}
