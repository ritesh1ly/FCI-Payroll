package in.gov.fci.payroll.platform.persistence.h2;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.h2.tools.TriggerAdapter;

/**
 * H2 port of {@code trg_employee_cpf_xuniq} (target DDL v4.0 Section 4; LC-9, FRS v0.4 Sec 7.4.1):
 * a staff CPF code is unique across the Corporation: none held by a labour member (a labour
 * member's code is on the labour profile only). Same code and message as Oracle.
 */
public class EmployeeCpfCrossUniqueTrigger extends TriggerAdapter {

    private static final String QUERY = "SELECT COUNT(*) FROM labour_profile WHERE cpf_code = ?";

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
                            "FCIPAY-CPF-102: CPF code already held by a labour member; CPF codes"
                                    + " are unique across the Corporation.",
                            "45000",
                            20104);
                }
            }
        }
    }
}
