package in.gov.fci.payroll.platform.persistence;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.stereotype.Component;

/**
 * Spring Batch 6 job repository (Spring Boot 4 upgrade): the job instance id sequence is renamed
 * {@code BATCH_JOB_SEQ} → {@code BATCH_JOB_INSTANCE_SEQ}, and Oracle gets the index on the
 * execution parameters (Spring Batch's own 6.0 migration scripts). The tables are unchanged.
 *
 * <ul>
 *   <li>Oracle renames the sequence: its next value is kept.
 *   <li>H2 cannot rename a sequence; Spring Batch's H2 script recreates it from 1, which would
 *       collide with the job instances of an existing database. Here the new sequence starts after
 *       both the old sequence's next value and the highest job instance id.
 * </ul>
 *
 * A Java migration because the H2 start value is computed. Rollback:
 * db/rollback/&lt;vendor&gt;/U029_1.
 */
@Component
public class V029_1__Spring_batch_6_job_instance_sequence extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        boolean oracle =
                connection
                        .getMetaData()
                        .getDatabaseProductName()
                        .toLowerCase(Locale.ROOT)
                        .contains("oracle");
        try (Statement ddl = connection.createStatement()) {
            if (oracle) {
                ddl.execute("RENAME BATCH_JOB_SEQ TO BATCH_JOB_INSTANCE_SEQ");
                ddl.execute(
                        "CREATE INDEX BATCH_JOB_EXEC_PARAMS_IDX ON"
                                + " BATCH_JOB_EXECUTION_PARAMS(JOB_EXECUTION_ID)");
                return;
            }
            long start = Math.max(nextOfOldSequence(connection), highestInstanceId(connection) + 1);
            ddl.execute("CREATE SEQUENCE BATCH_JOB_INSTANCE_SEQ START WITH " + start);
            ddl.execute("DROP SEQUENCE BATCH_JOB_SEQ");
        }
    }

    /** H2: the value BATCH_JOB_SEQ would have given next (1 for a fresh database). */
    static long nextOfOldSequence(Connection connection) throws SQLException {
        try (Statement query = connection.createStatement();
                ResultSet rs =
                        query.executeQuery(
                                "SELECT BASE_VALUE FROM INFORMATION_SCHEMA.SEQUENCES WHERE"
                                        + " SEQUENCE_NAME = 'BATCH_JOB_SEQ'")) {
            return rs.next() ? rs.getLong(1) : 1L;
        }
    }

    static long highestInstanceId(Connection connection) throws SQLException {
        try (Statement query = connection.createStatement();
                ResultSet rs =
                        query.executeQuery(
                                "SELECT COALESCE(MAX(JOB_INSTANCE_ID), 0) FROM"
                                        + " BATCH_JOB_INSTANCE")) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
