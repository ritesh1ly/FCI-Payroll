package in.gov.fci.payroll.db.kit;

import in.gov.fci.payroll.db.kit.Catalog.CodeObject;
import in.gov.fci.payroll.db.kit.Catalog.Column;
import in.gov.fci.payroll.db.kit.Catalog.Constraint;
import in.gov.fci.payroll.db.kit.Catalog.Identity;
import in.gov.fci.payroll.db.kit.Catalog.Index;
import in.gov.fci.payroll.db.kit.Catalog.Kind;
import in.gov.fci.payroll.db.kit.Catalog.Sequence;
import in.gov.fci.payroll.db.kit.Catalog.Table;
import in.gov.fci.payroll.db.kit.Catalog.Trigger;
import in.gov.fci.payroll.db.kit.Catalog.View;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the {@link Catalog} of an H2 schema (Oracle mode, ADR-0019): structure from
 * INFORMATION_SCHEMA, DDL text from H2's own {@code SCRIPT NODATA} (one row per statement, the form
 * H2 recreates an object from). Every SCRIPT statement must be classified, or the read fails: a new
 * kind of object can never be dropped silently from the kit.
 */
final class H2Catalog {

    private static final Pattern NAME = Pattern.compile("\"((?:[^\"]|\"\")+)\"");
    private static final Pattern RESTART = Pattern.compile("\\s+RESTART WITH -?\\d+");

    /** Optimizer statistics H2 adds to a column once a table has rows: not structure. */
    private static final Pattern SELECTIVITY = Pattern.compile(" SELECTIVITY \\d+");

    private static final Pattern H2_SYSTEM_NAME =
            Pattern.compile(
                    "^(CONSTRAINT_[0-9A-F]+|PRIMARY_KEY_[0-9A-F]+|FK_[0-9A-F]+_[0-9A-F]+)$");

    private H2Catalog() {}

    static Catalog read(Connection connection) throws SQLException {
        String schema = scalar(connection, "SELECT SCHEMA()");
        Map<String, String> tableDdl = new TreeMap<>();
        Map<String, String> constraintDdl = new TreeMap<>();
        Map<String, String> indexDdl = new TreeMap<>();
        Map<String, String> viewDdl = new TreeMap<>();
        Map<String, String> triggerDdl = new TreeMap<>();
        Map<String, String> sequenceDdl = new TreeMap<>();
        String qualifier = "\"" + schema + "\".";
        try (Statement s = connection.createStatement();
                ResultSet rs = s.executeQuery("SCRIPT NODATA NOSETTINGS")) {
            while (rs.next()) {
                // SCRIPT ends its lines with the JVM line separator: CRLF on Windows.
                String raw = rs.getString(1).replace("\r\n", "\n").strip();
                String sql = raw.replace(qualifier, "");
                if (sql.startsWith("CREATE USER ") || sql.startsWith("-- ")) {
                    continue;
                }
                if (sql.startsWith("CREATE SEQUENCE ")) {
                    sequenceDdl.put(firstName(sql), RESTART.matcher(sql).replaceAll(""));
                } else if (sql.startsWith("CREATE MEMORY TABLE ")
                        || sql.startsWith("CREATE CACHED TABLE ")) {
                    tableDdl.put(
                            firstName(sql),
                            SELECTIVITY
                                    .matcher(RESTART.matcher(sql).replaceAll(""))
                                    .replaceAll(""));
                } else if (sql.startsWith("ALTER TABLE ") && sql.contains(" ADD CONSTRAINT ")) {
                    constraintDdl.put(nthName(sql, 1), sql);
                } else if (sql.matches(
                        "(?s)^CREATE (UNIQUE (NULLS (ALL |NOT )?DISTINCT )?)?INDEX .*")) {
                    indexDdl.put(firstName(sql), sql);
                } else if (sql.startsWith("CREATE FORCE VIEW ") || sql.startsWith("CREATE VIEW ")) {
                    viewDdl.put(firstName(sql), sql);
                } else if (sql.startsWith("CREATE FORCE TRIGGER ")
                        || sql.startsWith("CREATE TRIGGER ")) {
                    triggerDdl.put(firstName(sql), sql);
                } else {
                    throw new IllegalStateException(
                            "SCRIPT statement of a kind the install kit does not know: "
                                    + sql.lines().findFirst().orElse(""));
                }
            }
        }
        Map<String, Table> tables = tables(connection, schema, tableDdl);
        List<Constraint> constraints = constraints(connection, schema, constraintDdl);
        List<Index> indexes = indexes(connection, schema, indexDdl);
        Map<String, View> views = views(connection, schema, viewDdl);
        List<Trigger> triggers = triggers(connection, schema, triggerDdl);
        List<Sequence> sequences = sequences(connection, schema, sequenceDdl);
        List<CodeObject> code = List.of();
        return new Catalog(
                Catalog.Vendor.H2, tables, constraints, indexes, views, triggers, sequences, code);
    }

