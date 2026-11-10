package in.gov.fci.payroll.iam.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.iam.business.model.AccessGroup;
import in.gov.fci.payroll.iam.business.model.AccessGroupScope;
import in.gov.fci.payroll.iam.business.model.AppRole;
import in.gov.fci.payroll.iam.business.model.AppUserAccessGroup;
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
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Mutation gaps of {@link ScopeResolver}: SRS-G-29 (both approval ids of an assignment and its
 * group are checked), SRS-G-48 (sign-in options and login locations in hierarchy order, then code).
 *
 * <p>Equivalent: ScopeResolver:343 (merge function of the collector, {@code (x, y) -> x}): the
 * entries come from a map, so no key is ever merged.
 */
class ScopeResolverMutationGapsTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Kolkata"));
    private static final long USER = 1L;

    private final AccessRepositories.Assignments assignments =
            mock(AccessRepositories.Assignments.class);
    private final AccessRepositories.Groups groups = mock(AccessRepositories.Groups.class);
    private final AccessRepositories.GroupScopes scopes =
            mock(AccessRepositories.GroupScopes.class);
    private final AccessRepositories.Roles roles = mock(AccessRepositories.Roles.class);
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
                    mock(AccessRepositories.UserRoles.class),
                    personaRoles,
                    personas,
                    approvals,
                    org,
                    uncached());

    private final Set<Long> approvedIds = new HashSet<>();
    private final List<AppUserAccessGroup> userAssignments = new ArrayList<>();
    private final List<AccessGroup> knownGroups = new ArrayList<>();
    private final List<AppRole> knownRoles = new ArrayList<>();
    private final List<AccessGroupScope> knownScopes = new ArrayList<>();

    private static CacheRegions uncached() {
        CacheRegions regions = mock(CacheRegions.class);
        AtomicLong version = new AtomicLong();
        when(regions.properties())
                .thenReturn(new CacheProperties(Duration.ofSeconds(10), Duration.ofMinutes(15)));
        when(regions.version(anyString())).thenAnswer(call -> version.incrementAndGet());
        return regions;
    }

    @BeforeEach
    void wire() {
        when(approvals.approved(any()))
                .thenAnswer(
                        i -> {
                            Collection<Long> asked = i.getArgument(0);
                            Set<Long> approved = new HashSet<>(asked);
                            approved.retainAll(approvedIds);
                            return approved;
                        });
        when(assignments.findByUserIdIn(any())).thenReturn(userAssignments);
        when(groups.findAllById(any())).thenReturn(knownGroups);
        when(roles.findAllById(any())).thenReturn(knownRoles);
        when(roles.findAllByOrderByCode()).thenReturn(knownRoles);
        when(scopes.findByAccessGroupIdIn(any())).thenReturn(knownScopes);
        when(org.subtree(any()))
                .thenAnswer(
                        i ->
                                new HashSet<>(
                                        Set.of(
                                                (Long) i.getArgument(0),
                                                100L + (Long) i.getArgument(0))));
    }

    private AppRole role(long id, String code) {
        AppRole role = mock(AppRole.class);
        when(role.getId()).thenReturn(id);
        when(role.getCode()).thenReturn(code);
        when(role.getName()).thenReturn("Role " + code);
        when(role.isActiveOn(TODAY)).thenReturn(true);
        knownRoles.add(role);
        return role;
    }

    private void group(long id, long roleId, long approval, long assignmentApproval, long office) {
        AccessGroup group = mock(AccessGroup.class);
        when(group.getId()).thenReturn(id);
        when(group.getCode()).thenReturn("AG-" + id);
        when(group.getName()).thenReturn("Group " + id);
        when(group.getRoleId()).thenReturn(roleId);
        when(group.isActiveOn(TODAY)).thenReturn(true);
        when(group.getApprovalId()).thenReturn(approval);
        when(group.getScopeType()).thenReturn(ScopeType.DIVISION);
        when(group.getAccessMode()).thenReturn(AccessMode.FULL);
        knownGroups.add(group);
        AppUserAccessGroup a = mock(AppUserAccessGroup.class);
        when(a.getUserId()).thenReturn(USER);
        when(a.getAccessGroupId()).thenReturn(id);
        when(a.isCurrentOn(TODAY)).thenReturn(true);
        when(a.getApprovalId()).thenReturn(assignmentApproval);
        userAssignments.add(a);
        AccessGroupScope s = mock(AccessGroupScope.class);
        when(s.getAccessGroupId()).thenReturn(id);
        when(s.getLocationId()).thenReturn(office);
        knownScopes.add(s);
    }

    private static OrgLocation office(long id, String code, LocationType type) {
        OrgLocation l = mock(OrgLocation.class);
        when(l.getId()).thenReturn(id);
        when(l.getCode()).thenReturn(code);
        when(l.getType()).thenReturn(type);
        when(l.isActiveOn(TODAY)).thenReturn(true);
        return l;
    }

    private static LoginPersona persona(String code) {
        LoginPersona p = mock(LoginPersona.class);
        when(p.getCode()).thenReturn(code);
        when(p.isActive()).thenReturn(true);
        when(p.isLocationRequired()).thenReturn(true);
        return p;
    }

    private static LoginPersonaRole admits(String persona, long roleId, Set<LocationType> types) {
        LoginPersonaRole r = mock(LoginPersonaRole.class);
        when(r.getPersonaCode()).thenReturn(persona);
        when(r.getRoleId()).thenReturn(roleId);
        when(r.allowedLocationTypes()).thenReturn(types);
        return r;
    }

    @Test
    void SRS_G_29_theApprovalsOfTheAssignmentAndOfTheGroupAreBothAskedFor() {
        role(10L, "PAYROLL_MAKER");
        group(1L, 10L, 905L, 900L, 40L);
        approvedIds.addAll(Set.of(900L, 905L));

        List<ActiveGroup> active = resolver.activeGroups(USER, TODAY);

        assertThat(active).extracting(ActiveGroup::id).containsExactly(1L);
        verify(approvals).approved(Set.of(900L, 905L));
    }

    @Test
    void SRS_G_29_aGroupWhoseOwnApprovalIsPendingOrWhoseAssignmentIsPendingIsNotUsable() {
        role(10L, "PAYROLL_MAKER");
        group(1L, 10L, 905L, 900L, 40L);

        approvedIds.add(900L);
        assertThat(resolver.activeGroups(USER, TODAY)).as("group pending").isEmpty();
        approvedIds.clear();
        approvedIds.add(905L);
        assertThat(resolver.activeGroups(USER, TODAY)).as("assignment pending").isEmpty();
    }

    private void twoPersonasOneRoleEach(Set<LocationType> first, Set<LocationType> second) {
        role(10L, "PAYROLL_MAKER");
        role(12L, "PAYROLL_CHECKER");
        approvedIds.add(900L);
        group(1L, 10L, 900L, 900L, 40L);
        group(2L, 12L, 900L, 900L, 50L);
        LoginPersona payroll = persona("PAYROLL");
        LoginPersona hqPayroll = persona("HQ_PAYROLL");
        when(personas.findAllByOrderByDisplayOrder()).thenReturn(List.of(payroll, hqPayroll));
        LoginPersonaRole a1 = admits("PAYROLL", 10L, first);
        LoginPersonaRole a2 = admits("HQ_PAYROLL", 12L, second);
        when(personaRoles.findAll()).thenReturn(List.of(a1, a2));
    }

    @Test
    void SRS_G_48_signInOptionsOfSeveralPersonasAreOrderedByOfficeTypeNotByPersona() {
        twoPersonasOneRoleEach(Set.of(LocationType.DEPOT), Set.of(LocationType.DO));
        OrgLocation depot = office(42L, "AA", LocationType.DEPOT);
        OrgLocation division = office(43L, "ZZ", LocationType.DO);
        when(org.locations(any()))
                .thenAnswer(
                        i -> {
                            Collection<Long> coverage = i.getArgument(0);
                            return coverage.contains(40L) ? List.of(depot) : List.of(division);
                        });

        List<ScopeResolver.RoleScope> options =
                resolver.availableScopes(USER, null, "EMPLOYEE", TODAY);

        assertThat(options).extracting(o -> o.location().getCode()).containsExactly("ZZ", "AA");
        assertThat(options.get(0).personaCode()).isEqualTo("HQ_PAYROLL");
        assertThat(options.get(0).roleCode()).isEqualTo("PAYROLL_CHECKER");
    }

    @Test
    void SRS_G_48_signInOptionsOfTheSameOfficeTypeAreOrderedByOfficeCode() {
        twoPersonasOneRoleEach(Set.of(LocationType.DO), Set.of(LocationType.DO));
        OrgLocation dl02 = office(42L, "DL02", LocationType.DO);
        OrgLocation dl01 = office(43L, "DL01", LocationType.DO);
        when(org.locations(any()))
                .thenAnswer(
                        i -> {
                            Collection<Long> coverage = i.getArgument(0);
                            return coverage.contains(40L) ? List.of(dl02) : List.of(dl01);
                        });

        List<ScopeResolver.RoleScope> options =
                resolver.availableScopes(USER, null, "EMPLOYEE", TODAY);

        assertThat(options).extracting(o -> o.location().getCode()).containsExactly("DL01", "DL02");
    }

    private void onePersonaManyLocations(OrgLocation... locations) {
        role(10L, "PAYROLL_MAKER");
        approvedIds.add(900L);
        group(1L, 10L, 900L, 900L, 40L);
        LoginPersonaRole both = admits("PAYROLL", 10L, Set.of(LocationType.DO, LocationType.DEPOT));
        when(personaRoles.findAll()).thenReturn(List.of(both));
        when(org.locations(any())).thenReturn(List.of(locations));
    }

    @Test
    void SRS_G_48_loginLocationsAreOrderedByHierarchyLevelThenCode() {
        onePersonaManyLocations(
                office(42L, "AA", LocationType.DEPOT), office(43L, "ZZ", LocationType.DO));

        Map<OrgLocation, List<ActiveGroup>> eligible =
                resolver.eligibleLocations(USER, "PAYROLL", TODAY);

        assertThat(eligible.keySet()).extracting(OrgLocation::getCode).containsExactly("ZZ", "AA");
    }

    @Test
    void SRS_G_48_loginLocationsOfTheSameTypeAreOrderedByCode() {
        onePersonaManyLocations(
                office(42L, "DL02", LocationType.DO),
                office(43L, "DL01", LocationType.DO),
                office(44L, "DL03", LocationType.DO));

        Map<OrgLocation, List<ActiveGroup>> eligible =
                resolver.eligibleLocations(USER, "PAYROLL", TODAY);

        assertThat(eligible.keySet())
                .extracting(OrgLocation::getCode)
                .containsExactly("DL01", "DL02", "DL03");
        assertThat(eligible.values()).allSatisfy(g -> assertThat(g).hasSize(1));
    }
}
