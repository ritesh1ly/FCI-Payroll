package in.gov.fci.payroll.iam.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.employee.api.EmployeeDirectory;
import in.gov.fci.payroll.iam.business.model.AccessGroup;
import in.gov.fci.payroll.iam.business.model.AccessGroupScope;
import in.gov.fci.payroll.iam.business.model.AppRole;
import in.gov.fci.payroll.iam.business.model.AppSession;
import in.gov.fci.payroll.iam.business.model.AppUser;
import in.gov.fci.payroll.iam.business.model.AppUserAccessGroup;
import in.gov.fci.payroll.iam.persistence.AccessRepositories;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.organisation.business.model.OrgZone;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.ApprovalStatuses;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
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
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

/**
 * A-03 groups and A-04 assignments beyond the refusals (SRS-22-19, SRS-22-13): what a group or an
 * assignment shows when its role, user, group or approval item is missing, where an assignment is
 * routed for approval, and the defaults an audit line takes.
 */
class AccessAdminServiceViewsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 24);
    private static final Instant NOW = Instant.parse("2026-10-24T06:00:00Z");

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
    private final AuditService audit = mock(AuditService.class);
    private final AdminScope adminScope = mock(AdminScope.class);
    private final SessionService sessions = mock(SessionService.class);

    private final AccessAdminService service =
            new AccessAdminService(
                    mock(AccessRepositories.Permissions.class),
                    mock(AccessRepositories.RolePermissions.class),
                    groups,
                    scopes,
                    roles,
                    users,
                    assignments,
                    statuses,
                    approvals,
                    org,
                    contexts,
                    mock(AccessPolicy.class),
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
                    Set.of(10L));

    AccessAdminServiceViewsTest() {
        when(contexts.today()).thenReturn(TODAY);
    }

    /** A row names a zone or an office, never both (a mocked Long would read 0, not null). */
    private static AccessGroupScope zoneRow(Long zoneId) {
        AccessGroupScope row = mock(AccessGroupScope.class);
        when(row.getZoneId()).thenReturn(zoneId);
        when(row.getLocationId()).thenReturn((Long) null);
        return row;
    }

    private static AccessGroupScope locationRow(Long locationId) {
        AccessGroupScope row = mock(AccessGroupScope.class);
        when(row.getZoneId()).thenReturn((Long) null);
        when(row.getLocationId()).thenReturn(locationId);
        return row;
    }

    private static OrgLocation location(long id, String code) {
        OrgLocation location = mock(OrgLocation.class);
        when(location.getId()).thenReturn(id);
        when(location.getCode()).thenReturn(code);
        when(location.displayLabel()).thenReturn(code + " office");
        return location;
    }

    private static AccessGroup group(long id, String code, Long roleId, Long approvalId) {
        AccessGroup group = mock(AccessGroup.class);
        when(group.getId()).thenReturn(id);
        when(group.getCode()).thenReturn(code);
        when(group.getName()).thenReturn(code + " group");
        when(group.getRoleId()).thenReturn(roleId);
        when(group.getScopeType()).thenReturn(ScopeType.ZONE);
        when(group.getApprovalId()).thenReturn(approvalId);
        when(group.getEffectiveFrom()).thenReturn(TODAY.minusDays(30));
        when(group.isActiveOn(TODAY)).thenReturn(true);
        return group;
    }

    private static AppRole role(long id, String code) {
        AppRole role = mock(AppRole.class);
        when(role.getId()).thenReturn(id);
        when(role.getCode()).thenReturn(code);
        return role;
    }

    @Test
    void SRS_G_37_endingAGroupWithoutAReasonRecordsTheDefaultAndShowsWhatIsStillKnown() {
        AccessGroup group = group(4L, "AG-NORTH", 9L, null);
        when(groups.findById(4L)).thenReturn(Optional.of(group));
        when(roles.findById(9L)).thenReturn(Optional.empty());
        AccessGroupScope northRow = zoneRow(3L);
        when(scopes.findByAccessGroupIdIn(List.of(4L))).thenReturn(List.of(northRow));
        OrgZone north = mock(OrgZone.class);
        when(north.getId()).thenReturn(3L);
        when(north.getCode()).thenReturn("N");
        when(north.getName()).thenReturn("North");
        when(org.zone(3L)).thenReturn(Optional.of(north));

        AccessAdminService.GroupView view = service.endGroup(admin, 4L, null, "  ");

        verify(group).endOn(TODAY);
        ArgumentCaptor<AuditService.Entry> entry = ArgumentCaptor.captor();
        verify(audit).record(eq("admin@x"), entry.capture());
        assertThat(entry.getValue().reason()).isEqualTo("Group end-dated");
        assertThat(entry.getValue().newValue()).isEqualTo(TODAY.toString());
        // A role removed from the catalogue and a group created before approvals existed.
        assertThat(view.roleCode()).isNull();
        assertThat(view.approvalStatus()).isEqualTo("NONE");
        assertThat(view.scopes())
                .containsExactly(new AccessAdminService.ScopeRow("ZONE", 3L, "N", "North"));
    }

    @Test
    void SRS_22_3_aNationalConsolidatedViewGroupNamesNoOfficeAndGoesToApproval() {
        AppRole administrator = role(5L, "SYSTEM_ADMINISTRATOR");
        when(roles.findById(5L)).thenReturn(Optional.of(administrator));
        when(approvals.submit(eq(admin), any(), isNull()))
                .thenReturn(new SubmittedApproval(77L, ApprovalStatus.PENDING_APPROVAL, null));
        when(groups.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        AccessAdminService.GroupView view =
                service.createGroup(
                        admin,
                        new AccessAdminService.NewGroup(
                                "ag-mis",
                                "National MIS",
                                5L,
                                ScopeType.GLOBAL,
                                true,
                                AccessMode.FULL,
                                null,
                                List.of(),
                                "MIS readers"),
                        null);

        assertThat(view.code()).isEqualTo("AG-MIS");
        assertThat(view.consolidated()).isTrue();
        assertThat(view.scopeType()).isEqualTo("GLOBAL");
        assertThat(view.roleCode()).isEqualTo("SYSTEM_ADMINISTRATOR");
        assertThat(view.effectiveFrom()).isEqualTo(TODAY);
        assertThat(view.approvalStatus()).isEqualTo("PENDING_APPROVAL");
        assertThat(view.approvalId()).isEqualTo(77L);
        assertThat(view.scopes()).isEmpty();
        verify(scopes, never()).save(any());
    }

    @Test
    void SRS_22_23_anAssignmentIsRoutedToTheZonalOfficeAboveTheGroupOrHeadquarters() {
        AppUser subject = mock(AppUser.class);
        when(subject.getId()).thenReturn(2L);
        when(subject.getStatus()).thenReturn(AppUser.Status.ACTIVE);
        when(subject.getLoginId()).thenReturn("maker@x");
        when(users.findById(2L)).thenReturn(Optional.of(subject));
        AccessGroup divisionGroup = group(5L, "AG-DO", 9L, 70L);
        when(groups.findById(5L)).thenReturn(Optional.of(divisionGroup));
        when(statuses.approved(any())).thenReturn(Set.of(70L));
        when(approvals.submit(eq(admin), any(), isNull()))
                .thenReturn(new SubmittedApproval(80L, ApprovalStatus.PENDING_APPROVAL, null));
        when(assignments.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        AppRole maker = role(9L, "PAYROLL_MAKER");
        when(roles.findAllByOrderByCode()).thenReturn(List.of(maker));
        OrgLocation headquarters = location(1L, "HQ");
        OrgLocation zonalNorth = location(30L, "ZO-N");
        when(org.headquarters()).thenReturn(Optional.of(headquarters));
        when(org.zonalOffice(3L)).thenReturn(Optional.of(zonalNorth));
        when(org.zonalOffice(4L)).thenReturn(Optional.empty());
        AccessAdminService.NewAssignment request =
                new AccessAdminService.NewAssignment(
                        2L, 5L, TODAY.plusDays(1), TODAY.plusDays(90), "joins the unit");

        AccessGroupScope northRow = zoneRow(3L);
        AccessGroupScope eastRow = zoneRow(4L);
        AccessGroupScope ownOffice = locationRow(7L);
        AccessGroupScope headquartersOffice = locationRow(8L);
        when(org.ancestorOrSelf(7L, in.gov.fci.payroll.organisation.business.model.LocationType.ZO))
                .thenReturn(Optional.of(zonalNorth));
        // US-GAP-14: the ZO above the group's office (never the RO / DO itself), else the zonal
        // office of its zone, else Headquarters.
        when(scopes.findByAccessGroupIdIn(List.of(5L))).thenReturn(List.of(northRow, ownOffice));
        AccessAdminService.AssignmentView view = service.propose(admin, request, null);
        when(scopes.findByAccessGroupIdIn(List.of(5L))).thenReturn(List.of(headquartersOffice));
        service.propose(admin, request, null);
        when(scopes.findByAccessGroupIdIn(List.of(5L))).thenReturn(List.of(northRow));
        service.propose(admin, request, null);
        when(scopes.findByAccessGroupIdIn(List.of(5L))).thenReturn(List.of(eastRow));
        service.propose(admin, request, null);
        when(scopes.findByAccessGroupIdIn(List.of(5L))).thenReturn(List.of());
        service.propose(admin, request, null);

        ArgumentCaptor<Submission> submitted = ArgumentCaptor.captor();
        verify(approvals, times(5)).submit(eq(admin), submitted.capture(), isNull());
        assertThat(submitted.getAllValues())
                .extracting(Submission::locationId)
                .containsExactly(30L, 1L, 30L, 1L, 1L);
        assertThat(submitted.getValue().entityKey()).isEqualTo("maker@x|AG-DO");
        assertThat(view.loginId()).isEqualTo("maker@x");
        assertThat(view.groupCode()).isEqualTo("AG-DO");
        assertThat(view.roleCode()).isEqualTo("PAYROLL_MAKER");
        assertThat(view.effectiveTo()).isEqualTo(TODAY.plusDays(90));
        assertThat(view.approvalStatus()).isEqualTo("PENDING_APPROVAL");
        assertThat(view.current()).as("grants nothing before approval").isFalse();
    }

    @Test
    void SRS_22_13_theAssignmentListShowsWhatItKnowsOfARemovedUserOrGroupAndWhetherItIsCurrent() {
        when(adminScope.isNational(admin)).thenReturn(true);
        AppUserAccessGroup current = mock(AppUserAccessGroup.class);
        when(current.getId()).thenReturn(21L);
        when(current.getUserId()).thenReturn(2L);
        when(current.getAccessGroupId()).thenReturn(5L);
        when(current.getApprovalId()).thenReturn(90L);
        when(current.isCurrentOn(TODAY)).thenReturn(true);
        AppUserAccessGroup orphan = mock(AppUserAccessGroup.class);
        when(orphan.getId()).thenReturn(22L);
        when(orphan.getUserId()).thenReturn(3L);
        when(orphan.getAccessGroupId()).thenReturn(6L);
        when(assignments.findAllByOrderByIdDesc(any()))
                .thenReturn(new PageImpl<>(List.of(current, orphan), PageRequest.of(0, 20), 2));
        AppUser user = mock(AppUser.class);
        when(user.getId()).thenReturn(2L);
        when(user.getLoginId()).thenReturn("maker@x");
        when(users.findAllById(any())).thenReturn(List.of(user));
        AccessGroup divisionGroup = group(5L, "AG-DO", 9L, 70L);
        when(groups.findAllById(any())).thenReturn(List.of(divisionGroup));
        when(roles.findAllByOrderByCode()).thenReturn(List.of());
        when(statuses.of(any())).thenReturn(Map.of(90L, ApprovalStatus.APPROVED));

        AccessAdminService.PageView<AccessAdminService.AssignmentView> page =
                service.assignments(admin, null, 0, 20);

        assertThat(page.total()).isEqualTo(2);
        AccessAdminService.AssignmentView first = page.items().get(0);
        assertThat(first.loginId()).isEqualTo("maker@x");
        assertThat(first.groupCode()).isEqualTo("AG-DO");
        assertThat(first.roleCode()).as("a role no longer in the catalogue").isNull();
        assertThat(first.approvalStatus()).isEqualTo("APPROVED");
        assertThat(first.current()).isTrue();
        AccessAdminService.AssignmentView second = page.items().get(1);
        assertThat(second.loginId()).isNull();
        assertThat(second.groupCode()).isNull();
        assertThat(second.roleCode()).isNull();
        assertThat(second.approvalStatus()).isEqualTo("NONE");
        assertThat(second.current()).isFalse();
    }

    // SC-03-0009: Access Group changes are in the audit trail (OTP outcomes stay on otp_challenge;
    // interface calls with module 23).
    @Test
    void SRS_22_13_aRevocationEndsTheSessionsAndItsAuditLineNamesIdsWhenTheUserOrGroupIsGone() {
        AppUserAccessGroup open = mock(AppUserAccessGroup.class);
        when(open.getUserId()).thenReturn(2L);
        when(open.getAccessGroupId()).thenReturn(5L);
        when(open.getRevokeCause()).thenReturn(AppUserAccessGroup.RevokeCause.ADMIN);
        when(assignments.findById(11L)).thenReturn(Optional.of(open));
        // Found for the scope check, gone by the time the audit line is written (deactivated by
        // another administrator meanwhile): the line still names the ids rather than failing.
        when(users.findById(2L))
                .thenReturn(Optional.of(mock(AppUser.class)))
                .thenReturn(Optional.empty());
        when(groups.findById(5L)).thenReturn(Optional.empty());
        when(roles.findAllByOrderByCode()).thenReturn(List.of());

        AccessAdminService.AssignmentView view = service.revoke(admin, 11L, null, " moved ");

        verify(open).revoke(AppUserAccessGroup.RevokeCause.ADMIN, "moved", NOW);
        verify(sessions).endAllSessions(2L, NOW, AppSession.EndReason.REVOCATION);
        verify(contexts).evictAll();
        ArgumentCaptor<AuditService.Entry> entry = ArgumentCaptor.captor();
        verify(audit).record(eq("admin@x"), entry.capture());
        assertThat(entry.getValue().entityKey()).isEqualTo("2|?");
        assertThat(entry.getValue().newValue()).isEqualTo("REVOKED ADMIN");
        assertThat(entry.getValue().reason()).isEqualTo("moved");
        assertThat(view.loginId()).isNull();
        assertThat(view.groupCode()).isNull();
        assertThat(view.approvalStatus()).isEqualTo("NONE");
    }

    /** A row that names neither a zone nor an office (chk_access_group_scope_one allows it). */
    private static AccessGroupScope openRow() {
        AccessGroupScope row = mock(AccessGroupScope.class);
        when(row.getZoneId()).thenReturn((Long) null);
        when(row.getLocationId()).thenReturn((Long) null);
        return row;
    }

    @Test
    void SRS_22_13_aScopedAdministratorSeesOnlyItsOfficesAssignments() {
        AccessContext noOffices =
                new AccessContext(
                        1L, "admin@x", "Admin", "s", "P", 10L, null, List.of(), Map.of(), Set.of(),
                        Set.of(), Set.of());
        when(adminScope.isNational(noOffices)).thenReturn(false);
        when(users.findAllById(any())).thenReturn(List.of());
        when(groups.findAllById(any())).thenReturn(List.of());
        when(roles.findAllByOrderByCode()).thenReturn(List.of());
        when(statuses.of(any())).thenReturn(Map.of());

        AccessAdminService.PageView<AccessAdminService.AssignmentView> page =
                service.assignments(noOffices, 2L, 0, 20);

        assertThat(page.total()).isZero();
        verify(users, never()).findAtLocations(any());
        verify(assignments, never()).findByUserIdInOrderByIdDesc(any(), any());

        // With its office: the assignments of that office's users, narrowed to the user asked.
        when(adminScope.isNational(admin)).thenReturn(false);
        AppUser local = mock(AppUser.class);
        when(local.getId()).thenReturn(2L);
        when(users.findAtLocations(Set.of(10L))).thenReturn(List.of(local));
        when(assignments.findByUserIdInOrderByIdDesc(any(), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));
        assertThat(service.assignments(admin, 2L, 0, 20).total()).isZero();
        verify(assignments).findByUserIdInOrderByIdDesc(eq(Set.of(2L)), any());
    }

    @Test
    void SRS_22_23_aGroupScopeNamingNoZoneOrOfficeRoutesToHeadquartersAndShowsNoRow() {
        AccessGroup open = group(6L, "AG-ALL", 9L, null);
        when(groups.findById(6L)).thenReturn(Optional.of(open));
        when(roles.findById(9L)).thenReturn(Optional.empty());
        AccessGroupScope row = openRow();
        when(scopes.findByAccessGroupIdIn(List.of(6L))).thenReturn(List.of(row));
        OrgLocation headquarters = location(1L, "HQ");
        when(org.headquarters()).thenReturn(Optional.of(headquarters));

        assertThat(service.routingLocation(open)).isEqualTo(1L);
        verify(org, never()).zonalOffice(any());
        AccessAdminService.GroupView view = service.endGroup(admin, 6L, null, "ended");
        assertThat(view.scopes()).isEmpty();
        verify(org, never()).zone(any());
        verify(org, never()).location(any());
    }
}
