package in.gov.fci.payroll.allowance.orchestration;

import in.gov.fci.payroll.allowance.persistence.AllowanceRepositories;
import in.gov.fci.payroll.payroll.api.CycleLocked;
import java.util.List;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * SRS-11-16: a selection approved while the month's cycle was frozen, after the member's result was
 * computed, is received after the freeze although no month was locked yet: when the cycle locks,
 * its arrear is raised for that month, in the lock's transaction. A selection the run read is
 * untouched: nothing differs.
 */
@Component
public class CafeteriaLockEffect {

    private final JdbcTemplate jdbc;
    private final AllowanceRepositories.Elections elections;
    private final CafeteriaArrears arrears;

    public CafeteriaLockEffect(
            JdbcTemplate jdbc,
            AllowanceRepositories.Elections elections,
            CafeteriaArrears arrears) {
        this.jdbc = jdbc;
        this.elections = elections;
        this.arrears = arrears;
    }

    @EventListener
    public void on(CycleLocked locked) {
        List<Long> late =
                jdbc.queryForList(
                        "SELECT c.election_id FROM cafeteria_election c JOIN payroll_result r ON"
                                + " r.employee_id = c.employee_id AND r.cycle_instance_id = ? AND"
                                + " r.run_type = 'ACTUAL' AND r.result_status = 'CURRENT' WHERE"
                                + " c.election_status IN ('APPLIED', 'ARREAR_RAISED') AND"
                                + " c.received_at > r.computed_at ORDER BY c.election_id",
                        Long.class,
                        locked.cycleInstanceId());
        for (Long id : late) {
            arrears.settle(elections.findById(id).orElseThrow(), locked.lockedBy(), null);
        }
    }
}
