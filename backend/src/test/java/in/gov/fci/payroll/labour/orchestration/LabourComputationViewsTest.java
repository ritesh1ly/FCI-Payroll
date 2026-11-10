package in.gov.fci.payroll.labour.orchestration;

import static in.gov.fci.payroll.ScriptedJdbc.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.ScriptedJdbc;
import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * C-03, C-04, C-05 and C-06 read side (Wireframes v6.1, OD-121; LBR-20d-2, LBR-20d-3, LBR-21d-1,
 * NWNP-22d-1): nothing is computed, each engine reads under its own screen, and the Status says
 * what stands in the way of a run.
 */
class LabourComputationViewsTest {

    private static final YearMonth PERIOD = YearMonth.of(2026, 1);
    private static final PageSpec SPEC = new PageSpec(0, 20);
    private static final String LATEST = "SELECT MAX(w.work_slip_date)";

    private final ScriptedJdbc db = new ScriptedJdbc();
    private final Authorization policy = mock(Authorization.class);
    private final LabourComputationViews views = new LabourComputationViews(db.named, policy);
    private final AccessContext user =
            new AccessContext(
                    3L,
                    "ldh@x",
                    "User",
                    "s",
                    "P",
                    300L,
                    null,
                    List.of(),
                    Map.of(),
                    Set.of(),
                    Set.of("LABOUR_DEALING_HAND"),
                    Set.of(300L));

    private static Map<String, Object> gang(long sardar, long mandal, long slips, Long mln) {
        return row(
                "GANG_ID",
                11L,
                "GANG_CODE",
                "DP-DL01-G002",
                "GANG_NAME",
                "Okhla Gang 1",
                "LOCATION_CODE",
                "DP-DL01",
                "LOCATION_NAME",
                "Depot Okhla",
                "LABOUR",
                4L,
                "SARDAR",
                sardar,
                "MANDAL",
                mandal,
                "HANDLING",
                2L,
                "ANCILLARY",
                0L,
                "PRESENT_DAYS",
                new BigDecimal("96.5"),
                "PRESENT_LABOUR",
                4L,
                "SLIPS",
                slips,
                "BAGS",
                new BigDecimal("4800"),
                "NO_WORK_HOURS",
                new BigDecimal("6"),
                "MLN",
                mln,
                "INCENTIVE",
                null);
    }

    @Test
    void SRS_G_54_eachEngineReadsUnderItsOwnScreen() {
        for (var e :
                Map.of("DEPT_LABOUR", "C-03", "DPS_LABOUR", "C-04", "NWNP_LABOUR", "C-05")
                        .entrySet()) {
            views.gangs(user, e.getKey(), PERIOD, SPEC);
            verify(policy).require(user, e.getValue(), "VIEW");
        }
    }

    @Test
    void SRS_G_2_aRefusedViewReadsNothing() {
        doThrow(new FciException(CommonErrorCode.ACCESS_DENIED))
                .when(policy)
                .require(user, "C-04", "VIEW");
        assertThatThrownBy(() -> views.summary(user, "DPS_LABOUR", PERIOD))
                .isInstanceOf(FciException.class);
        assertThat(db.queries("")).isEmpty();
    }

    @Test
    void LBR_21d_1_theSummaryStoresNoFigureOfARunThatNeverRan() {
        db.value("FROM labour_profile x JOIN org_location", 7L);
        db.value("FROM labour_gang g", 3L);
        var s = views.summary(user, "DPS_LABOUR", PERIOD);
        assertThat(s.population()).isEqualTo(7);
        assertThat(s.computed()).isZero();
        assertThat(s.wage()).isNull();
        assertThat(s.period()).isEqualTo("2026-01");
    }

    @Test
    void SRS_G_54_aPeriodWithoutSlipsShowsTheLatestEarlierOne() {
        db.value(LATEST, Date.valueOf(LocalDate.of(2025, 11, 30)));
        assertThat(views.summary(user, "NWNP_LABOUR", PERIOD).period()).isEqualTo("2025-11");
    }

    @Test
    void SRS_G_54_rowsSayWhatStandsInTheWayOfARun() {
        db.value("SELECT COUNT(*) FROM labour_gang g", 1L);
        db.rows("SELECT g.gang_id", gang(1, 1, 2, 400L));
        var r = views.gangs(user, "DEPT_LABOUR", PERIOD, SPEC).items().get(0);
        assertThat(r.status()).isEqualTo("NOT_COMPUTED");
        assertThat(r.depot()).isEqualTo("DP-DL01 - Depot Okhla");
        assertThat(r.mln()).isEqualTo(400L);
        assertThat(r.incentive()).isNull();
    }

