package in.gov.fci.payroll.iam.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.employee.api.EmployeeDirectory;
import in.gov.fci.payroll.iam.business.model.AccessGroupScope;
import in.gov.fci.payroll.iam.business.model.AppUser;
import in.gov.fci.payroll.iam.persistence.AccessRepositories;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.organisation.business.model.OrgZone;
import in.gov.fci.payroll.platform.export.ExportReader;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.AccessMode;
import in.gov.fci.payroll.platform.security.ActiveGroup;
import in.gov.fci.payroll.platform.security.ScopeType;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

/**
 * A-01 active-user report beyond the happy path (SRS-22-13, SRS-G-50): blank search text searches
 * everything, "inactive only" has no cut-off while the inactivity threshold is unset, a zone
 * administrator whose offices hold no employee sees nobody, and the unit column names "ALL" for a
 * national group, the zone for a zone group and the office for an office group.
 */
class UserReportServiceEdgeTest {

    private static final Instant NOW = Instant.parse("2026-10-24T06:00:00Z");

    private final AccessRepositories.Users users = mock(AccessRepositories.Users.class);
    private final AccessRepositories.GroupScopes scopes =
            mock(AccessRepositories.GroupScopes.class);
    private final ScopeResolver resolver = mock(ScopeResolver.class);
    private final EmployeeDirectory employees = mock(EmployeeDirectory.class);
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final SystemParameters parameters = mock(SystemParameters.class);
    private final AdminScope adminScope = mock(AdminScope.class);
    private final AccessContextService contexts = mock(AccessContextService.class);

    private final ExportReader exportReader = mock(ExportReader.class);
    private final UserReportService service =
            new UserReportService(
                    exportReader,
                    users,
                    mock(AccessRepositories.Assignments.class),
                    scopes,
                    resolver,
                    employees,
                    org,
                    parameters,
                    mock(AccessPolicy.class),
                    adminScope,
                    contexts,
                    Clock.fixed(NOW, ZoneOffset.UTC));

    private final AccessContext admin =
            new AccessContext(
                    1L,
                    "admin@x",
                    "Admin",
                    "s",
                    "P",
                    10L,
                    null,
                    List.of(),
                    Map.of(),
                    Set.of(),
                    Set.of(),
                    Set.of(10L));

    UserReportServiceEdgeTest() {
        when(contexts.today()).thenReturn(LocalDate.of(2026, 10, 24));
        when(parameters.configured(SystemParameters.Code.INACTIVE_USER_DAYS))
                .thenReturn(OptionalLong.empty());
    }

    private static ActiveGroup group(long id, String code, ScopeType type) {
        return new ActiveGroup(
                id,
                code,
                code,
                5L,
                "PAYROLL_MAKER",
                "Maker",
                type,
                false,
                Set.of(),
                Set.of(),
                AccessMode.FULL);
    }

    private static AccessGroupScope row(long groupId, Long zoneId, Long locationId) {
        AccessGroupScope r = mock(AccessGroupScope.class);
        when(r.getAccessGroupId()).thenReturn(groupId);
        when(r.getZoneId()).thenReturn(zoneId);
        when(r.getLocationId()).thenReturn(locationId);
        return r;
    }

    @Test
    void SRS_22_13_blankTextAndAnUnsetThresholdSearchEveryoneAndAZoneWithoutStaffSeesNobody() {
        when(adminScope.isNational(admin)).thenReturn(false);
        when(users.reportAmong(any(), any(), any(), any())).thenReturn(Page.empty());

        UserReportService.ActiveUserReport report = service.activeUsers(admin, "   ", true, 0, 20);

        assertThat(report.inactiveAfterDays()).isNull();
        assertThat(report.items()).isEmpty();
        // The zone's offices are bound, never the ids of its employees (deep profile 2026-10-27).
        verify(users).reportAmong(any(), any(), eq(Set.of(10L)), any());
        verify(employees, never()).idsAt(any());
    }

