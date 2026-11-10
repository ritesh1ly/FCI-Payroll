package in.gov.fci.payroll.platform.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.Test;

/** ADR-0025: the next value of the old job sequence is kept when it exists (H2). */
class V029_1MigrationMutationGapsTest {

    private final Connection connection = mock(Connection.class);
    private final Statement statement = mock(Statement.class);

    V029_1MigrationMutationGapsTest() throws Exception {
        when(connection.createStatement()).thenReturn(statement);
    }

    private void oldSequence(Long baseValue) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(baseValue != null);
        if (baseValue != null) {
            when(rs.getLong(1)).thenReturn(baseValue);
        }
        when(statement.executeQuery(anyString())).thenReturn(rs);
    }

    @Test
    void ADR_0025_theNextValueOfTheOldSequenceIsReadWhenItExists() throws Exception {
        oldSequence(57L);
        assertThat(V029_1__Spring_batch_6_job_instance_sequence.nextOfOldSequence(connection))
                .isEqualTo(57L);
    }

    @Test
    void ADR_0025_aFreshDatabaseWithoutTheOldSequenceStartsAtOne() throws Exception {
        oldSequence(null);
        assertThat(V029_1__Spring_batch_6_job_instance_sequence.nextOfOldSequence(connection))
                .isEqualTo(1L);
    }

    @Test
    void ADR_0025_h2StartsTheNewSequenceAtTheLaterOfTheOldNextValueAndTheHighestInstance()
            throws Exception {
        Context context = mock(Context.class);
        DatabaseMetaData meta = mock(DatabaseMetaData.class);
        when(meta.getDatabaseProductName()).thenReturn("H2");
        when(connection.getMetaData()).thenReturn(meta);
        when(context.getConnection()).thenReturn(connection);
        ResultSet old = mock(ResultSet.class);
        when(old.next()).thenReturn(true);
        when(old.getLong(1)).thenReturn(120L);
        ResultSet highest = mock(ResultSet.class);
        when(highest.next()).thenReturn(true);
        when(highest.getLong(1)).thenReturn(9L);
        when(statement.executeQuery(anyString()))
                .thenAnswer(
                        call ->
                                ((String) call.getArgument(0)).contains("INFORMATION_SCHEMA")
                                        ? old
                                        : highest);

        new V029_1__Spring_batch_6_job_instance_sequence().migrate(context);

        verify(statement).execute("CREATE SEQUENCE BATCH_JOB_INSTANCE_SEQ START WITH 120");
    }
}
