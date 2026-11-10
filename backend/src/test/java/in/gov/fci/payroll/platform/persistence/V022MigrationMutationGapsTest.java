package in.gov.fci.payroll.platform.persistence;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.Test;

/** The dictionary lookup of V022 is bound to the table it aligns. */
class V022MigrationMutationGapsTest {

    @Test
    void theCheckLookupIsBoundToEachTableInTurn() throws Exception {
        Connection connection = mock(Connection.class);
        Statement ddl = mock(Statement.class);
        PreparedStatement query = mock(PreparedStatement.class);
        Context context = mock(Context.class);
        DatabaseMetaData meta = mock(DatabaseMetaData.class);
        when(meta.getDatabaseProductName()).thenReturn("Oracle");
        when(connection.getMetaData()).thenReturn(meta);
        when(context.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(ddl);
        when(connection.prepareStatement(anyString())).thenReturn(query);
        ResultSet found = mock(ResultSet.class);
        when(found.next()).thenReturn(true, false, true, false);
        when(found.getString(1)).thenReturn("SYS_C0012", "SYS_C0034");
        when(query.executeQuery()).thenReturn(found);

        new V022__Align_pay_pattern_with_target_ddl().migrate(context);

        verify(query).setString(1, "EMPLOYEE");
        verify(query).setString(1, "PAY_SCALE_BAND");
    }
}