    /** The first quoted name after the statement's keywords. */
    static String firstName(String sql) {
        return nthName(sql, 0);
    }

    static String nthName(String sql, int n) {
        Matcher m = NAME.matcher(sql);
        for (int i = 0; i <= n; i++) {
            if (!m.find()) {
                throw new IllegalStateException("no name " + n + " in " + sql);
            }
        }
        return m.group(1).replace("\"\"", "\"");
    }

    private static Map<String, Table> tables(
            Connection connection, String schema, Map<String, String> ddl) throws SQLException {
        Map<String, List<Column>> columns = new TreeMap<>();
        try (PreparedStatement ps =
                connection.prepareStatement(
                        "SELECT c.TABLE_NAME, c.COLUMN_NAME, c.DATA_TYPE,"
                            + " c.CHARACTER_MAXIMUM_LENGTH, c.NUMERIC_PRECISION, c.NUMERIC_SCALE,"
                            + " c.DATETIME_PRECISION, c.IS_NULLABLE, c.COLUMN_DEFAULT,"
                            + " c.IS_IDENTITY, c.IDENTITY_GENERATION, c.IDENTITY_START,"
                            + " c.IDENTITY_INCREMENT, c.IDENTITY_BASE, c.DEFAULT_ON_NULL,"
                            + " c.IS_GENERATED, c.GENERATION_EXPRESSION FROM"
                            + " INFORMATION_SCHEMA.COLUMNS c JOIN INFORMATION_SCHEMA.TABLES t ON"
                            + " t.TABLE_SCHEMA = c.TABLE_SCHEMA AND t.TABLE_NAME = c.TABLE_NAME"
                            + " WHERE c.TABLE_SCHEMA = ? AND t.TABLE_TYPE = 'BASE TABLE' ORDER BY"
                            + " c.TABLE_NAME, c.ORDINAL_POSITION")) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String dataType = rs.getString(3);
                    String type = typeText(rs, dataType);
                    Identity identity = null;
                    if ("YES".equals(rs.getString(10))) {
                        identity =
                                new Identity(
                                        rs.getString(11),
                                        rs.getBoolean(15),
                                        rs.getLong(12),
                                        rs.getLong(13),
                                        rs.getLong(14));
                    }
                    columns.computeIfAbsent(rs.getString(1), k -> new ArrayList<>())
                            .add(
                                    new Column(
                                            rs.getString(2),
                                            type,
                                            kind(dataType, rs.getObject(7)),
                                            "YES".equals(rs.getString(8)),
                                            rs.getString(9),
                                            identity,
                                            "ALWAYS".equals(rs.getString(16))));
                }
            }
        }
        Map<String, Table> tables = new TreeMap<>();
        for (Map.Entry<String, List<Column>> e : columns.entrySet()) {
            String sql = ddl.get(e.getKey());
            if (sql == null) {
                throw new IllegalStateException("no SCRIPT DDL for table " + e.getKey());
            }
            tables.put(
                    e.getKey(), new Table(e.getKey(), List.copyOf(e.getValue()), sql, List.of()));
        }
        if (!tables.keySet().equals(ddl.keySet())) {
            throw new IllegalStateException("tables of SCRIPT and INFORMATION_SCHEMA differ");
        }
        return tables;
    }

    private static String typeText(ResultSet rs, String dataType) throws SQLException {
        return switch (dataType) {
            case "CHARACTER VARYING", "CHARACTER", "BINARY VARYING", "BINARY" ->
                    dataType + "(" + rs.getLong(4) + ")";
            case "NUMERIC" -> "NUMERIC(" + rs.getInt(5) + ", " + rs.getInt(6) + ")";
            case "TIMESTAMP", "TIME", "TIMESTAMP WITH TIME ZONE" ->
                    dataType + "(" + rs.getInt(7) + ")";
            default -> dataType;
        };
    }

    static Kind kind(String dataType, Object datetimePrecision) {
        return switch (dataType) {
            case "CHARACTER VARYING", "CHARACTER", "VARCHAR_IGNORECASE" -> Kind.STRING;
            case "CHARACTER LARGE OBJECT" -> Kind.CLOB;
            case "BINARY LARGE OBJECT" -> Kind.BLOB;
            case "BINARY VARYING", "BINARY" -> Kind.RAW;
            case "BOOLEAN" -> Kind.BOOLEAN;
            case "DATE" -> Kind.DATE;
            // Oracle mode: an Oracle DATE is TIMESTAMP(0).
            case "TIMESTAMP" ->
                    datetimePrecision instanceof Number n && n.intValue() == 0
                            ? Kind.DATE
                            : Kind.TIMESTAMP;
            case "TINYINT",
                    "SMALLINT",
                    "INTEGER",
                    "BIGINT",
                    "NUMERIC",
                    "DECFLOAT",
                    "REAL",
                    "DOUBLE PRECISION" ->
                    Kind.NUMBER;
            default -> throw new IllegalStateException("column type not handled: " + dataType);
        };
    }

    private static List<Constraint> constraints(
            Connection connection, String schema, Map<String, String> ddl) throws SQLException {
        Map<String, String[]> heads = new LinkedHashMap<>();
        try (PreparedStatement ps =
                connection.prepareStatement(
                        "SELECT tc.CONSTRAINT_NAME, tc.CONSTRAINT_TYPE, tc.TABLE_NAME,"
                            + " rc.UNIQUE_CONSTRAINT_NAME FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS"
                            + " tc LEFT JOIN INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS rc ON"
                            + " rc.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA AND rc.CONSTRAINT_NAME"
                            + " = tc.CONSTRAINT_NAME WHERE tc.CONSTRAINT_SCHEMA = ? ORDER BY"
                            + " tc.CONSTRAINT_NAME")) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    heads.put(
                            rs.getString(1),
                            new String[] {rs.getString(2), rs.getString(3), rs.getString(4)});
                }
            }
        }
        Map<String, List<String>> keyColumns = new TreeMap<>();
        try (PreparedStatement ps =
                connection.prepareStatement(
                        "SELECT CONSTRAINT_NAME, COLUMN_NAME FROM"
                                + " INFORMATION_SCHEMA.KEY_COLUMN_USAGE WHERE CONSTRAINT_SCHEMA = ?"
                                + " ORDER BY CONSTRAINT_NAME, ORDINAL_POSITION")) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    keyColumns
                            .computeIfAbsent(rs.getString(1), k -> new ArrayList<>())
                            .add(rs.getString(2));
                }
            }
        }
        List<Constraint> out = new ArrayList<>();
        for (Map.Entry<String, String[]> e : heads.entrySet()) {
            String name = e.getKey();
            String[] h = e.getValue();
            char type =
                    switch (h[0]) {
                        case "PRIMARY KEY" -> 'P';
                        case "UNIQUE" -> 'U';
                        case "CHECK" -> 'C';
                        case "FOREIGN KEY" -> 'R';
                        default -> throw new IllegalStateException("constraint type " + h[0]);
                    };
            String sql = ddl.get(name);
            if (sql == null) {
                throw new IllegalStateException("no SCRIPT DDL for constraint " + name);
            }
            List<String> cols = keyColumns.getOrDefault(name, List.of());
            String refTable = null;
            List<String> refCols = List.of();
            if (type == 'R') {
                String[] ref = heads.get(h[2]);
                refTable = ref[1];
                refCols = keyColumns.get(h[2]);
            }
            out.add(
                    new Constraint(
                            name,
                            type,
                            h[1],
                            List.copyOf(cols),
                            refTable,
                            List.copyOf(refCols),
                            H2_SYSTEM_NAME.matcher(name).matches(),
                            sql));
        }
        if (out.size() != ddl.size()) {
            throw new IllegalStateException("constraints of SCRIPT and INFORMATION_SCHEMA differ");
        }
        return out;
    }

    private static List<Index> indexes(
            Connection connection, String schema, Map<String, String> ddl) throws SQLException {
        Map<String, String[]> heads = new TreeMap<>();
        Map<String, List<String>> cols = new TreeMap<>();
        try (PreparedStatement ps =
                connection.prepareStatement(
                        "SELECT i.INDEX_NAME, i.TABLE_NAME, i.INDEX_TYPE_NAME, c.COLUMN_NAME,"
                            + " c.ORDERING_SPECIFICATION FROM INFORMATION_SCHEMA.INDEXES i JOIN"
                            + " INFORMATION_SCHEMA.INDEX_COLUMNS c ON c.INDEX_SCHEMA ="
                            + " i.INDEX_SCHEMA AND c.INDEX_NAME = i.INDEX_NAME WHERE i.INDEX_SCHEMA"
                            + " = ? ORDER BY i.INDEX_NAME, c.ORDINAL_POSITION")) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (!ddl.containsKey(rs.getString(1))) {
                        continue; // an index a constraint created for itself
                    }
                    heads.put(rs.getString(1), new String[] {rs.getString(2), rs.getString(3)});
                    cols.computeIfAbsent(rs.getString(1), k -> new ArrayList<>())
                            .add(rs.getString(4) + " " + rs.getString(5));
                }
            }
        }
        if (!heads.keySet().equals(ddl.keySet())) {
            throw new IllegalStateException("indexes of SCRIPT and INFORMATION_SCHEMA differ");
        }
        List<Index> out = new ArrayList<>();
        for (Map.Entry<String, String[]> e : heads.entrySet()) {
            out.add(
                    new Index(
                            e.getKey(),
                            e.getValue()[0],
                            List.copyOf(cols.get(e.getKey())),
                            e.getValue()[1].startsWith("UNIQUE"),
                            ddl.get(e.getKey())));
        }
        return out;
    }

    private static Map<String, View> views(
            Connection connection, String schema, Map<String, String> ddl) throws SQLException {
        Map<String, String> definitions = new TreeMap<>();
        try (PreparedStatement ps =
                connection.prepareStatement(
                        "SELECT TABLE_NAME, VIEW_DEFINITION FROM INFORMATION_SCHEMA.VIEWS WHERE"
                                + " TABLE_SCHEMA = ?")) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    definitions.put(rs.getString(1), rs.getString(2));
                }
            }
        }
        if (!definitions.keySet().equals(ddl.keySet())) {
            throw new IllegalStateException("views of SCRIPT and INFORMATION_SCHEMA differ");
        }
        Map<String, View> views = new TreeMap<>();
        for (Map.Entry<String, String> e : definitions.entrySet()) {
            Set<String> uses = new LinkedHashSet<>();
            String body = ddl.get(e.getKey());
            for (String other : definitions.keySet()) {
                if (!other.equals(e.getKey()) && body.contains("\"" + other + "\"")) {
                    uses.add(other);
                }
            }
            views.put(
                    e.getKey(),
                    new View(
                            e.getKey(),
                            body,
                            e.getValue().replace("\"" + schema + "\".", ""),
                            uses));
        }
        return views;
    }

    private static List<Trigger> triggers(
            Connection connection, String schema, Map<String, String> ddl) throws SQLException {
        Map<String, Trigger> out = new TreeMap<>();
        try (PreparedStatement ps =
                connection.prepareStatement(
                        "SELECT TRIGGER_NAME, EVENT_OBJECT_TABLE, ACTION_TIMING,"
                                + " EVENT_MANIPULATION, JAVA_CLASS FROM INFORMATION_SCHEMA.TRIGGERS"
                                + " WHERE TRIGGER_SCHEMA = ? ORDER BY TRIGGER_NAME,"
                                + " EVENT_MANIPULATION")) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString(1);
                    Trigger previous = out.get(name);
                    String events =
                            previous == null
                                    ? rs.getString(4)
                                    : previous.events() + "," + rs.getString(4);
                    String sql = ddl.get(name);
                    if (sql == null) {
                        throw new IllegalStateException("no SCRIPT DDL for trigger " + name);
                    }
                    out.put(
                            name,
                            new Trigger(
                                    name,
                                    rs.getString(2),
                                    rs.getString(3),
                                    events,
                                    rs.getString(5),
                                    true,
                                    sql,
                                    Set.of()));
                }
            }
        }
        if (!out.keySet().equals(ddl.keySet())) {
            throw new IllegalStateException("triggers of SCRIPT and INFORMATION_SCHEMA differ");
        }
        return List.copyOf(out.values());
    }

    private static List<Sequence> sequences(
            Connection connection, String schema, Map<String, String> ddl) throws SQLException {
        List<Sequence> out = new ArrayList<>();
        try (PreparedStatement ps =
                connection.prepareStatement(
                        "SELECT SEQUENCE_NAME, INCREMENT, BASE_VALUE FROM"
                                + " INFORMATION_SCHEMA.SEQUENCES WHERE SEQUENCE_SCHEMA = ? ORDER BY"
                                + " SEQUENCE_NAME")) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString(1);
                    String sql = ddl.get(name);
                    if (sql == null) {
                        // An identity column's own sequence is not in SCRIPT.
                        continue;
                    }
                    out.add(new Sequence(name, rs.getLong(2), rs.getLong(3), sql));
                }
            }
        }
        if (out.size() != ddl.size()) {
            throw new IllegalStateException("sequences of SCRIPT and INFORMATION_SCHEMA differ");
        }
        return out;
    }

    static String scalar(Connection connection, String sql) throws SQLException {
        try (Statement s = connection.createStatement();
                ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    /** Upper-case ASCII, for case-insensitive keyword checks. */
    static String upper(String s) {
        return s.toUpperCase(Locale.ROOT);
    }
}
