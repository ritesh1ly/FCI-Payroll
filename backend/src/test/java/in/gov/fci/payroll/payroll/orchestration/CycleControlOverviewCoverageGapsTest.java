package in.gov.fci.payroll.payroll.orchestration;

import static in.gov.fci.payroll.ScriptedJdbc.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.ScriptedJdbc;
import in.gov.fci.payroll.document.api.DocumentStore;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.LocationType;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.payroll.orchestration.CycleControlOverview.FallbackRow;
import in.gov.fci.payroll.payroll.orchestration.CycleControlOverview.LevelRow;
import in.gov.fci.payroll.payroll.orchestration.CycleControlOverview.Overview;
import in.gov.fci.payroll.payroll.orchestration.CycleControlOverview.PeriodRow;
import in.gov.fci.payroll.payroll.orchestration.CycleControlOverview.ReportRow;
import in.gov.fci.payroll.payroll.orchestration.CycleControlOverview.RunState;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.paging.Paged;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * C-01 Cycle Control overview, Period Register and Pre-Payroll Exception Report (SRS l.1755-1767,
 * SRS-15-12, SRS-15-39): an initiated cycle shows its status, flags, tiles, run progress and lease
 * as stored; the days to disbursement are counted from today in India; every organisational level
 * has a row even with no instance; a period or report the database cannot count is empty.
 */
class CycleControlOverviewCoverageGapsTest {

    private static final YearMonth PERIOD = YearMonth.of(2026, 10);
    private static final Instant AT = Instant.parse("2026-10-20T05:30:00Z");

    private final ScriptedJdbc db = new ScriptedJdbc();
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final CycleGateService gates = mock(CycleGateService.class);
    private final CycleLeases leases = mock(CycleLeases.class);
    private final PreRunChecks preRun = mock(PreRunChecks.class);
    private final AccessContext context = mock(AccessContext.class);
    private final OrgLocation unit = mock(OrgLocation.class);
    // 25 October 2026, 20:00 UTC is already 26 October in India.
    private final CycleControlOverview service =
            new CycleControlOverview(
                    db.named,
                    org,
                    mock(Authorization.class),
                    gates,
                    leases,
                    preRun,
                    mock(DocumentStore.class),
                    Clock.fixed(Instant.parse("2026-10-25T20:00:00Z"), ZoneOffset.UTC));

    @BeforeEach
    void session() {
        when(context.locationId()).thenReturn(9L);
        when(context.dataScope()).thenReturn(Set.of(5L));
        when(unit.getId()).thenReturn(5L);
        when(unit.getCode()).thenReturn("DH001");
        when(unit.getName()).thenReturn("Delhi Region");
        when(unit.getType()).thenReturn(LocationType.RO);
        when(org.cycleUnitOf(9L)).thenReturn(Optional.of(unit));
    }

    private void cycle(String supplementary, String mdp) {
        db.rows(
                "SELECT * FROM payroll_cycle_instance WHERE payroll_yymm",
                row(
                        "CYCLE_INSTANCE_ID",
                        41L,
                        "CYCLE_STATUS",
                        "FROZEN",
                        "FROZEN_BY",
                        "maker.delhi",
                        "FROZEN_AT",
                        Timestamp.from(AT),
                        "POSTED_TO_EBS_FLAG",
                        "Y ",
                        "SUPPLEMENTARY_OPEN",
                        supplementary,
                        "MDP_WINDOW_OPEN",
                        mdp,
                        "VERIFIED_BY",
                        "checker.delhi",
                        "VERIFIED_AT",
                        Timestamp.from(AT)));
    }

