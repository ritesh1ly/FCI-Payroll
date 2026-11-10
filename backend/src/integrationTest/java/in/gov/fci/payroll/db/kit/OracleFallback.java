package in.gov.fci.payroll.db.kit;

import in.gov.fci.payroll.db.kit.Catalog.Column;
import in.gov.fci.payroll.db.kit.Catalog.Constraint;
import in.gov.fci.payroll.db.kit.Catalog.Identity;
import in.gov.fci.payroll.db.kit.Catalog.Index;
import in.gov.fci.payroll.db.kit.Catalog.Kind;
import in.gov.fci.payroll.db.kit.Catalog.Sequence;
import in.gov.fci.payroll.db.kit.Catalog.Table;
import in.gov.fci.payroll.db.kit.Catalog.Trigger;
import in.gov.fci.payroll.db.kit.Catalog.View;
import in.gov.fci.payroll.db.kit.KitData.Row;
import in.gov.fci.payroll.db.kit.KitData.TableRows;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The Oracle kit WITHOUT an Oracle connection (INSTALL_KIT.md "Oracle fallback"): the Oracle
 * migration texts are replayed statically for what only they know (column types, defaults and
 * identity clauses, Oracle indexes, views, PL/SQL triggers, sequences, comments) and the H2 Flyway
 * build supplies the rest (constraints, nullability, the rows). The result is marked FALLBACK in
 * every file and must be regenerated from a real Oracle build ({@link OracleKitTool}) before an
 * installation; the same static replay gives the H2 / Oracle drift report.
 */
final class OracleFallback {