    @Test
    void SRS_G_50_theUnitColumnNamesAllAZoneOrAnOfficeAndAUserWithoutAccessHasNone() {
        when(adminScope.isNational(admin)).thenReturn(true);
        AppUser maker = mock(AppUser.class);
        when(maker.getId()).thenReturn(2L);
        when(maker.getStatus()).thenReturn(AppUser.Status.ACTIVE);
        AppUser idle = mock(AppUser.class);
        when(idle.getId()).thenReturn(3L);
        when(idle.getStatus()).thenReturn(AppUser.Status.ACTIVE);
        when(users.report(isNull(), isNull(), any()))
                .thenReturn(new PageImpl<>(List.of(maker, idle)));
        when(resolver.activeGroupsOf(any(), any()))
                .thenReturn(
                        Map.of(
                                2L,
                                List.of(
                                        group(30L, "AG-ALL", ScopeType.GLOBAL),
                                        group(31L, "AG-NORTH", ScopeType.ZONE),
                                        group(32L, "AG-DH", ScopeType.DIVISION))));
        AccessGroupScope zoneRow = row(31L, 7L, null);
        AccessGroupScope officeRow = row(32L, null, 20L);
        AccessGroupScope emptyRow = row(32L, null, null);
        when(scopes.findByAccessGroupIdIn(any())).thenReturn(List.of(zoneRow, officeRow, emptyRow));
        OrgZone north = mock(OrgZone.class);
        when(north.getName()).thenReturn("North");
        when(org.zone(7L)).thenReturn(Optional.of(north));
        OrgLocation delhi = mock(OrgLocation.class);
        when(delhi.displayLabel()).thenReturn("DO Delhi");
        when(org.location(20L)).thenReturn(Optional.of(delhi));

        UserReportService.ActiveUserReport report = service.activeUsers(admin, null, false, 0, 20);

        assertThat(report.items().get(0).access())
                .extracting(UserReportService.AccessRow::unit)
                .containsExactly("ALL", "North", "DO Delhi");
        assertThat(report.items().get(1).access()).isEmpty();
        assertThat(report.items().get(1).inactive()).isNull();
        verify(users).report(isNull(), isNull(), eq(PageRequest.of(0, 20)));
    }

    /** SRS-22-13: a zone administrator without offices in the session sees nobody. */
    @Test
    void SRS_22_13_aZoneAdministratorWithoutOfficesSeesNobody() {
        when(adminScope.isNational(any())).thenReturn(false);
        AccessContext none =
                new AccessContext(
                        1L, "admin@x", "Admin", "s", "P", 10L, null, List.of(), Map.of(), Set.of(),
                        Set.of(), Set.of());
        assertThat(service.activeUsers(none, "x", false, 0, 20).items()).isEmpty();
        verify(users, never()).reportAmong(any(), any(), any(), any());
    }

