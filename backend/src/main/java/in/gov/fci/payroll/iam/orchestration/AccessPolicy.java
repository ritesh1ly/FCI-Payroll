package in.gov.fci.payroll.iam.orchestration;

import in.gov.fci.payroll.audit.api.AuditService;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.LocationType;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.ErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.ActiveGroup;
import in.gov.fci.payroll.platform.security.Authorization;
import java.time.Clock;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * THE authorisation policy of the backend: {@code can(user, screen_code, action_code, target)}.
 *
 * <ol>
 *   <li>an active access group of the session grants (screen, action) through its role, effective
 *       today ({@code app_role_permission} is authoritative);
 *   <li>the target lies inside the session's data scope (login location and descendants, or the
 *       zone of a ZONE group; for eSeva, the user's own record);
 *   <li>a Headquarters consolidated-view group confers only VIEW and EXPORT on any Region's data:
 *       no approval or lock rights (SRS-22-3);
 *   <li>a screen on which the acting role holds a sensitive action (approve, or change access or
 *       settings) is reachable only within {@code STEP_UP_VALIDITY} of the user's last OTP
 *       confirmation (SRS-G-48 / SRS-G-21, Module 02 decision D-2): AUTH-403-STEP-UP otherwise.
 * </ol>
 *
 * Access is role and access group only (SRS-22-8): there is no per-user permission layer.
 *
 * <p>Every endpoint applies it server side: screen-level gates through {@code @PreAuthorize(
 * "@authz.can('M-01','VIEW')")}, record-level checks through the {@code require…} methods.
 */
@Component("authz")
public class AccessPolicy implements Authorization {

    /** Actions a consolidated (read-only MIS) view may perform. */
    public static final Set<String> READ_ACTIONS = Set.of("VIEW", "EXPORT");

    private final AccessContextService contexts;
    private final OrgDirectory org;
    private final AuditService audit;
    private final SystemParameters parameters;
    private final Clock clock;

    public AccessPolicy(
            AccessContextService contexts,
            OrgDirectory org,
            AuditService audit,
            SystemParameters parameters,
            Clock clock) {
        this.contexts = contexts;
        this.org = org;
        this.audit = audit;
        this.parameters = parameters;
        this.clock = clock;
    }

    /** Screen-level check: some active group (or the eSeva role) grants the permission. */
    public boolean can(String screen, String action) {
        return can(contexts.current(), screen, action);
    }

    /**
     * The endpoint gate of {@code @PreAuthorize("@authz.gate('M-01','VIEW')")}: true, or the
     * precise 403 (AUTHZ-403-PERMISSION with screen and action) instead of a generic denial.
     */
    public boolean gate(String screen, String action) {
        AccessContext context = contexts.current();
        if (!can(context, screen, action)) {
            throw permissionDenied(screen, action);
        }
        if (context.needsStepUp(screen, clock.instant(), parameters.stepUpValidity())) {
            throw new FciException(
                    ApiErrorCode.STEP_UP_REQUIRED,
                    new Object[] {screen},
                    java.util.List.of(),
                    null);
        }
        return true;
    }

    /**
     * An endpoint open to either of two permissions:
     * {@code @PreAuthorize("@authz.gateEither('M-08','CREATE','M-08','APPROVE')")}. Never write
     * {@code gate(a) or gate(b)}: {@link #gate} throws instead of answering false, so the second
     * permission would never be tried. The first permission held is gated (its step-up included);
     * holding neither is the 403 of the first.
     */
    public boolean gateEither(
            String screen, String action, String otherScreen, String otherAction) {
        if (!can(contexts.current(), screen, action)
                && can(contexts.current(), otherScreen, otherAction)) {
            return gate(otherScreen, otherAction);
        }
        return gate(screen, action);
    }

    @Override
    public boolean can(AccessContext context, String screen, String action) {
        return context.permissionKeys().contains(AccessContext.key(screen, action));
    }

    /** Permission at a location: all three rules of the class comment. */
    @Override
    public boolean canAt(AccessContext context, String screen, String action, Long locationId) {
        return decide(context, screen, action, locationId) == null;
    }

    /** The precise denial of {@code action} on {@code screen} at {@code locationId}, or null. */
    @Override
    public ApiErrorCode denialAt(
            AccessContext context, String screen, String action, Long locationId) {
        return decide(context, screen, action, locationId);
    }

    /**
     * Throws the precise 403 if {@code action} on {@code screen} at {@code locationId} is denied.
     */
    @Override
    public void requireAt(AccessContext context, String screen, String action, Long locationId) {
        enforce(context, screen, action, locationId, null);
    }

    @Override
    public void requireRecordAt(
            AccessContext context,
            String screen,
            String action,
            Long locationId,
            ErrorCode unknown) {
        enforce(context, screen, action, locationId, unknown);
    }

