package in.gov.fci.payroll.platform.persistence.h2;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import org.h2.tools.TriggerAdapter;

/**
 * H2 port of {@code trg_supp_component_excluded} (target DDL v4.0 S18; BR-PEN2-003): a
 * supplementary payment (I-23) may not carry a pay code that M-14 excludes from supplementary
 * payment ({@code pay_component.supp_excluded_flag = 'Y'}, e.g. Court Recovery 366 or Bank Loan
 * Recovery 397). The excluded set is configuration on {@code pay_component}, never a list in code.
 *
 * <p>Oracle fires on {@code INSERT OR UPDATE OF component_id}; H2 cannot name columns, so an update
 * is checked only when {@code component_id} actually changed.
 */
public class SupplementaryComponentExcludedTrigger extends TriggerAdapter {

    @Override
    public void fire(Connection conn, ResultSet oldRow, ResultSet newRow) throws SQLException {
        if (newRow == null) {
            return; // a delete: nothing to check (and business rows are never deleted)
        }
        Object component = newRow.getObject("COMPONENT_ID");
        if (component == null
                || (oldRow != null
                        && Objects.equals(component, oldRow.getObject("COMPONENT_ID")))) {
            return;
        }
        try (PreparedStatement ps =
                conn.prepareStatement(
                        "SELECT supp_excluded_flag FROM pay_component WHERE component_id = ?")) {
            ps.setObject(1, component);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && "Y".equals(rs.getString(1))) {
                    throw new SQLException(
                            "FCIPAY-SUP-001: this pay code is excluded from supplementary payment"
                                    + " (M-14 exclusion list).",
                            "45000",
                            20180);
                }
            }
        }
    }
}
