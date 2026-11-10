package in.gov.fci.payroll.penalty.api;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * SRS-14-13 (plan 12 D12-14): the effective-dated increment-stop flag the I-05 increment run reads
 * (module 17): an ACTIVE or PARTLY_REVOKED order of nature INCREMENT_STOP covering the day. A fully
 * revoked or cancelled one no longer stops the increment, and a partly revoked one no longer stops
 * it inside an approved waived period (SRS-14-17: its restoration is I-05's).
 */
@Component
public class IncrementStops {

    private final JdbcTemplate jdbc;

    public IncrementStops(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The Penalty IDs that stop the member's increment on {@code day} (empty: none). */
    public List<String> stopping(long employeeId, LocalDate day) {
        Date d = Date.valueOf(day);
        return jdbc.queryForList(
                "SELECT o.penalty_ref FROM penalty_order o WHERE o.employee_id = ? AND"
                        + " o.penalty_nature = 'INCREMENT_STOP' AND o.order_status IN ('ACTIVE',"
                        + " 'PARTLY_REVOKED') AND o.period_from <= ? AND (o.period_to IS NULL OR"
                        + " o.period_to >= ?) AND NOT EXISTS (SELECT 1 FROM penalty_revoke r WHERE"
                        + " r.order_id = o.order_id AND r.revoke_status = 'APPROVED' AND"
                        + " r.waived_period_from <= ? AND r.waived_period_to >= ?) ORDER BY"
                        + " o.order_id",
                String.class,
                employeeId,
                d,
                d,
                d,
                d);
    }
}
