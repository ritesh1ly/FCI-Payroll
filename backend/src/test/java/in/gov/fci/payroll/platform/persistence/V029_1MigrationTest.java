package in.gov.fci.payroll.platform.persistence;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.Test;

/**
 * The Spring Batch 6 job-instance sequence (Spring Boot 4 upgrade, ADR-0025): Oracle renames the
 * sequence and keeps its next value; H2 recreates it after the old sequence's next value and the
 * highest job instance, and a fresh database without the old sequence starts after the highest
 * instance.
 */
class V029_1MigrationTest {

    private final Connection connection = mock(Connection.class);
    private final Statement statement = mock(Statement.class);
    private final Context context = mock(Context.class);

    V029_1MigrationTest() throws Exception {
        when(context.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
    }

    private void database(String product) throws Exception {
        DatabaseMetaData meta = mock(DatabaseMetaData.class);
        when(meta.getDatabaseProductName()).thenReturn(product);
        when(connection.getMetaData()).thenReturn(meta);
    }

    @Test
    void ADR_0025_oracleRenamesTheSequenceAndKeepsItsNextValue() throws Exception {
        database("Oracle");
        new V029_1__Spring_batch_6_job_instance_sequence().migrate(context);
        verify(statement).execute("RENAME BATCH_JOB_SEQ TO BATCH_JOB_INSTANCE_SEQ");
        verify(statement, never()).execute("DROP SEQUENCE BATCH_JOB_SEQ");
    }

    @Test
    void ADR_0025_h2WithoutTheOldSequenceStartsAfterTheHighestInstance() throws Exception {
        database("H2");
        ResultSet none = mock(ResultSet.class);
        when(none.next()).thenReturn(false);
        ResultSet highest = mock(ResultSet.class);
        when(highest.next()).thenReturn(true);
        when(highest.getLong(1)).thenReturn(41L);
        when(statement.executeQuery(anyString()))
                .thenAnswer(
                        call ->
                                ((String) call.getArgument(0)).contains("INFORMATION_SCHEMA")
                                        ? none
                                        : highest);
        new V029_1__Spring_batch_6_job_instance_sequence().migrate(context);
        verify(statement).execute("CREATE SEQUENCE BATCH_JOB_INSTANCE_SEQ START WITH 42");
        verify(statement).execute("DROP SEQUENCE BATCH_JOB_SEQ");
    }
}