    @Test
    void SRS_15_12_anInitiatedCycleShowsItsStatusFlagsTilesRunAndLease() {
        cycle("Y", "N");
        db.rows(
                "FROM payroll_calendar",
                row("DISBURSEMENT_DATE", Date.valueOf(LocalDate.of(2026, 10, 31))));
        db.rows(
                "FROM computation_run r WHERE r.cycle_instance_id = :cycle",
                row(
                        "RUN_ID",
                        501L,
                        "RUN_STATUS",
                        "RUNNING",
                        "STARTED_BY",
                        "maker.delhi",
                        "STARTED_AT",
                        Timestamp.from(AT),
                        "UNIT_TOTAL",
                        10,
                        "UNIT_COMMITTED",
                        7,
                        "UNIT_FAILED",
                        1));
        db.rows(
                "GROUP BY org_unit_level",
                row("ORG_UNIT_LEVEL", "RO", "INSTANCES", 3L, "LOCKED", 1L));
        db.rows(
                "FROM v_cycle_hrms_fallback_status f",
                row(
                        "LOCATION_CODE", "DH001",
                        "LOCATION_NAME", "Delhi Region",
                        "FALLBACK_TYPE", "ATTENDANCE",
                        "ENABLEMENT_STATUS", "ENABLED",
                        "BLOCKS_LOCK", "Y"),
                row(
                        "LOCATION_CODE", "DH002",
                        "LOCATION_NAME", "Delhi Depot",
                        "FALLBACK_TYPE", "ATTENDANCE",
                        "ENABLEMENT_STATUS", "CLOSED",
                        "BLOCKS_LOCK", "N"));
        when(gates.engineStates(41L)).thenReturn(Map.of("STAFF", "COMPUTED"));
        when(gates.blockingExceptions(41L)).thenReturn(3L);
        CycleLeases.Lease lease =
                new CycleLeases.Lease(41L, "COMPUTE", "maker.delhi", AT, AT, 501L, false);
        when(leases.current(41L)).thenReturn(Optional.of(lease));
        List<PreRunChecks.Check> checks = List.of(mock(PreRunChecks.Check.class));
        when(preRun.evaluate(5L, PERIOD)).thenReturn(checks);

        Overview o = service.overview(context, PERIOD);

        assertThat(o.period()).isEqualTo("2026-10");
        assertThat(o.unitCode()).isEqualTo("DH001");
        assertThat(o.unitLabel()).isEqualTo("Delhi Region");
        assertThat(o.unitLevel()).isEqualTo("RO");
        assertThat(o.cycleId()).isEqualTo(41L);
        assertThat(o.status()).isEqualTo("FROZEN");
        assertThat(o.frozenBy()).isEqualTo("maker.delhi");
        assertThat(o.frozenAt()).isEqualTo(AT);
        assertThat(o.postedToEbs()).isTrue();
        assertThat(o.supplementaryOpen()).isTrue();
        assertThat(o.tiles().disbursement()).isEqualTo(LocalDate.of(2026, 10, 31));
        assertThat(o.tiles().daysToDisbursement()).isEqualTo(5L);
        assertThat(o.tiles().engines()).containsEntry("STAFF", "COMPUTED");
        assertThat(o.tiles().blockingExceptions()).isEqualTo(3);
        assertThat(o.tiles().verifiedBy()).isEqualTo("checker.delhi");
        assertThat(o.tiles().verifiedAt()).isEqualTo(AT);
        assertThat(o.preRun()).isSameAs(checks);
        assertThat(o.run()).isEqualTo(new RunState(501L, "RUNNING", "maker.delhi", AT, 10, 7, 1));
        assertThat(o.lease()).isSameAs(lease);
        assertThat(o.levels())
                .containsExactly(
                        new LevelRow("HQ", 0, 0),
                        new LevelRow("ZO", 0, 0),
                        new LevelRow("RO", 3, 1),
                        new LevelRow("DO", 0, 0));
        assertThat(o.fallback())
                .containsExactly(
                        new FallbackRow("DH001", "Delhi Region", "ATTENDANCE", "ENABLED", true),
                        new FallbackRow("DH002", "Delhi Depot", "ATTENDANCE", "CLOSED", false));
    }

    @Test
    void SRS_15_12_anMdpWindowAloneOpensTheSupplementaryFlagAndNoRunOrLeaseIsNull() {
        cycle("N", "Y");
        db.rows("FROM payroll_calendar", row("DISBURSEMENT_DATE", null));

        Overview o = service.overview(context, PERIOD);

        assertThat(o.supplementaryOpen()).isTrue();
        assertThat(o.tiles().disbursement()).isNull();
        assertThat(o.tiles().daysToDisbursement()).isNull();
        assertThat(o.run()).isNull();
        assertThat(o.lease()).isNull();
        verify(leases).current(41L);
    }

