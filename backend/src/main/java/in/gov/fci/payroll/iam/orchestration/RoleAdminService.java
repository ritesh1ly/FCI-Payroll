package in.gov.fci.payroll.iam.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.iam.business.ScreenAccessLetter;
import in.gov.fci.payroll.iam.business.model.AppPermission;
import in.gov.fci.payroll.iam.business.model.AppRole;
import in.gov.fci.payroll.iam.business.model.AppRolePermission;
import in.gov.fci.payroll.iam.persistence.AccessRepositories;
import in.gov.fci.payroll.platform.approval.ActionType;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.MakerChecker;
import in.gov.fci.payroll.platform.approval.OtpProof;
import in.gov.fci.payroll.platform.approval.Submission;
import in.gov.fci.payroll.platform.approval.SubmittedApproval;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.Loggable;
import in.gov.fci.payroll.platform.security.AccessContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A-02 Roles and the permission matrix ({@code app_role}, {@code app_permission}, {@code
 * app_role_permission}). Every change is a proposal a Payroll Checker approves with an OTP
 * (SRS-22-8, SRS-22-23), maintained at Headquarters only (SRS-22-19). Grants are effective-dated: a
 * revocation end-dates the row, it is never deleted. The System Administrator role is protected, so
 * nobody can widen the administrator's own rights (or remove the last way to administer the
 * system).
 */
@Service
@Loggable
public class RoleAdminService {

    public static final Set<String> PROTECTED_ROLES = Set.of("SYSTEM_ADMINISTRATOR");

    private static final Pattern ROLE_CODE = Pattern.compile("^[A-Z][A-Z0-9_]{2,29}$");

    public record RoleView(
            Long id,
            String code,
            String name,
            String description,
            String status,
            boolean approver,
            String roleType,
            String baseRole,
            boolean protectedRole) {}

    public record PermissionView(Long id, String screenCode, String actionCode, String name) {}

    /** The matrix: every role, every permission and the grants effective today. */
    public record Matrix(
            List<RoleView> roles, List<PermissionView> permissions, List<Grant> grants) {}

    public record Grant(Long roleId, Long permissionId) {}

    private final AccessRepositories.Roles roles;
    private final AccessRepositories.Permissions permissions;
    private final AccessRepositories.RolePermissions grants;
    private final AccessContextService contexts;
    private final AccessPolicy policy;
    private final AuditService audit;
    private final MakerChecker approvals;
    private final AdminScope adminScope;
    private final ObjectMapper mapper;

