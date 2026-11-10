package in.gov.fci.payroll.iam.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.employee.api.EmployeeDirectory;
import in.gov.fci.payroll.iam.business.model.AccessGroupScope;
import in.gov.fci.payroll.iam.business.model.AppUser;
import in.gov.fci.payroll.iam.persistence.AccessRepositories;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.LocationType;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.platform.export.ExportIncompleteException;
import in.gov.fci.payroll.platform.export.ExportReader;
import in.gov.fci.payroll.platform.export.ExportSink;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.AccessMode;
import in.gov.fci.payroll.platform.security.ActiveGroup;
import in.gov.fci.payroll.platform.security.ScopeType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * Mutation gaps of {@link UserReportService}: A-01 VIEW / EXPORT permissions, the inactivity
 * cut-off and search text, the export header and completeness check, and the F-7 readiness check
 * (SRS-G-50, SRS-22-11, SRS-22-13).
 */
class UserReportServiceMutationGapsTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate TODAY = LocalDate.now(IST);
    private static final Instant NOW = TODAY.atTime(11, 0).atZone(IST).toInstant();

    private final AccessRepositories.Users users = mock(AccessRepositories.Users.class);
    private final AccessRepositories.Assignments assignments =
            mock(AccessRepositories.Assignments.class);
    private final AccessRepositories.GroupScopes scopes =
            mock(AccessRepositories.GroupScopes.class);
    private final ScopeResolver resolver = mock(ScopeResolver.class);
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final SystemParameters parameters = mock(SystemParameters.class);
    private final AccessPolicy policy = mock(AccessPolicy.class);
    private final AdminScope adminScope = mock(AdminScope.class);
    private final AccessContextService contexts = mock(AccessContextService.class);
    private final ExportReader exportReader = mock(ExportReader.class);

    private final UserReportService service =
            new UserReportService(
                    exportReader,
                    users,
                    assignments,
                    scopes,
                    resolver,
                    mock(EmployeeDirectory.class),
                    org,
                    parameters,
                    policy,
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

    UserReportServiceMutationGapsTest() {
        when(contexts.today()).thenReturn(TODAY);
        when(parameters.configured(SystemParameters.Code.INACTIVE_USER_DAYS))
                .thenReturn(OptionalLong.of(30));
        when(parameters.configured(SystemParameters.Code.DO_MINIMUM_STAFF))
                .thenReturn(OptionalLong.of(2));
        when(adminScope.isNational(admin)).thenReturn(true);
    }

    // ------------------------------------------------------------------ active users

    @Test
    void SRS_G_50_theActiveUserReportNeedsA01ViewAndCutsOffOnlyWhenInactiveOnlyIsAsked() {
        PageRequest request = PageRequest.of(0, 20);
        Instant cutoff = NOW.minus(Duration.ofDays(30));
        when(users.report("%maker%", cutoff, request)).thenReturn(Page.empty(request));
        when(users.report("%maker%", null, request)).thenReturn(Page.empty(request));

        UserReportService.ActiveUserReport inactive =
                service.activeUsers(admin, " Maker ", true, 0, 20);
        UserReportService.ActiveUserReport all =
                service.activeUsers(admin, " Maker ", false, 0, 20);

        verify(policy, times(2)).require(admin, "A-01", "VIEW");
        verify(users).report("%maker%", cutoff, request);
        verify(users).report("%maker%", null, request);
        assertThat(inactive.inactiveAfterDays()).isEqualTo(30L);
        assertThat(all.inactiveAfterDays()).isEqualTo(30L);
    }

    // ------------------------------------------------------------------ export

    @Test
    @SuppressWarnings("unchecked")
    void SRS_G_13_theExportSizeNeedsA01ExportAndCountsTheFilteredScopedQuery() {
        PageRequest probe = PageRequest.of(0, 1);
        Instant cutoff = NOW.minus(Duration.ofDays(30));
        when(users.report("%asha%", cutoff, probe))
                .thenReturn(new PageImpl<>(List.of(), probe, 42));
        when(exportReader.count(any()))
                .thenAnswer(
                        call ->
                                ((Function<Pageable, Page<?>>) call.getArgument(0))
                                        .apply(probe)
                                        .getTotalElements());

        long size = service.exportSize(admin, "Asha", true);

        assertThat(size).isEqualTo(42L);
        verify(policy).require(admin, "A-01", "EXPORT");
    }

    @Test
    @SuppressWarnings("unchecked")
    void SRS_G_13_theExportWritesTheHeaderThenTheRowsOfTheFilteredQuery() throws Exception {
        AppUser maker = mock(AppUser.class);
        when(maker.getId()).thenReturn(2L);
        when(maker.getLoginId()).thenReturn("maker@x");
        when(maker.getDisplayName()).thenReturn("Asha Verma");
        when(maker.getStatus()).thenReturn(AppUser.Status.ACTIVE);
        PageRequest first = PageRequest.of(0, 10);
        Instant cutoff = NOW.minus(Duration.ofDays(30));
        when(users.report("%asha%", cutoff, first))
                .thenReturn(new PageImpl<>(List.of(maker), first, 1));
        when(resolver.activeGroupsOf(any(), any())).thenReturn(Map.of());
        when(exportReader.readAll(any(), any()))
                .thenAnswer(
                        call -> {
                            Function<Pageable, Page<AppUser>> query = call.getArgument(0);
                            ExportReader.Batch<AppUser> batch = call.getArgument(1);
                            Page<AppUser> page = query.apply(first);
                            batch.handle(page.getContent());
                            return page.getTotalElements();
                        });
        ExportSink sink = mock(ExportSink.class);
        when(sink.rows()).thenReturn(1L);

        service.export(admin, "Asha", true, sink);

        verify(policy).require(admin, "A-01", "EXPORT");
        verify(sink)
                .header(
                        "Login ID",
                        "Name",
                        "Status",
                        "Last sign-in",
                        "Days since sign-in",
                        "Inactive",
                        "Access (group / role / unit)");
        verify(sink).row("maker@x", "Asha Verma", "ACTIVE", null, null, "Y", "");
    }

    @Test
    void SRS_G_13_aShortExportIsNeverDeliveredAsComplete() throws Exception {
        when(exportReader.readAll(any(), any())).thenReturn(5L);
        ExportSink sink = mock(ExportSink.class);
        when(sink.rows()).thenReturn(3L);

        assertThatThrownBy(() -> service.export(admin, null, false, sink))
                .isInstanceOf(ExportIncompleteException.class);
    }

    // ------------------------------------------------------------------ readiness

    private static OrgLocation unit(
            long id, String code, LocationType type, boolean cycle, boolean active) {
        OrgLocation l = mock(OrgLocation.class);
        when(l.getId()).thenReturn(id);
        when(l.getCode()).thenReturn(code);
        when(l.getType()).thenReturn(type);
        when(l.isCycleUnit()).thenReturn(cycle);
        when(l.isActiveOn(TODAY)).thenReturn(active);
        when(l.displayLabel()).thenReturn(code + " label");
        return l;
    }

    private static ActiveGroup group(long id, String role, Set<Long> coverage) {
        return new ActiveGroup(
                id,
                "AG-" + id,
                "Group " + id,
                5L,
                role,
                "Role " + role,
                ScopeType.DIVISION,
                false,
                coverage,
                Set.of(),
                AccessMode.FULL);
    }

    private static AppUser account(long id, String login, AppUser.Status status) {
        AppUser u = mock(AppUser.class);
        when(u.getId()).thenReturn(id);
        when(u.getLoginId()).thenReturn(login);
        when(u.getStatus()).thenReturn(status);
        return u;
    }

    private static AccessGroupScope own(long groupId, Long location) {
        AccessGroupScope r = mock(AccessGroupScope.class);
        when(r.getAccessGroupId()).thenReturn(groupId);
        when(r.getLocationId()).thenReturn(location);
        return r;
    }

    private void readinessFixture() {
        // Units: HQ, three divisions, a unit that runs no payroll, a closed one.
        Map<Long, OrgLocation> locations = new LinkedHashMap<>();
        locations.put(22L, unit(22L, "DL03", LocationType.DO, true, true));
        locations.put(20L, unit(20L, "DL01", LocationType.DO, true, true));
        locations.put(30L, unit(30L, "XX01", LocationType.DO, false, true));
        locations.put(21L, unit(21L, "DL02", LocationType.DO, true, true));
        locations.put(31L, unit(31L, "XX02", LocationType.DO, true, false));
        locations.put(1L, unit(1L, "HQ", LocationType.HQ, true, true));
        OrgDirectory.Snapshot snap =
                new OrgDirectory.Snapshot(locations, Map.of(), Map.of(), Map.of(), Map.of());
        when(org.snapshot()).thenReturn(snap);

        when(assignments.findUserIdsWithOpenAssignments())
                .thenReturn(List.of(10L, 11L, 12L, 13L, 14L, 15L));
        List<AppUser> accounts =
                List.of(
                        account(10L, "hq.maker@fci-demo.test", AppUser.Status.ACTIVE),
                        account(11L, "hq.checker@fci-demo.test", AppUser.Status.ACTIVE),
                        account(12L, "dl.maker@fci-demo.test", AppUser.Status.ACTIVE),
                        account(13L, "dl.checker@fci-demo.test", AppUser.Status.ACTIVE),
                        account(14L, "auditor@fci-demo.test", AppUser.Status.ACTIVE),
                        account(15L, "gone@fci-demo.test", AppUser.Status.SUSPENDED));
        when(users.findAllById(any())).thenReturn(accounts);

        Set<Long> fromAbove = Set.of(1L, 20L, 21L);
        when(resolver.activeGroupsOf(Set.of(10L, 11L, 12L, 13L, 14L), TODAY))
                .thenReturn(
                        Map.of(
                                10L, List.of(group(100L, "PAYROLL_MAKER", fromAbove)),
                                11L, List.of(group(101L, "PAYROLL_CHECKER", fromAbove)),
                                12L, List.of(group(102L, "PAYROLL_MAKER", Set.of(20L))),
                                13L, List.of(group(103L, "PAYROLL_CHECKER", Set.of(20L))),
                                14L,
                                        List.of(
                                                group(
                                                        104L,
                                                        "PAYROLL_AUDITOR",
                                                        Set.of(1L, 20L, 21L, 22L)))));
        List<AccessGroupScope> rows =
                List.of(
                        own(100L, 1L),
                        own(100L, null),
                        own(101L, 1L),
                        own(102L, 20L),
                        own(103L, 20L),
                        own(104L, 1L),
                        own(104L, 20L),
                        own(104L, 21L),
                        own(104L, 22L));
        when(scopes.findByAccessGroupIdIn(Set.of(100L, 101L, 102L, 103L, 104L))).thenReturn(rows);
    }

    @Test
    void SRS_22_11_theReadinessOfEachPayrollUnitIsReadyCoveredFromAboveOrNotReady() {
        readinessFixture();

        UserReportService.ReadinessReport report = service.readiness(admin);

        verify(policy).require(admin, "A-01", "VIEW");
        assertThat(report.doMinimumStaff()).isEqualTo(2L);
        assertThat(report.units())
                .containsExactly(
                        new UserReportService.UnitReadiness(
                                1L,
                                "HQ",
                                "HQ label",
                                "HQ",
                                "READY",
                                List.of("hq.maker@fci-demo.test"),
                                List.of("hq.checker@fci-demo.test"),
                                2,
                                null),
                        new UserReportService.UnitReadiness(
                                20L,
                                "DL01",
                                "DL01 label",
                                "DO",
                                "READY",
                                List.of("dl.maker@fci-demo.test", "hq.maker@fci-demo.test"),
                                List.of("dl.checker@fci-demo.test", "hq.checker@fci-demo.test"),
                                2,
                                true),
                        new UserReportService.UnitReadiness(
                                21L,
                                "DL02",
                                "DL02 label",
                                "DO",
                                "COVERED_FROM_ABOVE",
                                List.of("hq.maker@fci-demo.test"),
                                List.of("hq.checker@fci-demo.test"),
                                0,
                                false),
                        new UserReportService.UnitReadiness(
                                22L,
                                "DL03",
                                "DL03 label",
                                "DO",
                                "NOT_READY",
                                List.of(),
                                List.of(),
                                0,
                                false));
    }

    @Test
    void SRS_22_11_aScopedAdministratorSeesOnlyTheUnitsOfItsOffices() {
        readinessFixture();
        AccessContext zonal =
                new AccessContext(
                        1L,
                        "zonal@x",
                        "Zonal",
                        "s",
                        "P",
                        10L,
                        null,
                        List.of(),
                        Map.of(),
                        Set.of(),
                        Set.of(),
                        Set.of(20L, 21L));
        when(adminScope.isNational(zonal)).thenReturn(false);

        UserReportService.ReadinessReport report = service.readiness(zonal);

        assertThat(report.units())
                .extracting(UserReportService.UnitReadiness::code)
                .containsExactly("DL01", "DL02");
    }

    @Test
    void SRS_22_11_whileTheMinimumStaffIsNotConfiguredNoUnitMeetsOrMissesIt() {
        readinessFixture();
        when(parameters.configured(SystemParameters.Code.DO_MINIMUM_STAFF))
                .thenReturn(OptionalLong.empty());

        UserReportService.ReadinessReport report = service.readiness(admin);

        assertThat(report.doMinimumStaff()).isNull();
        assertThat(report.units())
                .extracting(UserReportService.UnitReadiness::meetsMinimum)
                .containsOnlyNulls();
    }

    @Test
    void SRS_22_11_withoutAnyAssignmentEveryUnitIsNotReady() {
        OrgLocation hq = unit(1L, "HQ", LocationType.HQ, true, true);
        OrgDirectory.Snapshot snap =
                new OrgDirectory.Snapshot(Map.of(1L, hq), Map.of(), Map.of(), Map.of(), Map.of());
        when(org.snapshot()).thenReturn(snap);
        when(assignments.findUserIdsWithOpenAssignments()).thenReturn(List.of());
        when(users.findAllById(any())).thenReturn(List.of());

        UserReportService.ReadinessReport report = service.readiness(admin);

        assertThat(report.units()).hasSize(1);
        assertThat(report.units().get(0).readiness()).isEqualTo("NOT_READY");
        assertThat(report.units().get(0).ownStaff()).isZero();
    }
}