    /**
     * SRS-G-50 / SRS-22-13: the export marks each user inactive or not once the threshold is set,
     * and a group whose unit is not resolved exports an empty unit.
     */
    @Test
    @SuppressWarnings("unchecked")
    void SRS_G_50_theExportMarksInactivityAndAnUnresolvedUnitIsEmpty() throws Exception {
        when(adminScope.isNational(admin)).thenReturn(true);
        when(parameters.configured(SystemParameters.Code.INACTIVE_USER_DAYS))
                .thenReturn(OptionalLong.of(30));
        AppUser idle = mock(AppUser.class);
        when(idle.getId()).thenReturn(2L);
        when(idle.getLoginId()).thenReturn("idle@x");
        when(idle.getStatus()).thenReturn(AppUser.Status.ACTIVE);
        when(idle.getLastLoginAt()).thenReturn(NOW.minusSeconds(86_400L * 60));
        AppUser recent = mock(AppUser.class);
        when(recent.getId()).thenReturn(3L);
        when(recent.getLoginId()).thenReturn("recent@x");
        when(recent.getStatus()).thenReturn(AppUser.Status.ACTIVE);
        when(recent.getLastLoginAt()).thenReturn(NOW.minusSeconds(86_400L));
        when(resolver.activeGroupsOf(any(), any()))
                .thenReturn(Map.of(2L, List.of(group(40L, "AG-DEPOT", ScopeType.DEPOT))));
        when(scopes.findByAccessGroupIdIn(any())).thenReturn(List.of());
        when(exportReader.readAll(any(), any()))
                .thenAnswer(
                        call -> {
                            ExportReader.Batch<AppUser> batch = call.getArgument(1);
                            batch.handle(List.of(idle, recent));
                            return 2L;
                        });
        in.gov.fci.payroll.platform.export.ExportSink sink =
                mock(in.gov.fci.payroll.platform.export.ExportSink.class);
        when(sink.rows()).thenReturn(2L);
        service.export(admin, null, false, sink);
        verify(sink)
                .row(
                        eq("idle@x"),
                        any(),
                        any(),
                        any(),
                        any(),
                        eq("Y"),
                        eq("AG-DEPOT / PAYROLL_MAKER / "));
        verify(sink).row(eq("recent@x"), any(), any(), any(), any(), eq("N"), eq(""));
    }

    /**
     * SRS-G-50: the export names the unit of a group whose unit is resolved ("ALL" if national).
     */
    @Test
    @SuppressWarnings("unchecked")
    void SRS_G_50_theExportNamesAResolvedUnitAndNoInactivityWhileTheThresholdIsUnset()
            throws Exception {
        when(adminScope.isNational(admin)).thenReturn(true);
        AppUser maker = mock(AppUser.class);
        when(maker.getId()).thenReturn(2L);
        when(maker.getLoginId()).thenReturn("maker@x");
        when(maker.getStatus()).thenReturn(AppUser.Status.ACTIVE);
        when(resolver.activeGroupsOf(any(), any()))
                .thenReturn(Map.of(2L, List.of(group(30L, "AG-ALL", ScopeType.GLOBAL))));
        when(scopes.findByAccessGroupIdIn(any())).thenReturn(List.of());
        when(exportReader.readAll(any(), any()))
                .thenAnswer(
                        call -> {
                            ExportReader.Batch<AppUser> batch = call.getArgument(1);
                            batch.handle(List.of(maker));
                            return 1L;
                        });
        in.gov.fci.payroll.platform.export.ExportSink sink =
                mock(in.gov.fci.payroll.platform.export.ExportSink.class);
        when(sink.rows()).thenReturn(1L);
        service.export(admin, null, false, sink);
        verify(sink)
                .row(
                        eq("maker@x"),
                        any(),
                        any(),
                        any(),
                        any(),
                        isNull(),
                        eq("AG-ALL / PAYROLL_MAKER / ALL"));
    }

    /** SRS-G-50: the readiness report names the DO minimum staff only while it is configured. */
    @Test
    void SRS_G_50_theReadinessReportNamesTheMinimumStaffOnlyWhenConfigured() {
        OrgDirectory.Snapshot snapshot = mock(OrgDirectory.Snapshot.class);
        when(snapshot.locations()).thenReturn(Map.of());
        when(org.snapshot()).thenReturn(snapshot);
        when(adminScope.isNational(admin)).thenReturn(true);
        when(parameters.configured(SystemParameters.Code.DO_MINIMUM_STAFF))
                .thenReturn(OptionalLong.empty());
        assertThat(service.readiness(admin).doMinimumStaff()).isNull();
        when(parameters.configured(SystemParameters.Code.DO_MINIMUM_STAFF))
                .thenReturn(OptionalLong.of(3));
        UserReportService.ReadinessReport report = service.readiness(admin);
        assertThat(report.doMinimumStaff()).isEqualTo(3L);
        assertThat(report.units()).isEmpty();
    }
}