    @Test
    void SRS_15_12_neitherWindowOpenAndARunWithoutUnitsShowsNoUnitTotal() {
        cycle("N", null);
        db.rows(
                "FROM computation_run r WHERE r.cycle_instance_id = :cycle",
                row(
                        "RUN_ID",
                        501L,
                        "RUN_STATUS",
                        "QUEUED",
                        "STARTED_BY",
                        "maker.delhi",
                        "STARTED_AT",
                        null,
                        "UNIT_TOTAL",
                        null,
                        "UNIT_COMMITTED",
                        0,
                        "UNIT_FAILED",
                        0));

        Overview o = service.overview(context, PERIOD);

        assertThat(o.supplementaryOpen()).isFalse();
        assertThat(o.run())
                .isEqualTo(new RunState(501L, "QUEUED", "maker.delhi", null, null, 0, 0));
        // No calendar date: no countdown.
        assertThat(o.tiles().daysToDisbursement()).isNull();
    }

    @Test
    void SRS_G_2_aSessionWithoutAnyOfficeInScopeSeesNoInstanceOfAnyLevel() {
        when(context.dataScope()).thenReturn(null);

        Overview o = service.overview(context, PERIOD);

        assertThat(o.cycleId()).isNull();
        assertThat(o.status()).isNull();
        assertThat(o.postedToEbs()).isFalse();
        assertThat(o.levels()).extracting(LevelRow::instances).containsOnly(0L);
        verify(leases, never()).current(anyLong());
    }

    @Test
    void SRS_15_12_thePeriodRegisterShowsEachFlagBesideTheStatus() {
        db.value("SELECT COUNT(*) FROM payroll_cycle_instance WHERE org_unit_id", 2L);
        db.rows(
                "ORDER BY c.payroll_yymm DESC",
                row(
                        "PAYROLL_YYMM", "202610",
                        "CYCLE_STATUS", "LOCKED",
                        "VERIFIED_BY", "checker.delhi",
                        "VERIFIED_AT", Timestamp.from(AT),
                        "LOCKED_AT", Timestamp.from(AT),
                        "POSTED_TO_EBS_FLAG", "Y",
                        "SUPPLEMENTARY_OPEN", "N",
                        "MDP_WINDOW_OPEN", "Y",
                        "COMPUTED_AT", Timestamp.from(AT)),
                row(
                        "PAYROLL_YYMM", "202609",
                        "CYCLE_STATUS", "INITIATED",
                        "VERIFIED_BY", null,
                        "VERIFIED_AT", null,
                        "LOCKED_AT", null,
                        "POSTED_TO_EBS_FLAG", "N",
                        "SUPPLEMENTARY_OPEN", "Y",
                        "MDP_WINDOW_OPEN", "N",
                        "COMPUTED_AT", null));

        Paged<PeriodRow> page = service.periods(context, new PageSpec(0, 20));

        assertThat(page.total()).isEqualTo(2);
        assertThat(page.items())
                .containsExactly(
                        new PeriodRow(
                                "2026-10",
                                "LOCKED",
                                AT,
                                "checker.delhi",
                                AT,
                                AT,
                                true,
                                false,
                                true),
                        new PeriodRow(
                                "2026-09",
                                "INITIATED",
                                null,
                                null,
                                null,
                                null,
                                false,
                                true,
                                false));
    }

    @Test
    void SRS_15_39_theReportListsEachFailedCheckWithItsFixingScreen() {
        db.value("SELECT COUNT(*) FROM cycle_prepay_check_result r", 1L);
        db.rows(
                "SELECT l.location_code, l.location_name, p.check_code",
                row(
                        "LOCATION_CODE", "DH001",
                        "LOCATION_NAME", "Delhi Region",
                        "CHECK_CODE", "PC-01",
                        "CHECK_NAME", "Bank account",
                        "SEVERITY_AT_EVAL", "BLOCKING",
                        "FAILURE_COUNT", 4L,
                        "EVALUATION_DETAIL", "4 members",
                        "CORRECTION_SCREEN", "M-01"));

        Paged<ReportRow> page = service.report(context, PERIOD, new PageSpec(0, 20));

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items())
                .containsExactly(
                        new ReportRow(
                                "DH001",
                                "Delhi Region",
                                "PC-01",
                                "Bank account",
                                "BLOCKING",
                                4L,
                                "4 members",
                                "M-01"));
    }

    @Test
    void SRS_15_39_countsTheDatabaseDoesNotReturnAreEmptyLists() {
        db.value("SELECT COUNT(*)", ScriptedJdbc.NULL);
        when(context.dataScope()).thenReturn(Set.of());

        assertThat(service.periods(context, new PageSpec(0, 20)).total()).isZero();
        assertThat(service.report(context, PERIOD, new PageSpec(0, 20)).total()).isZero();
    }
}
