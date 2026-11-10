package in.gov.fci.payroll.iam.orchestration;

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
import in.gov.fci.payroll.platform.approval.ActionType;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import in.gov.fci.payroll.platform.approval.ApprovalStatuses;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.OtpProof;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.AccessMode;
import in.gov.fci.payroll.platform.security.ScopeType;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A-03 Access Groups and A-04 Access Assignments. Effective authority = role ∩ group scope, so both
 * are maker-checker: a new group or assignment is INERT until a Payroll Checker approves it (with
 * the OTP the approval workflow action names: ROLE_ACCESS_GROUP_CHANGE). Nobody assigns access to
 * themselves or approves an assignment of their own. Revocation is immediate.
 */
@Service
@Loggable
public class AccessAdminService {

    /** A group code: 3 to 40 characters, access_group.group_code VARCHAR2(40) (target DDL v4.0). */
    private static final Pattern GROUP_CODE = Pattern.compile("^[A-Z0-9][A-Z0-9-]{2,39}$");

    public record ScopeRow(String type, Long id, String code, String label) {}

    public record GroupView(
            Long id,
            String code,
            String name,
            String roleCode,
            String scopeType,
            boolean consolidated,
            AccessMode accessMode,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String approvalStatus,
            Long approvalId,
            List<ScopeRow> scopes,
            boolean assignable) {}

    /** A scope row to create: exactly one of zone or location (none for GLOBAL). */
    public record ScopeInput(Long zoneId, Long locationId) {}

    public record NewGroup(
            String code,
            String name,
            Long roleId,
            ScopeType scopeType,
            boolean consolidated,
            AccessMode accessMode,
            LocalDate effectiveFrom,
            List<ScopeInput> scopes,
            String reason) {}

    public record AssignmentView(
            Long id,
            Long userId,
            String loginId,
            Long groupId,
            String groupCode,
            String roleCode,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String assignedBy,
            String approvalStatus,
            Long approvalId,
            Instant revokedAt,
            String revokeCause,
            String revokeReason,
            boolean current) {}

    public record NewAssignment(
            Long userId,
            Long groupId,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String reason) {}

    public record PageView<T>(List<T> items, int page, int size, long total) {}

    /** The MIS screen whose VIEW a consolidated-view group's role must hold (SRS-22-3). */
    static final String MIS_SCREEN = "R-01";

    private final AccessRepositories.Permissions permissions;
    private final AccessRepositories.RolePermissions rolePermissions;
    private final AccessRepositories.Groups groups;
    private final AccessRepositories.GroupScopes scopes;
    private final AccessRepositories.Roles roles;
    private final AccessRepositories.Users users;
    private final AccessRepositories.Assignments assignments;
    private final ApprovalStatuses approvalItems;
    private final MakerChecker approvals;
    private final OrgDirectory org;
    private final AccessContextService contexts;
    private final AccessPolicy policy;
    private final AuditService audit;
    private final Clock clock;
    private final AdminScope adminScope;
    private final SessionService sessions;
    private final EmployeeDirectory employees;

