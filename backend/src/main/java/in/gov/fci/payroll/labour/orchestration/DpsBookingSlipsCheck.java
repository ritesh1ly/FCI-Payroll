package in.gov.fci.payroll.labour.orchestration;

import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.payroll.api.PreLockCheck;
import java.sql.Date;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The pre-payroll check DPS_BOOKING_SLIPS_APPROVED (plan 21 phase 21b; SRS-4-13, SRS-2-34,
 * SRS-15-39; DDL {@code pre_payroll_check}, DPS_LABOUR, BLOCK): the DPS booking-slips of the period
 * at the depots of the cycle unit that the Depot Manager has not approved (draft, with the Depot
 * Manager or returned) block the DPS payroll, listed by number. Each depot's cycle unit is resolved
 * as everywhere ({@code OrgDirectory.cycleUnitOf}, OD-11).
 */
@Component
public class DpsBookingSlipsCheck implements PreLockCheck {

    static final String KEY = "DPS_BOOKING_SLIPS_APPROVED";
    private static final int LISTED = 10;

    private final JdbcTemplate jdbc;
    private final OrgDirectory org;

    public DpsBookingSlipsCheck(JdbcTemplate jdbc, OrgDirectory org) {
        this.jdbc = jdbc;
        this.org = org;
    }

    @Override
    public String ruleKey() {
        return KEY;
    }

    @Override
    public Outcome evaluate(Cycle cycle) {
        String yymm = cycle.payrollYymm();
        YearMonth month =
                YearMonth.of(
                        Integer.parseInt(yymm.substring(0, 4)),
                        Integer.parseInt(yymm.substring(4)));
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT work_slip_number, depot_location_id FROM work_slip WHERE"
                                + " engine_code = 'DPS_LABOUR' AND work_slip_date BETWEEN ? AND ?"
                                + " AND slip_status IN ('DRAFT', 'SUBMITTED', 'REJECTED') ORDER BY"
                                + " work_slip_date, work_slip_number",
                        Date.valueOf(month.atDay(1)),
                        Date.valueOf(month.atEndOfMonth()));
        List<String> pending = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            long depot = ((Number) r.get("DEPOT_LOCATION_ID")).longValue();
            if (org.cycleUnitOf(depot)
                    .map(OrgLocation::getId)
                    .filter(id -> id == cycle.orgUnitId())
                    .isPresent()) {
                pending.add((String) r.get("WORK_SLIP_NUMBER"));
            }
        }
        String listed = String.join(", ", pending.subList(0, Math.min(LISTED, pending.size())));
        return new Outcome(
                pending.size(),
                pending.isEmpty()
                        ? "Every DPS booking-slip of " + month + " approved (I-02)"
                        : pending.size()
                                + " DPS booking-slip(s) of "
                                + month
                                + " not approved by the Depot Manager (I-02): "
                                + listed);
    }
}
