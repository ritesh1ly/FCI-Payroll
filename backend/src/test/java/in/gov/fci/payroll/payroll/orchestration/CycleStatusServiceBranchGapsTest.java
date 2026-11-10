package in.gov.fci.payroll.payroll.orchestration;

import static in.gov.fci.payroll.ScriptedJdbc.cols;
import static in.gov.fci.payroll.ScriptedJdbc.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.ScriptedJdbc;
import in.gov.fci.payroll.configuration.api.PayrollPeriods;
import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.LocationType;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.platform.paging.PageSpec;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.Authorization;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * D-01 cycle status home (SRS-15-14): the cycle units of the scope list the user's own unit first
 * and the others by code, wherever the own unit stands in the directory; the period's totals are
 * zero while no run has results (sums of no rows) and the sums once it has.
 */
class CycleStatusServiceBranchGapsTest {

    private static final YearMonth PERIOD = YearMonth.of(2026, 10);

    private final ScriptedJdbc db = new ScriptedJdbc();
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final PayrollPeriods periods = mock(PayrollPeriods.class);
    private final CycleStatusService service =
            new CycleStatusService(db.named, org, mock(Authorization.class), periods);

    private final AccessContext user =
            new AccessContext(
                    3L,
                    "zone@x",
                    "Zone",
                    "s",
                    "P",
                    300L,
                    null,
                    List.of(),
                    Map.of(),
                    Set.of(),
                    Set.of(),
                    Set.of(300L));

    private OrgLocation unit(long id, String code) {
        OrgLocation u = mock(OrgLocation.class);
        when(u.getId()).thenReturn(id);
        when(u.getCode()).thenReturn(code);
        when(u.getName()).thenReturn("Office " + code);
        when(u.getType()).thenReturn(LocationType.DO);
        when(u.isCycleUnit()).thenReturn(true);
        when(org.location(id)).thenReturn(Optional.of(u));
        when(org.cycleUnitOf(id)).thenReturn(Optional.of(u));
        when(org.subtree(id)).thenReturn(Set.of(id));
        return u;
    }

    CycleStatusServiceBranchGapsTest() {
        when(periods.current()).thenReturn(PERIOD);
    }

    @Test
    void SRS_15_14_theOwnUnitIsListedFirstAndTheOthersByCode() {
        OrgLocation own = unit(300L, "Z-OWN");
        OrgLocation b = unit(301L, "B");
        OrgLocation a = unit(302L, "A");
        OrgLocation c = unit(303L, "C");
        when(org.subtree(300L)).thenReturn(Set.of(300L, 301L, 302L, 303L));
        when(org.locations(any())).thenReturn(List.of(b, own, a, c));
        db.rows(
                "SELECT c.org_unit_id, c.cycle_status",
                cols(300L, "LOCKED", 12L, new BigDecimal("360000")));
        List<CycleStatusService.UnitRow> rows = service.units(user, new PageSpec(0, 20)).items();
        assertThat(rows)
                .extracting(CycleStatusService.UnitRow::unitCode)
                .containsExactly("Z-OWN", "A", "B", "C");
        assertThat(rows.getFirst().ownUnit()).isTrue();
        assertThat(rows.getFirst().status()).isEqualTo("LOCKED");
        assertThat(rows.get(1).status()).isEqualTo("NOT_INITIATED");
        assertThat(rows.get(1).members()).isNull();
    }

    @Test
    void SRS_15_14_thePeriodsTotalsAreZeroWithoutResultsAndTheSumsWithThem() {
        unit(300L, "DO-DEL");
        db.rows(
                "SUM(r.gross_earnings) AS g",
                row("n", 0L, "g", null, "d", null, "t", null, "f", null));
        CycleStatusService.Kpis none = service.status(user).kpis();
        assertThat(none.gross()).isEqualByComparingTo("0");
        assertThat(none.deductions()).isEqualByComparingTo("0");
        assertThat(none.net()).isEqualByComparingTo("0");

        db.rows(
                "SUM(r.gross_earnings) AS g",
                row(
                        "n",
                        2L,
                        "g",
                        new BigDecimal("90000.50"),
                        "d",
                        new BigDecimal("10000"),
                        "t",
                        new BigDecimal("80000.50"),
                        "f",
                        0L));
        CycleStatusService.Kpis some = service.status(user).kpis();
        assertThat(some.payable()).isEqualTo(2L);
        assertThat(some.gross()).isEqualByComparingTo("90000.50");
        assertThat(some.net()).isEqualByComparingTo("80000.50");
    }
}
