package in.gov.fci.payroll.platform.persistence.h2;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.h2.tools.TriggerAdapter;

/**
 * H2 port of {@code trg_cycle_status_forward_only} (target DDL v4.0, SRS-15-10, BR-CYC-016 / 017):
 * the payroll cycle moves forward (INITIATED, FROZEN, COMPUTED, VERIFIED, LOCKED) and a locked
 * cycle is never reopened. A regression before lock is admitted only when it is recorded in the
 * same transaction: an approved override in {@code cycle_override_log} (same from / to), an
 * un-freeze to INITIATED in {@code cycle_unfreeze} executed within the last five minutes, or a
 * Checker return from COMPUTED / VERIFIED to FROZEN with an OPEN {@code cycle_return}. Same codes
 * and messages as Oracle.
 */
public class CycleStatusForwardOnlyTrigger extends TriggerAdapter {

    private static final List<String> ORDER =
            List.of("INITIATED", "FROZEN", "COMPUTED", "VERIFIED", "LOCKED");

    @Override
    public void fire(Connection conn, ResultSet oldRow, ResultSet newRow) throws SQLException {
        if (oldRow == null || newRow == null) {
            return;
        }
        String before = oldRow.getString("CYCLE_STATUS");
        String after = newRow.getString("CYCLE_STATUS");
        if ("LOCKED".equals(before) && !"LOCKED".equals(after)) {
            throw new SQLException(
                    "FCIPAY-CYC-002: a locked payroll cycle is never reopened. Correct a locked"
                        + " period by a supplementary payment, a Maintain Direct Payroll entry or"
                        + " an arrear in a later cycle.",
                    "45000",
                    20131);
        }
        if (ORDER.indexOf(after) < ORDER.indexOf(before)
                && !recorded(conn, newRow.getLong("CYCLE_INSTANCE_ID"), before, after)) {
            throw new SQLException(
                    "FCIPAY-CYC-001: the payroll cycle is forward-only. A regression requires an"
                            + " approved un-freeze, a Checker return or an approved, OTP-gated"
                            + " override.",
                    "45000",
                    20130);
        }
    }

    /** Whether the regression from {@code before} to {@code after} is recorded as v4.0 admits. */
    static boolean recorded(Connection conn, long instance, String before, String after)
            throws SQLException {
        if (exists(
                conn,
                "SELECT 1 FROM cycle_override_log WHERE cycle_instance_id = ? AND from_status = ?"
                        + " AND to_status = ?",
                instance,
                before,
                after)) {
            return true;
        }
        if ("INITIATED".equals(after)
                && exists(
                        conn,
                        "SELECT 1 FROM cycle_unfreeze WHERE cycle_instance_id = ? AND from_status ="
                                + " ? AND executed_at >= DATEADD(MINUTE, -5, CURRENT_TIMESTAMP)",
                        instance,
                        before)) {
            return true;
        }
        return "FROZEN".equals(after)
                && ("COMPUTED".equals(before) || "VERIFIED".equals(before))
                && exists(
                        conn,
                        "SELECT 1 FROM cycle_return WHERE cycle_instance_id = ? AND from_status = ?"
                                + " AND return_status = 'OPEN'",
                        instance,
                        before);
    }

    private static boolean exists(Connection conn, String sql, long instance, String... statuses)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, instance);
            for (int i = 0; i < statuses.length; i++) {
                ps.setString(i + 2, statuses[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }
}
