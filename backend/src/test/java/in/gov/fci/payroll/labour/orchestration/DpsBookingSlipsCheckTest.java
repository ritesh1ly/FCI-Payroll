package in.gov.fci.payroll.labour.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.payroll.api.PreLockCheck;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The pre-payroll check DPS_BOOKING_SLIPS_APPROVED (plan 21 phase 21b; SRS-4-13, SRS-15-39): only
 * the unapproved booking-slips of the cycle unit's depots count, at most ten are listed by number.
 */
class DpsBookingSlipsCheckTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final OrgDirectory org = mock(OrgDirectory.class);
    private final DpsBookingSlipsCheck check = new DpsBookingSlipsCheck(jdbc, org);

    private void depotsOf(long unit, long... depots) {
        OrgLocation u = mock(OrgLocation.class);
        when(u.getId()).thenReturn(unit);
        for (long d : depots) {
            when(org.cycleUnitOf(d)).thenReturn(Optional.of(u));
        }
    }

    // SC-21-0014
    @Test
    void SRS_4_13_theUnapprovedBookingSlipsOfTheUnitsDepotsBlockAndAreNamed() {
        assertThat(check.ruleKey()).isEqualTo("DPS_BOOKING_SLIPS_APPROVED");
        depotsOf(40L, 7L);
        depotsOf(41L, 8L);
        when(org.cycleUnitOf(9L)).thenReturn(Optional.empty());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            rows.add(Map.of("WORK_SLIP_NUMBER", "WS-9-2026090" + i, "DEPOT_LOCATION_ID", 7L));
        }
        rows.add(Map.of("WORK_SLIP_NUMBER", "WS-OTHER", "DEPOT_LOCATION_ID", 8L));
        rows.add(Map.of("WORK_SLIP_NUMBER", "WS-NONE", "DEPOT_LOCATION_ID", 9L));
        when(jdbc.queryForList(anyString(), any(java.sql.Date.class), any(java.sql.Date.class)))
                .thenReturn(rows);
        PreLockCheck.Outcome o = check.evaluate(new PreLockCheck.Cycle(1L, 40L, "202609"));
        assertThat(o.failures()).isEqualTo(12);
        assertThat(o.detail())
                .contains("12 DPS booking-slip(s) of 2026-09 not approved")
                .contains("WS-9-202609010")
                .doesNotContain("WS-9-202609011")
                .doesNotContain("WS-OTHER")
                .doesNotContain("WS-NONE");
    }

    @Test
    void SRS_4_13_everyBookingSlipApprovedPasses() {
        when(jdbc.queryForList(anyString(), any(java.sql.Date.class), any(java.sql.Date.class)))
                .thenReturn(List.of());
        PreLockCheck.Outcome o = check.evaluate(new PreLockCheck.Cycle(1L, 40L, "202612"));
        assertThat(o.failures()).isZero();
        assertThat(o.detail()).isEqualTo("Every DPS booking-slip of 2026-12 approved (I-02)");
    }

    // US-GAP-333 (SRS-4-13 l.4738): a month with no booking-slip passes; a slip entered after the
    // check ran is found by the next run (each run reads the slips as they stand).
    @Test
    void SRS_4_13_noBookingSlipPassesAndASlipEnteredLaterIsFoundByTheNextRun() {
        depotsOf(40L, 7L);
        when(jdbc.queryForList(anyString(), any(java.sql.Date.class), any(java.sql.Date.class)))
                .thenReturn(List.of())
                .thenReturn(
                        List.of(
                                Map.of(
                                        "WORK_SLIP_NUMBER",
                                        "WS-9-20260930-1",
                                        "DEPOT_LOCATION_ID",
                                        7L)));
        PreLockCheck.Cycle cycle = new PreLockCheck.Cycle(1L, 40L, "202609");
        assertThat(check.evaluate(cycle).failures()).isZero();
        PreLockCheck.Outcome later = check.evaluate(cycle);
        assertThat(later.failures()).isOne();
        assertThat(later.detail()).contains("WS-9-20260930-1");
    }
}