    public AccessAdminService(
            AccessRepositories.Permissions permissions,
            AccessRepositories.RolePermissions rolePermissions,
            AccessRepositories.Groups groups,
            AccessRepositories.GroupScopes scopes,
            AccessRepositories.Roles roles,
            AccessRepositories.Users users,
            AccessRepositories.Assignments assignments,
            ApprovalStatuses approvalItems,
            MakerChecker approvals,
            OrgDirectory org,
            AccessContextService contexts,
            AccessPolicy policy,
            AuditService audit,
            Clock clock,
            AdminScope adminScope,
            SessionService sessions,
            EmployeeDirectory employees) {
        this.adminScope = adminScope;
        this.sessions = sessions;
        this.employees = employees;
        this.permissions = permissions;
        this.rolePermissions = rolePermissions;
        this.groups = groups;
        this.scopes = scopes;
        this.roles = roles;
        this.users = users;
        this.assignments = assignments;
        this.approvalItems = approvalItems;
        this.approvals = approvals;
        this.org = org;
        this.contexts = contexts;
        this.policy = policy;
        this.audit = audit;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- A-03 Access Groups

    @Transactional(readOnly = true)
    public List<GroupView> groups(AccessContext context) {
        policy.require(context, "A-03", "VIEW");
        List<AccessGroup> all = groups.findAllByOrderByCode();
        Map<Long, List<AccessGroupScope>> rows =
                scopes.findByAccessGroupIdIn(all.stream().map(AccessGroup::getId).toList()).stream()
                        .collect(Collectors.groupingBy(AccessGroupScope::getAccessGroupId));
        Map<Long, AppRole> roleById =
                roles.findAllByOrderByCode().stream()
                        .collect(Collectors.toMap(AppRole::getId, Function.identity()));
        Map<Long, ApprovalStatus> items =
                approvalsOf(all.stream().map(AccessGroup::getApprovalId).toList());
        return all.stream()
                .map(
                        g ->
                                view(
                                        context,
                                        g,
                                        roleById.get(g.getRoleId()),
                                        rows.getOrDefault(g.getId(), List.of()),
                                        items.get(g.getApprovalId())))
                .toList();
    }

    @Transactional
    public GroupView createGroup(AccessContext context, NewGroup request, OtpProof otp) {
        // SRS-22-19 / 22-23: access-group definitions are maintained at Headquarters only.
        adminScope.requireNational(context, "access_group");
        String code = request.code() == null ? "" : request.code().strip().toUpperCase(Locale.ROOT);
        if (!GROUP_CODE.matcher(code).matches()
                || request.name() == null
                || request.name().isBlank()
                || request.scopeType() == null
                || request.roleId() == null) {
            throw new FciException(CommonErrorCode.VALIDATION_FAILED);
        }
        if (groups.existsByCode(code)) {
            throw new FciException(CommonErrorCode.CONFLICT);
        }
        AppRole role =
                roles.findById(request.roleId())
                        .orElseThrow(() -> new FciException(CommonErrorCode.VALIDATION_FAILED));
        List<ScopeInput> inputs = request.scopes() == null ? List.of() : request.scopes();
        validateScopes(request.scopeType(), request.consolidated(), inputs);
        LocalDate from =
                request.effectiveFrom() == null ? contexts.today() : request.effectiveFrom();
        SubmittedApproval item =
                approvals.submit(
                        context,
                        new Submission(
                                "A-03",
                                "CREATE",
                                "access_group",
                                code,
                                ActionType.CREATE,
                                context.locationId(),
                                "{\"role\":\""
                                        + role.getCode()
                                        + "\",\"scopeType\":\""
                                        + request.scopeType()
                                        + "\"}",
                                from,
                                request.reason()),
                        otp);
        AccessGroup group =
                groups.save(
                        AccessGroup.define(
                                code,
                                request.name().strip(),
                                role.getId(),
                                request.scopeType(),
                                request.consolidated(),
                                request.accessMode(),
                                from,
                                item.id(),
                                item.otpChallengeId()));
        List<AccessGroupScope> rows = new ArrayList<>();
        for (ScopeInput input : inputs) {
            rows.add(
                    scopes.save(
                            AccessGroupScope.of(
                                    group.getId(), input.zoneId(), input.locationId())));
        }
        return view(context, group, role, rows, item.status());
    }

    /** End-dates a group (A-03 EDIT); its assignments stop working from the next day. */
    @Transactional
    public GroupView endGroup(
            AccessContext context, Long groupId, LocalDate lastDay, String reason) {
        policy.require(context, "A-03", "EDIT");
        adminScope.requireNational(context, "access_group");
        AccessGroup group =
                groups.findById(groupId)
                        .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
        LocalDate day = lastDay == null ? contexts.today() : lastDay;
        if (day.isBefore(group.getEffectiveFrom())) {
            throw new FciException(CommonErrorCode.VALIDATION_FAILED);
        }
        group.endOn(day);
        groups.save(group);
        contexts.evictAll();
        audit.record(
                context.loginId(),
                AuditService.Entry.change(
                                "access_group",
                                group.getCode(),
                                "effective_to",
                                null,
                                day.toString(),
                                reason == null || reason.isBlank() ? "Group end-dated" : reason)
                        .module("A-03"));
        return view(
                context,
                group,
                roles.findById(group.getRoleId()).orElse(null),
                scopes.findByAccessGroupIdIn(List.of(groupId)),
                approvalsOf(java.util.Collections.singletonList(group.getApprovalId()))
                        .get(group.getApprovalId()));
    }

    private void validateScopes(ScopeType type, boolean consolidated, List<ScopeInput> inputs) {
        if (consolidated && type != ScopeType.HEADQUARTERS && type != ScopeType.GLOBAL) {
            throw new FciException(ApiErrorCode.INVALID_SCOPE);
        }
        if (type == ScopeType.GLOBAL) {
            if (!inputs.isEmpty()) {
                throw new FciException(ApiErrorCode.INVALID_SCOPE);
            }
            return;
        }
        if (inputs.isEmpty()) {
            throw new FciException(ApiErrorCode.INVALID_SCOPE);
        }
        for (ScopeInput input : inputs) {
            if ((input.zoneId() == null) == (input.locationId() == null)) {
                throw new FciException(ApiErrorCode.INVALID_SCOPE);
            }
            switch (type) {
                case ZONE -> {
                    if (input.zoneId() == null || org.zone(input.zoneId()).isEmpty()) {
                        throw new FciException(ApiErrorCode.INVALID_SCOPE);
                    }
                }
                default -> {
                    Optional<OrgLocation> location =
                            input.locationId() == null
                                    ? Optional.empty()
                                    : org.location(input.locationId());
                    if (location.isEmpty()
                            || !location.get().getType().name().equals(locationTypeFor(type))) {
                        throw new FciException(ApiErrorCode.INVALID_SCOPE);
                    }
                }
            }
        }
    }

    /** The org_location.location_type a scope type's location rows must have. */
    private static String locationTypeFor(ScopeType type) {
        return switch (type) {
            case HEADQUARTERS -> "HQ";
            case REGION -> "RO";
            case DIVISION -> "DO";
            case DEPOT -> "DEPOT";
            default -> "";
        };
    }

    // ---------------------------------------------------------------- A-04 Assignments

    @Transactional(readOnly = true)
    public PageView<AssignmentView> assignments(
            AccessContext context, Long userId, int page, int size) {
        policy.require(context, "A-04", "VIEW");
        PageRequest request = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100));
        Page<AppUserAccessGroup> found;
        if (adminScope.isNational(context)) {
            found =
                    userId == null
                            ? assignments.findAllByOrderByIdDesc(request)
                            : assignments.findByUserIdOrderByIdDesc(userId, request);
        } else {
            // A scoped administrator sees the assignments of its own offices' users only.
            java.util.Set<Long> visible = new java.util.HashSet<>();
            if (!context.dataScope().isEmpty()) {
                users.findAtLocations(context.dataScope()).forEach(u -> visible.add(u.getId()));
            }
            if (userId != null) {
                visible.retainAll(java.util.Set.of(userId));
            }
            found =
                    visible.isEmpty()
                            ? Page.empty(request)
                            : assignments.findByUserIdInOrderByIdDesc(visible, request);
        }
        List<AppUserAccessGroup> rows = found.getContent();
        Map<Long, AppUser> userById =
                byId(
                        users.findAllById(
                                rows.stream().map(AppUserAccessGroup::getUserId).toList()),
                        AppUser::getId);
        Map<Long, AccessGroup> groupById =
                byId(
                        groups.findAllById(
                                rows.stream().map(AppUserAccessGroup::getAccessGroupId).toList()),
                        AccessGroup::getId);
        Map<Long, AppRole> roleById =
                roles.findAllByOrderByCode().stream()
                        .collect(Collectors.toMap(AppRole::getId, Function.identity()));
        Map<Long, ApprovalStatus> items =
                approvalsOf(rows.stream().map(AppUserAccessGroup::getApprovalId).toList());
        LocalDate today = contexts.today();
        return new PageView<>(
                rows.stream()
                        .map(
                                a ->
                                        view(
                                                a,
                                                userById.get(a.getUserId()),
                                                groupById.get(a.getAccessGroupId()),
                                                roleById,
                                                items.get(a.getApprovalId()),
                                                today))
                        .toList(),
                found.getNumber(),
                found.getSize(),
                found.getTotalElements());
    }

    /**
     * Proposes an assignment; it grants nothing until a Payroll Checker approves it with an OTP.
     */
    @Transactional
    public AssignmentView propose(AccessContext context, NewAssignment request, OtpProof otp) {
        if (request.userId() == null || request.groupId() == null) {
            throw new FciException(CommonErrorCode.VALIDATION_FAILED);
        }
        if (request.userId().equals(context.userId())) {
            throw new FciException(ApiErrorCode.SELF_ASSIGNMENT);
        }
        AppUser subject =
                users.findById(request.userId())
                        .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
        // The scope first: an administrator outside the user's offices learns nothing of the
        // account, not even whether it is locked or suspended (SRS-22-2; Wave A review, A-04).
        adminScope.requireUser(context, subject);
        // X-01 User (SRS l.1121): "Must match an active user account" - a locked, suspended or
        // deactivated (separated) account is refused, not only a deactivated one (US-GAP-125).
        if (subject.getStatus() != AppUser.Status.ACTIVE) {
            throw new FciException(ApiErrorCode.INVALID_STATUS_CHANGE);
        }
        AccessGroup group =
                groups.findById(request.groupId())
                        .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
        adminScope.requireGroup(
                context, group, scopes.findByAccessGroupIdIn(List.of(group.getId())));
        LocalDate today = contexts.today();
        boolean groupApproved =
                group.getApprovalId() != null
                        && approvalItems
                                .approved(java.util.List.of(group.getApprovalId()))
                                .contains(group.getApprovalId());
        if (!groupApproved || !group.isActiveOn(today)) {
            throw new FciException(ApiErrorCode.GROUP_NOT_APPROVED);
        }
        // SRS-22-3 / SRS v0.5 line 1105: a Headquarters consolidated-view group is assigned only if
        // its role permits read-only MIS access - read here as VIEW on R-01 Consolidated MIS (the
        // MIS screen; interpretation listed for FCI to confirm, tracker 02.6i).
        if (group.isConsolidatedView() && !permitsReadOnlyMis(group.getRoleId(), today)) {
            throw new FciException(ApiErrorCode.CONSOLIDATED_NEEDS_MIS);
        }
        LocalDate from = request.effectiveFrom() == null ? today : request.effectiveFrom();
        if (from.isBefore(today)
                || (request.effectiveTo() != null && request.effectiveTo().isBefore(from))) {
            throw new FciException(CommonErrorCode.VALIDATION_FAILED);
        }
        SubmittedApproval item =
                approvals.submit(
                        context,
                        new Submission(
                                "A-04",
                                "CREATE",
                                "app_user_access_group",
                                subject.getLoginId() + "|" + group.getCode(),
                                ActionType.CREATE,
                                routingLocation(group),
                                "{\"group\":\"" + group.getCode() + "\"}",
                                from,
                                request.reason()),
                        otp);
        AppUserAccessGroup assignment =
                assignments.save(
                        AppUserAccessGroup.propose(
                                subject.getId(),
                                group.getId(),
                                from,
                                request.effectiveTo(),
                                context.loginId(),
                                item.otpChallengeId(),
                                item.id()));
        Map<Long, AppRole> roleById =
                roles.findAllByOrderByCode().stream()
                        .collect(Collectors.toMap(AppRole::getId, Function.identity()));
        return view(assignment, subject, group, roleById, item.status(), today);
    }

    /**
     * Revokes an assignment at once (A-04 DELETE); the user's sessions lose it on the next call.
     */
    @Transactional
    public AssignmentView revoke(
            AccessContext context,
            Long assignmentId,
            AppUserAccessGroup.RevokeCause cause,
            String reason) {
        policy.require(context, "A-04", "DELETE");
        if (reason == null || reason.isBlank()) {
            throw new FciException(ApiErrorCode.REASON_REQUIRED);
        }
        AppUserAccessGroup assignment =
                assignments
                        .findById(assignmentId)
                        .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
        if (assignment.getUserId().equals(context.userId())) {
            throw new FciException(ApiErrorCode.SELF_ASSIGNMENT);
        }
        if (assignment.getRevokedAt() != null) {
            throw new FciException(ApiErrorCode.INVALID_STATUS_CHANGE);
        }
        AppUser target =
                users.findById(assignment.getUserId())
                        .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
        adminScope.requireUser(context, target);
        assignment.revoke(
                cause == null ? AppUserAccessGroup.RevokeCause.ADMIN : cause,
                reason.strip(),
                clock.instant());
        assignments.save(assignment);
        // SRS-SEC-14: a revocation ends the user's sessions at once (Module 02 D-5).
        sessions.endAllSessions(
                assignment.getUserId(), clock.instant(), AppSession.EndReason.REVOCATION);
        contexts.evictAll();
        AppUser subject = users.findById(assignment.getUserId()).orElse(null);
        AccessGroup group = groups.findById(assignment.getAccessGroupId()).orElse(null);
        audit.record(
                context.loginId(),
                AuditService.Entry.change(
                                "app_user_access_group",
                                (subject == null ? assignment.getUserId() : subject.getLoginId())
                                        + "|"
                                        + (group == null ? "?" : group.getCode()),
                                "revoked_at",
                                null,
                                "REVOKED " + assignment.getRevokeCause(),
                                reason.strip())
                        .module("A-04"));
        Map<Long, AppRole> roleById =
                roles.findAllByOrderByCode().stream()
                        .collect(Collectors.toMap(AppRole::getId, Function.identity()));
        return view(
                assignment,
                subject,
                group,
                roleById,
                approvalsOf(java.util.Collections.singletonList(assignment.getApprovalId()))
                        .get(assignment.getApprovalId()),
                contexts.today());
    }

    /**
     * Where an assignment is checked (SRS-22-23; FRS V0.5 §28 "HQ; ZO for zonal users"; US-GAP-14):
     * the Zonal Office above the group's first location row, else the zonal office of its zone,
     * else Headquarters; never the group's own RO / DO (which RO / DO users count as "zonal users"
     * is CLR-20).
     */
    Long routingLocation(AccessGroup group) {
        List<AccessGroupScope> rows = scopes.findByAccessGroupIdIn(List.of(group.getId()));
        for (AccessGroupScope row : rows) {
            if (row.getLocationId() != null) {
                Optional<OrgLocation> zonal =
                        org.ancestorOrSelf(
                                row.getLocationId(),
                                in.gov.fci.payroll.organisation.business.model.LocationType.ZO);
                if (zonal.isPresent()) {
                    return zonal.get().getId();
                }
                return org.headquarters().map(OrgLocation::getId).orElse(null);
            }
        }
        for (AccessGroupScope row : rows) {
            if (row.getZoneId() != null) {
                Optional<OrgLocation> zonal = org.zonalOffice(row.getZoneId());
                if (zonal.isPresent()) {
                    return zonal.get().getId();
                }
            }
        }
        return org.headquarters().map(OrgLocation::getId).orElse(null);
    }

    /** The role holds VIEW on R-01 Consolidated MIS today. */
    private boolean permitsReadOnlyMis(Long roleId, LocalDate today) {
        return permissions
                .findByScreenCodeAndActionCode(MIS_SCREEN, "VIEW")
                .flatMap(
                        p ->
                                rolePermissions.findById(
                                        new in.gov.fci.payroll.iam.business.model.AppRolePermission
                                                .Key(roleId, p.getId())))
                .filter(rp -> rp.isActiveOn(today))
                .isPresent();
    }

    // ---------------------------------------------------------------- views

    private Map<Long, ApprovalStatus> approvalsOf(List<Long> ids) {
        Set<Long> distinct = new HashSet<>(ids);
        distinct.remove(null);
        return approvalItems.of(distinct);
    }

    private static <T> Map<Long, T> byId(List<T> rows, Function<T, Long> id) {
        Map<Long, T> result = new HashMap<>();
        rows.forEach(r -> result.put(id.apply(r), r));
        return result;
    }

    private GroupView view(
            AccessContext context,
            AccessGroup g,
            AppRole role,
            List<AccessGroupScope> rows,
            ApprovalStatus item) {
        List<ScopeRow> scopeRows = new ArrayList<>();
        for (AccessGroupScope row : rows) {
            if (row.getLocationId() != null) {
                org.location(row.getLocationId())
                        .ifPresent(
                                l ->
                                        scopeRows.add(
                                                new ScopeRow(
                                                        "LOCATION",
                                                        l.getId(),
                                                        l.getCode(),
                                                        l.displayLabel())));
            } else if (row.getZoneId() != null) {
                org.zone(row.getZoneId())
                        .ifPresent(
                                z ->
                                        scopeRows.add(
                                                new ScopeRow(
                                                        "ZONE",
                                                        z.getId(),
                                                        z.getCode(),
                                                        z.getName())));
            }
        }
        return new GroupView(
                g.getId(),
                g.getCode(),
                g.getName(),
                role == null ? null : role.getCode(),
                g.getScopeType().name(),
                g.isConsolidatedView(),
                g.getAccessMode(),
                g.getEffectiveFrom(),
                g.getEffectiveTo(),
                item == null ? "NONE" : item.name(),
                g.getApprovalId(),
                scopeRows,
                adminScope.reaches(context, g, rows));
    }

    private static AssignmentView view(
            AppUserAccessGroup a,
            AppUser user,
            AccessGroup group,
            Map<Long, AppRole> roles,
            ApprovalStatus item,
            LocalDate today) {
        AppRole role = group == null ? null : roles.get(group.getRoleId());
        boolean approved = item != null && item == ApprovalStatus.APPROVED;
        return new AssignmentView(
                a.getId(),
                a.getUserId(),
                user == null ? null : user.getLoginId(),
                a.getAccessGroupId(),
                group == null ? null : group.getCode(),
                role == null ? null : role.getCode(),
                a.getEffectiveFrom(),
                a.getEffectiveTo(),
                a.getAssignedBy(),
                item == null ? "NONE" : item.name(),
                a.getApprovalId(),
                a.getRevokedAt(),
                a.getRevokeCause() == null ? null : a.getRevokeCause().name(),
                a.getRevokeReason(),
                approved && a.isCurrentOn(today));
    }
}