    @Test
    void SRS_G_54_statusOrderOfAGang() {
        var dept = List.of("DEPT_LABOUR");
        var nwnp = List.of("NWNP_LABOUR");
        var both = List.of("DEPT_LABOUR", "DPS_LABOUR");
        assertThat(LabourComputationViews.status(dept, 1, 0, 0, null, null, false))
                .isEqualTo("NO_MANDAL");
        assertThat(LabourComputationViews.status(dept, 0, 1, 0, null, null, false))
                .isEqualTo("NO_MANDAL");
        assertThat(LabourComputationViews.status(nwnp, 1, 0, 0, null, null, false))
                .isEqualTo("GANG_INCOMPLETE");
        assertThat(LabourComputationViews.status(dept, 1, 1, 0, null, null, false))
                .isEqualTo("WORK_SLIP_MISSING");
        assertThat(LabourComputationViews.status(both, 1, 1, 1, null, null, true))
                .isEqualTo("NORM_MISSING");
        assertThat(LabourComputationViews.status(dept, 1, 1, 1, null, null, false))
                .isEqualTo("NOT_COMPUTED");
        assertThat(LabourComputationViews.status(both, 1, 1, 1, 400L, BigDecimal.TEN, true))
                .isEqualTo("COMPUTED");
    }

    @Test
    void SRS_G_54_incentiveReadsOnItsOwnScreenAndAMissingNormIsNotZero() {
        db.value("SELECT COUNT(*) FROM labour_gang g", 1L);
        db.rows("SELECT g.gang_id", gang(1, 1, 1, null));
        var rows = views.incentiveGangs(user, PERIOD, SPEC).items();
        verify(policy).require(user, "C-06", "VIEW");
        assertThat(rows.get(0).status()).isEqualTo("NORM_MISSING");
        assertThat(rows.get(0).mln()).isNull();
    }

    @Test
    void SRS_2_51_incentiveBasicIsListedBesideSalaryBasic() {
        db.value("SELECT COUNT(*) FROM labour_incentive_basic", 1L);
        db.rows(
                "SELECT b.incentive_basic_id",
                row(
                        "INCENTIVE_BASIC_ID",
                        5L,
                        "LABOUR_NUMBER",
                        "L10701",
                        "FIRST_NAME",
                        "Harpal",
                        "LAST_NAME",
                        "Sandhu",
                        "CURRENT_BASIC",
                        new BigDecimal("23800"),
                        "INCENTIVE_BASIC",
                        new BigDecimal("16900"),
                        "EFFECTIVE_FROM",
                        Date.valueOf("2026-01-01")));
        var r = views.basics(user, SPEC).items().get(0);
        assertThat(r.name()).isEqualTo("Harpal Sandhu");
        assertThat(r.incentiveBasic()).isEqualByComparingTo("16900");
    }

    @Test
    void SRS_2_50_recomputationsAreReadAsStored() {
        db.value("SELECT COUNT(*) FROM labour_incentive_recompute c JOIN", 1L);
        db.rows(
                "SELECT c.recompute_id",
                row(
                        "RECOMPUTE_ID",
                        2L,
                        "GANG_CODE",
                        "DP-DL01-G002",
                        "PAYROLL_YYMM",
                        "202601",
                        "RECOMPUTE_REASON",
                        "LATE_ATTACHMENT",
                        "ORIGINAL_TOTAL",
                        new BigDecimal("68300"),
                        "REVISED_TOTAL",
                        new BigDecimal("71920")));
        assertThat(views.recomputations(user, SPEC).items().get(0).reason())
                .isEqualTo("LATE_ATTACHMENT");
    }

    @Test
    void SRS_G_54_incentiveTilesCountNormsAndKeepNoInventedValue() {
        when(policy.isAtHeadquarters(user)).thenReturn(true);
        db.value("SELECT COUNT(DISTINCT g.depot_location_id) FROM labour_gang g", 2L);
        var s = views.incentiveSummary(user, PERIOD);
        assertThat(s.depots()).isEqualTo(2);
        assertThat(s.value()).isNull();
    }
}
