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
import java.sql.Clob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the {@link Catalog} of the connected Oracle schema (the schema owner's USER_* views).
 * Tables, keys and indexes are written from the dictionary (so the identity of each object is under
 * the kit's control: no storage clauses, no schema prefix, a system-named constraint left to the
 * database to name); views, triggers and PL/SQL come from DBMS_METADATA, the text Oracle itself
 * recreates them from. Order: USER_DEPENDENCIES.
 */
final class OracleCatalog {

    private static final Pattern NOT_NULL_CHECK = Pattern.compile("^\"[^\"]+\" IS NOT NULL$");
    private static final Pattern IDENTITY_OPTION = Pattern.compile("([A-Z_ ]+): ([^,]+)");

    private OracleCatalog() {}

    static Catalog read(Connection c) throws SQLException {
        String owner = H2Catalog.scalar(c, "SELECT USER FROM dual");
        metadataTransforms(c);
        Map<String, Long> sequenceNext = new HashMap<>();
        List<Sequence> sequences = new ArrayList<>();
        try (Statement s = c.createStatement();
                ResultSet rs =
                        s.executeQuery(
                                "SELECT sequence_name, min_value, max_value, increment_by,"
                                        + " cycle_flag, order_flag, cache_size, last_number FROM"
                                        + " user_sequences ORDER BY sequence_name")) {
            while (rs.next()) {
                String name = rs.getString(1);
                sequenceNext.put(name, rs.getLong(8));
                if (name.startsWith("ISEQ$$")) {
                    continue;
                }
                String ddl =
                        "CREATE SEQUENCE "
                                + KitData.quote(name)
                                + " START WITH "
                                + rs.getString(2)
                                + " INCREMENT BY "
                                + rs.getString(4)
                                + " MINVALUE "
                                + rs.getString(2)
                                + " MAXVALUE "
                                + rs.getString(3)
                                + (rs.getLong(7) == 0 ? " NOCACHE" : " CACHE " + rs.getLong(7))
                                + ("Y".equals(rs.getString(5)) ? " CYCLE" : " NOCYCLE")
                                + ("Y".equals(rs.getString(6)) ? " ORDER" : " NOORDER");
                sequences.add(new Sequence(name, rs.getLong(4), rs.getLong(8), ddl));
            }
        }
        Map<String, Identity> identities = new HashMap<>();
        Map<String, String> identityText = new HashMap<>();
        try (Statement s = c.createStatement();
                ResultSet rs =
                        s.executeQuery(
                                "SELECT table_name, column_name, generation_type, sequence_name,"
                                        + " identity_options FROM user_tab_identity_cols")) {
            while (rs.next()) {
                Map<String, String> opts = options(rs.getString(5));
                identities.put(
                        rs.getString(1) + "." + rs.getString(2),
                        new Identity(
                                rs.getString(3),
                                false,
                                Long.parseLong(opts.getOrDefault("START WITH", "1").trim()),
                                Long.parseLong(opts.getOrDefault("INCREMENT BY", "1").trim()),
                                sequenceNext.getOrDefault(rs.getString(4), 1L)));
                identityText.put(
                        rs.getString(1) + "." + rs.getString(2),
                        identityClause(rs.getString(3), opts));
            }
        }
        Map<String, Table> tables = tables(c, identities, identityText);
        Map<String, List<String>> consColumns =
                columns(
                        c,
                        "SELECT constraint_name, column_name FROM user_cons_columns ORDER BY"
                                + " constraint_name, position");
        Map<String, String[]> indexHeads = new TreeMap<>();
        try (Statement s = c.createStatement();
                ResultSet rs =
                        s.executeQuery(
                                "SELECT index_name, table_name, uniqueness, index_type, generated"
                                    + " FROM user_indexes WHERE index_type IN ('NORMAL',"
                                    + " 'FUNCTION-BASED NORMAL', 'BITMAP', 'FUNCTION-BASED BITMAP')"
                                    + " AND table_name NOT LIKE 'BIN$%'")) {
            while (rs.next()) {
                indexHeads.put(
                        rs.getString(1),
                        new String[] {
                            rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)
                        });
            }
        }
        Map<String, List<String>> indexColumns = indexColumns(c);
        List<Constraint> constraints = new ArrayList<>();
        Map<String, String[]> keyHead = new HashMap<>();
        Set<String> ownedIndexes = new LinkedHashSet<>();
        try (Statement s = c.createStatement();
                ResultSet rs =
                        s.executeQuery(
                                "SELECT constraint_name, constraint_type, table_name,"
                                    + " r_constraint_name, delete_rule, status, validated,"
                                    + " deferrable, deferred, generated, index_name,"
                                    + " search_condition_vc FROM user_constraints WHERE"
                                    + " constraint_type IN ('P', 'U', 'R', 'C') AND table_name NOT"
                                    + " LIKE 'BIN$%' ORDER BY constraint_name")) {
            while (rs.next()) {
                String[] h = new String[12];
                for (int i = 0; i < 12; i++) {
                    h[i] = rs.getString(i + 1);
                }
                keyHead.put(h[0], h);
            }
        }
        for (String[] h : keyHead.values()) {
            char type = h[1].charAt(0);
            boolean system = "GENERATED NAME".equals(h[9]);
            if (type == 'C'
                    && system
                    && h[11] != null
                    && NOT_NULL_CHECK.matcher(h[11].strip()).matches()) {
                continue; // written inline as NOT NULL
            }
            if (!tables.containsKey(h[2])) {
                continue;
            }
            List<String> cols = consColumns.getOrDefault(h[0], List.of());
            String name = system ? "" : "CONSTRAINT " + KitData.quote(h[0]) + " ";
            StringBuilder ddl = new StringBuilder();
            String refTable = null;
            List<String> refCols = List.of();
            switch (type) {
                case 'P', 'U' -> {
                    String idx = h[10];
                    String[] ih = idx == null ? null : indexHeads.get(idx);
                    if (ih != null && !idx.equals(h[0]) && "N".equals(ih[3])) {
                        // The key uses an index created before it: create that index first, then
                        // name it.
                        ddl.append(indexDdl(idx, ih, indexColumns.get(idx))).append(";\n");
                        ownedIndexes.add(idx);
                        ddl.append("ALTER TABLE ")
                                .append(KitData.quote(h[2]))
                                .append(" ADD ")
                                .append(name)
                                .append(type == 'P' ? "PRIMARY KEY (" : "UNIQUE (")
                                .append(list(cols))
                                .append(") USING INDEX ")
                                .append(KitData.quote(idx));
                    } else {
                        if (idx != null) {
                            ownedIndexes.add(idx);
                        }
                        ddl.append("ALTER TABLE ")
                                .append(KitData.quote(h[2]))
                                .append(" ADD ")
                                .append(name)
                                .append(type == 'P' ? "PRIMARY KEY (" : "UNIQUE (")
                                .append(list(cols))
                                .append(')');
                    }
                }
                case 'R' -> {
                    String[] parent = keyHead.get(h[3]);
                    refTable = parent[2];
                    refCols = consColumns.get(h[3]);
                    ddl.append("ALTER TABLE ")
                            .append(KitData.quote(h[2]))
                            .append(" ADD ")
                            .append(name)
                            .append("FOREIGN KEY (")
                            .append(list(cols))
                            .append(") REFERENCES ")
                            .append(KitData.quote(refTable))
                            .append(" (")
                            .append(list(refCols))
                            .append(')');
                    if ("CASCADE".equals(h[4])) {
                        ddl.append(" ON DELETE CASCADE");
                    } else if ("SET NULL".equals(h[4])) {
                        ddl.append(" ON DELETE SET NULL");
                    }
                }
                default ->
                        ddl.append("ALTER TABLE ")
                                .append(KitData.quote(h[2]))
                                .append(" ADD ")
                                .append(name)
                                .append("CHECK (")
                                .append(h[11].strip())
                                .append(')');
            }
            if ("DEFERRABLE".equals(h[7])) {
                ddl.append(" DEFERRABLE INITIALLY ")
                        .append("DEFERRED".equals(h[8]) ? "DEFERRED" : "IMMEDIATE");
            }
            if ("DISABLED".equals(h[5])) {
                ddl.append(" DISABLE");
            } else if ("NOT VALIDATED".equals(h[6])) {
                ddl.append(" ENABLE NOVALIDATE");
            }
            constraints.add(
                    new Constraint(
                            h[0],
                            type,
                            h[2],
                            List.copyOf(cols),
                            refTable,
                            List.copyOf(refCols),
                            system,
                            ddl.toString()));
        }
        constraints.sort(
                java.util.Comparator.comparing(Constraint::table).thenComparing(Constraint::name));
        List<Index> indexes = new ArrayList<>();
        for (Map.Entry<String, String[]> e : indexHeads.entrySet()) {
            if (ownedIndexes.contains(e.getKey()) || !tables.containsKey(e.getValue()[0])) {
                continue;
            }
            List<String> cols = indexColumns.get(e.getKey());
            indexes.add(
                    new Index(
                            e.getKey(),
                            e.getValue()[0],
                            cols,
                            "UNIQUE".equals(e.getValue()[1]),
                            indexDdl(e.getKey(), e.getValue(), cols)));
        }
        Map<String, Set<String>> deps = dependencies(c);
        Map<String, View> views = new TreeMap<>();
        try (Statement s = c.createStatement();
                ResultSet rs =
                        s.executeQuery(
                                "SELECT view_name, text FROM user_views ORDER BY view_name")) {
            List<String[]> rows = new ArrayList<>();
            while (rs.next()) {
                rows.add(new String[] {rs.getString(1), rs.getString(2)});
            }
            for (String[] r : rows) {
                Set<String> uses = new LinkedHashSet<>();
                for (String d : deps.getOrDefault("VIEW " + r[0], Set.of())) {
                    if (d.startsWith("VIEW ")) {
                        uses.add(d.substring(5));
                    }
                }
                String ddl = strip(metadata(c, "VIEW", r[0]), owner);
                views.put(r[0], new View(r[0], ddl, r[1], uses));
            }
        }
        List<Trigger> triggers = new ArrayList<>();
        try (Statement s = c.createStatement();
                ResultSet rs =
                        s.executeQuery(
                                "SELECT trigger_name, table_name, trigger_type, triggering_event,"
                                    + " status FROM user_triggers WHERE base_object_type = 'TABLE'"
                                    + " ORDER BY trigger_name")) {
            List<String[]> rows = new ArrayList<>();
            while (rs.next()) {
                rows.add(
                        new String[] {
                            rs.getString(1),
                            rs.getString(2),
                            rs.getString(3),
                            rs.getString(4),
                            rs.getString(5)
                        });
            }
            for (String[] r : rows) {
                String ddl = strip(metadata(c, "TRIGGER", r[0]), owner);
                int alter = ddl.indexOf("\nALTER TRIGGER ");
                if (alter > 0) {
                    ddl = ddl.substring(0, alter).strip();
                }
                Set<String> uses = new LinkedHashSet<>();
                for (String d : deps.getOrDefault("TRIGGER " + r[0], Set.of())) {
                    if (d.startsWith("SEQUENCE ")) {
                        uses.add("S:" + d.substring(9));
                    } else if (!d.startsWith("TABLE ") && !d.startsWith("VIEW ")) {
                        uses.add("C:" + d);
                    }
                }
                triggers.add(
                        new Trigger(
                                r[0], r[1], r[2], r[3], ddl, "ENABLED".equals(r[4]), ddl, uses));
            }
        }
        List<CodeObject> code = new ArrayList<>();
        try (Statement s = c.createStatement();
                ResultSet rs =
                        s.executeQuery(
                                "SELECT object_type, object_name FROM user_objects WHERE"
                                        + " object_type IN ('PACKAGE', 'PACKAGE BODY', 'PROCEDURE',"
                                        + " 'FUNCTION', 'TYPE', 'TYPE BODY') ORDER BY object_type,"
                                        + " object_name")) {
            List<String[]> rows = new ArrayList<>();
            while (rs.next()) {
                rows.add(new String[] {rs.getString(1), rs.getString(2)});
            }
            for (String[] r : rows) {
                String kind =
                        switch (r[0]) {
                            case "PACKAGE" -> "PACKAGE_SPEC";
                            case "TYPE" -> "TYPE_SPEC";
                            default -> r[0].replace(' ', '_');
                        };
                Set<String> uses = new LinkedHashSet<>();
                for (String d : deps.getOrDefault(r[0] + " " + r[1], Set.of())) {
                    if (!d.startsWith("TABLE ")
                            && !d.startsWith("VIEW ")
                            && !d.startsWith("SEQUENCE ")) {
                        uses.add(d);
                    }
                }
                code.add(new CodeObject(r[0], r[1], strip(metadata(c, kind, r[1]), owner), uses));
            }
        }
        try (Statement s = c.createStatement();
                ResultSet rs =
                        s.executeQuery(
                                "SELECT synonym_name, table_owner, table_name, db_link FROM"
                                        + " user_synonyms")) {
            while (rs.next()) {
                String target =
                        (rs.getString(2) == null ? "" : KitData.quote(rs.getString(2)) + ".")
                                + KitData.quote(rs.getString(3))
                                + (rs.getString(4) == null ? "" : "@" + rs.getString(4));
                code.add(
                        new CodeObject(
                                "SYNONYM",
                                rs.getString(1),
                                "CREATE OR REPLACE SYNONYM "
                                        + KitData.quote(rs.getString(1))
                                        + " FOR "
                                        + target,
                                Set.of()));
            }
        }
        return new Catalog(
                Catalog.Vendor.ORACLE,
                tables,
                constraints,
                indexes,
                views,
                triggers,
                sequences,
                code);
    }

    private static Map<String, String> options(String text) {
        Map<String, String> out = new HashMap<>();
        Matcher m = IDENTITY_OPTION.matcher(text == null ? "" : text);
        while (m.find()) {
            out.put(m.group(1).trim(), m.group(2).trim());
        }
        return out;
    }

    private static String identityClause(String generation, Map<String, String> o) {
        long cache = Long.parseLong(o.getOrDefault("CACHE_SIZE", "20"));
        return "GENERATED "
                + generation
                + "%ONNULL% AS IDENTITY (START WITH "
                + o.getOrDefault("START WITH", "1")
                + " INCREMENT BY "
                + o.getOrDefault("INCREMENT BY", "1")
                + " MINVALUE "
                + o.getOrDefault("MIN_VALUE", "1")
                + " MAXVALUE "
                + o.getOrDefault("MAX_VALUE", "9999999999999999999999999999")
                + (cache == 0 ? " NOCACHE" : " CACHE " + cache)
                + ("Y".equals(o.get("CYCLE_FLAG")) ? " CYCLE" : " NOCYCLE")
                + ("Y".equals(o.get("ORDER_FLAG")) ? " ORDER" : " NOORDER")
                + ")";
    }

    private static Map<String, Table> tables(
            Connection c, Map<String, Identity> identities, Map<String, String> identityText)
            throws SQLException {
        Map<String, List<Column>> cols = new TreeMap<>();
        Map<String, List<String>> lines = new TreeMap<>();
        try (Statement s = c.createStatement();
                ResultSet rs =
                        s.executeQuery(
                                "SELECT c.table_name, c.column_name, c.data_type, c.data_length,"
                                    + " c.data_precision, c.data_scale, c.char_used, c.char_length,"
                                    + " c.nullable, c.identity_column, c.virtual_column,"
                                    + " c.default_on_null, c.data_default FROM user_tab_cols c JOIN"
                                    + " user_tables t ON t.table_name = c.table_name WHERE"
                                    + " c.hidden_column = 'NO' AND t.table_name NOT LIKE 'BIN$%'"
                                    + " AND t.nested = 'NO' AND t.secondary = 'N' ORDER BY"
                                    + " c.table_name, c.column_id")) {
            while (rs.next()) {
                String table = rs.getString(1);
                String col = rs.getString(2);
                String dataType = rs.getString(3);
                String type =
                        type(
                                dataType,
                                rs.getLong(4),
                                rs.getObject(5, Integer.class),
                                rs.getObject(6, Integer.class),
                                rs.getString(7),
                                rs.getLong(8));
                boolean nullable = "Y".equals(rs.getString(9));
                boolean identity = "YES".equals(rs.getString(10));
                boolean virtual = "YES".equals(rs.getString(11));
                boolean onNull = "YES".equals(rs.getString(12));
                String dflt = rs.getString(13);
                dflt = dflt == null ? null : dflt.strip();
                Identity id = null;
                StringBuilder line =
                        new StringBuilder("    ")
                                .append(KitData.quote(col))
                                .append(' ')
                                .append(type);
                if (identity) {
                    Identity i = identities.get(table + "." + col);
                    id = new Identity(i.generation(), onNull, i.start(), i.increment(), i.next());
                    line.append(' ')
                            .append(
                                    identityText
                                            .get(table + "." + col)
                                            .replace("%ONNULL%", onNull ? " ON NULL" : ""));
                    dflt = null;
                } else if (virtual) {
                    line.append(" GENERATED ALWAYS AS (").append(dflt).append(") VIRTUAL");
                } else if (dflt != null) {
                    line.append(onNull ? " DEFAULT ON NULL " : " DEFAULT ").append(dflt);
                }
                if (!nullable && !virtual) {
                    line.append(" NOT NULL");
                }
                cols.computeIfAbsent(table, k -> new ArrayList<>())
                        .add(
                                new Column(
                                        col,
                                        type,
                                        kind(dataType),
                                        nullable,
                                        virtual ? null : dflt,
                                        id,
                                        virtual));
                lines.computeIfAbsent(table, k -> new ArrayList<>()).add(line.toString());
            }
        }
        Map<String, List<String>> comments = new TreeMap<>();
        try (Statement s = c.createStatement();
                ResultSet rs =
                        s.executeQuery(
                                "SELECT table_name, NULL, comments FROM user_tab_comments WHERE"
                                    + " comments IS NOT NULL AND table_type = 'TABLE' UNION ALL"
                                    + " SELECT table_name, column_name, comments FROM"
                                    + " user_col_comments WHERE comments IS NOT NULL ORDER BY 1, 2"
                                    + " NULLS FIRST")) {
            while (rs.next()) {
                String target =
                        rs.getString(2) == null
                                ? "TABLE " + KitData.quote(rs.getString(1))
                                : "COLUMN "
                                        + KitData.quote(rs.getString(1))
                                        + "."
                                        + KitData.quote(rs.getString(2));
                comments.computeIfAbsent(rs.getString(1), k -> new ArrayList<>())
                        .add(KitWriter.oracleComment(target, rs.getString(3)));
            }
        }
        Map<String, Table> out = new TreeMap<>();
        for (Map.Entry<String, List<Column>> e : cols.entrySet()) {
            String ddl =
                    "CREATE TABLE "
                            + KitData.quote(e.getKey())
                            + " (\n"
                            + String.join(",\n", lines.get(e.getKey()))
                            + "\n)";
            out.put(
                    e.getKey(),
                    new Table(
                            e.getKey(),
                            List.copyOf(e.getValue()),
                            ddl,
                            List.copyOf(comments.getOrDefault(e.getKey(), List.of()))));
        }
        return out;
    }

    static String type(
            String dataType,
            long length,
            Integer precision,
            Integer scale,
            String charUsed,
            long charLength) {
        return switch (dataType) {
            case "VARCHAR2", "CHAR" ->
                    dataType
                            + "("
                            + ("C".equals(charUsed) ? charLength + " CHAR" : length + " BYTE")
                            + ")";
            case "NVARCHAR2", "NCHAR" -> dataType + "(" + charLength + ")";
            case "RAW" -> "RAW(" + length + ")";
            case "NUMBER" ->
                    precision == null
                            ? (scale == null ? "NUMBER" : "NUMBER(*," + scale + ")")
                            : scale == null || scale == 0
                                    ? "NUMBER(" + precision + ")"
                                    : "NUMBER(" + precision + "," + scale + ")";
            case "FLOAT" -> "FLOAT(" + precision + ")";
            default -> dataType;
        };
    }

    static Kind kind(String dataType) {
        if (dataType.startsWith("TIMESTAMP")) {
            return Kind.TIMESTAMP;
        }
        return switch (dataType) {
            case "VARCHAR2", "CHAR", "NVARCHAR2", "NCHAR" -> Kind.STRING;
            case "CLOB", "NCLOB" -> Kind.CLOB;
            case "BLOB" -> Kind.BLOB;
            case "RAW" -> Kind.RAW;
            case "DATE" -> Kind.DATE;
            case "NUMBER", "FLOAT", "BINARY_FLOAT", "BINARY_DOUBLE" -> Kind.NUMBER;
            default ->
                    throw new IllegalStateException("Oracle column type not handled: " + dataType);
        };
    }

    private static Map<String, List<String>> columns(Connection c, String sql) throws SQLException {
        Map<String, List<String>> out = new HashMap<>();
        try (Statement s = c.createStatement();
                ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                out.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(rs.getString(2));
            }
        }
        return out;
    }

    private static Map<String, List<String>> indexColumns(Connection c) throws SQLException {
        Map<String, String> expressions = new HashMap<>();
        try (Statement s = c.createStatement();
                ResultSet rs =
                        s.executeQuery(
                                "SELECT index_name, column_position, column_expression FROM"
                                        + " user_ind_expressions")) {
            while (rs.next()) {
                expressions.put(rs.getString(1) + "#" + rs.getInt(2), rs.getString(3));
            }
        }
        Map<String, List<String>> out = new HashMap<>();
        try (Statement s = c.createStatement();
                ResultSet rs =
                        s.executeQuery(
                                "SELECT index_name, column_name, column_position, descend FROM"
                                    + " user_ind_columns ORDER BY index_name, column_position")) {
            while (rs.next()) {
                String expr = expressions.get(rs.getString(1) + "#" + rs.getInt(3));
                String col = expr != null ? expr.strip() : KitData.quote(rs.getString(2));
                out.computeIfAbsent(rs.getString(1), k -> new ArrayList<>())
                        .add(col + ("DESC".equals(rs.getString(4)) ? " DESC" : ""));
            }
        }
        return out;
    }

    private static String indexDdl(String name, String[] head, List<String> cols) {
        String kind =
                "UNIQUE".equals(head[1]) ? "UNIQUE " : head[2].contains("BITMAP") ? "BITMAP " : "";
        return "CREATE "
                + kind
                + "INDEX "
                + KitData.quote(name)
                + " ON "
                + KitData.quote(head[0])
                + " ("
                + String.join(", ", cols)
                + ")";
    }

    private static String list(List<String> cols) {
        return String.join(", ", cols.stream().map(KitData::quote).toList());
    }

    /** USER_DEPENDENCIES inside the schema: "TYPE NAME" to the "TYPE NAME"s it references. */
    private static Map<String, Set<String>> dependencies(Connection c) throws SQLException {
        Map<String, Set<String>> out = new HashMap<>();
        try (Statement s = c.createStatement();
                ResultSet rs =
                        s.executeQuery(
                                "SELECT type, name, referenced_type, referenced_name FROM"
                                        + " user_dependencies WHERE referenced_owner = USER AND NOT"
                                        + " (type = referenced_type AND name = referenced_name)")) {
            while (rs.next()) {
                out.computeIfAbsent(
                                rs.getString(1) + " " + rs.getString(2), k -> new LinkedHashSet<>())
                        .add(rs.getString(3) + " " + rs.getString(4));
            }
        }
        return out;
    }

    private static void metadataTransforms(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute(
                    "BEGIN DBMS_METADATA.SET_TRANSFORM_PARAM(DBMS_METADATA.SESSION_TRANSFORM,"
                            + " 'SQLTERMINATOR', FALSE);"
                            + " DBMS_METADATA.SET_TRANSFORM_PARAM(DBMS_METADATA.SESSION_TRANSFORM,"
                            + " 'PRETTY', TRUE); END;");
        }
    }

    private static String metadata(Connection c, String type, String name) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement("SELECT DBMS_METADATA.GET_DDL(?, ?) FROM dual")) {
            ps.setString(1, type);
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                Clob clob = rs.getClob(1);
                return clob.getSubString(1, (int) clob.length()).strip();
            }
        }
    }

    /** Removes the schema prefix DBMS_METADATA writes, so the kit installs under any schema. */
    static String strip(String ddl, String owner) {
        // EDITIONABLE is the default; without it the header reads as every Oracle script tool
        // expects (scripts/oracle_19c_check.py recognises CREATE OR REPLACE TRIGGER as PL/SQL).
        return ddl.replace("\"" + owner + "\".", "")
                .replace(" NONEDITIONABLE ", " ")
                .replace(" EDITIONABLE ", " ");
    }
}
