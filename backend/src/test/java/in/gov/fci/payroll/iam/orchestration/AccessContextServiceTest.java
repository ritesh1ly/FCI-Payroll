package in.gov.fci.payroll.iam.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.iam.business.model.AppPermission;
import in.gov.fci.payroll.iam.business.model.AppRolePermission;
import in.gov.fci.payroll.iam.business.model.AppSession;
import in.gov.fci.payroll.iam.business.model.AppUser;
import in.gov.fci.payroll.iam.persistence.AccessRepositories;
import in.gov.fci.payroll.iam.persistence.IdentityRepositories;
import in.gov.fci.payroll.platform.cache.CacheProperties;
import in.gov.fci.payroll.platform.cache.CacheRegions;
import in.gov.fci.payroll.platform.config.ApiProperties;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.CurrentUser;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The access context of a session (SRS-SEC-14, ADR-0009): a cached context answers only its own
 * user; a session that is not found, an account that is not active or a session of another user
 * gives no context; an eSeva session without the eSeva role has no direct rights; a grant of a
 * permission no longer catalogued or not in force today confers nothing.
 */
class AccessContextServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-29T06:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 29);

    private final IdentityRepositories.Sessions sessions =
            mock(IdentityRepositories.Sessions.class);
    private final AccessRepositories.Users users = mock(AccessRepositories.Users.class);
    private final AccessRepositories.Permissions permissions =
            mock(AccessRepositories.Permissions.class);
    private final AccessRepositories.RolePermissions rolePermissions =
            mock(AccessRepositories.RolePermissions.class);
    private final ScopeResolver scopes = mock(ScopeResolver.class);
    private final AppSession session = mock(AppSession.class);
    private final AppUser user = mock(AppUser.class);
    private final CurrentUser currentUser = mock(CurrentUser.class);
    private final CacheRegions regions = mock(CacheRegions.class);
    private final AccessContextService service;

    AccessContextServiceTest() {
        ApiProperties properties = mock(ApiProperties.class);
        when(properties.businessZone()).thenReturn(ZoneId.of("Asia/Kolkata"));
        SystemParameters parameters = mock(SystemParameters.class);
        when(parameters.sessionAbsoluteTimeout()).thenReturn(Duration.ofHours(8));
        CacheProperties cache = mock(CacheProperties.class);
        when(cache.maxAge()).thenReturn(Duration.ofMinutes(5));
        when(regions.properties()).thenReturn(cache);
        service =
                new AccessContextService(
                        sessions,
                        users,
                        permissions,
                        rolePermissions,
                        scopes,
                        currentUser,
                        Clock.fixed(NOW, ZoneOffset.UTC),
                        properties,
                        parameters,
                        regions);
        when(session.isOpen()).thenReturn(true);
        when(session.getCreatedAt()).thenReturn(NOW.minusSeconds(60));
        when(session.getUserId()).thenReturn(7L);
        when(session.activeGroupIdList()).thenReturn(List.of());
        when(session.getEmployeeId()).thenReturn(70L);
        when(sessions.findByTokenHash(anyString())).thenReturn(Optional.of(session));
        when(user.getStatus()).thenReturn(AppUser.Status.ACTIVE);
        when(user.getLoginId()).thenReturn("u7");
        when(users.findById(7L)).thenReturn(Optional.of(user));
        when(users.findById(8L)).thenReturn(Optional.of(user));
        when(scopes.activeGroups(any(), any())).thenReturn(List.of());
        when(scopes.activeDirectRole(any(), anyString(), any())).thenReturn(Optional.empty());
    }

    @Test
    void SRS_SEC_14_aCachedContextAnswersOnlyItsOwnUser() {
        Optional<AccessContext> built = service.forSession(7L, "token-a");
        assertThat(built).isPresent();
        assertThat(built.get().directPermissions()).as("no eSeva role").isEmpty();
        assertThat(service.forSession(7L, "token-a")).isPresent();
        assertThat(service.forSession(8L, "token-a")).isEmpty();
    }

    @Test
    void SRS_SEC_14_noSessionNoActiveAccountOrAnotherUsersSessionGivesNoContext() {
        assertThat(service.forSession(8L, "token-b")).as("another user's session").isEmpty();
        when(user.getStatus()).thenReturn(AppUser.Status.LOCKED);
        assertThat(service.forSession(7L, "token-c")).as("account not active").isEmpty();
        when(sessions.findByTokenHash(anyString())).thenReturn(Optional.empty());
        assertThat(service.forSession(7L, "token-d")).as("no session").isEmpty();
    }

    @Test
    void SRS_SEC_14_aGrantOfAnUncataloguedOrLapsedPermissionConfersNothing() {
        AppPermission view = mock(AppPermission.class);
        when(view.getId()).thenReturn(1L);
        when(view.key()).thenReturn("A-01:VIEW");
        when(permissions.findAllByOrderByScreenCodeAscActionCodeAsc()).thenReturn(List.of(view));
        AppRolePermission inForce = grant(1L, true);
        AppRolePermission lapsed = grant(1L, false);
        AppRolePermission unknown = grant(99L, true);
        when(rolePermissions.findByRoleIds(Set.of(5L)))
                .thenReturn(List.of(inForce, lapsed, unknown));
        assertThat(service.permissionsByRole(List.of(5L), TODAY))
                .containsOnlyKeys(5L)
                .containsEntry(5L, Set.of("A-01:VIEW"));
        when(rolePermissions.findByRoleIds(Set.of(5L))).thenReturn(List.of(lapsed, unknown));
        assertThat(service.permissionsByRole(List.of(5L), TODAY)).isEmpty();
    }

    private static AppRolePermission grant(long permissionId, boolean active) {
        AppRolePermission g = mock(AppRolePermission.class);
        when(g.getPermissionId()).thenReturn(permissionId);
        when(g.getRoleId()).thenReturn(5L);
        when(g.isActiveOn(TODAY)).thenReturn(active);
        return g;
    }

    /** SRS-G-48 / FRS V0.5 section 28: the eSeva scope grants the eSeva role's permissions only. */
    @Test
    void SRS_G_48_theEsevaScopeGrantsTheEsevaRoleOnlyWhileItIsHeld() {
        when(session.getActiveLocationId()).thenReturn(null);
        assertThat(service.forSession(7L, "token-e").orElseThrow().directPermissions())
                .as("no eSeva role held")
                .isEmpty();

        in.gov.fci.payroll.iam.business.model.AppRole eseva =
                mock(in.gov.fci.payroll.iam.business.model.AppRole.class);
        when(eseva.getId()).thenReturn(5L);
        when(scopes.activeDirectRole(any(), anyString(), any())).thenReturn(Optional.of(eseva));
        AppPermission view = mock(AppPermission.class);
        when(view.getId()).thenReturn(1L);
        when(view.key()).thenReturn("E-01:VIEW");
        when(permissions.findAllByOrderByScreenCodeAscActionCodeAsc()).thenReturn(List.of(view));
        AppRolePermission granted = grant(1L, true);
        when(rolePermissions.findByRoleIds(Set.of(5L))).thenReturn(List.of(granted));
        AccessContext built = service.forSession(7L, "token-f").orElseThrow();
        assertThat(built.directPermissions()).containsExactly("E-01:VIEW");
        assertThat(built.directRoleCodes()).containsExactly(AccessContextService.ESEVA_ROLE);
    }

    private void authenticationRequired(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        FciException.class,
                        e ->
                                assertThat(e.getErrorCode())
                                        .isEqualTo(CommonErrorCode.AUTHENTICATION_REQUIRED));
    }

    @Test
    void SRS_SEC_14_aCallWithoutASignedInUserIsAskedToSignIn() {
        when(currentUser.userId()).thenReturn(Optional.empty());
        when(currentUser.sessionId()).thenReturn(Optional.of("token-x"));
        authenticationRequired(service::current);
    }

    @Test
    void SRS_SEC_14_aCallWithAUserButNoSessionIsAskedToSignIn() {
        when(currentUser.userId()).thenReturn(Optional.of("7"));
        when(currentUser.sessionId()).thenReturn(Optional.empty());
        authenticationRequired(service::current);
    }

    @Test
    void SRS_SEC_14_aCallWhoseSessionGivesNoContextIsAskedToSignIn() {
        when(currentUser.userId()).thenReturn(Optional.of("7"));
        when(currentUser.sessionId()).thenReturn(Optional.of("token-gone"));
        when(sessions.findByTokenHash(anyString())).thenReturn(Optional.empty());
        authenticationRequired(service::current);
    }

    @Test
    void SRS_SEC_14_aSignedInCallGetsTheContextOfItsOwnSession() {
        when(currentUser.userId()).thenReturn(Optional.of("7"));
        when(currentUser.sessionId()).thenReturn(Optional.of("token-ok"));
        AccessContext context = service.current();
        assertThat(context.userId()).isEqualTo(7L);
        assertThat(context.loginId()).isEqualTo("u7");
        assertThat(context.sessionId()).isEqualTo("token-ok");
    }

    @Test
    void SRS_SEC_14_aSessionIsUsableOnlyWhileOpenOfTheSameUserActiveAndWithinTheAbsoluteTimeout() {
        assertThat(service.isUsable(7L, "token-u")).isTrue();
        assertThat(service.isUsable(8L, "token-u")).as("another user's session").isFalse();

        when(session.getCreatedAt()).thenReturn(NOW.minus(Duration.ofHours(8)));
        assertThat(service.isUsable(7L, "token-u2"))
                .as("exactly at the absolute timeout")
                .isFalse();
        when(session.getCreatedAt()).thenReturn(NOW.minus(Duration.ofHours(8)).plusSeconds(1));
        assertThat(service.isUsable(7L, "token-u3")).as("a second inside").isTrue();

        when(session.isOpen()).thenReturn(false);
        assertThat(service.isUsable(7L, "token-u4")).as("signed out").isFalse();
        when(session.isOpen()).thenReturn(true);

        when(user.getStatus()).thenReturn(AppUser.Status.LOCKED);
        assertThat(service.isUsable(7L, "token-u5")).as("locked account").isFalse();
        when(user.getStatus()).thenReturn(AppUser.Status.ACTIVE);

        when(users.findById(7L)).thenReturn(Optional.empty());
        assertThat(service.isUsable(7L, "token-u6")).as("account gone").isFalse();
        when(sessions.findByTokenHash(anyString())).thenReturn(Optional.empty());
        assertThat(service.isUsable(7L, "token-u7")).as("no session").isFalse();
    }

    @Test
    void SRS_SEC_14_aCappedSessionIsRefusedEvenWhenItsContextIsCached() {
        assertThat(service.forSession(7L, "token-cap")).isPresent();
        when(session.getCreatedAt()).thenReturn(NOW.minus(Duration.ofHours(9)));
        assertThat(service.isUsable(7L, "token-cap")).isFalse();
        assertThat(service.forSession(7L, "token-cap"))
                .as("the entry was dropped, the rebuild finds the cap")
                .isEmpty();
    }

    @Test
    void SRS_SEC_14_evictingAnAccountChangeMarksTheAccessRegionChangedForEveryInstance() {
        service.evictAll();
        verify(regions).changed(AccessContextService.REGION, "access");
    }

    @Test
    void SRS_SEC_14_evictingOneSessionLeavesTheOthersCached() {
        service.forSession(7L, "token-1");
        service.forSession(7L, "token-2");
        when(sessions.findByTokenHash(anyString())).thenReturn(Optional.empty());
        service.evictSession(sha("token-1"));
        assertThat(service.forSession(7L, "token-1")).as("rebuilt, no session").isEmpty();
        assertThat(service.forSession(7L, "token-2")).as("still cached").isPresent();
    }

    /** SRS-G-29: a request of the open screen that loads while the office is switched. */
    @Test
    void SRS_G_29_aLoadThatBeganBeforeAScopeSwitchIsNotLeftInTheCache() {
        String key = sha("token-1");
        var open = sessions.findByTokenHash(key);
        when(sessions.findByTokenHash(key))
                .thenAnswer(
                        i -> {
                            service.evictSession(key);
                            return open;
                        });
        assertThat(service.forSession(7L, "token-1")).isPresent();
        org.mockito.Mockito.clearInvocations(sessions);
        service.forSession(7L, "token-1");
        org.mockito.Mockito.verify(sessions).findByTokenHash(key);
    }

    /** SRS-G-29: what a request loaded between the eviction and the commit goes at the commit. */
    @Test
    void SRS_G_29_aScopeSwitchEvictsAgainAfterItsCommit() {
        String key = sha("token-1");
        org.springframework.transaction.support.TransactionSynchronizationManager
                .initSynchronization();
        try {
            service.evictSession(key);
            service.forSession(7L, "token-1");
            org.mockito.Mockito.clearInvocations(sessions);
            service.forSession(7L, "token-1");
            org.mockito.Mockito.verifyNoInteractions(sessions);
            org.springframework.transaction.support.TransactionSynchronizationManager
                    .getSynchronizations()
                    .forEach(
                            org.springframework.transaction.support.TransactionSynchronization
                                    ::afterCommit);
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clear();
        }
        service.forSession(7L, "token-1");
        org.mockito.Mockito.verify(sessions).findByTokenHash(key);
    }

    private static String sha(String token) {
        return in.gov.fci.payroll.platform.security.SecureTokens.sha256Hex(token);
    }
}
