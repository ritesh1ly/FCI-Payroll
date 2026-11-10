package in.gov.fci.payroll.integration.orchestration;

import in.gov.fci.payroll.integration.business.IntegrationErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Component;

/** Shared reads and writes of the DDL Section 22 tables (registry lookups, keyed inserts). */
@Component
class IntegrationStore {

    /** One registry row: its id, code, counterparty and direction. */
    record Registered(long id, String code, String counterparty, String direction) {}

    private final JdbcTemplate jdbc;

    IntegrationStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    JdbcTemplate jdbc() {
        return jdbc;
    }

    /** The registered interface, or INT-404-INTERFACE. */
    Registered interfaceOf(String code) {
        List<Registered> rows =
                jdbc.query(
                        "SELECT interface_id, interface_code, counterparty_system, direction FROM"
                                + " interface_registry WHERE interface_code = ?",
                        (rs, i) ->
                                new Registered(
                                        rs.getLong(1),
                                        rs.getString(2),
                                        rs.getString(3),
                                        rs.getString(4)),
                        code);
        if (rows.isEmpty()) {
            throw new FciException(
                    IntegrationErrorCode.UNKNOWN_INTERFACE, new Object[] {code}, List.of(), null);
        }
        return rows.getFirst();
    }

    /**
     * Inserts one row of a Section 22 table and answers its generated key (Spring builds the
     * statement from the table's columns: no SQL text is passed around).
     */
    long insert(String table, String keyColumn, Map<String, Object> values) {
        Number id =
                new SimpleJdbcInsert(jdbc)
                        .withTableName(table)
                        .usingColumns(values.keySet().toArray(String[]::new))
                        .usingGeneratedKeyColumns(keyColumn)
                        .executeAndReturnKey(values);
        return id.longValue();
    }

    /** The columns of a row in order, as name / value pairs; a value may be null. */
    static Map<String, Object> row(Object... pairs) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            values.put((String) pairs[i], pairs[i + 1]);
        }
        return values;
    }
}
