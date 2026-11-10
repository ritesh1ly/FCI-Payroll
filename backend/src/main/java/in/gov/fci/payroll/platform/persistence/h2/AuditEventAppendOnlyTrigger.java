package in.gov.fci.payroll.platform.persistence.h2;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.h2.tools.TriggerAdapter;

/**
 * H2 port of {@code trg_audit_event_append_only}: no role, including System Administrator, may
 * amend or delete an audit row (BR-AUDIT, target DDL S05).
 */
public class AuditEventAppendOnlyTrigger extends TriggerAdapter {

    @Override
    public void fire(Connection conn, ResultSet oldRow, ResultSet newRow) throws SQLException {
        throw new SQLException(
                "FCIPAY-AUD-001: the audit trail is append-only. No role, including System"
                        + " Administrator, may amend or delete an audit row.",
                "45000",
                20110);
    }
}
