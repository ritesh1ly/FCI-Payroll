package in.gov.fci.payroll.iam.orchestration;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.iam.business.SensitiveRoles;
import in.gov.fci.payroll.iam.business.model.AppPermission;
import in.gov.fci.payroll.iam.business.model.AppRole;
import in.gov.fci.payroll.iam.business.model.AppRolePermission;
import in.gov.fci.payroll.iam.business.model.AppSession;
import in.gov.fci.payroll.iam.business.model.AppUser;
import in.gov.fci.payroll.iam.persistence.AccessRepositories;
import in.gov.fci.payroll.iam.persistence.IdentityRepositories;
import in.gov.fci.payroll.platform.cache.CacheRegions;
import in.gov.fci.payroll.platform.cache.VersionedCache;
import in.gov.fci.payroll.platform.config.ApiProperties;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.ActiveGroup;
import in.gov.fci.payroll.platform.security.CurrentAccess;
import in.gov.fci.payroll.platform.security.CurrentUser;
import in.gov.fci.payroll.platform.security.SecureTokens;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Builds the {@link AccessContext} of the calling session, cached per session (cache region ACCESS,
 * at most a minute). Every change of roles, permissions, groups, assignments or accounts marks the
 * region changed in its transaction ({@link #evictAll()}): immediate on this instance, within
 * {@code fci.cache.check-interval} on every other one (ADR-0018 A10). A sign-out or scope switch
 * drops only this session's entry ({@link #evictSession}); other instances do not serve it because
 * the shared HTTP session is gone.
 *
 * <p>SRS-G-29, SRS-15-6: the office chosen is the session's data scope from the very next request.
 * A scope switch evicts inside its transaction, so a request of the open screen that loads between
 * the eviction and the commit reads the old office: the eviction runs again after the commit, and a
 * load that began before an eviction is not left in the cache.
 */
@Service
public class AccessContextService implements CurrentAccess {

    public static final String ESEVA_ROLE = "EMPLOYEE_ESEVA";

    public static final String REGION = "ACCESS";

    private final IdentityRepositories.Sessions sessions;
    private final AccessRepositories.Users users;
    private final AccessRepositories.Permissions permissions;
    private final AccessRepositories.RolePermissions rolePermissions;
    private final ScopeResolver scopes;
    private final CurrentUser currentUser;
    private final Clock clock;
    private final ApiProperties properties;
    private final SystemParameters parameters;
    private final VersionedCache<String, AccessContext> cache;

    /** Numbers the evictions of this instance; a load keeps the number it began under. */
    private final AtomicLong evictions = new AtomicLong();

    /** The number of each session's last eviction, kept longer than any load takes. */
    private final Cache<String, Long> evictedAt =
            Caffeine.newBuilder()
                    .maximumSize(100_000)
                    .expireAfterWrite(Duration.ofMinutes(5))
                    .build();

    public AccessContextService(
            IdentityRepositories.Sessions sessions,
            AccessRepositories.Users users,
            AccessRepositories.Permissions permissions,
            AccessRepositories.RolePermissions rolePermissions,
            ScopeResolver scopes,
            CurrentUser currentUser,
            Clock clock,
            ApiProperties properties,
            SystemParameters parameters,
            CacheRegions regions) {
        this.cache = VersionedCache.of(REGION, regions, 10_000, Duration.ofMinutes(1));
        this.sessions = sessions;
        this.users = users;
        this.permissions = permissions;
        this.rolePermissions = rolePermissions;
        this.scopes = scopes;
        this.currentUser = currentUser;
        this.clock = clock;
        this.properties = properties;
        this.parameters = parameters;
    }

    /** Today in the business time zone (effective dates are calendar dates in India). */
    @Override
    public LocalDate today() {
        return LocalDate.now(clock.withZone(properties.businessZone()));
    }

    /** The context of the authenticated caller; 401 if the session is no longer usable. */
    @Override
    public AccessContext current() {
        String userId =
                currentUser
                        .userId()
                        .orElseThrow(
                                () -> new FciException(CommonErrorCode.AUTHENTICATION_REQUIRED));
        String sessionId =
                currentUser
                        .sessionId()
                        .orElseThrow(
                                () -> new FciException(CommonErrorCode.AUTHENTICATION_REQUIRED));
        return forSession(Long.valueOf(userId), sessionId)
                .orElseThrow(() -> new FciException(CommonErrorCode.AUTHENTICATION_REQUIRED));
    }

    /** The context of the session of {@code sessionToken}; cached by the token's hash. */
    public Optional<AccessContext> forSession(Long userId, String sessionToken) {
        String key = SecureTokens.sha256Hex(sessionToken);
        Optional<AccessContext> cached = cache.getIfCurrent(key);
        if (cached.isPresent()) {
            return cached.get().userId().equals(userId) ? cached : Optional.empty();
        }
        long version = cache.version();
        long began = evictions.get();
        Optional<AccessContext> built = build(userId, sessionToken, key);
        built.ifPresent(
                context -> {
                    cache.put(key, version, context);
                    // Read after the put: an eviction that came first is seen here, a later one
                    // removes this entry itself.
                    Long evicted = evictedAt.getIfPresent(key);
                    if (evicted != null && evicted > began) {
                        cache.invalidateLocally(key);
                    }
                });
        return built;
    }

    /**
     * Whether a token's session is still usable (open session, active account). The session row is
     * read on EVERY request, never from the cache: a sign-out, a revoked role or assignment, a lock
     * or a suspected hijack ends the session in the database, and every instance refuses its very
     * next request (no window of the cache's check interval). The rights themselves come from the
     * cached context, which a change evicts.
     */
    @Transactional(readOnly = true)
    public boolean isUsable(Long userId, String sessionId) {
        String tokenHash = SecureTokens.sha256Hex(sessionId);
        // The 8-hour cap and the account's status are judged here too, on every request (security
        // review 2026-11-04, W-9): judged only when the context is built, a capped session or a
        // locked / deactivated account (however it was set) would be served from the cache until
        // the entry expired.
        boolean open =
                sessions.findByTokenHash(tokenHash)
                                .filter(AppSession::isOpen)
                                .filter(s -> s.getUserId().equals(userId))
                                .filter(this::withinAbsoluteTimeout)
                                .isPresent()
                        && users.findById(userId)
                                .filter(u -> u.getStatus() == AppUser.Status.ACTIVE)
                                .isPresent();
        if (!open) {
            cache.invalidateLocally(tokenHash);
            return false;
        }
        return forSession(userId, sessionId).isPresent();
    }

    private boolean withinAbsoluteTimeout(AppSession session) {
        return clock.instant()
                .isBefore(session.getCreatedAt().plus(parameters.sessionAbsoluteTimeout()));
    }

    /**
     * Drops the cached context of the session whose token hash is {@code tokenHash}, now and,
     * inside a transaction, again once it has committed (see the class comment).
     */
    public void evictSession(String tokenHash) {
        drop(tokenHash);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            drop(tokenHash);
                        }
                    });
        }
    }

    private void drop(String tokenHash) {
        evictedAt.put(tokenHash, evictions.incrementAndGet());
        cache.invalidateLocally(tokenHash);
    }

    /** After any change of roles, permissions, groups or assignments: every instance rebuilds. */
    public void evictAll() {
        cache.changed("access");
    }

    private Optional<AccessContext> build(Long userId, String sessionId, String tokenHash) {
        Optional<AppSession> session =
                sessions.findByTokenHash(tokenHash)
                        .filter(AppSession::isOpen)
                        // Absolute timeout (ADR-0009): no session outlives it, however active.
                        .filter(this::withinAbsoluteTimeout);
        Optional<AppUser> user =
                users.findById(userId).filter(u -> u.getStatus() == AppUser.Status.ACTIVE);
        if (session.isEmpty() || user.isEmpty() || !session.get().getUserId().equals(userId)) {
            return Optional.empty();
        }
        LocalDate today = today();
        Set<Long> chosen = new HashSet<>(session.get().activeGroupIdList());
        List<ActiveGroup> active =
                scopes.activeGroups(userId, today).stream()
                        .filter(g -> chosen.contains(g.id()))
                        .toList();
        Map<Long, Set<String>> byRole =
                permissionsByRole(active.stream().map(ActiveGroup::roleId).toList(), today);
        Map<Long, Set<String>> byGroup = new HashMap<>();
        // access_mode (v4.0): a READ_ONLY or REPORTS_ONLY group confers only part of its role.
        active.forEach(
                g ->
                        byGroup.put(
                                g.id(),
                                byRole.getOrDefault(g.roleId(), Set.of()).stream()
                                        .filter(g.accessMode()::admits)
                                        .collect(Collectors.toUnmodifiableSet())));
        Set<String> direct = Set.of();
        Set<String> directRoles = Set.of();
        Long employeeId = session.get().getEmployeeId();
        // The eSeva permissions belong to the Employee (eSeva) scope only (no office): a staff
        // session acts in its chosen role and switches to eSeva in the top bar (FRS V0.5 §28).
        if (employeeId != null && session.get().getActiveLocationId() == null) {
            Optional<AppRole> eseva = scopes.activeDirectRole(userId, ESEVA_ROLE, today);
            if (eseva.isPresent()) {
                direct =
                        permissionsByRole(List.of(eseva.get().getId()), today)
                                .getOrDefault(eseva.get().getId(), Set.of());
                directRoles = Set.of(ESEVA_ROLE);
            }
        }
        Long location = session.get().getActiveLocationId();
        Set<String> sensitive =
                SensitiveRoles.sensitiveScreens(
                        byGroup.values().stream().flatMap(Set::stream).toList());
        return Optional.of(
                new AccessContext(
                        userId,
                        user.get().getLoginId(),
                        // No personal data in the cached context (nor in the logs that print it):
                        // the
                        // name is read from the database where it is shown (/auth/me).
                        null,
                        sessionId,
                        session.get().getPersonaCode(),
                        location,
                        employeeId,
                        active,
                        Map.copyOf(byGroup),
                        direct,
                        directRoles,
                        location == null ? Set.of() : scopes.dataScope(location, active),
                        sensitive,
                        session.get().getStepUpAt()));
    }

    /** {@code SCREEN:ACTION} keys per role, effective today. */
    @Transactional(readOnly = true)
    public Map<Long, Set<String>> permissionsByRole(Collection<Long> roleIds, LocalDate today) {
        if (roleIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, AppPermission> catalogue =
                permissions.findAllByOrderByScreenCodeAscActionCodeAsc().stream()
                        .collect(Collectors.toMap(AppPermission::getId, p -> p));
        Map<Long, Set<String>> result = new HashMap<>();
        for (AppRolePermission grant : rolePermissions.findByRoleIds(Set.copyOf(roleIds))) {
            AppPermission permission = catalogue.get(grant.getPermissionId());
            if (permission != null && grant.isActiveOn(today)) {
                result.computeIfAbsent(grant.getRoleId(), k -> new HashSet<>())
                        .add(permission.key());
            }
        }
        return result;
    }
}
