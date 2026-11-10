package in.gov.fci.payroll.platform.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.stereotype.Component;

/**
 * Aligns the pay pattern codes with target DDL v4.0: {@code employee.pay_pattern} and {@code
 * pay_scale_band.scale_type} admitted 'FNB' (V001 / V002, generated from v3.0), while v4.0 and the
 * rest of the schema ({@code rate_set_line}, the pay rules, the M-16 list PAY_PATTERN) use 'FND',
 * so an FND employee could not be stored. The two CHECKs were declared inline, so the database
 * named them (Oracle SYS_C…, H2 CONSTRAINT_…): this migration finds each by its condition in the
 * dictionary, drops it, converts any FNB row to FND and adds the named constraint with the v4.0
 * values. A Java migration because neither name is known to a plain script. Rollback:
 * db/rollback/&lt;vendor&gt;/U022.
 */
@Component
public class V022__Align_pay_pattern_with_target_ddl extends BaseJavaMigration {

    private record Target(String table, String column, String constraint) {}

    private static final List<Target> TARGETS =
            List.of(
                    new Target("EMPLOYEE", "pay_pattern", "chk_employee_pay_pattern"),
                    new Target("PAY_SCALE_BAND", "scale_type", "chk_pay_scale_band_scale_type"));

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
            for (Target target : TARGETS) {
                for (String name : fnbChecks(connection, oracle, target.table())) {
                    ddl.execute(
                            "ALTER TABLE "
                                    + target.table().toLowerCase(Locale.ROOT)
                                    + " DROP CONSTRAINT "
                                    + quote(name));
                }
                ddl.executeUpdate(
                        "UPDATE "
                                + target.table().toLowerCase(Locale.ROOT)
                                + " SET "
                                + target.column()
                                + " = 'FND' WHERE "
                                + target.column()
                                + " = 'FNB'");
                ddl.execute(
                        "ALTER TABLE "
                                + target.table().toLowerCase(Locale.ROOT)
                                + " ADD CONSTRAINT "
                                + target.constraint()
                                + " CHECK ("
                                + target.column()
                                + " IN ('IDA','CDA','FND'))");
            }
        }
    }

    /** The CHECK constraints of {@code table} whose condition names 'FNB'. */
    private static List<String> fnbChecks(Connection connection, boolean oracle, String table)
            throws SQLException {
        String sql =
                oracle
                        ? "SELECT constraint_name FROM user_constraints WHERE table_name = ? AND"
                                + " constraint_type = 'C' AND search_condition_vc LIKE '%''FNB''%'"
                        : "SELECT tc.constraint_name FROM information_schema.table_constraints tc"
                                + " JOIN information_schema.check_constraints cc ON"
                                + " cc.constraint_schema = tc.constraint_schema AND"
                                + " cc.constraint_name = tc.constraint_name WHERE tc.table_name = ?"
                                + " AND tc.constraint_type = 'CHECK' AND cc.check_clause LIKE"
                                + " '%''FNB''%'";
        List<String> names = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, table);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    names.add(rows.getString(1));
                }
            }
        }
        if (names.isEmpty()) {
            throw new IllegalStateException(
                    "No CHECK naming 'FNB' found on "
                            + table
                            + ": schema not as V001/V002 created it");
        }
        return names;
    }

    private static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
