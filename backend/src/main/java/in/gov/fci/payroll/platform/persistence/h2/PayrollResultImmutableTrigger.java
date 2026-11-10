package in.gov.fci.payroll.platform.persistence.h2;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.h2.tools.TriggerAdapter;

/**
 * H2 port of {@code trg_payroll_result_immutable} and {@code trg_prc_immutable} (BR-AUDIT-005): a
 * locked payroll result, and every component line of it, can be neither changed nor deleted. The
 * condition is on the old row alone, as in Oracle, so a two-step unlock cannot defeat it.
 */
public class PayrollResultImmutableTrigger extends TriggerAdapter {

    @Override
    public void fire(Connection conn, ResultSet oldRow, ResultSet newRow) throws SQLException {
        if (oldRow == null || !"Y".equals(oldRow.getString("IS_LOCKED"))) {
            return;
        }
        if ("PAYROLL_RESULT_COMPONENT".equalsIgnoreCase(tableName)) {
            throw new SQLException(
                    "FCIPAY-RES-003: a component line of a locked payroll result is immutable.",
                    "45000",
                    20142);
        }
        if (newRow == null) {
            throw new SQLException(
                    "FCIPAY-RES-001: a locked payroll result cannot be deleted.", "45000", 20140);
        }
        throw new SQLException(
                "FCIPAY-RES-002: a locked payroll result is immutable. Correct through a"
                        + " supplementary payment, an MDP entry or a retrospective arrear event.",
                "45000",
                20141);
    }
}
