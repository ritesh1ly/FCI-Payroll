package in.gov.fci.payroll.platform.persistence.h2;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import org.h2.tools.TriggerAdapter;

/**
 * H2 port of {@code trg_supp_period_locked} (target DDL v4.0 S18; BR-CYC-010, SRS-G-33): "A
 * supplementary payment can be entered only after the month-end lock of the period it relates to."
 * The row must point at a cycle instance whose status is LOCKED, and its {@code for_period_yymm}
 * must be that instance's period. Migrated history ({@code record_origin = 'MIGRATED'}) predates
 * the rule and is exempt, as in Oracle.
 *
 * <p>Oracle fires on {@code INSERT OR UPDATE OF cycle_instance_id, for_period_yymm}; here an update
 * is checked only when one of those two columns changed.
 */
public class SupplementaryPeriodLockedTrigger extends TriggerAdapter {

    @Override
    public void fire(Connection conn, ResultSet oldRow, ResultSet newRow) throws SQLException {
        if (newRow == null || !"SYSTEM".equals(newRow.getString("RECORD_ORIGIN"))) {
            return;
        }
        Object cycle = newRow.getObject("CYCLE_INSTANCE_ID");
        String period = newRow.getString("FOR_PERIOD_YYMM");
        if (cycle == null) {
            return; // chk_supp_locked_cycle (a CHECK) already refuses a SYSTEM row without a cycle
        }
        if (oldRow != null
                && Objects.equals(cycle, oldRow.getObject("CYCLE_INSTANCE_ID"))
                && Objects.equals(period, oldRow.getString("FOR_PERIOD_YYMM"))) {
            return; // neither watched column changed
        }
        try (PreparedStatement ps =
                conn.prepareStatement(
                        "SELECT cycle_status, payroll_yymm FROM payroll_cycle_instance WHERE"
                                + " cycle_instance_id = ?")) {
            ps.setObject(1, cycle);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return; // the foreign key reports a missing instance
                }
                // Same order as Oracle: the period mismatch is reported before the lock state.
                if (!Objects.equals(rs.getString("PAYROLL_YYMM"), period)) {
                    throw new SQLException(
                            "FCIPAY-SUP-003: the supplementary period must be the period of the"
                                    + " locked cycle instance it is raised against.",
                            "45000",
                            20182);
                }
                if (!"LOCKED".equals(rs.getString("CYCLE_STATUS"))) {
                    throw new SQLException(
                            "FCIPAY-SUP-002: supplementary payment is accepted only for a month-end"
                                    + " locked period (BR-CYC-010).",
                            "45000",
                            20181);
                }
            }
        }
    }
}
