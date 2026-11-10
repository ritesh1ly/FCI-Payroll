package in.gov.fci.payroll.platform.persistence.h2;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.h2.tools.TriggerAdapter;

/**
 * H2 port of {@code trg_work_slip_no_delete} (target DDL v4.0 Section 14, BR-AUDIT-006): a saved
 * work-slip is never deleted; it is corrected by controlled amendment. Same code and message as
 * Oracle.
 */
public class WorkSlipNoDeleteTrigger extends TriggerAdapter {

    @Override
    public void fire(Connection conn, ResultSet oldRow, ResultSet newRow) throws SQLException {
        throw new SQLException(
                "FCIPAY-WSL-001: BR-AUDIT-006 - a saved work-slip may never be deleted. Correct it"
                        + " by controlled amendment.",
                "45000",
                20160);
    }
}
