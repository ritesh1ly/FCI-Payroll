package in.gov.fci.payroll.platform.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import org.springframework.jdbc.core.ArgumentPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.SqlProvider;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

/** The identity value the database generated for an INSERT made through {@code JdbcTemplate}. */
public final class GeneratedKeys {

    private GeneratedKeys() {}

    /**
     * The single generated key as a {@code long}.
     *
     * @throws IllegalStateException if the database returned none (the INSERT did not run as asked)
     */
    public static long id(KeyHolder holder) {
        Number key = holder.getKey();
        if (key == null) {
            throw new IllegalStateException("The database returned no generated key");
        }
        return key.longValue();
    }

    /**
     * Runs {@code INSERT ... VALUES (?, ...)} with {@code args} bound as {@code
     * JdbcTemplate.update(sql, args)} binds them, and answers the identity the database generated
     * for {@code keyColumn} in this statement (Oracle review F2). Never read your own row back with
     * {@code MAX(id)}: identities are NOORDER and cached per instance (RAC, the Autonomous
     * Database), and a concurrent insert with the same natural key makes {@code MAX} answer the
     * other session's row. Oracle returns keys for {@code INSERT ... VALUES} only, not for {@code
     * INSERT ... SELECT}.
     *
     * @throws IllegalStateException if the database returned no key
     */
    public static long insert(JdbcOperations jdbc, String sql, String keyColumn, Object... args) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(new Insert(sql, keyColumn, args), key);
        return id(key);
    }

    /** The INSERT with its key column; its SQL is visible to a statement log (SqlProvider). */
    static final class Insert implements PreparedStatementCreator, SqlProvider {

        private final String sql;
        private final String keyColumn;
        private final Object[] args;

        Insert(String sql, String keyColumn, Object[] args) {
            this.sql = sql;
            this.keyColumn = keyColumn;
            this.args = args == null ? null : args.clone();
        }

        /** The values bound, in order (for tests that check what was written). */
        Object[] args() {
            return args == null ? null : args.clone();
        }

        @Override
        public PreparedStatement createPreparedStatement(Connection connection)
                throws SQLException {
            PreparedStatement ps = connection.prepareStatement(sql, new String[] {keyColumn});
            try {
                new ArgumentPreparedStatementSetter(args).setValues(ps);
            } catch (SQLException | RuntimeException ex) {
                ps.close();
                throw ex;
            }
            return ps;
        }

        @Override
        public String getSql() {
            return sql;
        }
    }
}
