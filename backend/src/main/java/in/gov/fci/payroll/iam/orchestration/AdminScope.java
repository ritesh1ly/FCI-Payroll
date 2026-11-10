package in.gov.fci.payroll.iam.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.employee.api.EmployeeDirectory;
import in.gov.fci.payroll.iam.business.model.AccessGroup;
import in.gov.fci.payroll.iam.business.model.AccessGroupScope;
import in.gov.fci.payroll.iam.business.model.AppUser;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.ErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.AccessMode;
import in.gov.fci.payroll.platform.security.ScopeType;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The administrative scope of the acting administrator (SRS-22-13, SRS-22-19, SRS-22-23; Module 02
 * decision D-4; OWASP ASVS V4.1.3 / V4.2.1, least privilege and no horizontal escalation):
 *
 * <ul>
 *   <li>a NATIONAL administrator acts at Headquarters or through a GLOBAL access group: every user,
 *       group and role;
 *   <li>an administrator of a zone, region or division acts only on users whose employee record
 *       lies inside the session's data scope, and assigns only access groups whose whole scope lies
 *       inside it; a user without an employee record (system accounts) is national only;
 *   <li>roles, permissions and access-group definitions are national only (Headquarters).
 * </ul>
 *
 * A refusal outside the scope is a security event (horizontal escalation attempt).
 */
@Component
public class AdminScope {

    private final AccessPolicy policy;
    private final EmployeeDirectory employees;
    private final OrgDirectory org;
    private final AuditService audit;

    public AdminScope(
            AccessPolicy policy,
            EmployeeDirectory employees,
            OrgDirectory org,
            AuditService audit) {
        this.policy = policy;
        this.employees = employees;
        this.org = org;
        this.audit = audit;
    }

    /** Sees nationally: acting at Headquarters, or through a GLOBAL group (any access mode). */
    public boolean isNational(AccessContext context) {
        return context.groups().stream().anyMatch(g -> g.scopeType() == ScopeType.GLOBAL)
                || policy.isAtHeadquarters(context);
    }

    /**
     * Changes nationally: acting at Headquarters, or through a GLOBAL group of FULL access.
     * Security review 2026-10-31: a READ_ONLY or REPORTS_ONLY GLOBAL group active beside a regional
     * administrator group made every change national; such a group confers only VIEW and EXPORT, so
     * it widens what is seen, never what is changed.
     */
    public boolean isNationalToChange(AccessContext context) {
        return context.groups().stream()
                        .anyMatch(
                                g ->
                                        g.scopeType() == ScopeType.GLOBAL
                                                && g.accessMode() == AccessMode.FULL)
                || policy.isAtHeadquarters(context);
    }

    /** Roles, permissions, group definitions: Headquarters only. */
    public void requireNational(AccessContext context, String what) {
        if (!isNationalToChange(context)) {
            refuse(context, "national", what, ApiErrorCode.ADMIN_HQ_ONLY);
        }
    }

    /** Whether the administrator may act on {@code user} (change it). */
    public boolean covers(AccessContext context, AppUser user) {
        return covers(context, user, isNationalToChange(context));
    }

    private boolean covers(AccessContext context, AppUser user, boolean national) {
        if (national) {
            return true;
        }
        Long employeeId = user.getEmployeeId();
        return employeeId != null
                && employees
                        .locationOf(employeeId)
                        .map(context.dataScope()::contains)
                        .orElse(false);
    }

    /** The administrator may see {@code user} (a national read-only group sees every user). */
    public void requireUserVisible(AccessContext context, AppUser user) {
        if (!covers(context, user, isNational(context))) {
            refuse(context, "app_user", String.valueOf(user.getId()), CommonErrorCode.NOT_FOUND);
        }
    }

    /** The administrator may change {@code user}. */
    public void requireUser(AccessContext context, AppUser user) {
        if (!covers(context, user)) {
            // A user of another office reads as unknown (404, project owner 2026-10-24).
            refuse(context, "app_user", String.valueOf(user.getId()), CommonErrorCode.NOT_FOUND);
        }
    }

    /** A new user's employee record must lie in scope (system accounts: national only). */
    public void requireNewUser(AccessContext context, Long employeeId) {
        if (isNationalToChange(context)) {
            return;
        }
        boolean inside =
                employeeId != null
                        && employees
                                .locationOf(employeeId)
                                .map(context.dataScope()::contains)
                                .orElse(false);
        if (!inside) {
            refuse(context, "employee", String.valueOf(employeeId), CommonErrorCode.NOT_FOUND);
        }
    }

    /** The whole scope of {@code group} must lie inside the administrator's data scope. */
    public void requireGroup(
            AccessContext context, AccessGroup group, Collection<AccessGroupScope> rows) {
        if (!reaches(context, group, rows)) {
            refuse(context, "access_group", group.getCode(), ApiErrorCode.OUT_OF_SCOPE);
        }
    }

    /**
     * The group lies inside the administrator's reach. A-04 offers only these (OD-130): a click on
     * the screen can never be the OUT_OF_SCOPE refusal, which is an offence.
     */
    public boolean reaches(
            AccessContext context, AccessGroup group, Collection<AccessGroupScope> rows) {
        if (isNationalToChange(context)) {
            return true;
        }
        Set<Long> reach = new HashSet<>();
        boolean national = rows.isEmpty();
        for (AccessGroupScope row : rows) {
            if (row.getZoneId() != null) {
                reach.addAll(org.inZone(row.getZoneId()));
            } else if (row.getLocationId() != null) {
                reach.addAll(org.subtree(row.getLocationId()));
            }
        }
        return !national
                && !group.isConsolidatedView()
                && !reach.isEmpty()
                && context.dataScope().containsAll(reach);
    }

    private void refuse(AccessContext context, String entity, String key, ErrorCode code) {
        audit.securityEvent(
                AuditService.Security.OUT_OF_SCOPE_ACCESS,
                context.loginId(),
                entity,
                key,
                "admin-scope");
        FciException refused = new FciException(code);
        throw code == ApiErrorCode.OUT_OF_SCOPE ? refused.asAccessOffence() : refused;
    }
}
