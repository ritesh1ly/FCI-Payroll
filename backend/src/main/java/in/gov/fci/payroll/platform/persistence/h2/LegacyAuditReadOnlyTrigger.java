package in.gov.fci.payroll.platform.persistence.h2;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.h2.tools.TriggerAdapter;

/**
 * H2 port of {@code trg_legacy_audit_read_only}: migrated legacy audit records are immutable for
 * every role, System Administrator included (SRS-20-21, BR-AUDIT-008).
 */
public class LegacyAuditReadOnlyTrigger extends TriggerAdapter {

    @Override
    public void fire(Connection conn, ResultSet oldRow, ResultSet newRow) throws SQLException {
        throw new SQLException(
                "FCIPAY-AUD-002: migrated legacy audit records are immutable. No role, including"
                        + " System Administrator, may amend or delete them.",
                "45000",
                20111);
    }
}
