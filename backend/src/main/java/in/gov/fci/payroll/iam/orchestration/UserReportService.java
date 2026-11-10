package in.gov.fci.payroll.iam.orchestration;

import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.employee.api.EmployeeDirectory;
import in.gov.fci.payroll.iam.business.UserGovernance;
import in.gov.fci.payroll.iam.business.model.AccessGroupScope;
import in.gov.fci.payroll.iam.business.model.AppUser;
import in.gov.fci.payroll.iam.persistence.AccessRepositories;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.LocationType;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.platform.export.ExportReader;
import in.gov.fci.payroll.platform.export.ExportSink;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.persistence.Likes;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.ActiveGroup;
import in.gov.fci.payroll.platform.security.ScopeType;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * User-governance reports of X-01 (A-01), Module 02:
 *
 * <ul>
 *   <li>F-3 Active User Report (SRS-G-50): user ID, role, unit, access group and last sign-in,
 *       flagging users inactive beyond the A-07 setting INACTIVE_USER_DAYS ("not configured" until
 *       FCI sets it). Paged on the server; a scoped administrator sees its own offices' users only.
 *   <li>F-7 readiness check (SRS-G-50, SRS-22-11): every unit that runs payroll has a Maker and a
 *       separate Checker, its own or reaching it from above; a Divisional Office's own staff
 *       against the A-07 setting DO_MINIMUM_STAFF ("not configured" until FCI sets it).
 * </ul>
 *
 * Read-only: nothing here changes access.
 */
@Service
@Loggable
public class UserReportService {

    /** One access a user holds today: group, its role and the offices it reaches. */
    public record AccessRow(String groupCode, String roleCode, String unit) {}

    public record ActiveUserRow(
            Long userId,
            String loginId,
            String displayName,
            String status,
            Instant lastLoginAt,
            Long daysSinceLogin,
            Boolean inactive,
            List<AccessRow> access) {}

    /** {@code inactiveAfterDays} is null while the threshold is not configured. */
    public record ActiveUserReport(
            Long inactiveAfterDays, List<ActiveUserRow> items, int page, int size, long total) {}

    public record UnitReadiness(
            Long locationId,
            String code,
            String label,
            String type,
            String readiness,
            List<String> makers,
            List<String> checkers,
            int ownStaff,
            Boolean meetsMinimum) {}

    /** {@code doMinimumStaff} is null while the setting is not configured. */
    public record ReadinessReport(Long doMinimumStaff, List<UnitReadiness> units) {}

    static final String MAKER = "PAYROLL_MAKER";
    static final String CHECKER = "PAYROLL_CHECKER";

    private final AccessRepositories.Users users;
    private final AccessRepositories.Assignments assignments;
    private final AccessRepositories.GroupScopes scopes;
    private final ScopeResolver scopeResolver;
    private final EmployeeDirectory employees;
    private final OrgDirectory org;
    private final SystemParameters parameters;
    private final AccessPolicy policy;
    private final AdminScope adminScope;
    private final AccessContextService contexts;
    private final Clock clock;

    private final ExportReader exportReader;

    public UserReportService(
            ExportReader exportReader,
            AccessRepositories.Users users,
            AccessRepositories.Assignments assignments,
            AccessRepositories.GroupScopes scopes,
            ScopeResolver scopeResolver,
            EmployeeDirectory employees,
            OrgDirectory org,
            SystemParameters parameters,
            AccessPolicy policy,
            AdminScope adminScope,
            AccessContextService contexts,
            Clock clock) {
        this.exportReader = exportReader;
        this.users = users;
        this.assignments = assignments;
        this.scopes = scopes;
        this.scopeResolver = scopeResolver;
        this.employees = employees;
        this.org = org;
        this.parameters = parameters;
        this.policy = policy;
        this.adminScope = adminScope;
        this.contexts = contexts;
        this.clock = clock;
    }

