package in.gov.fci.payroll.platform.persistence.h2;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.h2.tools.TriggerAdapter;

/**
 * H2 port of {@code trg_rule_version_temporal} (target DDL v4.0 S06), BR-RULE-002: at most one
 * APPROVED version of a rule key is active on any date. The Oracle version also notes an end date
 * without a successor; that gap is recorded by the service layer, as there.
 */
public class RuleVersionTemporalTrigger extends TriggerAdapter {

    private static final String OVERLAP =
            "SELECT COUNT(*) FROM rule_version a JOIN rule_version b"
                    + " ON a.rule_id = b.rule_id AND a.rule_version_id < b.rule_version_id"
                    + " AND a.approval_state = 'APPROVED' AND b.approval_state = 'APPROVED'"
                    + " AND a.effective_from <= COALESCE(b.effective_to, DATE '9999-12-31')"
                    + " AND COALESCE(a.effective_to, DATE '9999-12-31') >= b.effective_from"
                    + " WHERE a.rule_id = ?";

    @Override
    public void fire(Connection conn, ResultSet oldRow, ResultSet newRow) throws SQLException {
        if (newRow == null || !"APPROVED".equals(newRow.getString("APPROVAL_STATE"))) {
            return;
        }
        try (PreparedStatement statement = conn.prepareStatement(OVERLAP)) {
            statement.setLong(1, newRow.getLong("RULE_ID"));
            try (ResultSet result = statement.executeQuery()) {
                if (result.next() && result.getLong(1) > 0) {
                    throw new SQLException(
                            "FCIPAY-RUL-001: an overlapping active version already exists for this"
                                + " rule key. Point-in-time reconstruction must never return more"
                                + " than one.",
                            "45000",
                            20120);
                }
            }
        }
    }
}
