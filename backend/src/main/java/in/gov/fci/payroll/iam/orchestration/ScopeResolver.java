package in.gov.fci.payroll.iam.orchestration;

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
import in.gov.fci.payroll.platform.cache.CacheRegions;
import in.gov.fci.payroll.platform.cache.VersionedCache;
import in.gov.fci.payroll.platform.security.ActiveGroup;
import in.gov.fci.payroll.platform.security.ScopeType;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves WHERE a user may act: effective authority = role (WHAT, app_role_permission) intersected
 * with access-group scope (WHERE / ON WHOM, access_group_scope). Used by login options, login,
 * switch-scope and every request's access context, so the four can never disagree.
 */
@Component
public class ScopeResolver {

    /** The location types a persona admits, per admitted role. */
    public record PersonaRule(String roleCode, Set<LocationType> locationTypes) {}

    /**
     * One scope a user may act in: an office and ONE role there (the acting role), with the access
     * groups of that role that admit the office. Employee (eSeva) is the scope without an office
     * ({@code location == null}, no groups: own record only).
     *
     * @param personaCode the login persona that admits the role at this office type (landing
     *     screen, labels); a role admitted by several personas takes the first in display order
     */
    public record RoleScope(
            String personaCode,
            OrgLocation location,
            String roleCode,
            String roleName,
            List<ActiveGroup> groups) {

        public boolean isEseva() {
            return location == null;
        }

        public boolean matches(String role, Long locationId) {
            return roleCode.equals(role)
                    && Objects.equals(location == null ? null : location.getId(), locationId);
        }
    }

    private final AccessRepositories.Assignments assignments;
    private final AccessRepositories.Groups groups;
    private final AccessRepositories.GroupScopes scopes;
    private final AccessRepositories.Roles roles;
    private final AccessRepositories.UserRoles userRoles;
    private final IdentityRepositories.PersonaRoles personaRoles;
    private final IdentityRepositories.Personas personas;
    private final ApprovalStatuses approvals;
    private final OrgDirectory org;

    private final VersionedCache<String, List<RoleScope>> available;

    public ScopeResolver(
            AccessRepositories.Assignments assignments,
            AccessRepositories.Groups groups,
            AccessRepositories.GroupScopes scopes,
            AccessRepositories.Roles roles,
            AccessRepositories.UserRoles userRoles,
            IdentityRepositories.PersonaRoles personaRoles,
            IdentityRepositories.Personas personas,
            ApprovalStatuses approvals,
            OrgDirectory org,
            CacheRegions regions) {
        // The scope switcher reads the user's scopes on every page; they change only with an
        // access change, which bumps the ACCESS region (AccessContextService.evictAll). Same
        // one-minute ceiling as the cached access contexts (deep profile 2026-10-27).
        this.available =
                VersionedCache.of(
                        AccessContextService.REGION, regions, 10_000, Duration.ofMinutes(1));
        this.assignments = assignments;
        this.groups = groups;
        this.scopes = scopes;
        this.roles = roles;
        this.userRoles = userRoles;
        this.personaRoles = personaRoles;
        this.personas = personas;
        this.approvals = approvals;
        this.org = org;
    }

    /** Roles a persona admits and, per role, the selectable login-location types. */
    @Transactional(readOnly = true)
    public List<PersonaRule> personaRules(String personaCode) {
        Map<Long, AppRole> byId =
                roles.findAllByOrderByCode().stream()
                        .collect(Collectors.toMap(AppRole::getId, r -> r));
        List<PersonaRule> rules = new ArrayList<>();
        for (LoginPersonaRole rule : personaRoles.findAll()) {
            if (rule.getPersonaCode().equals(personaCode) && byId.containsKey(rule.getRoleId())) {
                rules.add(
                        new PersonaRule(
                                byId.get(rule.getRoleId()).getCode(), rule.allowedLocationTypes()));
            }
        }
        return rules;
    }

    /**
     * Groups usable today: assignment within its dates, not revoked and APPROVED; group within its
     * dates and APPROVED (or without approval only if seeded before the approval rule existed:
     * never in this schema); role not retired.
     */
    @Transactional(readOnly = true)
    public List<ActiveGroup> activeGroups(Long userId, LocalDate today) {
        return activeGroupsOf(List.of(userId), today).getOrDefault(userId, List.of());
    }