    public RoleAdminService(
            AccessRepositories.Roles roles,
            AccessRepositories.Permissions permissions,
            AccessRepositories.RolePermissions grants,
            AccessContextService contexts,
            AccessPolicy policy,
            AuditService audit,
            MakerChecker approvals,
            AdminScope adminScope,
            ObjectMapper mapper) {
        this.approvals = approvals;
        this.adminScope = adminScope;
        this.mapper = mapper;
        this.roles = roles;
        this.permissions = permissions;
        this.grants = grants;
        this.contexts = contexts;
        this.policy = policy;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public Matrix matrix(AccessContext context) {
        policy.require(context, "A-02", "VIEW");
        LocalDate today = contexts.today();
        List<Grant> active = new ArrayList<>();
        for (AppRolePermission grant : grants.findAll()) {
            if (grant.isActiveOn(today)) {
                active.add(new Grant(grant.getRoleId(), grant.getPermissionId()));
            }
        }
        return new Matrix(
                roles.findAllByOrderByCode().stream().map(RoleAdminService::view).toList(),
                permissions.findAllByOrderByScreenCodeAscActionCodeAsc().stream()
                        .map(
                                p ->
                                        new PermissionView(
                                                p.getId(),
                                                p.getScreenCode(),
                                                p.getActionCode(),
                                                p.getName()))
                        .toList(),
                active);
    }

    /**
     * One screen of the X-01 Screen-Level Access Matrix: role code to its letter ({@link
     * ScreenAccessLetter}); a role with no access has no entry.
     */
    public record ScreenAccess(String screenCode, Map<String, String> access) {}

    /**
     * The X-01 Screen-Level Access Matrix (SRS-22-8, the X-01 screen design): the grants in force
     * per screen and role, read-only. A viewer of X-01 reads it with A-08 VIEW, which opens neither
     * A-02 nor any change: a change stays an A-02 proposal.
     */
    @Transactional(readOnly = true)
    public List<ScreenAccess> screenAccess(AccessContext context) {
        policy.require(context, "A-08", "VIEW");
        LocalDate today = contexts.today();
        Map<Long, String> roleCodes = new HashMap<>();
        roles.findAllByOrderByCode().forEach(r -> roleCodes.put(r.getId(), r.getCode()));
        Map<Long, AppPermission> byId = new HashMap<>();
        // Every screen has a row, also one no role holds yet; in code order.
        Map<String, Map<String, Set<String>>> screens = new TreeMap<>();
        for (AppPermission p : permissions.findAllByOrderByScreenCodeAscActionCodeAsc()) {
            byId.put(p.getId(), p);
            screens.computeIfAbsent(p.getScreenCode(), s -> new TreeMap<>());
        }
        for (AppRolePermission grant : grants.findAll()) {
            AppPermission permission = byId.get(grant.getPermissionId());
            String role = roleCodes.get(grant.getRoleId());
            if (grant.isActiveOn(today) && permission != null && role != null) {
                screens.get(permission.getScreenCode())
                        .computeIfAbsent(role, r -> new HashSet<>())
                        .add(permission.getActionCode());
            }
        }
        List<ScreenAccess> rows = new ArrayList<>();
        screens.forEach(
                (screen, byRole) -> {
                    Map<String, String> letters = new TreeMap<>();
                    byRole.forEach(
                            (role, actions) ->
                                    ScreenAccessLetter.of(actions)
                                            .ifPresent(letter -> letters.put(role, letter)));
                    rows.add(new ScreenAccess(screen, letters));
                });
        return rows;
    }

    /** A proposal sent for checking: nothing changes until a Payroll Checker approves it. */
    public record Proposal(Long approvalId, String status) {}

    /**
     * Proposes a new role (A-02 CREATE). SRS-22-8 / SRS-22-23: a Payroll Checker approves every
     * role definition with an OTP; the role exists only after that approval (OWASP ASVS V1.4.1 /
     * V4.1.3: no single administrator widens access alone). Headquarters only (SRS-22-19).
     */
    @Transactional
    public Proposal proposeRole(
            AccessContext context,
            String code,
            String name,
            String description,
            boolean approver,
            AppRole.RoleType roleType,
            AppRole.BaseRole baseRole,
            String reason,
            OtpProof otp) {
        policy.require(context, "A-02", "CREATE");
        adminScope.requireNational(context, ROLE);
        String normalised = code == null ? "" : code.strip().toUpperCase(Locale.ROOT);
        AppRole.RoleType type = roleType == null ? AppRole.RoleType.MODULE : roleType;
        if (!ROLE_CODE.matcher(normalised).matches()
                || name == null
                || name.isBlank()
                || (type != AppRole.RoleType.READ_ONLY && baseRole == null)) {
            throw new FciException(CommonErrorCode.VALIDATION_FAILED);
        }
        if (reason == null || reason.isBlank()) {
            throw new FciException(ApiErrorCode.REASON_REQUIRED);
        }
        if (roles.findByCode(normalised).isPresent()) {
            throw new FciException(CommonErrorCode.CONFLICT);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", normalised);
        payload.put("name", name.strip());
        payload.put("description", description);
        payload.put("approver", approver);
        payload.put("roleType", type.name());
        payload.put(
                "baseRole",
                type == AppRole.RoleType.READ_ONLY || baseRole == null ? null : baseRole.name());
        SubmittedApproval item =
                approvals.submit(
                        context,
                        new Submission(
                                "A-02",
                                "CREATE",
                                ROLE,
                                normalised,
                                ActionType.CREATE,
                                context.locationId(),
                                mapper.writeValueAsString(payload),
                                contexts.today(),
                                reason.strip()),
                        otp);
        return new Proposal(item.id(), item.status().name());
    }

    /**
     * Proposes granting or withdrawing one (role, permission) (A-02 EDIT), under maker-checker with
     * an OTP (SRS-22-8 / SRS-22-23): an administrator can never give a role a right on their own,
     * which closes the path "grant APPROVE to a role, then use it". The protected System
     * Administrator role cannot be changed at all. Headquarters only.
     */
    @Transactional
    public Proposal proposeGrant(
            AccessContext context,
            Long roleId,
            Long permissionId,
            boolean granted,
            String reason,
            OtpProof otp) {
        policy.require(context, "A-02", "EDIT");
        adminScope.requireNational(context, GRANT);
        if (reason == null || reason.isBlank()) {
            throw new FciException(ApiErrorCode.REASON_REQUIRED);
        }
        AppRole role =
                roles.findById(roleId)
                        .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
        if (PROTECTED_ROLES.contains(role.getCode())) {
            throw new FciException(ApiErrorCode.PROTECTED_ROLE);
        }
        AppPermission permission = permission(permissionId);
        AppRolePermission current =
                grants.findById(new AppRolePermission.Key(roleId, permissionId)).orElse(null);
        if ((current != null && current.isActiveOn(contexts.today())) == granted) {
            throw new FciException(ApiErrorCode.INVALID_STATUS_CHANGE);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("roleId", roleId);
        payload.put("permissionId", permissionId);
        payload.put("granted", granted);
        SubmittedApproval item =
                approvals.submit(
                        context,
                        new Submission(
                                "A-02",
                                "EDIT",
                                GRANT,
                                role.getCode() + "|" + permission.key(),
                                ActionType.UPDATE,
                                context.locationId(),
                                mapper.writeValueAsString(payload),
                                contexts.today(),
                                reason.strip()),
                        otp);
        return new Proposal(item.id(), item.status().name());
    }

    /**
     * Applies an APPROVED A-02 proposal in the decision's transaction (a failure rolls the decision
     * back). The state is re-checked at approval time: a grant already in force is not applied
     * twice, and a protected role is never changed.
     */
    @EventListener
    public void on(ApprovalDecided decision) {
        if (!decision.approved() || decision.payload() == null) {
            return;
        }
        if (ROLE.equals(decision.entityName())) {
            applyRole(decision);
        } else if (GRANT.equals(decision.entityName())) {
            applyGrant(decision);
        }
    }

    private void applyRole(ApprovalDecided decision) {
        JsonNode p = read(decision.payload());
        String code = p.path("code").asString();
        if (roles.findByCode(code).isPresent()) {
            throw new FciException(CommonErrorCode.CONFLICT);
        }
        AppRole.RoleType type = AppRole.RoleType.valueOf(p.path("roleType").asString());
        JsonNode base = p.path("baseRole");
        JsonNode description = p.path("description");
        roles.save(
                AppRole.create(
                        code,
                        p.path("name").asString(),
                        description.isString() ? description.asString() : null,
                        p.path("approver").asBoolean(false),
                        type,
                        base.isString() ? AppRole.BaseRole.valueOf(base.asString()) : null,
                        contexts.today()));
        audit.record(
                decision.checkerLoginId(),
                AuditService.Entry.change(
                                ROLE, code, "CREATE", null, "PROVISIONAL", "Role approved")
                        .module("A-02"));
    }

    private void applyGrant(ApprovalDecided decision) {
        JsonNode p = read(decision.payload());
        Long roleId = p.path("roleId").asLong();
        Long permissionId = p.path("permissionId").asLong();
        boolean granted = p.path("granted").asBoolean();
        AppRole role =
                roles.findById(roleId)
                        .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
        if (PROTECTED_ROLES.contains(role.getCode())) {
            throw new FciException(ApiErrorCode.PROTECTED_ROLE);
        }
        AppPermission permission = permission(permissionId);
        LocalDate today = contexts.today();
        AppRolePermission.Key key = new AppRolePermission.Key(roleId, permissionId);
        AppRolePermission grant = grants.findById(key).orElse(null);
        boolean before = grant != null && grant.isActiveOn(today);
        if (before == granted) {
            return;
        }
        if (granted) {
            if (grant == null) {
                grant = new AppRolePermission(roleId, permissionId, today);
            } else {
                grant.grantFrom(today);
            }
        } else {
            grant.endBefore(today);
        }
        grants.save(grant);
        contexts.evictAll();
        audit.record(
                decision.checkerLoginId(),
                AuditService.Entry.change(
                                GRANT,
                                role.getCode() + "|" + permission.key(),
                                "granted",
                                String.valueOf(before),
                                String.valueOf(granted),
                                "Permission matrix change approved")
                        .module("A-02"));
    }

    private AppPermission permission(Long permissionId) {
        return permissions.findAllByOrderByScreenCodeAscActionCodeAsc().stream()
                .filter(x -> x.getId().equals(permissionId))
                .findFirst()
                .orElseThrow(() -> new FciException(CommonErrorCode.NOT_FOUND));
    }

    private JsonNode read(String payload) {
        try {
            return mapper.readTree(payload);
        } catch (JacksonException e) {
            throw new IllegalStateException(e);
        }
    }

    static final String ROLE = "app_role";
    static final String GRANT = "app_role_permission";

    static RoleView view(AppRole r) {
        return new RoleView(
                r.getId(),
                r.getCode(),
                r.getName(),
                r.getDescription(),
                r.getStatus().name(),
                r.isApprover(),
                r.getRoleType() == null ? null : r.getRoleType().name(),
                r.getBaseRole() == null ? null : r.getBaseRole().name(),
                PROTECTED_ROLES.contains(r.getCode()));
    }
}
