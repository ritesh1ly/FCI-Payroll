package in.gov.fci.payroll.platform.persistence.h2;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.h2.tools.TriggerAdapter;

/**
 * H2 port of {@code trg_work_slip_role_singleton} (target DDL v4.0 Section 14): a work-slip may not
 * contain more than one Sardar or more than one Mandal. Same code and message as Oracle.
 */
public class WorkSlipRoleSingletonTrigger extends TriggerAdapter {

    private static final String QUERY =
            "SELECT COUNT(*) FROM (SELECT gang_role, COUNT(*) AS c FROM work_slip_participant WHERE"
                    + " work_slip_id = ? AND gang_role IN ('SARDAR', 'MANDAL') GROUP BY gang_role)"
                    + " WHERE c > 1";

    @Override
    public void fire(Connection conn, ResultSet oldRow, ResultSet newRow) throws SQLException {
        if (newRow == null
                || !("SARDAR".equals(newRow.getString("GANG_ROLE"))
                        || "MANDAL".equals(newRow.getString("GANG_ROLE")))) {
            return;
        }
        try (PreparedStatement statement = conn.prepareStatement(QUERY)) {
            statement.setLong(1, newRow.getLong("WORK_SLIP_ID"));
            try (ResultSet result = statement.executeQuery()) {
                if (result.next() && result.getLong(1) > 0) {
                    throw new SQLException(
                            "FCIPAY-WSL-002: a work-slip may not contain more than one Sardar or"
                                    + " more than one Mandal.",
                            "45000",
                            20161);
                }
            }
        }
    }
}