    /**
     * {@link #activeGroups} for many users in a handful of queries (reports over every user, e.g.
     * the Active User Report and the readiness check). Users without a usable group are absent.
     */
    @Transactional(readOnly = true)
    public Map<Long, List<ActiveGroup>> activeGroupsOf(Collection<Long> userIds, LocalDate today) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        List<AppUserAccessGroup> current =
                assignments.findByUserIdIn(userIds).stream()
                        .filter(a -> a.isCurrentOn(today))
                        .toList();
        if (current.isEmpty()) {
            return Map.of();
        }
        Map<Long, AccessGroup> groupById =
                groups
                        .findAllById(
                                current.stream()
                                        .map(AppUserAccessGroup::getAccessGroupId)
                                        .distinct()
                                        .toList())
                        .stream()
                        .collect(Collectors.toMap(AccessGroup::getId, Function.identity()));
        Set<Long> approvalIds = new HashSet<>();
        current.forEach(a -> approvalIds.add(a.getApprovalId()));
        groupById.values().forEach(g -> approvalIds.add(g.getApprovalId()));
        approvalIds.remove(null);
        Set<Long> approved = approvals.approved(approvalIds);
        Map<Long, AppRole> roleById =
                roles
                        .findAllById(
                                groupById.values().stream()
                                        .map(AccessGroup::getRoleId)
                                        .distinct()
                                        .toList())
                        .stream()
                        .collect(Collectors.toMap(AppRole::getId, Function.identity()));
        Map<Long, List<AccessGroupScope>> scopesByGroup =
                scopes.findByAccessGroupIdIn(groupById.keySet()).stream()
                        .collect(Collectors.groupingBy(AccessGroupScope::getAccessGroupId));
        Map<Long, ActiveGroup> activeById = new HashMap<>();
        Map<Long, Map<Long, ActiveGroup>> result = new LinkedHashMap<>();
        for (AppUserAccessGroup assignment : current) {
            AccessGroup group = groupById.get(assignment.getAccessGroupId());
            if (group == null
                    || !group.isActiveOn(today)
                    || !approved.contains(assignment.getApprovalId())
                    || !approved.contains(group.getApprovalId())) {
                continue;
            }
            AppRole role = roleById.get(group.getRoleId());
            if (role == null || !role.isActiveOn(today)) {
                continue;
            }
            ActiveGroup active =
                    activeById.computeIfAbsent(
                            group.getId(),
                            id -> toActive(group, role, scopesByGroup.getOrDefault(id, List.of())));
            result.computeIfAbsent(assignment.getUserId(), u -> new LinkedHashMap<>())
                    .putIfAbsent(group.getId(), active);
        }
        Map<Long, List<ActiveGroup>> byUser = new LinkedHashMap<>();
        result.forEach((user, active) -> byUser.put(user, List.copyOf(active.values())));
        return byUser;
    }

    /**
     * Every (office, acting role) the user may work in today, from the user's OWN active access
     * groups and direct eSeva grant: the options of the top-bar office and role selectors, the
     * scope chosen at sign-in and the only targets of a switch. Offices in hierarchy order (HQ,
     * zone, region, division, depot), roles in persona display order; Employee (eSeva) last.
     */
    @Transactional(readOnly = true)
    public List<RoleScope> availableScopes(
            Long userId, Long employeeId, String esevaRoleCode, LocalDate today) {
        return available.get(
                userId + "|" + employeeId + "|" + esevaRoleCode + "|" + today,
                k -> List.copyOf(resolveScopes(userId, employeeId, esevaRoleCode, today)));
    }

    private List<RoleScope> resolveScopes(
            Long userId, Long employeeId, String esevaRoleCode, LocalDate today) {
        Map<String, RoleScope> byKey = new LinkedHashMap<>();
        LoginPersona esevaPersona = null;
        for (LoginPersona persona : personas.findAllByOrderByDisplayOrder()) {
            if (!persona.isActive()) {
                continue;
            }
            if (!persona.isLocationRequired()) {
                esevaPersona = esevaPersona == null ? persona : esevaPersona;
                continue;
            }
            eligibleLocations(userId, persona.getCode(), today)
                    .forEach(
                            (location, admitting) -> {
                                Map<String, List<ActiveGroup>> byRole = new LinkedHashMap<>();
                                admitting.forEach(
                                        g ->
                                                byRole.computeIfAbsent(
                                                                g.roleCode(),
                                                                k -> new ArrayList<>())
                                                        .add(g));
                                byRole.forEach(
                                        (role, groupsOfRole) ->
                                                byKey.putIfAbsent(
                                                        location.getId() + "|" + role,
                                                        new RoleScope(
                                                                persona.getCode(),
                                                                location,
                                                                role,
                                                                groupsOfRole.get(0).roleName(),
                                                                List.copyOf(groupsOfRole))));
                            });
        }
        List<RoleScope> result =
                byKey.values().stream()
                        .sorted(
                                (a, b) -> {
                                    int byType =
                                            a.location()
                                                    .getType()
                                                    .compareTo(b.location().getType());
                                    return byType != 0
                                            ? byType
                                            : a.location()
                                                    .getCode()
                                                    .compareTo(b.location().getCode());
                                })
                        .collect(Collectors.toCollection(ArrayList::new));
        if (employeeId != null && esevaPersona != null) {
            String personaCode = esevaPersona.getCode();
            activeDirectRole(userId, esevaRoleCode, today)
                    .ifPresent(
                            role ->
                                    result.add(
                                            new RoleScope(
                                                    personaCode,
                                                    null,
                                                    role.getCode(),
                                                    role.getName(),
                                                    List.of())));
        }
        return List.copyOf(result);
    }

    /** The eSeva role, granted directly (scope: the user's own employee record). */
    @Transactional(readOnly = true)
    public Optional<AppRole> activeDirectRole(Long userId, String roleCode, LocalDate today) {
        Optional<AppRole> role = roles.findByCode(roleCode);
        if (role.isEmpty() || !role.get().isActiveOn(today)) {
            return Optional.empty();
        }
        Optional<AppUserRole> grant =
                userRoles.findById(new AppUserRole.Key(userId, role.get().getId()));
        if (grant.isEmpty()
                || !grant.get().isActiveOn(today)
                || grant.get().getApprovalId() == null) {
            return Optional.empty();
        }
        boolean approved =
                approvals
                        .approved(java.util.List.of(grant.get().getApprovalId()))
                        .contains(grant.get().getApprovalId());
        return approved ? role : Optional.empty();
    }

    /**
     * Login locations a persona admits for the user, each with the groups that admit it: a location
     * covered by an active group of an admitted role, of a type that role may sign in at, and
     * itself active.
     */
    @Transactional(readOnly = true)
    public Map<OrgLocation, List<ActiveGroup>> eligibleLocations(
            Long userId, String personaCode, LocalDate today) {
        Map<String, Set<LocationType>> allowed = new LinkedHashMap<>();
        personaRules(personaCode).forEach(r -> allowed.put(r.roleCode(), r.locationTypes()));
        Map<OrgLocation, List<ActiveGroup>> eligible = new LinkedHashMap<>();
        for (ActiveGroup group : activeGroups(userId, today)) {
            Set<LocationType> types = allowed.get(group.roleCode());
            if (types == null || types.isEmpty()) {
                continue;
            }
            for (OrgLocation location : org.locations(group.coverage())) {
                if (types.contains(location.getType()) && location.isActiveOn(today)) {
                    eligible.computeIfAbsent(location, k -> new ArrayList<>()).add(group);
                }
            }
        }
        // Stable order for the login picker: by hierarchy level, then code.
        return eligible.entrySet().stream()
                .sorted(
                        (a, b) -> {
                            int byType = a.getKey().getType().compareTo(b.getKey().getType());
                            return byType != 0
                                    ? byType
                                    : a.getKey().getCode().compareTo(b.getKey().getCode());
                        })
                .collect(
                        Collectors.toMap(
                                Map.Entry::getKey,
                                Map.Entry::getValue,
                                (x, y) -> x,
                                LinkedHashMap::new));
    }

    /**
     * Data scope of a session (SRS-22-2, SRS-G-29): the office the session works at and its
     * descendants, within the group's coverage - one rule for every scope type, so choosing a lower
     * office in the top bar narrows what is seen. A ZONE group at its own zonal office keeps the
     * whole zone (every office whose zone it is, even one not filed under the zonal office in the
     * tree); a GLOBAL group reaches every location. Project owner, 2026-10-24: a zone group at a
     * division sees that division, as a region group does.
     */
    public Set<Long> dataScope(Long loginLocationId, Collection<ActiveGroup> active) {
        Set<Long> scope = new HashSet<>();
        OrgLocation at =
                loginLocationId == null ? null : org.location(loginLocationId).orElse(null);
        for (ActiveGroup group : active) {
            if (group.scopeType() == ScopeType.GLOBAL) {
                scope.addAll(org.all());
            } else if (group.scopeType() == ScopeType.ZONE
                    && at != null
                    && at.getType() == LocationType.ZO
                    && group.zoneIds().contains(at.getZoneId())) {
                scope.addAll(org.inZone(at.getZoneId()));
            } else {
                Set<Long> subtree = org.subtree(loginLocationId);
                subtree.retainAll(group.coverage());
                scope.addAll(subtree);
            }
        }
        return Set.copyOf(scope);
    }

    private ActiveGroup toActive(AccessGroup group, AppRole role, List<AccessGroupScope> rows) {
        Set<Long> coverage = new HashSet<>();
        Set<Long> zoneIds = new HashSet<>();
        if (group.getScopeType() == ScopeType.GLOBAL) {
            coverage.addAll(org.all());
        }
        for (AccessGroupScope row : rows) {
            if (row.getLocationId() != null) {
                coverage.addAll(org.subtree(row.getLocationId()));
            } else if (row.getZoneId() != null) {
                zoneIds.add(row.getZoneId());
                coverage.addAll(org.inZone(row.getZoneId()));
            }
        }
        return new ActiveGroup(
                group.getId(),
                group.getCode(),
                group.getName(),
                role.getId(),
                role.getCode(),
                role.getName(),
                group.getScopeType(),
                group.isConsolidatedView(),
                Set.copyOf(coverage),
                Set.copyOf(zoneIds),
                group.getAccessMode());
    }
}
