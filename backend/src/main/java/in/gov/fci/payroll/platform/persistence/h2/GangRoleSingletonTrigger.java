package in.gov.fci.payroll.platform.persistence.h2;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.h2.tools.TriggerAdapter;

/**
 * H2 port of {@code trg_gang_role_singleton} (target DDL v4.0 Section 4; SRS-2-1, BR-LABM-001): a
 * gang holds exactly one active Sardar and one active Mandal on any date. Same code and message as
 * Oracle.
 */
public class GangRoleSingletonTrigger extends TriggerAdapter {

    private static final String QUERY =
            "SELECT COUNT(*) FROM labour_gang_membership a JOIN labour_gang_membership b ON"
                + " a.gang_id = b.gang_id AND a.gang_role = b.gang_role AND a.membership_id <"
                + " b.membership_id AND a.gang_role IN ('SARDAR', 'MANDAL') AND a.effective_from <="
                + " COALESCE(b.effective_to, DATE '9999-12-31') AND COALESCE(a.effective_to, DATE"
                + " '9999-12-31') >= b.effective_from WHERE a.gang_id = ?";

    @Override
    public void fire(Connection conn, ResultSet oldRow, ResultSet newRow) throws SQLException {
        if (newRow == null
                || !("SARDAR".equals(newRow.getString("GANG_ROLE"))
                        || "MANDAL".equals(newRow.getString("GANG_ROLE")))) {
            return;
        }
        try (PreparedStatement statement = conn.prepareStatement(QUERY)) {
            statement.setLong(1, newRow.getLong("GANG_ID"));
            try (ResultSet result = statement.executeQuery()) {
                if (result.next() && result.getLong(1) > 0) {
                    throw new SQLException(
                            "FCIPAY-LBR-001: a gang may hold exactly one active Sardar and one"
                                    + " active Mandal for any given date.",
                            "45000",
                            20102);
                }
            }
        }
    }
}
