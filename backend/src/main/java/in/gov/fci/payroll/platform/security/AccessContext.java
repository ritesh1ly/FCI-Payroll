package in.gov.fci.payroll.platform.security;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The active scope of one signed-in session, rebuilt from {@code app_session} and the user's
 * CURRENT assignments (so a revocation takes effect on the next request).
 *
 * @param locationId the login location (null for eSeva)
 * @param employeeId the user's own employee record (eSeva scope)
 * @param groups the session's active access groups that are still valid today
 * @param groupPermissions {@code SCREEN:ACTION} keys granted by each group's role, effective today
 * @param directPermissions keys of a directly granted role (eSeva), scoped to {@code employeeId}
 * @param dataScope locations whose data this session may see
 * @param sensitiveScreens screens on which the acting role holds a sensitive action (approve, or
 *     change access or settings): reading or acting on them needs a recent OTP step-up (SRS-G-48 /
 *     SRS-G-21, Module 02 decision D-2)
 * @param stepUpAt when the user last confirmed this session with an OTP, or null
 *     <p>Access is role and access group only (SRS-22-8): there is no per-user permission layer.
 */
public record AccessContext(
        Long userId,
        String loginId,
        String displayName,
        String sessionId,
        String personaCode,
        Long locationId,
        Long employeeId,
        List<ActiveGroup> groups,
        Map<Long, Set<String>> groupPermissions,
        Set<String> directPermissions,
        Set<String> directRoleCodes,
        Set<Long> dataScope,
        Set<String> sensitiveScreens,
        Instant stepUpAt) {

    /** A context that needs no step-up. */
    public AccessContext(
            Long userId,
            String loginId,
            String displayName,
            String sessionId,
            String personaCode,
            Long locationId,
            Long employeeId,
            List<ActiveGroup> groups,
            Map<Long, Set<String>> groupPermissions,
            Set<String> directPermissions,
            Set<String> directRoleCodes,
            Set<Long> dataScope) {
        this(
                userId,
                loginId,
                displayName,
                sessionId,
                personaCode,
                locationId,
                employeeId,
                groups,
                groupPermissions,
                directPermissions,
                directRoleCodes,
                dataScope,
                Set.of(),
                null);
    }

    public static String key(String screen, String action) {
        return screen + ":" + action;
    }

    /** Role codes active in this session. */
    public Set<String> roleCodes() {
        Set<String> codes = new HashSet<>(directRoleCodes);
        groups.forEach(g -> codes.add(g.roleCode()));
        return codes;
    }

    /** Every permission key of the session (menu and toolbar; scope is checked per call). */
    public Set<String> permissionKeys() {
        Set<String> keys = new HashSet<>(directPermissions);
        groupPermissions.values().forEach(keys::addAll);
        return keys;
    }

    /**
     * Whether {@code screen} needs a step-up now: it is sensitive for this session and the last
     * confirmation is older than {@code validity} (or there is none).
     */
    public boolean needsStepUp(String screen, Instant now, Duration validity) {
        return sensitiveScreens.contains(screen) && !stepUpValid(now, validity);
    }

    /** The last OTP confirmation is still within {@code validity}. */
    public boolean stepUpValid(Instant now, Duration validity) {
        return stepUpAt != null && now.isBefore(stepUpAt.plus(validity));
    }

    public boolean isEseva() {
        return employeeId != null && groups.isEmpty();
    }
}
