package in.gov.fci.payroll.penalty.api;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The penalty and recovery clearance of a Full &amp; Final Settlement (plan 11 phase 11b;
 * SRS-10-27, SRS-10-9, SRS-14-1): each recovery order of the member still active or paused for the
 * settlement, with the amount not yet recovered (the order's total less its recovered schedule).
 */
@Component
public class PenaltyClearance {

    /** One order with a balance to recover. */
    public record Outstanding(
            long orderId, String penaltyRef, String orderReference, BigDecimal amount) {}

    private final JdbcTemplate jdbc;

    public PenaltyClearance(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<Outstanding> outstanding(long employeeId) {
        return jdbc
                .query(
                        "SELECT o.order_id, o.penalty_ref, o.order_reference,"
                            + " o.total_recovery_amount - COALESCE((SELECT SUM(s.recovered_amount)"
                            + " FROM penalty_recovery_schedule s WHERE s.order_id = o.order_id), 0)"
                            + " AS owed FROM penalty_order o WHERE o.employee_id = ? AND"
                            + " o.order_status IN ('ACTIVE', 'PAUSED_FNF', 'PARTLY_REVOKED') AND"
                            + " o.total_recovery_amount IS NOT NULL ORDER BY o.order_id",
                        (rs, i) ->
                                new Outstanding(
                                        rs.getLong("order_id"),
                                        rs.getString("penalty_ref"),
                                        rs.getString("order_reference"),
                                        rs.getBigDecimal("owed")),
                        employeeId)
                .stream()
                .filter(o -> o.amount() != null && o.amount().signum() > 0)
                .toList();
    }
}
