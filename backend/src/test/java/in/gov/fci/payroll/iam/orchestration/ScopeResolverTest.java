package in.gov.fci.payroll.iam.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.iam.business.model.AccessGroup;
import in.gov.fci.payroll.iam.business.model.AccessGroupScope;
import in.gov.fci.payroll.iam.business.model.AppRole;
import in.gov.fci.payroll.iam.business.model.AppUserAccessGroup;
import in.gov.fci.payroll.iam.business.model.AppUserRole;
import in.gov.fci.payroll.iam.business.model.LoginPersona;
import in.gov.fci.payroll.iam.business.model.LoginPersonaRole;
import in.gov.fci.payroll.iam.persistence.AccessRepositories;
import in.gov.fci.payroll.iam.persistence.IdentityRepositories;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.LocationType;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.platform.approval.ApprovalStatuses;
import in.gov.fci.payroll.platform.cache.CacheProperties;
import in.gov.fci.payroll.platform.cache.CacheRegions;
import in.gov.fci.payroll.platform.security.AccessMode;
import in.gov.fci.payroll.platform.security.ActiveGroup;
import in.gov.fci.payroll.platform.security.ScopeType;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Where a user may act (SRS-G-29, SRS-G-48; KB RBAC: effective authority = role intersected with
 * access-group scope). A group counts only while its assignment and the group are current and
 * approved and its role is active; a group reaches its offices' subtrees, its zones, or everything
 * when GLOBAL. The sign-in options are the roles a persona admits, at active offices of a type that
 * role may sign in at, in hierarchy order, with the eSeva scope last and only for an employee whose
 * direct eSeva grant is current and approved.
 */
class ScopeResolverTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 23);
    private static final long USER = 1L;
    private static final long APPROVED = 900L;
    private static final long PENDING = 901L;

    private final AccessRepositories.Assignments assignments =
            mock(AccessRepositories.Assignments.class);
    private final AccessRepositories.Groups groups = mock(AccessRepositories.Groups.class);
    private final AccessRepositories.GroupScopes scopes =
            mock(AccessRepositories.GroupScopes.class);
    private final AccessRepositories.Roles roles = mock(AccessRepositories.Roles.class);
    private final AccessRepositories.UserRoles userRoles = mock(AccessRepositories.UserRoles.class);
    private final IdentityRepositories.PersonaRoles personaRoles =
            mock(IdentityRepositories.PersonaRoles.class);
    private final IdentityRepositories.Personas personas =
            mock(IdentityRepositories.Personas.class);
    private final ApprovalStatuses approvals = mock(ApprovalStatuses.class);
    private final OrgDirectory org = mock(OrgDirectory.class);

    private final ScopeResolver resolver =
            new ScopeResolver(
                    assignments,
                    groups,
                    scopes,
                    roles,
                    userRoles,
                    personaRoles,
                    personas,
                    approvals,
                    org,
                    uncached());

    /**
     * A cache region whose version moves on every read: each call resolves afresh, so a test may
     * change the stubs between calls (the cache itself is VersionedCacheTest's).
     */
    private static CacheRegions uncached() {
        CacheRegions regions = mock(CacheRegions.class);
        AtomicLong version = new AtomicLong();
        when(regions.properties())
                .thenReturn(new CacheProperties(Duration.ofSeconds(10), Duration.ofMinutes(15)));
        when(regions.version(anyString())).thenAnswer(call -> version.incrementAndGet());
        return regions;
    }

    private final List<AppUserAccessGroup> userAssignments = new ArrayList<>();
    private final List<AccessGroup> knownGroups = new ArrayList<>();
    private final List<AppRole> knownRoles = new ArrayList<>();
    private final List<AccessGroupScope> knownScopes = new ArrayList<>();

    @BeforeEach
    void wire() {
        when(approvals.approved(any()))
                .thenAnswer(
                        i -> {
                            Collection<Long> asked = i.getArgument(0);
                            Set<Long> approved = new HashSet<>(asked);
                            approved.retainAll(Set.of(APPROVED));
                            return approved;
                        });
        when(assignments.findByUserIdIn(any())).thenReturn(userAssignments);
        when(groups.findAllById(any())).thenReturn(knownGroups);
        when(roles.findAllById(any())).thenReturn(knownRoles);
        when(scopes.findByAccessGroupIdIn(any())).thenReturn(knownScopes);
        when(org.subtree(any()))
                .thenAnswer(
                        i ->
                                new HashSet<>(
                                        Set.of(
                                                (Long) i.getArgument(0),
                                                100L + (Long) i.getArgument(0))));
        when(org.inZone(any())).thenAnswer(i -> Set.of(500L + (Long) i.getArgument(0)));
        when(org.all()).thenReturn(Set.of(1L, 2L, 3L));
    }

    // ---------------------------------------------------------------- fixtures

    private AppRole role(long id, String code, boolean active) {
        AppRole role = mock(AppRole.class);
        when(role.getId()).thenReturn(id);
        when(role.getCode()).thenReturn(code);
        when(role.getName()).thenReturn("Role " + code);
        when(role.isActiveOn(TODAY)).thenReturn(active);
        knownRoles.add(role);
        return role;
    }

    private AccessGroup group(long id, long roleId, boolean active, long approval, ScopeType type) {
        AccessGroup group = mock(AccessGroup.class);
        when(group.getId()).thenReturn(id);
        when(group.getCode()).thenReturn("AG-" + id);
        when(group.getName()).thenReturn("Group " + id);
        when(group.getRoleId()).thenReturn(roleId);
        when(group.isActiveOn(TODAY)).thenReturn(active);
        when(group.getApprovalId()).thenReturn(approval);
        when(group.getScopeType()).thenReturn(type);
        when(group.getAccessMode()).thenReturn(AccessMode.FULL);
        knownGroups.add(group);
        return group;
    }

    private void assigned(long user, long groupId, boolean current, long approval) {
        AppUserAccessGroup a = mock(AppUserAccessGroup.class);
        when(a.getUserId()).thenReturn(user);
        when(a.getAccessGroupId()).thenReturn(groupId);
        when(a.isCurrentOn(TODAY)).thenReturn(current);
        when(a.getApprovalId()).thenReturn(approval);
        userAssignments.add(a);
    }

    private void covers(long groupId, Long zone, Long location) {
        AccessGroupScope s = mock(AccessGroupScope.class);
        when(s.getAccessGroupId()).thenReturn(groupId);
        when(s.getZoneId()).thenReturn(zone);
        when(s.getLocationId()).thenReturn(location);
        knownScopes.add(s);
    }

    private static OrgLocation office(long id, String code, LocationType type, boolean active) {
        OrgLocation l = mock(OrgLocation.class);
        when(l.getId()).thenReturn(id);
        when(l.getCode()).thenReturn(code);
        when(l.getType()).thenReturn(type);
        when(l.isActiveOn(TODAY)).thenReturn(active);
        return l;
    }

    private static LoginPersona persona(String code, boolean active, boolean locationRequired) {
        LoginPersona p = mock(LoginPersona.class);
        when(p.getCode()).thenReturn(code);
        when(p.isActive()).thenReturn(active);
        when(p.isLocationRequired()).thenReturn(locationRequired);
        return p;
    }

    private static LoginPersonaRole admits(String persona, long roleId, Set<LocationType> types) {
        LoginPersonaRole r = mock(LoginPersonaRole.class);
        when(r.getPersonaCode()).thenReturn(persona);
        when(r.getRoleId()).thenReturn(roleId);
        when(r.allowedLocationTypes()).thenReturn(types);
        return r;
    }

    // ---------------------------------------------------------------- usable groups

    @Test
    void SRS_G_29_aGroupCountsOnlyWhileItsAssignmentAndItselfAreCurrentApprovedAndItsRoleActive() {
        role(10L, "PAYROLL_MAKER", true);
        role(11L, "RETIRED_ROLE", false);
        group(1L, 10L, true, APPROVED, ScopeType.DIVISION);
        group(2L, 10L, false, APPROVED, ScopeType.DIVISION);
        group(3L, 10L, true, APPROVED, ScopeType.DIVISION);
        group(4L, 10L, true, PENDING, ScopeType.DIVISION);
        group(5L, 11L, true, APPROVED, ScopeType.DIVISION);
        group(7L, 99L, true, APPROVED, ScopeType.DIVISION);
        assigned(USER, 1L, true, APPROVED);
        assigned(USER, 1L, true, APPROVED); // assigned twice: one group
        assigned(USER, 2L, true, APPROVED); // the group has ended
        assigned(USER, 3L, true, PENDING); // the assignment awaits its checker
        assigned(USER, 4L, true, APPROVED); // the group awaits its checker
        assigned(USER, 5L, true, APPROVED); // its role is retired
        assigned(USER, 6L, true, APPROVED); // no such group
        assigned(USER, 7L, true, APPROVED); // no such role
        assigned(USER, 8L, false, APPROVED); // the assignment has ended
        covers(1L, null, 40L);

        List<ActiveGroup> active = resolver.activeGroups(USER, TODAY);

        assertThat(active).extracting(ActiveGroup::id).containsExactly(1L);
        assertThat(active.getFirst().roleCode()).isEqualTo("PAYROLL_MAKER");
        assertThat(active.getFirst().coverage()).containsExactlyInAnyOrder(40L, 140L);
    }

    @Test
    void SRS_G_29_withoutUsersOrCurrentAssignmentsNoGroupIsReadAtAll() {
        assertThat(resolver.activeGroupsOf(List.of(), TODAY)).isEmpty();
        verify(assignments, never()).findByUserIdIn(any());
        assigned(USER, 1L, false, APPROVED);
        assertThat(resolver.activeGroupsOf(List.of(USER), TODAY)).isEmpty();
        verify(groups, never()).findAllById(any());
    }

    @Test
    void SRS_G_29_aGroupReachesItsOfficesSubtreesItsZonesOrEverythingWhenGlobal() {
        role(10L, "PAYROLL_CHECKER", true);
        group(1L, 10L, true, APPROVED, ScopeType.ZONE);
        group(2L, 10L, true, APPROVED, ScopeType.GLOBAL);
        assigned(USER, 1L, true, APPROVED);
        assigned(2L, 1L, true, APPROVED);
        assigned(2L, 2L, true, APPROVED);
        covers(1L, null, 40L);
        covers(1L, 3L, null);
        covers(1L, null, null); // an empty row reaches nothing

        Map<Long, List<ActiveGroup>> byUser = resolver.activeGroupsOf(List.of(USER, 2L), TODAY);

        ActiveGroup zonal = byUser.get(USER).getFirst();
        assertThat(zonal.coverage()).containsExactlyInAnyOrder(40L, 140L, 503L);
        assertThat(zonal.zoneIds()).containsExactly(3L);
        assertThat(byUser.get(2L)).extracting(ActiveGroup::id).containsExactly(1L, 2L);
        assertThat(byUser.get(2L).get(0)).as("built once, shared").isSameAs(zonal);
        assertThat(byUser.get(2L).get(1).coverage()).containsExactlyInAnyOrder(1L, 2L, 3L);
    }

    // ---------------------------------------------------------------- the eSeva grant

    private AppUserRole grant(boolean active, Long approval) {
        AppUserRole grant = mock(AppUserRole.class);
        when(grant.isActiveOn(TODAY)).thenReturn(active);
        when(grant.getApprovalId()).thenReturn(approval);
        return grant;
    }

    @Test
    void SRS_G_48_theESevaRoleIsGrantedDirectlyAndCountsOnlyWhileCurrentAndApproved() {
        assertThat(resolver.activeDirectRole(USER, "EMPLOYEE", TODAY)).as("no such role").isEmpty();
        AppRole retired = role(20L, "OLD_ESEVA", false);
        when(roles.findByCode("OLD_ESEVA")).thenReturn(Optional.of(retired));
        assertThat(resolver.activeDirectRole(USER, "OLD_ESEVA", TODAY)).isEmpty();

        AppRole eseva = role(21L, "EMPLOYEE", true);
        when(roles.findByCode("EMPLOYEE")).thenReturn(Optional.of(eseva));
        AppUserRole.Key key = new AppUserRole.Key(USER, 21L);
        assertThat(resolver.activeDirectRole(USER, "EMPLOYEE", TODAY))
                .as("never granted")
                .isEmpty();
        for (AppUserRole notUsable :
                List.of(grant(false, APPROVED), grant(true, null), grant(true, PENDING))) {
            when(userRoles.findById(key)).thenReturn(Optional.of(notUsable));
            assertThat(resolver.activeDirectRole(USER, "EMPLOYEE", TODAY)).isEmpty();
        }
        AppUserRole usable = grant(true, APPROVED);
        when(userRoles.findById(key)).thenReturn(Optional.of(usable));
        assertThat(resolver.activeDirectRole(USER, "EMPLOYEE", TODAY)).contains(eseva);
    }

    // ---------------------------------------------------------------- sign-in options

    @Test
    void SRS_G_48_theSignInOptionsAreAdmittedRolesAtActiveOfficesOfAnAllowedTypeThenESeva() {
        role(10L, "PAYROLL_MAKER", true);
        role(12L, "PAYROLL_CHECKER", true);
        role(13L, "ESTABLISHMENT_HR", true);
        AppRole eseva = role(21L, "EMPLOYEE", true);
        when(roles.findAllByOrderByCode()).thenReturn(knownRoles);
        when(roles.findByCode("EMPLOYEE")).thenReturn(Optional.of(eseva));
        AppUserRole esevaGrant = grant(true, APPROVED);
        when(userRoles.findById(new AppUserRole.Key(USER, 21L)))
                .thenReturn(Optional.of(esevaGrant));
        List<LoginPersona> inDisplayOrder =
                List.of(
                        persona("PAYROLL", true, true),
                        persona("RETIRED_PERSONA", false, true),
                        persona("ESEVA", true, false),
                        persona("ESEVA_LEGACY", true, false));
        when(personas.findAllByOrderByDisplayOrder()).thenReturn(inDisplayOrder);
        List<LoginPersonaRole> rules =
                List.of(
                        admits("PAYROLL", 10L, Set.of(LocationType.DO, LocationType.DEPOT)),
                        admits("PAYROLL", 12L, Set.of()), // signs in nowhere
                        admits("PAYROLL", 99L, Set.of(LocationType.DO)), // unknown role
                        admits("HR", 13L, Set.of(LocationType.RO)));
        when(personaRoles.findAll()).thenReturn(rules);
        group(1L, 10L, true, APPROVED, ScopeType.DIVISION);
        group(2L, 12L, true, APPROVED, ScopeType.DIVISION);
        group(3L, 13L, true, APPROVED, ScopeType.REGION);
        assigned(USER, 1L, true, APPROVED);
        assigned(USER, 2L, true, APPROVED);
        assigned(USER, 3L, true, APPROVED);
        OrgLocation dl02 = office(41L, "DL02", LocationType.DO, true);
        OrgLocation dl01 = office(40L, "DL01", LocationType.DO, true);
        OrgLocation depot = office(42L, "DP01", LocationType.DEPOT, true);
        OrgLocation closed = office(43L, "DL03", LocationType.DO, false);
        OrgLocation region = office(44L, "RO-DL", LocationType.RO, true);
        when(org.locations(any())).thenReturn(List.of(depot, dl02, closed, region, dl01));

        List<ScopeResolver.RoleScope> options =
                resolver.availableScopes(USER, 7L, "EMPLOYEE", TODAY);

        assertThat(options)
                .extracting(o -> o.location() == null ? "eSeva" : o.location().getCode())
                .containsExactly("DL01", "DL02", "DP01", "eSeva");
        assertThat(options)
                .allSatisfy(o -> assertThat(o.roleCode()).isIn("PAYROLL_MAKER", "EMPLOYEE"));
        ScopeResolver.RoleScope esevaScope = options.getLast();
        assertThat(esevaScope.isEseva()).isTrue();
        assertThat(esevaScope.personaCode()).as("the first eSeva persona").isEqualTo("ESEVA");
        assertThat(esevaScope.groups()).isEmpty();
        assertThat(esevaScope.matches("EMPLOYEE", null)).isTrue();
        assertThat(esevaScope.matches("EMPLOYEE", 40L)).isFalse();
        assertThat(options.getFirst().matches("PAYROLL_MAKER", 40L)).isTrue();
        assertThat(options.getFirst().matches("PAYROLL_CHECKER", 40L)).isFalse();

        // A user without an employee record has no eSeva scope.
        assertThat(resolver.availableScopes(USER, null, "EMPLOYEE", TODAY))
                .noneMatch(ScopeResolver.RoleScope::isEseva);
    }

    @Test
    void SRS_G_48_withoutAnESevaPersonaThereIsNoESevaScope() {
        List<LoginPersona> onlyPayroll = List.of(persona("PAYROLL", true, true));
        when(personas.findAllByOrderByDisplayOrder()).thenReturn(onlyPayroll);
        assertThat(resolver.availableScopes(USER, 7L, "EMPLOYEE", TODAY)).isEmpty();
        verify(roles, never()).findByCode("EMPLOYEE");
    }

    // ---------------------------------------------------------------- data scope

    private static ActiveGroup active(ScopeType type, Set<Long> coverage, Set<Long> zones) {
        return new ActiveGroup(
                1L,
                "AG-1",
                "Group 1",
                9L,
                "PAYROLL_MAKER",
                "Maker",
                type,
                false,
                coverage,
                zones,
                AccessMode.FULL);
    }

    private OrgLocation at(long id, LocationType type, Long zone) {
        OrgLocation l = office(id, "L" + id, type, true);
        when(l.getZoneId()).thenReturn(zone);
        when(org.location(id)).thenReturn(Optional.of(l));
        return l;
    }

    /** SRS-22-2: a zone group keeps its whole zone only at its own zonal office. */
    @Test
    void SRS_22_2_aZoneGroupKeepsTheWholeZoneOnlyAtAZonalOfficeOfItsOwnZone() {
        ActiveGroup zone = active(ScopeType.ZONE, Set.of(7L, 107L, 40L), Set.of(7L));
        doReturn(new HashSet<>()).when(org).subtree(null);

        assertThat(resolver.dataScope(null, List.of(zone))).as("no office").isEmpty();

        at(7L, LocationType.ZO, 7L);
        assertThat(resolver.dataScope(7L, List.of(zone))).containsExactly(507L);

        at(40L, LocationType.ZO, 8L);
        assertThat(resolver.dataScope(40L, List.of(zone)))
                .as("a zonal office of another zone: its subtree within the coverage")
                .containsExactly(40L);

        at(41L, LocationType.DO, 7L);
        assertThat(resolver.dataScope(41L, List.of(zone)))
                .as("a division of the zone: its subtree within the coverage")
                .isEmpty();

        when(org.location(99L)).thenReturn(Optional.empty());
        assertThat(resolver.dataScope(99L, List.of(zone))).as("an unknown office").isEmpty();

        assertThat(
                        resolver.dataScope(
                                7L, List.of(active(ScopeType.REGION, Set.of(7L, 107L), Set.of()))))
                .as("a region group at a zonal office: its subtree within the coverage")
                .containsExactlyInAnyOrder(7L, 107L);
        assertThat(resolver.dataScope(41L, List.of(active(ScopeType.GLOBAL, Set.of(), Set.of()))))
                .containsExactlyInAnyOrder(1L, 2L, 3L);
    }
}
