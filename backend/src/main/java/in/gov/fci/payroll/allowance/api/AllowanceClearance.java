package in.gov.fci.payroll.allowance.api;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The allowances clearance of a Full &amp; Final Settlement (plan 11 phase 11b; SRS-10-27,
 * SRS-10-13 "PLI advance"): the PLI advance still outstanding on each of the member's policies,
 * from its latest transaction ({@code pli_transaction.advance_outstanding}; DDL comment: "ADVANCE
 * feeds fs_recovery_detail.recovery_type=PLI_ADVANCE").
 */
@Component
public class AllowanceClearance {

    /** One policy with an advance outstanding. */
    public record Outstanding(long policyId, String reference, BigDecimal amount) {}

    private final JdbcTemplate jdbc;

    public AllowanceClearance(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<Outstanding> outstanding(long employeeId) {
        return jdbc.query(
                "SELECT t.pli_policy_id, t.advance_outstanding FROM pli_transaction t WHERE"
                    + " t.employee_id = ? AND t.pli_txn_id = (SELECT MAX(x.pli_txn_id) FROM"
                    + " pli_transaction x WHERE x.pli_policy_id = t.pli_policy_id AND"
                    + " x.advance_outstanding IS NOT NULL) AND t.advance_outstanding > 0 ORDER BY"
                    + " t.pli_policy_id",
                (rs, i) ->
                        new Outstanding(
                                rs.getLong(1), "PLI policy " + rs.getLong(1), rs.getBigDecimal(2)),
                employeeId);
    }
}