    /** F-3 (SRS-G-50): one page of users in the administrator's scope, inactive ones flagged. */
    @Transactional(readOnly = true)
    public ActiveUserReport activeUsers(
            AccessContext context, String text, boolean inactiveOnly, int page, int size) {
        policy.require(context, "A-01", "VIEW");
        OptionalLong threshold = parameters.configured(SystemParameters.Code.INACTIVE_USER_DAYS);
        Instant now = clock.instant();
        Page<AppUser> found =
                inScope(
                        context,
                        like(text),
                        cutoff(inactiveOnly, threshold, now),
                        PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100)));
        return new ActiveUserReport(
                threshold.isPresent() ? threshold.getAsLong() : null,
                rows(found.getContent(), threshold, now),
                found.getNumber(),
                found.getSize(),
                found.getTotalElements());
    }

    /** Rows the F-3 export would write (the size check before streaming). */
    @Transactional(readOnly = true)
    public long exportSize(AccessContext context, String text, boolean inactiveOnly) {
        policy.require(context, "A-01", "EXPORT");
        OptionalLong threshold = parameters.configured(SystemParameters.Code.INACTIVE_USER_DAYS);
        Instant before = cutoff(inactiveOnly, threshold, clock.instant());
        String like = like(text);
        return exportReader.count(page -> inScope(context, like, before, page));
    }

    /**
     * F-3 as a list export (SRS-G-50, SRS-G-13): every user of the filter in scope, uncapped, by
     * login ID as on screen, one row per user (the access groups in one cell), in one read-only
     * snapshot whose count the rows written are checked against. The download log of SRS-22-29
     * (report_export_log) comes with the reports module (15).
     */
    @Transactional(readOnly = true, isolation = Isolation.SERIALIZABLE)
    public void export(AccessContext context, String text, boolean inactiveOnly, ExportSink sink)
            throws IOException {
        policy.require(context, "A-01", "EXPORT");
        OptionalLong threshold = parameters.configured(SystemParameters.Code.INACTIVE_USER_DAYS);
        Instant now = clock.instant();
        Instant before = cutoff(inactiveOnly, threshold, now);
        String like = like(text);
        sink.header(
                "Login ID",
                "Name",
                "Status",
                "Last sign-in",
                "Days since sign-in",
                "Inactive",
                "Access (group / role / unit)");
        long expected =
                exportReader.readAll(
                        page -> inScope(context, like, before, page),
                        users -> {
                            for (ActiveUserRow row : rows(users, threshold, now)) {
                                sink.row(
                                        row.loginId(),
                                        row.displayName(),
                                        row.status(),
                                        row.lastLoginAt(),
                                        row.daysSinceLogin(),
                                        row.inactive() == null ? null : row.inactive() ? "Y" : "N",
                                        row.access().stream()
                                                .map(
                                                        a ->
                                                                a.groupCode()
                                                                        + " / "
                                                                        + a.roleCode()
                                                                        + " / "
                                                                        + (a.unit() == null
                                                                                ? ""
                                                                                : a.unit()))
                                                .collect(Collectors.joining("; ")));
                            }
                        });
        ExportSink.requireComplete(expected, sink);
    }

    /** The report rows of these users: access today, last sign-in, inactivity. */
    private List<ActiveUserRow> rows(List<AppUser> shown, OptionalLong threshold, Instant now) {
        Map<Long, List<ActiveGroup>> access =
                scopeResolver.activeGroupsOf(
                        shown.stream().map(AppUser::getId).toList(), contexts.today());
        Map<Long, String> unitOfGroup = unitLabels(access);
        return shown.stream()
                .map(
                        u ->
                                new ActiveUserRow(
                                        u.getId(),
                                        u.getLoginId(),
                                        u.getDisplayName(),
                                        u.getStatus().name(),
                                        u.getLastLoginAt(),
                                        UserGovernance.daysSince(u.getLastLoginAt(), now),
                                        UserGovernance.inactive(
                                                        u.getLastLoginAt(),
                                                        u.getCreatedAt(),
                                                        threshold,
                                                        now)
                                                .orElse(null),
                                        access.getOrDefault(u.getId(), List.of()).stream()
                                                .map(
                                                        g ->
                                                                new AccessRow(
                                                                        g.code(),
                                                                        g.roleCode(),
                                                                        unitOfGroup.get(g.id())))
                                                .toList()))
                .toList();
    }

    /** Inactive only: the cut-off goes into the query (none while the threshold is unset). */
    private static Instant cutoff(boolean inactiveOnly, OptionalLong threshold, Instant now) {
        return inactiveOnly && threshold.isPresent()
                ? now.minus(java.time.Duration.ofDays(threshold.getAsLong()))
                : null;
    }

    private static String like(String text) {
        return text == null || text.isBlank()
                ? null
                : "%" + Likes.escape(text.strip().toLowerCase(Locale.ROOT)) + "%";
    }

    /**
     * F-7 (SRS-G-50, SRS-22-11): the readiness of every payroll unit in the administrator's scope.
     */
    @Transactional(readOnly = true)
    public ReadinessReport readiness(AccessContext context) {
        policy.require(context, "A-01", "VIEW");
        LocalDate today = contexts.today();
        OptionalLong minimum = parameters.configured(SystemParameters.Code.DO_MINIMUM_STAFF);
        // Only users holding an open assignment can be a unit's Maker or Checker.
        List<AppUser> active =
                users.findAllById(assignments.findUserIdsWithOpenAssignments()).stream()
                        .filter(u -> u.getStatus() == AppUser.Status.ACTIVE)
                        .toList();
        Map<Long, String> loginById =
                active.stream().collect(Collectors.toMap(AppUser::getId, AppUser::getLoginId));
        Map<Long, List<ActiveGroup>> access =
                scopeResolver.activeGroupsOf(loginById.keySet(), today);
        Map<Long, Set<Long>> ownLocationsOfGroup = ownLocations(access);
        Set<Long> visible = adminScope.isNational(context) ? null : context.dataScope();
        List<UnitReadiness> units = new ArrayList<>();
        for (OrgLocation unit : cycleUnits(today)) {
            if (visible != null && !visible.contains(unit.getId())) {
                continue;
            }
            Set<Long> ownMakers = new HashSet<>();
            Set<Long> ownCheckers = new HashSet<>();
            Set<Long> allMakers = new HashSet<>();
            Set<Long> allCheckers = new HashSet<>();
            access.forEach(
                    (userId, groups) -> {
                        for (ActiveGroup g : groups) {
                            if (!g.coverage().contains(unit.getId())) {
                                continue;
                            }
                            boolean own =
                                    ownLocationsOfGroup
                                            .getOrDefault(g.id(), Set.of())
                                            .contains(unit.getId());
                            if (MAKER.equals(g.roleCode())) {
                                allMakers.add(userId);
                                if (own) {
                                    ownMakers.add(userId);
                                }
                            } else if (CHECKER.equals(g.roleCode())) {
                                allCheckers.add(userId);
                                if (own) {
                                    ownCheckers.add(userId);
                                }
                            }
                        }
                    });
            Set<Long> ownStaff = new HashSet<>(ownMakers);
            ownStaff.addAll(ownCheckers);
            units.add(
                    new UnitReadiness(
                            unit.getId(),
                            unit.getCode(),
                            unit.displayLabel(),
                            unit.getType().name(),
                            UserGovernance.readiness(ownMakers, ownCheckers, allMakers, allCheckers)
                                    .name(),
                            logins(allMakers, loginById),
                            logins(allCheckers, loginById),
                            ownStaff.size(),
                            unit.getType() == LocationType.DO
                                    ? UserGovernance.meetsMinimum(ownStaff.size(), minimum)
                                            .orElse(null)
                                    : null));
        }
        return new ReadinessReport(minimum.isPresent() ? minimum.getAsLong() : null, units);
    }

    // ---------------------------------------------------------------- helpers

    /** One page of the users the administrator may see (a zone administrator: its offices only). */
    private Page<AppUser> inScope(
            AccessContext context, String like, Instant before, Pageable request) {
        if (adminScope.isNational(context)) {
            return users.report(like, before, request);
        }
        // The scope is resolved in the query (EXISTS on the member's office), never the ids of
        // every employee in it bound into IN (deep profile 2026-10-27; Oracle: at most 1,000).
        return context.dataScope().isEmpty()
                ? Page.empty(request)
                : users.reportAmong(like, before, context.dataScope(), request);
    }

    /** The payroll units: active locations marked as cycle units, HQ first, then by code. */
    private List<OrgLocation> cycleUnits(LocalDate today) {
        return org.snapshot().locations().values().stream()
                .filter(OrgLocation::isCycleUnit)
                .filter(l -> l.isActiveOn(today))
                .sorted(
                        Comparator.comparing((OrgLocation l) -> l.getType().ordinal())
                                .thenComparing(OrgLocation::getCode))
                .toList();
    }

    /** The offices each group names directly (its LOCATION scope rows): a unit's "own" staff. */
    private Map<Long, Set<Long>> ownLocations(Map<Long, List<ActiveGroup>> access) {
        Set<Long> groupIds = new HashSet<>();
        access.values().forEach(groups -> groups.forEach(g -> groupIds.add(g.id())));
        return scopes.findByAccessGroupIdIn(groupIds).stream()
                .filter(row -> row.getLocationId() != null)
                .collect(
                        Collectors.groupingBy(
                                AccessGroupScope::getAccessGroupId,
                                Collectors.mapping(
                                        AccessGroupScope::getLocationId, Collectors.toSet())));
    }

    /** The unit column: "All offices", the zones, or the offices a group names. */
    private Map<Long, String> unitLabels(Map<Long, List<ActiveGroup>> access) {
        Map<Long, ActiveGroup> groups = new java.util.HashMap<>();
        access.values().forEach(list -> list.forEach(g -> groups.putIfAbsent(g.id(), g)));
        Map<Long, List<AccessGroupScope>> rows =
                scopes.findByAccessGroupIdIn(groups.keySet()).stream()
                        .collect(Collectors.groupingBy(AccessGroupScope::getAccessGroupId));
        Map<Long, String> labels = new java.util.HashMap<>();
        groups.forEach(
                (id, g) -> {
                    if (g.scopeType() == ScopeType.GLOBAL) {
                        labels.put(id, "ALL");
                        return;
                    }
                    List<String> names = new ArrayList<>();
                    for (AccessGroupScope row : rows.getOrDefault(id, List.of())) {
                        if (row.getLocationId() != null) {
                            org.location(row.getLocationId())
                                    .ifPresent(l -> names.add(l.displayLabel()));
                        } else if (row.getZoneId() != null) {
                            org.zone(row.getZoneId()).ifPresent(z -> names.add(z.getName()));
                        }
                    }
                    labels.put(id, String.join(", ", names));
                });
        return labels;
    }

    private static List<String> logins(Set<Long> ids, Map<Long, String> loginById) {
        return ids.stream().map(loginById::get).sorted().toList();
    }
}
