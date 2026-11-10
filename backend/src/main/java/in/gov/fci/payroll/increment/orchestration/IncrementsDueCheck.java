package in.gov.fci.payroll.increment.orchestration;

import in.gov.fci.payroll.organisation.api.OrgDirectory;
import in.gov.fci.payroll.organisation.business.model.OrgLocation;
import in.gov.fci.payroll.payroll.api.PreLockCheck;
import java.sql.Date;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The pre-payroll check INCREMENTS_DUE_APPLIED (plan 17 phase 17c; SRS-13-21, IN-11; DDL {@code
 * pre_payroll_check}, ALL, WARN): the staff members of the cycle unit whose increment fell due in
 * or before the month and is not yet applied - their next due date has not moved on - are counted
 * as a warning for Establishment / HR (I-05). Each member's cycle unit is resolved as everywhere
 * ({@code OrgDirectory.cycleUnitOf}, OD-11).
 */
@Component
public class IncrementsDueCheck implements PreLockCheck {

    static final String KEY = "INCREMENTS_DUE_APPLIED";

    private final JdbcTemplate jdbc;
    private final OrgDirectory org;

    public IncrementsDueCheck(JdbcTemplate jdbc, OrgDirectory org) {
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
                        "SELECT e.location_id, COUNT(*) AS n FROM employee e JOIN"
                                + " employee_increment_due d ON d.employee_id = e.employee_id WHERE"
                                + " e.record_status = 'ACTIVE' AND e.employment_status ="
                                + " 'IN_SERVICE' AND e.emp_lbr_flag = 'N' AND d.next_due_date <= ?"
                                + " GROUP BY e.location_id",
                        Date.valueOf(month.atEndOfMonth()));
        long due = 0;
        for (Map<String, Object> r : rows) {
            Object location = r.get("LOCATION_ID");
            if (location != null
                    && org.cycleUnitOf(((Number) location).longValue())
                            .map(OrgLocation::getId)
                            .filter(id -> id == cycle.orgUnitId())
                            .isPresent()) {
                due += ((Number) r.get("N")).longValue();
            }
        }
        return new Outcome(
                due, due + " increments due in or before " + month + " not applied (I-05)");
    }
}