    private static final Pattern VERSION = Pattern.compile("^V([0-9_]+)__.*\\.sql$");
    private static final Pattern CREATE_TABLE =
            Pattern.compile(
                    "^CREATE\\s+(GLOBAL\\s+TEMPORARY\\s+)?TABLE\\s+\"?(\\w+)\"?\\s*\\((.*)\\)[^)]*$",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ALTER_TABLE =
            Pattern.compile(
                    "^ALTER\\s+TABLE\\s+\"?(\\w+)\"?\\s+(.*)$",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern TYPE =
            Pattern.compile(
                    "^(VARCHAR2\\s*\\([^)]*\\)|VARCHAR\\s*\\([^)]*\\)|NVARCHAR2\\s*\\([^)]*\\)|CHAR\\s*\\([^)]*\\)|NCHAR\\s*\\([^)]*\\)"
                        + "|NUMBER\\s*\\([^)]*\\)|NUMBER|FLOAT(\\s*\\([^)]*\\))?|INTEGER|INT|SMALLINT|DATE"
                        + "|TIMESTAMP(\\s*\\(\\s*\\d\\s*\\))?(\\s+WITH\\s+(LOCAL\\s+)?TIME\\s+ZONE)?|CLOB|NCLOB|BLOB"
                        + "|RAW\\s*\\([^)]*\\)|BINARY_DOUBLE|BINARY_FLOAT)",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern IDENTITY =
            Pattern.compile(
                    "GENERATED\\s+(ALWAYS|BY\\s+DEFAULT(\\s+ON\\s+NULL)?)\\s+AS\\s+IDENTITY(\\s*\\(([^)]*)\\))?",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern DEFAULT =
            Pattern.compile(
                    "\\bDEFAULT\\s+(ON\\s+NULL\\s+)?(.+?)(?=\\s+(NOT\\s+NULL|NULL|CONSTRAINT|PRIMARY|UNIQUE|CHECK"
                        + "|REFERENCES|GENERATED)\\b|\\s*$)",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern NAMED =
            Pattern.compile(
                    "^CREATE\\s+(OR\\s+REPLACE\\s+)?(FORCE\\s+)?(UNIQUE\\s+|BITMAP\\s+)?"
                            + "(INDEX|VIEW|TRIGGER|SEQUENCE)\\s+\"?(\\w+)\"?",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern DROP =
            Pattern.compile(
                    "^DROP\\s+(TABLE|INDEX|VIEW|TRIGGER|SEQUENCE)\\s+\"?(\\w+)\"?",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern RENAME =
            Pattern.compile(
                    "^RENAME\\s+\"?(\\w+)\"?\\s+TO\\s+\"?(\\w+)\"?", Pattern.CASE_INSENSITIVE);
    private static final Pattern COMMENT =
            Pattern.compile(
                    "^COMMENT\\s+ON\\s+(TABLE|COLUMN)\\s+\"?(\\w+)\"?(\\.\"?(\\w+)\"?)?\\s+IS\\s",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern CONSTRAINT_ITEM =
            Pattern.compile(
                    "^(CONSTRAINT\\s|PRIMARY\\s+KEY\\b|UNIQUE\\s*\\(|CHECK\\s*\\(|FOREIGN\\s+KEY\\b)");
    private static final Pattern DYNAMIC_SEQUENCE =
            Pattern.compile(
                    "EXECUTE\\s+IMMEDIATE\\s+'CREATE\\s+SEQUENCE\\s+\"?(\\w+)\"?",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern H2_NUMBER_CAST =
            Pattern.compile(
                    "CAST\\((-?\\d+(?:\\.\\d+)?) AS (?:BIGINT|INTEGER|SMALLINT|TINYINT|DECFLOAT"
                            + "|NUMERIC\\(\\d+(?:, ?\\d+)?\\))\\)");
    private static final Pattern COMMENT_TEXT =
            Pattern.compile(
                    "\\bIS\\s+'((?:[^']|'')*)'\\s*;?\\s*$",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ON_TABLE =
            Pattern.compile("\\bON\\s+\"?(\\w+)\"?", Pattern.CASE_INSENSITIVE);

    private OracleFallback() {}

    /** A column as the Oracle migrations leave it. */
    static final class OraColumn {
        String type;
        String defaultExpr;
        boolean defaultOnNull;
        String identity;
        Boolean notNull;
    }

    /** The static replay of the Oracle migrations. */
    static final class Replay {
        final Map<String, LinkedHashMap<String, OraColumn>> tables = new TreeMap<>();
        final Map<String, String> indexes = new TreeMap<>();
        final Map<String, String> indexTable = new TreeMap<>();
        final Map<String, String> views = new TreeMap<>();
        final Map<String, String> triggers = new TreeMap<>();
        final Map<String, String> sequences = new TreeMap<>();
        final Map<String, String> comments = new LinkedHashMap<>();
        final List<String> unparsed = new ArrayList<>();
    }

    /** Replays {@code db/migration/oracle} and {@code db/migration/common} in version order. */
    static Replay replay(Path db) throws IOException {
        Map<List<Integer>, Path> files = new TreeMap<>(OracleFallback::compareVersions);
        for (String dir : List.of("migration/oracle", "migration/common")) {
            try (Stream<Path> s = Files.list(db.resolve(dir))) {
                for (Path f : s.toList()) {
                    Matcher m = VERSION.matcher(f.getFileName().toString());
                    if (m.matches()) {
                        files.put(version(m.group(1)), f);
                    }
                }
            }
        }
        Replay r = new Replay();
        files.put(
                List.of(29, 1), null); // V029_1 is a Java migration: replayed below at its version
        for (Map.Entry<List<Integer>, Path> e : files.entrySet()) {
            if (e.getValue() != null) {
                for (String unit :
                        SqlFiles.oracleUnits(
                                Files.readString(e.getValue(), StandardCharsets.UTF_8))) {
                    apply(r, stripComments(unit).strip(), unit.strip());
                }
            }
            if (e.getKey().equals(List.of(29, 1))) {
                // V029_1 (Java, Oracle branch): BATCH_JOB_SEQ renamed, the parameters index added.
                apply(
                        r,
                        "RENAME BATCH_JOB_SEQ TO BATCH_JOB_INSTANCE_SEQ",
                        "RENAME BATCH_JOB_SEQ TO BATCH_JOB_INSTANCE_SEQ");
                String idx =
                        "CREATE INDEX BATCH_JOB_EXEC_PARAMS_IDX ON"
                                + " BATCH_JOB_EXECUTION_PARAMS(JOB_EXECUTION_ID)";
                apply(r, idx, idx);
            }
        }
        return r;
    }

    private static List<Integer> version(String v) {
        List<Integer> out = new ArrayList<>();
        for (String p : v.split("_")) {
            out.add(Integer.valueOf(p));
        }
        return out;
    }

    private static int compareVersions(List<Integer> a, List<Integer> b) {
        for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
            int x = i < a.size() ? a.get(i) : 0;
            int y = i < b.size() ? b.get(i) : 0;
            if (x != y) {
                return Integer.compare(x, y);
            }
        }
        return 0;
    }

    private static void apply(Replay r, String sql, String original) {
        String up = sql.toUpperCase(Locale.ROOT);
        Matcher m;
        if ((m = CREATE_TABLE.matcher(sql)).matches()) {
            LinkedHashMap<String, OraColumn> cols = new LinkedHashMap<>();
            for (String item : split(m.group(3))) {
                column(cols, item, false);
            }
            r.tables.put(m.group(2).toUpperCase(Locale.ROOT), cols);
        } else if ((m = ALTER_TABLE.matcher(sql)).matches()) {
            alter(r, m.group(1).toUpperCase(Locale.ROOT), m.group(2).strip());
        } else if ((m = NAMED.matcher(sql)).find()) {
            String kind = m.group(4).toUpperCase(Locale.ROOT);
            String name = m.group(5).toUpperCase(Locale.ROOT);
            switch (kind) {
                case "INDEX" -> {
                    r.indexes.put(name, sql);
                    Matcher on = ON_TABLE.matcher(sql);
                    if (on.find()) {
                        r.indexTable.put(name, on.group(1).toUpperCase(Locale.ROOT));
                    }
                }
                case "VIEW" -> r.views.put(name, sql);
                case "TRIGGER" -> r.triggers.put(name, sql);
                default -> r.sequences.put(name, sql);
            }
        } else if ((m = DROP.matcher(sql)).find()) {
            String name = m.group(2).toUpperCase(Locale.ROOT);
            switch (m.group(1).toUpperCase(Locale.ROOT)) {
                case "TABLE" -> {
                    r.tables.remove(name);
                    r.indexTable
                            .entrySet()
                            .removeIf(
                                    e -> {
                                        boolean gone = e.getValue().equals(name);
                                        if (gone) {
                                            r.indexes.remove(e.getKey());
                                        }
                                        return gone;
                                    });
                    r.triggers.entrySet().removeIf(e -> onTable(e.getValue(), name));
                }
                case "INDEX" -> {
                    r.indexes.remove(name);
                    r.indexTable.remove(name);
                }
                case "VIEW" -> r.views.remove(name);
                case "TRIGGER" -> r.triggers.remove(name);
                default -> r.sequences.remove(name);
            }
        } else if ((m = RENAME.matcher(sql)).find()) {
            String from = m.group(1).toUpperCase(Locale.ROOT);
            String to = m.group(2).toUpperCase(Locale.ROOT);
            if (r.sequences.containsKey(from)) {
                r.sequences.put(
                        to,
                        r.sequences
                                .remove(from)
                                .replaceFirst("(?i)" + Pattern.quote(m.group(1)), to));
            } else if (r.tables.containsKey(from)) {
                r.tables.put(to, r.tables.remove(from));
            }
        } else if ((m = COMMENT.matcher(sql)).find()) {
            String target =
                    m.group(2).toUpperCase(Locale.ROOT)
                            + (m.group(4) == null ? "" : "." + m.group(4).toUpperCase(Locale.ROOT));
            // The comment text written again as an ASCII literal (some migrations hold an em dash).
            Matcher text = COMMENT_TEXT.matcher(original);
            String what =
                    m.group(4) == null
                            ? "TABLE " + KitData.quote(m.group(2).toUpperCase(Locale.ROOT))
                            : "COLUMN "
                                    + KitData.quote(m.group(2).toUpperCase(Locale.ROOT))
                                    + "."
                                    + KitData.quote(m.group(4).toUpperCase(Locale.ROOT));
            r.comments.put(
                    target,
                    text.find()
                            ? KitWriter.oracleComment(what, text.group(1).replace("''", "'"))
                            : original);
        } else if ((up.startsWith("BEGIN") || up.startsWith("DECLARE"))
                && (m = DYNAMIC_SEQUENCE.matcher(sql)).find()) {
            // V076_1 creates penalty_ref_seq in a block (its start value is computed): the kit
            // creates it at 1 and restarts it above the loaded rows like every sequence.
            String name = m.group(1).toUpperCase(Locale.ROOT);
            r.sequences.put(name, "CREATE SEQUENCE " + name + " START WITH 1 NOCYCLE");
        } else if (!up.startsWith("INSERT")
                && !up.startsWith("UPDATE")
                && !up.startsWith("DELETE")
                && !up.startsWith("MERGE")
                && !up.startsWith("BEGIN")
                && !up.startsWith("DECLARE")
                && !up.startsWith("SELECT")
                && !up.startsWith("WITH")) {
            r.unparsed.add(KitSteps.first(original));
        }
    }

    private static boolean onTable(String triggerDdl, String table) {
        Matcher on = ON_TABLE.matcher(stripComments(triggerDdl));
        return on.find() && on.group(1).equalsIgnoreCase(table);
    }

    private static void alter(Replay r, String table, String rest) {
        LinkedHashMap<String, OraColumn> cols =
                r.tables.computeIfAbsent(table, k -> new LinkedHashMap<>());
        String up = rest.toUpperCase(Locale.ROOT);
        if (up.startsWith("ADD CONSTRAINT")
                || up.startsWith("DROP CONSTRAINT")
                || up.startsWith("ADD PRIMARY")
                || up.startsWith("ADD UNIQUE")
                || up.startsWith("ADD CHECK")
                || up.startsWith("ADD FOREIGN")
                || up.startsWith("DISABLE")
                || up.startsWith("ENABLE")) {
            return;
        }
        if (up.startsWith("ADD")) {
            for (String item : items(rest.substring(3).strip())) {
                column(cols, item, false);
            }
        } else if (up.startsWith("MODIFY")) {
            for (String item : items(rest.substring(6).strip())) {
                column(cols, item, true);
            }
        } else if (up.startsWith("DROP COLUMN")) {
            cols.remove(rest.substring(11).strip().replace("\"", "").toUpperCase(Locale.ROOT));
        } else if (up.startsWith("DROP")) {
            for (String item : items(rest.substring(4).strip())) {
                cols.remove(item.strip().replace("\"", "").toUpperCase(Locale.ROOT));
            }
        } else if (up.startsWith("RENAME COLUMN")) {
            String[] p = rest.substring(13).strip().split("\\s+(?i:TO)\\s+");
            OraColumn c = cols.remove(p[0].strip().replace("\"", "").toUpperCase(Locale.ROOT));
            if (c != null) {
                cols.put(p[1].strip().replace("\"", "").toUpperCase(Locale.ROOT), c);
            }
        }
    }

    private static List<String> items(String s) {
        String t = s.strip();
        if (t.startsWith("(") && t.endsWith(")")) {
            return split(t.substring(1, t.length() - 1));
        }
        return List.of(t);
    }

    /** Top-level comma split (parentheses and literals respected). */
    static List<String> split(String body) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        boolean str = false;
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '\'') {
                str = !str;
            } else if (!str && c == '(') {
                depth++;
            } else if (!str && c == ')') {
                depth--;
            } else if (!str && depth == 0 && c == ',') {
                out.add(b.toString().strip());
                b.setLength(0);
                continue;
            }
            b.append(c);
        }
        if (!b.toString().isBlank()) {
            out.add(b.toString().strip());
        }
        return out;
    }

    private static void column(Map<String, OraColumn> cols, String item, boolean modify) {
        String t = item.strip().replaceAll("\\s+", " ");
        String upper = t.toUpperCase(Locale.ROOT);
        if (t.isEmpty() || CONSTRAINT_ITEM.matcher(upper).find()) {
            return;
        }
        int sp = t.indexOf(' ');
        String name = (sp < 0 ? t : t.substring(0, sp)).replace("\"", "").toUpperCase(Locale.ROOT);
        String rest = sp < 0 ? "" : t.substring(sp + 1).strip();
        OraColumn c = modify ? cols.computeIfAbsent(name, k -> new OraColumn()) : new OraColumn();
        Matcher type = TYPE.matcher(rest);
        if (type.find()) {
            c.type = normaliseType(type.group(1));
        }
        Matcher id = IDENTITY.matcher(rest);
        if (id.find()) {
            String opts = id.group(4);
            String previous = c.identity;
            String generation = id.group(1).toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
            c.identity =
                    "GENERATED "
                            + generation
                            + " AS IDENTITY"
                            + (opts != null
                                    ? " (" + opts.strip() + ")"
                                    : previous != null && previous.contains("(")
                                            ? previous.substring(previous.indexOf(" ("))
                                            : "");
        }
        Matcher d = DEFAULT.matcher(rest);
        if (d.find() && !id.find(0)) {
            c.defaultOnNull = d.group(1) != null;
            c.defaultExpr = d.group(2).strip();
        }
        if (upper.matches(".*\\bNOT NULL\\b.*")) {
            c.notNull = true;
        } else if (modify && upper.matches(".*\\bNULL\\b.*")) {
            c.notNull = false;
        }
        if (!modify) {
            cols.put(name, c);
        }
    }

    static String normaliseType(String t) {
        String u =
                t.toUpperCase(Locale.ROOT)
                        .replaceAll("\\s+", "")
                        .replaceFirst("^VARCHAR\\(", "VARCHAR2(");
        u =
                u.replace("WITHTIMEZONE", " WITH TIME ZONE")
                        .replace("WITHLOCALTIMEZONE", " WITH LOCAL TIME ZONE");
        if (u.matches("(VARCHAR2|CHAR)\\(\\d+\\)")) {
            return u.replace(")", " BYTE)");
        }
        if (u.matches("(VARCHAR2|CHAR)\\(\\d+(BYTE|CHAR)\\)")) {
            return u.replaceAll("(\\d+)(BYTE|CHAR)", "$1 $2");
        }
        return switch (u) {
            case "INTEGER", "INT", "SMALLINT" -> "NUMBER(*,0)";
            case "TIMESTAMP" -> "TIMESTAMP(6)";
            default -> u.replaceAll("NUMBER\\((\\d+),0\\)", "NUMBER($1)");
        };
    }

    static String stripComments(String sql) {
        StringBuilder out = new StringBuilder();
        boolean str = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (!str && sql.startsWith("--", i)) {
                int e = sql.indexOf('\n', i);
                i = e < 0 ? sql.length() : e - 1;
                continue;
            }
            if (!str && sql.startsWith("/*", i)) {
                int e = sql.indexOf("*/", i + 2);
                i = e < 0 ? sql.length() : e + 1;
                continue;
            }
            if (c == '\'') {
                str = !str;
            }
            out.append(c);
        }
        return out.toString();
    }

    /** H2 type to Oracle, where the migrations do not say (should not happen; reported). */
    static String mapType(Column c) {
        String t = c.type();
        if (t.startsWith("CHARACTER VARYING(")) {
            return "VARCHAR2(" + t.substring(18, t.length() - 1) + " BYTE)";
        }
        if (t.startsWith("CHARACTER(")) {
            return "CHAR(" + t.substring(10, t.length() - 1) + " BYTE)";
        }
        if (t.startsWith("NUMERIC(")) {
            return "NUMBER("
                    + t.substring(8, t.length() - 1).replace(" ", "").replaceAll(",0$", "")
                    + ")";
        }
        if (t.equals("TIMESTAMP(0)")) {
            return "DATE";
        }
        if (t.startsWith("TIMESTAMP(")) {
            return t;
        }
        return switch (t) {
            case "BIGINT" -> "NUMBER(19)";
            case "INTEGER" -> "NUMBER(10)";
            case "SMALLINT" -> "NUMBER(5)";
            case "BOOLEAN" -> "NUMBER(1)";
            case "DECFLOAT" -> "NUMBER";
            case "CHARACTER LARGE OBJECT" -> "CLOB";
            case "BINARY LARGE OBJECT" -> "BLOB";
            default -> t.startsWith("BINARY VARYING(") ? "RAW(" + t.substring(15) : t;
        };
    }

    /**
     * Flyway's own history table on Oracle, exactly as Flyway creates it (build_setup_script.py).
     */
    static final String FLYWAY_HISTORY =
            "CREATE TABLE \"flyway_schema_history\" (\n"
                    + "    \"installed_rank\" INT NOT NULL,\n"
                    + "    \"version\" VARCHAR2(50),\n"
                    + "    \"description\" VARCHAR2(200) NOT NULL,\n"
                    + "    \"type\" VARCHAR2(20) NOT NULL,\n"
                    + "    \"script\" VARCHAR2(1000) NOT NULL,\n"
                    + "    \"checksum\" INT,\n"
                    + "    \"installed_by\" VARCHAR2(100) NOT NULL,\n"
                    + "    \"installed_on\" TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,\n"
                    + "    \"execution_time\" INT NOT NULL,\n"
                    + "    \"success\" NUMBER(1) NOT NULL\n"
                    + ")";

    /**
     * Differences between the H2 and the Oracle schema found by the replay, for the drift report.
     */
    record Drift(List<String> lines) {}

    /**
     * The Oracle catalog of the fallback.
     *
     * @param h2 the H2 catalog of a Flyway build (structure and identity counters)
     * @param r the replay of the Oracle migrations
     * @param drift receives the H2 / Oracle differences
     */
    static Catalog convert(Catalog h2, Replay r, List<String> drift) {
        Map<String, Table> tables = new TreeMap<>();
        Set<String> dropped = new LinkedHashSet<>();
        for (Table t : h2.tables().values()) {
            if (t.name().equals("flyway_schema_history")) {
                List<Column> cols = new ArrayList<>();
                for (Column c : t.columns()) {
                    cols.add(
                            new Column(
                                    c.name(),
                                    mapType(c),
                                    c.kind(),
                                    c.nullable(),
                                    c.defaultExpr(),
                                    null,
                                    false));
                }
                tables.put(t.name(), new Table(t.name(), cols, FLYWAY_HISTORY, List.of()));
                continue;
            }
            LinkedHashMap<String, OraColumn> ora = r.tables.get(t.name());
            if (ora == null) {
                drift.add("table " + t.name() + ": in H2 only");
                continue;
            }
            List<Column> cols = new ArrayList<>();
            List<String> lines = new ArrayList<>();
            for (Column c : t.columns()) {
                OraColumn o = ora.get(c.name());
                if (o == null) {
                    drift.add(
                            "column "
                                    + t.name()
                                    + "."
                                    + c.name()
                                    + ": in H2 only"
                                    + (c.generated() ? " (computed)" : ""));
                    dropped.add(t.name() + "." + c.name());
                    continue;
                }
                String type = o.type != null ? o.type : mapType(c);
                if (o.type == null) {
                    drift.add(
                            "column "
                                    + t.name()
                                    + "."
                                    + c.name()
                                    + ": Oracle type not found, mapped from H2 "
                                    + c.type());
                }
                if (o.notNull != null && o.notNull == c.nullable() && c.identity() == null) {
                    drift.add(
                            "column "
                                    + t.name()
                                    + "."
                                    + c.name()
                                    + ": NOT NULL differs (Oracle "
                                    + o.notNull
                                    + ")");
                }
                StringBuilder line =
                        new StringBuilder("    ")
                                .append(KitData.quote(c.name()))
                                .append(' ')
                                .append(type);
                Identity id = null;
                if (c.identity() != null) {
                    String clause =
                            o.identity != null
                                    ? o.identity
                                    : "GENERATED BY DEFAULT ON NULL AS IDENTITY";
                    line.append(' ').append(clause);
                    id =
                            new Identity(
                                    clause.contains("ALWAYS") ? "ALWAYS" : "BY DEFAULT",
                                    clause.contains("ON NULL"),
                                    c.identity().start(),
                                    c.identity().increment(),
                                    c.identity().next());
                } else if (o.defaultExpr != null) {
                    line.append(o.defaultOnNull ? " DEFAULT ON NULL " : " DEFAULT ")
                            .append(o.defaultExpr);
                }
                if (!c.nullable()) {
                    line.append(" NOT NULL");
                }
                lines.add(line.toString());
                cols.add(
                        new Column(
                                c.name(),
                                type,
                                kindOf(type, c.kind()),
                                c.nullable(),
                                c.identity() == null ? o.defaultExpr : null,
                                id,
                                false));
            }
            for (String name : ora.keySet()) {
                if (t.columns().stream().noneMatch(c -> c.name().equals(name))) {
                    drift.add(
                            "column "
                                    + t.name()
                                    + "."
                                    + name
                                    + ": in Oracle only (not in the fallback kit)");
                }
            }
            List<String> comments = new ArrayList<>();
            for (Map.Entry<String, String> e : r.comments.entrySet()) {
                String target = e.getKey();
                if (target.equals(t.name())
                        || target.startsWith(t.name() + ".")
                                && cols.stream()
                                        .anyMatch(c -> target.equals(t.name() + "." + c.name()))) {
                    comments.add(e.getValue());
                }
            }
            tables.put(
                    t.name(),
                    new Table(
                            t.name(),
                            cols,
                            "CREATE TABLE "
                                    + KitData.quote(t.name())
                                    + " (\n"
                                    + String.join(",\n", lines)
                                    + "\n)",
                            comments));
        }
        for (String t : r.tables.keySet()) {
            if (!h2.tables().containsKey(t)) {
                drift.add("table " + t + ": in Oracle only (not in the fallback kit)");
            }
        }
        List<Constraint> constraints = new ArrayList<>();
        for (Constraint k : h2.constraints()) {
            if (!tables.containsKey(k.table())
                    || k.refTable() != null && !tables.containsKey(k.refTable())
                    || k.columns().stream().anyMatch(c -> dropped.contains(k.table() + "." + c))) {
                drift.add(
                        "constraint "
                                + k.name()
                                + " on "
                                + k.table()
                                + ": on H2-only columns, left out");
                continue;
            }
            constraints.add(
                    new Constraint(
                            k.name(),
                            k.type(),
                            k.table(),
                            k.columns(),
                            k.refTable(),
                            k.refColumns(),
                            k.systemName(),
                            oracleConstraint(k)));
        }
        constraints.sort(Comparator.comparing(Constraint::table).thenComparing(Constraint::name));
        List<Index> indexes = new ArrayList<>();
        Set<String> h2Indexes = new LinkedHashSet<>();
        for (Index i : h2.indexes()) {
            h2Indexes.add(i.name().toUpperCase(Locale.ROOT));
        }
        for (Map.Entry<String, String> e : r.indexes.entrySet()) {
            String table = r.indexTable.get(e.getKey());
            if (table == null || !tables.containsKey(table)) {
                continue;
            }
            if (!h2Indexes.contains(e.getKey())) {
                drift.add("index " + e.getKey() + " on " + table + ": in Oracle only");
            }
            indexes.add(
                    new Index(
                            e.getKey(),
                            table,
                            List.of(),
                            e.getValue().toUpperCase(Locale.ROOT).contains("UNIQUE"),
                            e.getValue()));
        }
        // Flyway creates its history table's index itself on Oracle (no migration does).
        indexes.add(
                new Index(
                        "flyway_schema_history_s_idx",
                        "flyway_schema_history",
                        List.of("\"success\""),
                        false,
                        "CREATE INDEX \"flyway_schema_history_s_idx\" ON \"flyway_schema_history\""
                                + " (\"success\")"));
        h2Indexes.remove("FLYWAY_SCHEMA_HISTORY_S_IDX");
        for (String i : h2Indexes) {
            if (!r.indexes.containsKey(i)) {
                drift.add("index " + i + ": in H2 only");
            }
        }
        Map<String, View> views = new TreeMap<>();
        for (Map.Entry<String, String> e : r.views.entrySet()) {
            Set<String> uses = new LinkedHashSet<>();
            for (String other : r.views.keySet()) {
                if (!other.equals(e.getKey())
                        && Pattern.compile("\\b" + other + "\\b", Pattern.CASE_INSENSITIVE)
                                .matcher(e.getValue())
                                .find()) {
                    uses.add(other);
                }
            }
            views.put(e.getKey(), new View(e.getKey(), e.getValue(), e.getValue(), uses));
        }
        List<Trigger> triggers = new ArrayList<>();
        for (Map.Entry<String, String> e : r.triggers.entrySet()) {
            Matcher on = ON_TABLE.matcher(stripComments(e.getValue()));
            String table = on.find() ? on.group(1).toUpperCase(Locale.ROOT) : "?";
            triggers.add(
                    new Trigger(
                            e.getKey(), table, "", "", e.getValue(), true, e.getValue(), Set.of()));
        }
        Set<String> h2Triggers = new LinkedHashSet<>();
        h2.triggers().forEach(t -> h2Triggers.add(t.name()));
        for (Trigger t : triggers) {
            if (!h2Triggers.contains(t.name())) {
                drift.add("trigger " + t.name() + ": in Oracle only");
            }
        }
        for (String t : h2Triggers) {
            if (!r.triggers.containsKey(t)) {
                drift.add("trigger " + t + ": in H2 only");
            }
        }
        List<Sequence> sequences = new ArrayList<>();
        Map<String, Sequence> h2Seq = new TreeMap<>();
        h2.sequences().forEach(s -> h2Seq.put(s.name(), s));
        for (Map.Entry<String, String> e : r.sequences.entrySet()) {
            Sequence h = h2Seq.get(e.getKey());
            if (h == null) {
                drift.add("sequence " + e.getKey() + ": in Oracle only");
            }
            sequences.add(
                    new Sequence(
                            e.getKey(),
                            h == null ? 1 : h.increment(),
                            h == null ? 1 : h.next(),
                            e.getValue()));
        }
        for (String s : h2Seq.keySet()) {
            if (!r.sequences.containsKey(s)) {
                drift.add("sequence " + s + ": in H2 only");
            }
        }
        for (String u : r.unparsed) {
            drift.add("statement not replayed: " + u);
        }
        return new Catalog(
                Catalog.Vendor.ORACLE,
                tables,
                constraints,
                indexes,
                views,
                triggers,
                sequences,
                List.of());
    }

    private static Kind kindOf(String oracleType, Kind h2) {
        if (oracleType.startsWith("TIMESTAMP")) {
            return Kind.TIMESTAMP;
        }
        if (oracleType.equals("DATE")) {
            return Kind.DATE;
        }
        return h2;
    }

    /** The H2 constraint DDL in Oracle syntax (a database-named one left unnamed). */
    static String oracleConstraint(Constraint k) {
        String name = k.systemName() ? "" : "CONSTRAINT " + KitData.quote(k.name()) + " ";
        String head = "ALTER TABLE " + KitData.quote(k.table()) + " ADD " + name;
        String cols = String.join(", ", k.columns().stream().map(KitData::quote).toList());
        return switch (k.type()) {
            case 'P' -> head + "PRIMARY KEY (" + cols + ")";
            case 'U' -> head + "UNIQUE (" + cols + ")";
            case 'R' ->
                    head
                            + "FOREIGN KEY ("
                            + cols
                            + ") REFERENCES "
                            + KitData.quote(k.refTable())
                            + " ("
                            + String.join(
                                    ", ", k.refColumns().stream().map(KitData::quote).toList())
                            + ")"
                            + (k.ddl().contains("ON DELETE CASCADE")
                                    ? " ON DELETE CASCADE"
                                    : k.ddl().contains("ON DELETE SET NULL")
                                            ? " ON DELETE SET NULL"
                                            : "");
            default -> {
                String d =
                        k.ddl().replaceAll("\\s+NOCHECK\\s*;?\\s*$", "").replaceAll(";\\s*$", "");
                int at = d.indexOf(" CHECK");
                // H2 writes a number literal with its type, CAST(0 AS BIGINT): Oracle has no
                // BIGINT.
                String check = H2_NUMBER_CAST.matcher(d.substring(at + 6).strip()).replaceAll("$1");
                yield head + "CHECK " + check;
            }
        };
    }

    /** The H2 rows restricted to the columns of the Oracle tables. */
    static Map<String, TableRows> project(Map<String, TableRows> rows, Catalog oracle) {
        Map<String, TableRows> out = new TreeMap<>();
        for (Table t : oracle.tables().values()) {
            TableRows h = rows.get(t.name());
            int[] map = t.columns().stream().mapToInt(c -> h.index(c.name())).toArray();
            List<String> names = t.columns().stream().map(Column::name).toList();
            List<Kind> kinds = t.columns().stream().map(Column::kind).toList();
            int[] pk = new int[h.pk().length];
            for (int i = 0; i < pk.length; i++) {
                pk[i] = names.indexOf(h.columns().get(h.pk()[i]));
            }
            LinkedHashMap<String, Row> projected = new LinkedHashMap<>();
            for (Row r : h.rows().values()) {
                String[] v = new String[map.length];
                BitSet rel = new BitSet();
                for (int i = 0; i < map.length; i++) {
                    v[i] = r.values()[map[i]];
                    rel.set(2 * i, r.relative().get(2 * map[i]));
                    rel.set(2 * i + 1, r.relative().get(2 * map[i] + 1));
                }
                projected.put(r.key(), new Row(r.key(), v, rel));
            }
            out.put(t.name(), new TableRows(t.name(), names, kinds, pk, projected, new BitSet()));
        }
        return out;
    }
}
