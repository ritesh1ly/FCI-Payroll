package in.gov.fci.payroll.platform.persistence.h2;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import org.h2.tools.TriggerAdapter;

/**
 * H2 port of {@code trg_cpf_freeze_irreversible} (target DDL v4.0 Section 10, SRS-6-46): a CPF
 * interest freeze is never removed, and its financial year, zone or freeze time change only with an
 * APPROVED authorisation in {@code approval_queue} (entity {@code CPF_INTEREST_FREEZE}, key = the
 * freeze id). Same codes and messages as Oracle.
 */
public class CpfFreezeIrreversibleTrigger extends TriggerAdapter {

    @Override
    public void fire(Connection conn, ResultSet oldRow, ResultSet newRow) throws SQLException {
        if (newRow == null) {
            throw new SQLException(
                    "FCIPAY-CPF-001: a CPF interest freeze cannot be removed.", "45000", 20150);
        }
        boolean keyChanged =
                !Objects.equals(oldRow.getString("FIN_YEAR"), newRow.getString("FIN_YEAR"))
                        || !Objects.equals(oldRow.getObject("ZONE_ID"), newRow.getObject("ZONE_ID"))
                        || !Objects.equals(
                                oldRow.getTimestamp("FROZEN_AT"), newRow.getTimestamp("FROZEN_AT"));
        if (keyChanged && !approved(conn, oldRow.getLong("FREEZE_ID"))) {
            throw new SQLException(
                    "FCIPAY-CPF-002: correcting a CPF interest freeze requires an approved"
                            + " authorisation.",
                    "45000",
                    20151);
        }
    }

    private static boolean approved(Connection conn, long freezeId) throws SQLException {
        try (PreparedStatement ps =
                conn.prepareStatement(
                        "SELECT 1 FROM approval_queue WHERE entity_name = 'CPF_INTEREST_FREEZE'"
                                + " AND entity_key = ? AND status = 'APPROVED'")) {
            ps.setString(1, Long.toString(freezeId));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }
}
