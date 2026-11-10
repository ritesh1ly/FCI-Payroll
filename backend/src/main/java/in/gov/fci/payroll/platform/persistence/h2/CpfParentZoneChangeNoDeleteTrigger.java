package in.gov.fci.payroll.platform.persistence.h2;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.h2.tools.TriggerAdapter;

/**
 * H2 port of {@code trg_cpf_pzc_no_delete} (target DDL v4.0 Section 10, BR-AUDIT-009, SRS-6-52): a
 * CPF parent-zone change record is never deleted, whatever its status. Same code and message as
 * Oracle.
 */
public class CpfParentZoneChangeNoDeleteTrigger extends TriggerAdapter {

    @Override
    public void fire(Connection conn, ResultSet oldRow, ResultSet newRow) throws SQLException {
        throw new SQLException(
                "FCIPAY-CPF-003: BR-AUDIT-009 - a CPF parent-zone change record is never deleted.",
                "45000",
                20152);
    }
}
