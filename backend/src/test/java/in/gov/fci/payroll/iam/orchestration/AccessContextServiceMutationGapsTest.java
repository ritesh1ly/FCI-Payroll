package in.gov.fci.payroll.iam.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.AccessMode;
import in.gov.fci.payroll.platform.security.ActiveGroup;
import in.gov.fci.payroll.platform.security.CurrentUser;
import in.gov.fci.payroll.platform.security.ScopeType;
import in.gov.fci.payroll.platform.security.SecureTokens;
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
 * Kills the PIT survivors of {@link AccessContextService} (SRS-SEC-14, SRS-G-48, ADR-0009, ADR-0018
 * A10): the cache really holds a built context, the groups the session chose and their access mode
 * decide the permissions, the data scope follows the active location and a sensitive action marks
 * its screen.
 */
class AccessContextServiceMutationGapsTest {

    private static final Instant NOW = Instant.parse("2026-11-04T20:00:00Z");

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
    private final CacheRegions regions = mock(CacheRegions.class);
    private final AccessContextService service;

    AccessContextServiceMutationGapsTest() {
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
                        mock(CurrentUser.class),
                        Clock.fixed(NOW, ZoneOffset.UTC),
                        properties,
                        parameters,
                        regions);
        when(session.isOpen()).thenReturn(true);
        when(session.getCreatedAt()).thenReturn(NOW.minusSeconds(60));
        when(session.getUserId()).thenReturn(7L);
        when(session.activeGroupIdList()).thenReturn(List.of(1L, 2L));
        when(session.getEmployeeId()).thenReturn(null);
        when(session.getActiveLocationId()).thenReturn(null);
        when(session.getPersonaCode()).thenReturn("MAKER");
        when(session.getStepUpAt()).thenReturn(NOW.minusSeconds(30));
        when(sessions.findByTokenHash(anyString())).thenReturn(Optional.of(session));
        when(user.getStatus()).thenReturn(AppUser.Status.ACTIVE);
        when(user.getLoginId()).thenReturn("u7");
        when(users.findById(7L)).thenReturn(Optional.of(user));
        when(scopes.activeDirectRole(any(), anyString(), any())).thenReturn(Optional.empty());
    }

    private static ActiveGroup group(long id, AccessMode mode) {
        return new ActiveGroup(
                id,
                "G" + id,
                "Group " + id,
                5L,
                "ROLE_5",
                "Role 5",
                ScopeType.REGION,
                false,
                Set.of(11L),
                Set.of(),
                mode);
    }

    private void roleGrants(String... keys) {
        long id = 100;
        List<AppPermission> catalogue = new java.util.ArrayList<>();
        List<AppRolePermission> grants = new java.util.ArrayList<>();
        for (String key : keys) {
            AppPermission p = mock(AppPermission.class);
            when(p.getId()).thenReturn(id);
            when(p.key()).thenReturn(key);
            catalogue.add(p);
            AppRolePermission g = mock(AppRolePermission.class);
            when(g.getPermissionId()).thenReturn(id);
            when(g.getRoleId()).thenReturn(5L);
            when(g.isActiveOn(any())).thenReturn(true);
            grants.add(g);
            id++;
        }
        when(permissions.findAllByOrderByScreenCodeAscActionCodeAsc()).thenReturn(catalogue);
        when(rolePermissions.findByRoleIds(any())).thenReturn(grants);
    }

    @Test
    void SRS_SEC_14_today_isTheCalendarDayInTheBusinessZoneNotUtc() {
        // 20:00 UTC on 4 Nov is 01:30 IST on 5 Nov
        assertThat(service.today()).isEqualTo(LocalDate.of(2026, 11, 5));
    }

    @Test
    void ADR_0018_aBuiltContextIsCachedSoTheSecondCallReadsNothing() {
        assertThat(service.forSession(7L, "tok-cache")).isPresent();
        assertThat(service.forSession(7L, "tok-cache")).isPresent();
        verify(sessions, times(1)).findByTokenHash(SecureTokens.sha256Hex("tok-cache"));
        verify(users, times(1)).findById(7L);
    }

    @Test
    void ADR_0018_anEmptyResultIsNeverCached() {
        when(sessions.findByTokenHash(anyString())).thenReturn(Optional.empty());
        assertThat(service.forSession(7L, "tok-none")).isEmpty();
        when(sessions.findByTokenHash(anyString())).thenReturn(Optional.of(session));
        assertThat(service.forSession(7L, "tok-none")).as("built afresh").isPresent();
    }

    @Test
    void SRS_G_48_onlyTheGroupsTheSessionChoseConferPermissionsAndTheAccessModeNarrowsThem() {
        ActiveGroup readOnly = group(1, AccessMode.READ_ONLY);
        ActiveGroup full = group(2, AccessMode.FULL);
        ActiveGroup notChosen = group(3, AccessMode.FULL);
        when(scopes.activeGroups(any(), any())).thenReturn(List.of(readOnly, full, notChosen));
        roleGrants("A-01:VIEW", "A-01:EDIT", "R-02:EXPORT");

        AccessContext context = service.forSession(7L, "tok-groups").orElseThrow();

        assertThat(context.groups()).containsExactly(readOnly, full);
        assertThat(context.groupPermissions()).containsOnlyKeys(1L, 2L);
        assertThat(context.groupPermissions().get(1L))
                .containsExactlyInAnyOrder("A-01:VIEW", "R-02:EXPORT");
        assertThat(context.groupPermissions().get(2L))
                .containsExactlyInAnyOrder("A-01:VIEW", "A-01:EDIT", "R-02:EXPORT");
    }

    @Test
    void SRS_G_48_aReportsOnlyGroupConfersReportScreensViewAndExportOnly() {
        ActiveGroup reports = group(1, AccessMode.REPORTS_ONLY);
        when(scopes.activeGroups(any(), any())).thenReturn(List.of(reports));
        roleGrants("A-01:VIEW", "R-02:EXPORT", "R-02:EDIT");
        AccessContext context = service.forSession(7L, "tok-reports").orElseThrow();
        assertThat(context.groupPermissions().get(1L)).containsExactly("R-02:EXPORT");
    }

    @Test
    void SRS_SEC_14_aSensitiveActionOfAChosenGroupMarksItsScreenForStepUp() {
        ActiveGroup full = group(2, AccessMode.FULL);
        when(scopes.activeGroups(any(), any())).thenReturn(List.of(full));
        roleGrants("W-01:APPROVE", "A-03:EDIT", "A-04:VIEW", "M-10:EDIT");
        AccessContext context = service.forSession(7L, "tok-sens").orElseThrow();
        assertThat(context.sensitiveScreens()).containsExactlyInAnyOrder("W-01", "A-03");
    }

    @Test
    void SRS_SEC_14_theDataScopeFollowsTheActiveLocationAndIsEmptyWithoutOne() {
        ActiveGroup full = group(1, AccessMode.FULL);
        when(scopes.activeGroups(any(), any())).thenReturn(List.of(full));
        when(scopes.dataScope(any(), any())).thenReturn(Set.of(11L, 12L));

        AccessContext none = service.forSession(7L, "tok-noloc").orElseThrow();
        assertThat(none.dataScope()).isEmpty();
        assertThat(none.locationId()).isNull();
        verify(scopes, never()).dataScope(any(), any());

        when(session.getActiveLocationId()).thenReturn(11L);
        AccessContext located = service.forSession(7L, "tok-loc").orElseThrow();
        assertThat(located.dataScope()).containsExactlyInAnyOrder(11L, 12L);
        assertThat(located.locationId()).isEqualTo(11L);
        verify(scopes).dataScope(11L, List.of(full));
    }

    @Test
    void SRS_SEC_14_theContextCarriesTheSessionFactsAndNoPersonalName() {
        when(scopes.activeGroups(any(), any())).thenReturn(List.of());
        when(session.getEmployeeId()).thenReturn(70L);
        when(session.getActiveLocationId()).thenReturn(11L);
        AccessContext context = service.forSession(7L, "tok-facts").orElseThrow();
        assertThat(context.userId()).isEqualTo(7L);
        assertThat(context.loginId()).isEqualTo("u7");
        assertThat(context.displayName()).as("no personal data in the cache").isNull();
        assertThat(context.sessionId()).isEqualTo("tok-facts");
        assertThat(context.personaCode()).isEqualTo("MAKER");
        assertThat(context.employeeId()).isEqualTo(70L);
        assertThat(context.stepUpAt()).isEqualTo(NOW.minusSeconds(30));
        assertThat(context.directPermissions()).isEmpty();
        assertThat(context.directRoleCodes()).isEmpty();
        verify(scopes, never()).activeDirectRole(any(), anyString(), any());
    }

    @Test
    void SRS_G_48_theEsevaRoleIsLookedUpOnlyForAnEmployeeSessionWithoutAnOffice() {
        when(scopes.activeGroups(any(), any())).thenReturn(List.of());
        // an employee session with an office: no lookup
        when(session.getEmployeeId()).thenReturn(70L);
        when(session.getActiveLocationId()).thenReturn(11L);
        service.forSession(7L, "tok-e1");
        verify(scopes, never()).activeDirectRole(any(), anyString(), any());
        // an office session of a non-employee: no lookup
        when(session.getEmployeeId()).thenReturn(null);
        when(session.getActiveLocationId()).thenReturn(null);
        service.forSession(7L, "tok-e2");
        verify(scopes, never()).activeDirectRole(any(), anyString(), any());
        // an employee without an office: the lookup of the eSeva role
        when(session.getEmployeeId()).thenReturn(70L);
        service.forSession(7L, "tok-e3");
        verify(scopes)
                .activeDirectRole(7L, AccessContextService.ESEVA_ROLE, LocalDate.of(2026, 11, 5));
    }

    @Test
    void SRS_SEC_14_aSessionFoundNotUsableDropsItsCachedContext() {
        when(scopes.activeGroups(any(), any())).thenReturn(List.of());
        assertThat(service.forSession(7L, "tok-drop")).isPresent();
        when(session.isOpen()).thenReturn(false);
        assertThat(service.isUsable(7L, "tok-drop")).isFalse();
        // cached entry dropped: the rebuild finds the closed session
        assertThat(service.forSession(7L, "tok-drop")).isEmpty();
    }

    @Test
    void SRS_SEC_14_aUsableSessionIsStillUsableAndStaysCached() {
        when(scopes.activeGroups(any(), any())).thenReturn(List.of());
        assertThat(service.isUsable(7L, "tok-ok")).isTrue();
        assertThat(service.forSession(7L, "tok-ok")).isPresent();
        // isUsable read the session row, forSession then served the cache built by isUsable
        verify(sessions, times(2)).findByTokenHash(SecureTokens.sha256Hex("tok-ok"));
    }

    @Test
    void SRS_SEC_14_aSessionOfAnotherUserIsNotUsableEvenWhenItsContextIsCached() {
        when(scopes.activeGroups(any(), any())).thenReturn(List.of());
        assertThat(service.forSession(7L, "tok-owner")).isPresent();
        // the token now belongs to another user: the session row decides, not the cache
        when(session.getUserId()).thenReturn(99L);

        assertThat(service.isUsable(7L, "tok-owner")).isFalse();
        assertThat(service.forSession(7L, "tok-owner")).as("cache dropped").isEmpty();
    }

    @Test
    void SRS_SEC_14_aLockedAccountIsNotUsableEvenWhenItsContextIsCached() {
        when(scopes.activeGroups(any(), any())).thenReturn(List.of());
        assertThat(service.forSession(7L, "tok-locked")).isPresent();
        when(user.getStatus()).thenReturn(AppUser.Status.LOCKED);

        assertThat(service.isUsable(7L, "tok-locked")).isFalse();
        assertThat(service.forSession(7L, "tok-locked")).as("cache dropped").isEmpty();
    }

    @Test
    void SRS_SEC_14_aCachedContextOfAnotherUserNeverMakesTheSessionUsable() {
        when(scopes.activeGroups(any(), any())).thenReturn(List.of());
        assertThat(service.forSession(7L, "tok-swap")).isPresent();
        // the open session and active account now name user 8, but the cache holds user 7
        AppUser other = mock(AppUser.class);
        when(other.getStatus()).thenReturn(AppUser.Status.ACTIVE);
        when(users.findById(8L)).thenReturn(Optional.of(other));
        when(session.getUserId()).thenReturn(8L);

        assertThat(service.isUsable(8L, "tok-swap")).isFalse();
    }
}
