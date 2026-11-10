package in.gov.fci.payroll.platform.persistence.h2;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.h2.tools.TriggerAdapter;

/**
 * H2 port of {@code trg_labour_cpf_xuniq} (target DDL v4.0 Section 4; LC-9, FRS v0.4 Sec 8.7.1.2):
 * a labour CPF code is unique across the Corporation: none held by a staff member. Same code and
 * message as Oracle.
 */
public class LabourCpfCrossUniqueTrigger extends TriggerAdapter {

    private static final String QUERY = "SELECT COUNT(*) FROM employee WHERE cpf_code = ?";

    @Override
    public void fire(Connection conn, ResultSet oldRow, ResultSet newRow) throws SQLException {
        if (newRow == null || newRow.getString("CPF_CODE") == null) {
            return;
        }
        try (PreparedStatement statement = conn.prepareStatement(QUERY)) {
            statement.setString(1, newRow.getString("CPF_CODE"));
            try (ResultSet result = statement.executeQuery()) {
                if (result.next() && result.getLong(1) > 0) {
                    throw new SQLException(
                            "FCIPAY-CPF-101: CPF code already held by a staff member; CPF codes are"
                                    + " unique across the Corporation.",
                            "45000",
                            20103);
                }
            }
        }
    }
}
