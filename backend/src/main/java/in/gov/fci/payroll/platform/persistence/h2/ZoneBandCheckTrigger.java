package in.gov.fci.payroll.platform.persistence.h2;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.h2.tools.TriggerAdapter;

/** H2 port of {@code trg_org_zone_band_check}: CPF number bands may not overlap between zones. */
public class ZoneBandCheckTrigger extends TriggerAdapter {

    private static final String OVERLAP =
            "SELECT COUNT(*) FROM org_zone a JOIN org_zone b ON a.zone_id < b.zone_id"
                    + " AND a.cpf_number_range_start IS NOT NULL"
                    + " AND b.cpf_number_range_start IS NOT NULL"
                    + " AND a.cpf_number_range_start <= b.cpf_number_range_end"
                    + " AND a.cpf_number_range_end >= b.cpf_number_range_start";

    @Override
    public void fire(Connection conn, ResultSet oldRow, ResultSet newRow) throws SQLException {
        if (newRow == null || newRow.getObject("CPF_NUMBER_RANGE_START") == null) {
            return;
        }
        try (PreparedStatement statement = conn.prepareStatement(OVERLAP);
                ResultSet result = statement.executeQuery()) {
            if (result.next() && result.getLong(1) > 0) {
                throw new SQLException(
                        "FCIPAY-ORG-001: CPF number bands overlap between zones.", "45000", 20101);
            }
        }
    }
}
