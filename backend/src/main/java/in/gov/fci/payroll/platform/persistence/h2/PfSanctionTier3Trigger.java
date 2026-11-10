package in.gov.fci.payroll.platform.persistence.h2;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.h2.tools.TriggerAdapter;

/**
 * H2 port of {@code trg_pf_sanction_tier3} (target DDL v4.0 Section 10, SRS-6-4, UC-05): a CPF
 * sanction reaches SANCTIONED only when the CURRENT pass of its tier chain holds an OTP-confirmed
 * tier-3 (Competent Authority) SANCTIONED action. A sanction from an earlier pass, before a return,
 * does not count. Same code and message as Oracle.
 */
public class PfSanctionTier3Trigger extends TriggerAdapter {

    @Override
    public void fire(Connection conn, ResultSet oldRow, ResultSet newRow) throws SQLException {
        if (!"SANCTIONED".equals(newRow.getString("WORKFLOW_STAGE"))
                || "SANCTIONED".equals(oldRow.getString("WORKFLOW_STAGE"))) {
            return;
        }
        if (!tier3OnCurrentPass(conn, newRow.getLong("SANCTION_ID"))) {
            throw new SQLException(
                    "FCIPAY-CPF-004: a CPF sanction requires an OTP-confirmed Competent Authority"
                            + " action on the current pass.",
                    "45000",
                    20153);
        }
    }

    private static boolean tier3OnCurrentPass(Connection conn, long sanctionId)
            throws SQLException {
        try (PreparedStatement ps =
                conn.prepareStatement(
                        "SELECT 1 FROM cpf_sanction_tier t WHERE t.sanction_id = ? AND t.tier_no ="
                            + " 3 AND t.tier_action = 'SANCTIONED' AND t.otp_challenge_id IS NOT"
                            + " NULL AND t.pass_no = (SELECT MAX(x.pass_no) FROM cpf_sanction_tier"
                            + " x WHERE x.sanction_id = ?)")) {
            ps.setLong(1, sanctionId);
            ps.setLong(2, sanctionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }
}