    /** {@code unknown} null: the 403 of an office the user names; else a record's 404. */
    private void enforce(
            AccessContext context,
            String screen,
            String action,
            Long locationId,
            ErrorCode unknown) {
        ApiErrorCode denial = decide(context, screen, action, locationId);
        if (denial == ApiErrorCode.OUT_OF_SCOPE) {
            audit.securityEvent(
                    AuditService.Security.OUT_OF_SCOPE_ACCESS,
                    context.loginId(),
                    "org_location",
                    String.valueOf(locationId),
                    screen + ":" + action);
            FciException refused = new FciException(unknown != null ? unknown : denial);
            // OD-130: a check on the session's own office names nothing the user chose: such a
            // refusal is an access set-up gap (login office outside the group's scope), no offence.
            throw java.util.Objects.equals(locationId, context.locationId())
                    ? refused
                    : refused.asAccessOffence();
        }
        if (denial == ApiErrorCode.PERMISSION_DENIED) {
            throw permissionDenied(screen, action);
        }
        if (denial != null) {
            throw new FciException(denial);
        }
    }

    /** OD-130: a call the role does not hold is an offence, wherever the check ran. */
    private static FciException permissionDenied(String screen, String action) {
        return new FciException(
                        ApiErrorCode.PERMISSION_DENIED,
                        new Object[] {screen, action},
                        java.util.List.of(),
                        null)
                .asAccessOffence();
    }

    /** Screen-level requirement without a location (HQ functions, admin, own record). */
    @Override
    public void require(AccessContext context, String screen, String action) {
        requireAt(context, screen, action, null);
    }

    @Override
    public void recordForeignAttempt(
            AccessContext context, String screen, String action, String entity, Object key) {
        audit.securityEvent(
                AuditService.Security.OUT_OF_SCOPE_ACCESS,
                context.loginId(),
                entity,
                String.valueOf(key),
                screen + ":" + action);
    }

    /** Record-level check on an employee: own record for eSeva, data scope otherwise. */
    @Override
    public void requireEmployeeRecord(
            AccessContext context, String screen, String action, Long employeeId, Long locationId) {
        if (context.directPermissions().contains(AccessContext.key(screen, action))
                && employeeId.equals(context.employeeId())) {
            return;
        }
        if (context.groups().isEmpty()) {
            if (context.directPermissions().contains(AccessContext.key(screen, action))) {
                // eSeva: another member's record does not exist for this member. OD-130: asking
                // for it is an offence all the same; the answer stays this 404.
                throw new FciException(CommonErrorCode.NOT_FOUND).asAccessOffence();
            }
            throw permissionDenied(screen, action);
        }
        if (locationId == null && !isAtHeadquarters(context)) {
            // Security review 2026-10-31: an employee without an office (an incomplete HRMS record)
            // is in no office's scope, so it is not every office's: Headquarters only.
            audit.securityEvent(
                    AuditService.Security.OUT_OF_SCOPE_ACCESS,
                    context.loginId(),
                    "employee",
                    String.valueOf(employeeId),
                    screen + ":" + action);
            throw new FciException(CommonErrorCode.NOT_FOUND).asAccessOffence();
        }
        requireRecordAt(context, screen, action, locationId);
    }

    private ApiErrorCode decide(
            AccessContext context, String screen, String action, Long locationId) {
        String key = AccessContext.key(screen, action);
        boolean held = false;
        boolean onlyReadOnly = false;
        for (ActiveGroup group : context.groups()) {
            if (!context.groupPermissions().getOrDefault(group.id(), Set.of()).contains(key)) {
                continue;
            }
            held = true;
            if (group.consolidated()
                    && !READ_ACTIONS.contains(action)
                    && isRegionData(locationId)) {
                onlyReadOnly = true;
                continue;
            }
            if (locationId == null || context.dataScope().contains(locationId)) {
                return null;
            }
        }
        if (!held) {
            if (context.directPermissions().contains(key) && locationId == null) {
                return null;
            }
            return ApiErrorCode.PERMISSION_DENIED;
        }
        return onlyReadOnly ? ApiErrorCode.READ_ONLY_CONSOLIDATED : ApiErrorCode.OUT_OF_SCOPE;
    }

    /** Data of a Region or below (anything that is not the Headquarters office itself). */
    private boolean isRegionData(Long locationId) {
        return locationId != null
                && org.location(locationId).map(l -> l.getType() != LocationType.HQ).orElse(true);
    }

    /** The session's login location is the Headquarters office. */
    @Override
    public boolean isAtHeadquarters(AccessContext context) {
        return org.location(context.locationId())
                .map(l -> l.getType() == LocationType.HQ)
                .orElse(false);
    }
}
