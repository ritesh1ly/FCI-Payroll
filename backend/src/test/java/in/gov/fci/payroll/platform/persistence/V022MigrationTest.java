package in.gov.fci.payroll.platform.persistence;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.Test;

/**
 * The pay-pattern alignment with target DDL v4.0 (IDA / CDA / FND, not FNB): on Oracle the unnamed
 * CHECK is found in the Oracle dictionary, dropped and replaced by the named v4.0 constraint after
 * FNB rows become FND; a schema without such a CHECK is not the one V001 / V002 created, and the
 * migration stops rather than guessing.
 */
class V022MigrationTest {

    private final Connection connection = mock(Connection.class);
    private final Statement ddl = mock(Statement.class);
    private final PreparedStatement query = mock(PreparedStatement.class);
    private final Context context = mock(Context.class);

    V022MigrationTest() throws Exception {
        when(context.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(ddl);
        when(connection.prepareStatement(anyString())).thenReturn(query);
    }

    private void database(String product) throws Exception {
        DatabaseMetaData meta = mock(DatabaseMetaData.class);
        when(meta.getDatabaseProductName()).thenReturn(product);
        when(connection.getMetaData()).thenReturn(meta);
    }

    @Test
    void oracleFindsTheUnnamedCheckInItsDictionaryAndAddsTheNamedOne() throws Exception {
        database("Oracle");
        ResultSet found = mock(ResultSet.class);
        when(found.next()).thenReturn(true, false, true, false);
        when(found.getString(1)).thenReturn("SYS_C0012", "SYS_C0034");
        when(query.executeQuery()).thenReturn(found);
        new V022__Align_pay_pattern_with_target_ddl().migrate(context);
        verify(connection, times(2)).prepareStatement(contains("FROM user_constraints"));
        verify(connection, never()).prepareStatement(contains("information_schema"));
        verify(ddl).execute("ALTER TABLE employee DROP CONSTRAINT \"SYS_C0012\"");
        verify(ddl).execute("ALTER TABLE pay_scale_band DROP CONSTRAINT \"SYS_C0034\"");
        verify(ddl)
                .executeUpdate("UPDATE employee SET pay_pattern = 'FND' WHERE pay_pattern = 'FNB'");
        verify(ddl)
                .execute(
                        "ALTER TABLE employee ADD CONSTRAINT chk_employee_pay_pattern CHECK"
                                + " (pay_pattern IN ('IDA','CDA','FND'))");
    }

    @Test
    void aSchemaWithoutTheFnbCheckStopsTheMigration() throws Exception {
        database("H2");
        ResultSet none = mock(ResultSet.class);
        when(none.next()).thenReturn(false);
        when(query.executeQuery()).thenReturn(none);
        assertThatThrownBy(() -> new V022__Align_pay_pattern_with_target_ddl().migrate(context))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No CHECK naming 'FNB' found on EMPLOYEE");
        verify(ddl, never()).executeUpdate(anyString());
    }
}
