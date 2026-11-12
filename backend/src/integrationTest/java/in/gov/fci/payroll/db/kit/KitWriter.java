package in.gov.fci.payroll.db.kit;

import in.gov.fci.payroll.db.kit.Catalog.CodeObject;
import in.gov.fci.payroll.db.kit.Catalog.Column;
import in.gov.fci.payroll.db.kit.Catalog.Constraint;
import in.gov.fci.payroll.db.kit.Catalog.Index;
import in.gov.fci.payroll.db.kit.Catalog.Kind;
import in.gov.fci.payroll.db.kit.Catalog.Sequence;
import in.gov.fci.payroll.db.kit.Catalog.Table;
import in.gov.fci.payroll.db.kit.Catalog.Trigger;
import in.gov.fci.payroll.db.kit.Catalog.Vendor;
import in.gov.fci.payroll.db.kit.Catalog.View;
import in.gov.fci.payroll.db.kit.KitData.Row;
import in.gov.fci.payroll.db.kit.KitData.TableRows;
import in.gov.fci.payroll.db.kit.KitPlan.Delete;
import in.gov.fci.payroll.db.kit.KitPlan.Insert;
import in.gov.fci.payroll.db.kit.KitPlan.Op;
import in.gov.fci.payroll.db.kit.KitSteps.Step;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Writes the kit's SQL files of one vendor from the ordered {@link Step}s and {@link Op}s. The text
 * conventions are fixed so that {@link SqlFiles} (and SQL*Plus / SQLcl, H2 RUNSCRIPT) split them
 * the same way: Oracle SQL statements end with ";" at the end of a line, PL/SQL units with a line
 * holding only "/", no line is longer than 2,000 characters (SQL*Plus refuses 2,500), no string
 * literal holds a line break, and every file is printable ASCII (R-ASCII: Hindi text as UNISTR /
 * U&amp; escapes).
 */
final class KitWriter {

    /** Characters of one string piece in a literal (a line stays far below SQL*Plus' limit). */
    private static final int PIECE = 900;

    /** Statements per Oracle data block (one round trip each over the WAN). */
    private static final int BLOCK = 40;

    private KitWriter() {}

    /**
     * One data script.
     *
     * @param file file name, e.g. 03_dml_reference.sql
     * @param title what it loads
     * @param demoOnly whether it must never run in production
     * @param ops the row operations in order
     * @param next the data set after the script (identity and sequence restart values)
     * @param nextCatalog the catalog of the profile build after the script (counters)
     * @param historyBefore rows of flyway_schema_history the script expects to find
     * @param historyAfter rows it leaves
     * @param disabled foreign keys that close a cycle: off during the load, validated after it
     */
    record Stage(
            String file,
            String title,
            boolean demoOnly,
            List<Op> ops,
            Map<String, TableRows> next,
            Catalog nextCatalog,
            int historyBefore,
            int historyAfter,
            java.util.Set<Constraint> disabled) {}

    /**
     * Generation facts written into the headers.
     *
     * @param generatedOn India's date of the generation
     * @param monthIndex year * 12 + month of that date (the relative-date base)
     * @param fiscalYear the financial year (April to March) of that date, by its first year
     * @param source what the kit was generated from
     * @param fallback Oracle only: generated without an Oracle connection (approximate)
     */
    record Meta(
            String generatedOn, int monthIndex, int fiscalYear, String source, boolean fallback) {}

    // ------------------------------------------------------------------------------------- H2

    static final class H2 implements KitSteps.DdlText {
        @Override
        public String sequence(Sequence s) {
            return s.ddl();
        }

        @Override
        public String table(Table t) {
            return t.ddl();
        }

        @Override
        public String constraint(Constraint k) {
            return k.ddl();
        }

        @Override
        public String index(Index i) {
            return i.ddl();
        }

        @Override
        public String view(View v) {
            return v.ddl();
        }

        @Override
        public String trigger(Trigger t) {
            return t.ddl();
        }

        @Override
        public String dropView(String name) {
            return "DROP VIEW IF EXISTS " + KitData.quote(name) + " CASCADE;";
        }

        @Override
        public String dropTable(String name) {
            return "DROP TABLE IF EXISTS " + KitData.quote(name) + " CASCADE;";
        }

        @Override
        public String dropSequence(String name) {
            return "DROP SEQUENCE IF EXISTS " + KitData.quote(name) + ";";
        }

        @Override
        public String dropCode(CodeObject o) {
            throw new IllegalStateException("H2 has no stored code: " + o.name());
        }

        @Override
        public boolean plsqlDrops() {
            return false;
        }
    }

    static String h2Script(String header, List<Step> steps) {
        StringBuilder b = new StringBuilder(header);
        String section = null;
        for (Step s : steps) {
            if (!s.section().equals(section)) {
                section = s.section();
                b.append("\n-- ").append(section).append('\n');
            }
            b.append(terminate(s.sql())).append('\n');
        }
        return b.toString();
    }

    private static String terminate(String sql) {
        String t = sql.strip();
        return t.endsWith(";") ? t : t + ";";
    }

    /** 06 on H2, after the data load: nothing to compile; the object counts must be the kit's. */
    static String h2Check(String header, Catalog c) {
        StringBuilder b = new StringBuilder(header);
        b.append(
                        "-- H2 keeps no stored code that a DDL change could leave invalid (its"
                                + " triggers are\n")
                .append(
                        "-- Java classes, loaded when they first fire): there is nothing to"
                                + " compile.\n")
                .append(
                        "-- Each check divides by zero, and so stops RUNSCRIPT, when a count is not"
                                + " the kit's.\n");
        check(
                b,
                "TABLES",
                c.tables().keySet(),
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = SCHEMA() AND"
                        + " TABLE_TYPE = 'BASE TABLE' AND TABLE_NAME IN ");
        check(
                b,
                "VIEWS",
                c.views().keySet(),
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.VIEWS WHERE TABLE_SCHEMA = SCHEMA() AND"
                        + " TABLE_NAME IN ");
        check(
                b,
                "SEQUENCES",
                c.sequences().stream().map(Sequence::name).toList(),
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.SEQUENCES WHERE SEQUENCE_SCHEMA = SCHEMA()"
                        + " AND SEQUENCE_NAME IN ");
        check(
                b,
                "TRIGGERS",
                new TreeSet<>(c.triggers().stream().map(Trigger::name).toList()),
                "SELECT COUNT(DISTINCT TRIGGER_NAME) FROM INFORMATION_SCHEMA.TRIGGERS WHERE"
                        + " TRIGGER_SCHEMA = SCHEMA() AND TRIGGER_NAME IN ");
        check(
                b,
                "INDEXES",
                c.indexes().stream().map(Index::name).toList(),
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEXES WHERE INDEX_SCHEMA = SCHEMA() AND"
                        + " INDEX_NAME IN ");
        check(
                b,
                "CONSTRAINTS",
                c.constraints().stream().map(Constraint::name).toList(),
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA"
                        + " = SCHEMA() AND CONSTRAINT_NAME IN ");
        return b.toString();
    }

    private static void check(
            StringBuilder b, String what, java.util.Collection<String> names, String count) {
        b.append("\n-- ")
                .append(names.size())
                .append(' ')
                .append(what.toLowerCase(java.util.Locale.ROOT))
                .append('\n');
        if (names.isEmpty()) {
            b.append("SELECT 1 / CASE WHEN (")
                    .append(
                            count.replace(" IN ", " IS NOT NULL")
                                    .replace(" AND TABLE_NAME IS NOT NULL", ""))
                    .append(") >= 0 THEN 1 ELSE 0 END AS \"KIT_CHECK_NO_")
                    .append(what)
                    .append("\";\n");
            return;
        }
        b.append("SELECT 1 / CASE WHEN (").append(count).append("(\n");
        int i = 0;
        StringBuilder line = new StringBuilder("    ");
        for (String n : names) {
            String lit = "'" + n.replace("'", "''") + "'" + (++i < names.size() ? ", " : "");
            if (line.length() + lit.length() > 110) {
                b.append(line.toString().stripTrailing()).append('\n');
                line = new StringBuilder("    ");
            }
            line.append(lit);
        }
        b.append(line.toString().stripTrailing()).append('\n');
        b.append(")) = ")
                .append(names.size())
                .append(" THEN 1 ELSE 0 END AS \"KIT_CHECK_")
                .append(names.size())
                .append('_')
                .append(what)
                .append("\";\n");
    }

    /** A data script for H2. */
    static String h2Data(String header, Stage st, Catalog c, Meta meta) {
        StringBuilder b = new StringBuilder(header);
        b.append(
                        "\n"
                                + "-- Values the Flyway seeds computed from today keep their place"
                                + " relative to today\n")
                .append(
                        "-- (INSTALL_KIT.md \"Relative dates\"): @KIT_SHIFT = months between the"
                                + " generation month and\n")
                .append(
                        "-- India's month today, for values that move with the month; @KIT_FY ="
                                + " financial years\n")
                .append(
                        "-- (April to March) between them, for values anchored on the financial"
                                + " year.\n")
                .append(
                        "SET @KIT_SHIFT = EXTRACT(YEAR FROM CAST(CURRENT_TIMESTAMP AT TIME ZONE"
                                + " 'Asia/Kolkata' AS DATE)) * 12\n")
                .append(
                        "    + EXTRACT(MONTH FROM CAST(CURRENT_TIMESTAMP AT TIME ZONE"
                                + " 'Asia/Kolkata' AS DATE)) - ")
                .append(meta.monthIndex())
                .append(";\n")
                .append(
                        "SET @KIT_FY = EXTRACT(YEAR FROM CAST(CURRENT_TIMESTAMP AT TIME ZONE"
                                + " 'Asia/Kolkata' AS DATE))\n")
                .append(
                        "    - CASE WHEN EXTRACT(MONTH FROM CAST(CURRENT_TIMESTAMP AT TIME ZONE"
                                + " 'Asia/Kolkata' AS DATE)) < 4")
                .append(" THEN 1 ELSE 0 END - ")
                .append(meta.fiscalYear())
                .append(";\n");
        b.append(
                        "\n"
                                + "-- Runs only on the state the previous script leaves"
                                + " (flyway_schema_history holds ")
                .append(st.historyBefore())
                .append(" rows).\n")
                .append("SELECT 1 / CASE WHEN (SELECT COUNT(*) FROM \"flyway_schema_history\") = ")
                .append(st.historyBefore())
                .append(" THEN 1 ELSE 0 END AS \"KIT_CHECK_PREVIOUS_STAGE\";\n");
        b.append(
                        "\n"
                                + "-- Triggers are dropped for the load and recreated after it"
                                + " (INSTALL_KIT.md \"Triggers\n")
                .append(
                        "-- during the load\"): the rows are the end state, which the triggers"
                                + " already produced.\n");
        for (Trigger t : c.triggers()) {
            b.append("DROP TRIGGER IF EXISTS ").append(KitData.quote(t.name())).append(";\n");
        }
        b.append(
                        "\n"
                            + "-- The foreign keys that close a cycle between tables are dropped"
                            + " for the load and added again\n")
                .append(
                        "-- after it, which checks every row (INSTALL_KIT.md \"Foreign-key"
                                + " cycles\").\n");
        for (Constraint k : st.disabled()) {
            b.append("ALTER TABLE ")
                    .append(KitData.quote(k.table()))
                    .append(" DROP CONSTRAINT ")
                    .append(KitData.quote(k.name()))
                    .append(";\n");
        }
        appendOps(b, st, Vendor.H2, null);
        b.append("\n-- The cycle foreign keys again, validated\n");
        for (Constraint k : st.disabled()) {
            b.append(terminate(k.ddl().replaceAll("\\s+NOCHECK\\s*;?\\s*$", ""))).append('\n');
        }
        b.append("\n-- Identity columns continue above the loaded rows\n");
        for (String line : restarts(st, Vendor.H2)) {
            b.append(line).append('\n');
        }
        b.append("\n-- Triggers again\n");
        for (Trigger t : c.triggers()) {
            b.append(terminate(t.ddl())).append('\n');
        }
        b.append("\nSELECT 1 / CASE WHEN (SELECT COUNT(*) FROM \"flyway_schema_history\") = ")
                .append(st.historyAfter())
                .append(" THEN 1 ELSE 0 END AS \"KIT_CHECK_HISTORY_LOADED\";\n")
                .append("COMMIT;\n");
        return b.toString();
    }

    // --------------------------------------------------------------------------------- Oracle

    static final class Oracle implements KitSteps.DdlText {
        @Override
        public String sequence(Sequence s) {
            return s.ddl();
        }

        @Override
        public String table(Table t) {
            return t.ddl();
        }

        @Override
        public String constraint(Constraint k) {
            return k.ddl();
        }

        @Override
        public String index(Index i) {
            return i.ddl();
        }

        @Override
        public String view(View v) {
            return v.ddl();
        }

        @Override
        public String trigger(Trigger t) {
            return t.ddl();
        }

        @Override
        public String dropView(String name) {
            return drop("VIEW " + KitData.quote(name), -942);
        }

        @Override
        public String dropTable(String name) {
            return drop("TABLE " + KitData.quote(name) + " CASCADE CONSTRAINTS PURGE", -942);
        }

        @Override
        public String dropSequence(String name) {
            return drop("SEQUENCE " + KitData.quote(name), -2289);
        }

        @Override
        public String dropCode(CodeObject o) {
            return switch (o.type()) {
                case "SYNONYM" -> drop("SYNONYM " + KitData.quote(o.name()), -1434);
                case "TYPE" -> drop("TYPE " + KitData.quote(o.name()) + " FORCE", -4043);
                default -> drop(o.type() + " " + KitData.quote(o.name()), -4043);
            };
        }

        @Override
        public boolean plsqlDrops() {
            return true;
        }

        /** One block per object: only "does not exist" is ignored, anything else stops the run. */
        static String drop(String what, int doesNotExist) {
            return "BEGIN\n    EXECUTE IMMEDIATE 'DROP "
                    + what.replace("'", "''")
                    + "';\nEXCEPTION\n"
                    + "    WHEN OTHERS THEN\n        IF SQLCODE <> "
                    + doesNotExist
                    + " THEN\n            RAISE;\n        END IF;\nEND;";
        }
    }

    static final String ORACLE_SESSION =
            "SET DEFINE OFF\n"
                    + "SET SQLBLANKLINES ON\n"
                    + "SET SERVEROUTPUT ON\n"
                    + "SET FEEDBACK OFF\n"
                    + "WHENEVER SQLERROR EXIT FAILURE ROLLBACK\n"
                    + "WHENEVER OSERROR EXIT FAILURE ROLLBACK\n"
                    + "ALTER SESSION SET TIME_ZONE = 'UTC';\n"
                    + "ALTER SESSION DISABLE PARALLEL DML;\n"
                    + "ALTER SESSION SET NLS_LANGUAGE = 'AMERICAN';\n"
                    + "ALTER SESSION SET NLS_TERRITORY = 'AMERICA';\n";

    /**
     * Step 0 of 02_ddl on Oracle: the kit partitions tables as DDL v4.0 defines (interval range,
     * hash subpartitions, LOCAL indexes), which needs the Partitioning option (Enterprise Edition
     * or an Autonomous Database). Stops before any DDL when v$option says it is off; a login that
     * cannot read v$option gets a note for the DBA and the run goes on.
     */
    static final String ORACLE_PARTITIONING_CHECK =
            "\nPROMPT Step 0: the Partitioning option (DDL v4.0 partitions the large tables)\n"
                    + "DECLARE\n"
                    + "    v VARCHAR2(64);\n"
                    + "BEGIN\n"
                    + "    EXECUTE IMMEDIATE 'SELECT value FROM v$option WHERE parameter ="
                    + " ''Partitioning''' INTO v;\n"
                    + "    IF v <> 'TRUE' THEN\n"
                    + "        RAISE_APPLICATION_ERROR(-20990, 'The Partitioning option is not"
                    + " enabled (v$option Partitioning = ' || v || '): DDL v4.0 partitions the"
                    + " large tables; use Oracle Enterprise Edition with Partitioning or an"
                    + " Autonomous Database');\n"
                    + "    END IF;\n"
                    + "EXCEPTION\n"
                    + "    WHEN NO_DATA_FOUND THEN\n"
                    + "        RAISE_APPLICATION_ERROR(-20990, 'v$option does not list the"
                    + " Partitioning option: DDL v4.0 needs it');\n"
                    + "    WHEN OTHERS THEN\n"
                    + "        IF SQLCODE = -942 THEN\n"
                    + "            DBMS_OUTPUT.PUT_LINE('NOTE: this login cannot read v$option:"
                    + " the DBA must confirm the Partitioning option (DDL v4.0 needs it)');\n"
                    + "        ELSE\n"
                    + "            RAISE;\n"
                    + "        END IF;\n"
                    + "END;\n"
                    + "/\n";

    static String oracleScript(String header, List<Step> steps) {
        StringBuilder b = new StringBuilder(header).append(ORACLE_SESSION);
        String section = null;
        for (Step s : steps) {
            if (!s.section().equals(section)) {
                section = s.section();
                b.append("\nPROMPT ").append(section).append('\n');
            }
            b.append(oracleUnit(s.sql(), s.plsql())).append('\n');
        }
        return b.toString();
    }

    /**
     * An Oracle comment. COMMENT ON takes one literal, which can neither be split into pieces nor
     * hold UNISTR escapes, and a long comment would pass SQL*Plus' line limit: the statement is
     * built in PL/SQL from the escaped pieces instead (the text is the same).
     *
     * @param target {@code TABLE "T"} or {@code COLUMN "T"."C"}
     */
    static String oracleComment(String target, String text) {
        String value = literal(Kind.STRING, text, KitData.FIXED, Vendor.ORACLE, new Lobs());
        return "BEGIN\n    EXECUTE IMMEDIATE 'COMMENT ON "
                + target.replace("'", "''")
                + " IS '''\n        || REPLACE("
                + value.replace("\n", "\n            ")
                + ", '''', '''''') || '''';\nEND;";
    }

    static String oracleUnit(String sql, boolean plsql) {
        String t = sql.strip();
        if (plsql) {
            if (t.endsWith("/")) {
                t = t.substring(0, t.length() - 1).stripTrailing();
            }
            return t + "\n/";
        }
        return t.endsWith(";") ? t : t + ";";
    }

    /**
     * 06 on Oracle, after the data load: recompile what the DDL left INVALID, then refuse invalid
     * objects and wrong counts.
     */
    static String oracleCompile(String header, Catalog c) {
        // OD-160: the kit runs in schema PAYROLL from any login, so the dictionary is read through
        // the ALL_* views filtered on the owner, never the login's own USER_* views.
        String s = OracleSchema.SCHEMA;
        StringBuilder b = new StringBuilder(header).append(ORACLE_SESSION);
        b.append("\nPROMPT Recompiling invalid objects\n")
                .append("BEGIN\n")
                .append("    DBMS_UTILITY.COMPILE_SCHEMA(schema => '")
                .append(s)
                .append("', compile_all => FALSE);\n")
                .append("END;\n/\n")
                .append("DECLARE\n    n NUMBER;\nBEGIN\n")
                .append("    SELECT COUNT(*) INTO n FROM all_objects WHERE owner = '")
                .append(s)
                .append("' AND status <> 'VALID';\n")
                .append("    IF n > 0 THEN\n")
                .append(
                        "        RAISE_APPLICATION_ERROR(-20997, n || ' invalid objects after"
                                + " the DDL and the data load: see ALL_ERRORS of owner ")
                .append(s)
                .append("');\n")
                .append("    END IF;\nEND;\n/\n");
        b.append("\nPROMPT Checking the object counts of the kit\n");
        String owner = " WHERE owner = '" + s + "'";
        oracleCount(
                b,
                "tables",
                c.tables().size(),
                "SELECT COUNT(*) INTO n FROM all_tables"
                        + owner
                        + " AND table_name NOT LIKE 'BIN$%'");
        oracleCount(b, "views", c.views().size(), "SELECT COUNT(*) INTO n FROM all_views" + owner);
        oracleCount(
                b,
                "sequences",
                c.sequences().size(),
                "SELECT COUNT(*) INTO n FROM all_sequences WHERE sequence_owner = '"
                        + s
                        + "' AND sequence_name NOT LIKE 'ISEQ$$%'");
        oracleCount(
                b,
                "triggers",
                c.triggers().size(),
                "SELECT COUNT(*) INTO n FROM all_triggers" + owner);
        oracleCount(
                b,
                "foreign keys",
                c.foreignKeys().size(),
                "SELECT COUNT(*) INTO n FROM all_constraints"
                        + owner
                        + " AND constraint_type = 'R'");
        oracleCount(
                b,
                "primary keys",
                (int) c.constraints().stream().filter(k -> k.type() == 'P').count(),
                "SELECT COUNT(*) INTO n FROM all_constraints"
                        + owner
                        + " AND constraint_type = 'P' AND table_name NOT LIKE 'BIN$%'");
        return b.toString();
    }

    private static void oracleCount(StringBuilder b, String what, int expected, String count) {
        b.append("DECLARE\n    n NUMBER;\nBEGIN\n    ")
                .append(count)
                .append(";\n")
                .append("    IF n <> ")
                .append(expected)
                .append(" THEN\n")
                .append("        RAISE_APPLICATION_ERROR(-20996, n || ' ")
                .append(what)
                .append(", the kit has ")
                .append(expected)
                .append("');\n")
                .append("    END IF;\nEND;\n/\n");
    }

    /** A data script for Oracle. */
    static String oracleData(String header, Stage st, Catalog c, Meta meta) {
        StringBuilder b = new StringBuilder(header).append(ORACLE_SESSION);
        b.append("\nPROMPT Checking that the previous script ran (flyway_schema_history holds ")
                .append(st.historyBefore())
                .append(" rows)\n")
                .append(
                        "DECLARE\n"
                                + "    n NUMBER;\n"
                                + "BEGIN\n"
                                + "    SELECT COUNT(*) INTO n FROM \"flyway_schema_history\";\n")
                .append("    IF n <> ")
                .append(st.historyBefore())
                .append(" THEN\n")
                .append(
                        "        RAISE_APPLICATION_ERROR(-20995, 'flyway_schema_history holds ' ||"
                                + " n || ' rows, ")
                .append(st.historyBefore())
                .append(" expected: run the kit in order');\n")
                .append("    END IF;\nEND;\n/\n");
        java.util.Set<String> triggerTables = new TreeSet<>();
        for (Trigger t : c.triggers()) {
            triggerTables.add(t.table());
        }
        b.append(
                "\n"
                    + "PROMPT Triggers are disabled for the load (INSTALL_KIT.md \"Triggers during"
                    + " the load\")\n");
        for (String t : triggerTables) {
            b.append("ALTER TABLE ").append(KitData.quote(t)).append(" DISABLE ALL TRIGGERS;\n");
        }
        b.append(
                "\n"
                    + "PROMPT The foreign keys that close a cycle between tables are disabled for"
                    + " the load\n");
        b.append(oracleCycleKeys(st.disabled(), "DISABLE"));
        appendOps(b, st, Vendor.ORACLE, meta);
        b.append("COMMIT;\n");
        b.append("\nPROMPT The cycle foreign keys again, validated\n");
        b.append(oracleCycleKeys(st.disabled(), "ENABLE VALIDATE"));
        b.append("\nPROMPT Identity columns and sequences continue above the loaded rows\n");
        for (String line : restarts(st, Vendor.ORACLE)) {
            b.append(line).append('\n');
        }
        b.append("\nPROMPT Triggers again\n");
        for (String t : triggerTables) {
            b.append("ALTER TABLE ").append(KitData.quote(t)).append(" ENABLE ALL TRIGGERS;\n");
        }
        for (Trigger t : c.triggers()) {
            if (!t.enabled()) {
                b.append("ALTER TRIGGER ").append(KitData.quote(t.name())).append(" DISABLE;\n");
            }
        }
        b.append(
                        "DECLARE\n"
                                + "    n NUMBER;\n"
                                + "BEGIN\n"
                                + "    SELECT COUNT(*) INTO n FROM \"flyway_schema_history\";\n")
                .append("    IF n <> ")
                .append(st.historyAfter())
                .append(" THEN\n")
                .append(
                        "        RAISE_APPLICATION_ERROR(-20994, 'flyway_schema_history holds ' ||"
                                + " n || ' rows after the load, ")
                .append(st.historyAfter())
                .append(" expected');\n")
                .append("    END IF;\nEND;\n/\n");
        return b.toString();
    }

    /**
     * Disables or enables (validating) the cycle foreign keys. They are found by table, columns and
     * parent table, not by name: a key Oracle named (SYS_C...) has another name in every schema.
     */
    static String oracleCycleKeys(java.util.Set<Constraint> keys, String action) {
        if (keys.isEmpty()) {
            return "";
        }
        StringBuilder b =
                new StringBuilder("DECLARE\n")
                        .append("    PROCEDURE fk(t VARCHAR2, cols VARCHAR2, p VARCHAR2) IS\n")
                        .append("        n PLS_INTEGER := 0;\n")
                        .append("    BEGIN\n")
                        .append(
                                "        FOR k IN (SELECT c.constraint_name FROM all_constraints"
                                        + " c\n")
                        .append(
                                "                    JOIN all_constraints r ON r.owner = c.r_owner"
                                        + " AND r.constraint_name = c.r_constraint_name\n")
                        .append("                   WHERE c.owner = '")
                        .append(OracleSchema.SCHEMA)
                        .append(
                                "' AND c.constraint_type = 'R' AND c.table_name = t"
                                        + " AND r.table_name = p\n")
                        .append(
                                "                     AND (SELECT LISTAGG(cc.column_name, ',')"
                                        + " WITHIN GROUP (ORDER BY cc.position)\n")
                        .append(
                                "                            FROM all_cons_columns cc WHERE"
                                        + " cc.owner = c.owner AND cc.constraint_name ="
                                        + " c.constraint_name) = cols) LOOP\n")
                        .append("            EXECUTE IMMEDIATE 'ALTER TABLE ")
                        .append(OracleSchema.SCHEMA)
                        .append(".\"' || t || '\" ")
                        .append(action)
                        .append(" CONSTRAINT \"' || k.constraint_name || '\"';\n")
                        .append("            n := n + 1;\n")
                        .append("        END LOOP;\n")
                        .append("        IF n = 0 THEN\n")
                        .append(
                                "            RAISE_APPLICATION_ERROR(-20993, 'No foreign key ' || t"
                                        + " || '(' || cols || ') -> ' || p);\n")
                        .append("        END IF;\n")
                        .append("    END;\n")
                        .append("BEGIN\n");
        for (Constraint k : keys) {
            b.append("    fk('")
                    .append(k.table())
                    .append("', '")
                    .append(String.join(",", k.columns()))
                    .append("', '")
                    .append(k.refTable())
                    .append("');\n");
        }
        return b.append("END;\n/\n").toString();
    }

    // ----------------------------------------------------------------------------- data rows

    private static void appendOps(StringBuilder b, Stage st, Vendor v, Meta meta) {
        String table = null;
        int inBlock = 0;
        String kind = null;
        for (Op op : st.ops()) {
            TableRows t = st.next().get(op.table());
            String k = op.getClass().getSimpleName();
            boolean newGroup = !op.table().equals(table) || !k.equals(kind);
            if (v == Vendor.ORACLE && inBlock > 0 && (inBlock >= BLOCK || newGroup)) {
                b.append("END;\n/\n");
                inBlock = 0;
            }
            if (newGroup) {
                table = op.table();
                kind = k;
                b.append(v == Vendor.H2 ? "\n-- " : "\nPROMPT ")
                        .append(k)
                        .append(' ')
                        .append(table)
                        .append('\n');
            }
            if (v == Vendor.ORACLE && inBlock == 0) {
                b.append(
                                "DECLARE\n"
                                        + "    k PLS_INTEGER;\n"
                                        + "    f PLS_INTEGER;\n"
                                        + "    c1 CLOB;\n"
                                        + "    c2 CLOB;\n"
                                        + "    c3 CLOB;\n")
                        .append("    b1 BLOB;\n    b2 BLOB;\nBEGIN\n")
                        .append(
                                "    -- k: months, f: financial years between the generation and"
                                        + " India's today\n")
                        .append(
                                "    SELECT EXTRACT(YEAR FROM SYSTIMESTAMP AT TIME ZONE"
                                        + " 'Asia/Kolkata') * 12\n")
                        .append(
                                "           + EXTRACT(MONTH FROM SYSTIMESTAMP AT TIME ZONE"
                                        + " 'Asia/Kolkata') - ")
                        .append(meta.monthIndex())
                        .append(",\n")
                        .append(
                                "           EXTRACT(YEAR FROM SYSTIMESTAMP AT TIME ZONE"
                                        + " 'Asia/Kolkata')\n")
                        .append(
                                "           - CASE WHEN EXTRACT(MONTH FROM SYSTIMESTAMP AT TIME"
                                        + " ZONE 'Asia/Kolkata') < 4")
                        .append(" THEN 1 ELSE 0 END - ")
                        .append(meta.fiscalYear())
                        .append("\n")
                        .append("      INTO k, f FROM dual;\n");
            }
            b.append(statement(t, op, v)).append('\n');
            inBlock++;
        }
        if (v == Vendor.ORACLE && inBlock > 0) {
            b.append("END;\n/\n");
        }
    }

    static String statement(TableRows t, Op op, Vendor v) {
        Lobs lobs = new Lobs();
        String sql =
                switch (op) {
                    case Insert ins -> insert(t, ins.row(), v, lobs);
                    case Delete del ->
                            "DELETE FROM "
                                    + KitData.quote(t.table())
                                    + where(t, del.row(), v, lobs)
                                    + ";";
                };
        String indent = v == Vendor.ORACLE ? "    " : "";
        StringBuilder out = new StringBuilder();
        for (String pre : lobs.pre) {
            out.append(indent).append(pre).append('\n');
        }
        for (String line : sql.split("\n", -1)) {
            out.append(indent).append(line).append('\n');
        }
        return out.toString().stripTrailing();
    }

    private static String insert(TableRows t, Row r, Vendor v, Lobs lobs) {
        List<String> cols = new ArrayList<>();
        List<String> vals = new ArrayList<>();
        for (int i = 0; i < t.columns().size(); i++) {
            if (r.values()[i] == null || t.generated().get(i)) {
                continue;
            }
            cols.add(KitData.quote(t.columns().get(i)));
            vals.add(literal(t.kinds().get(i), r.values()[i], KitData.clock(r, i), v, lobs));
        }
        // Columns that are NULL are written explicitly too: a column default must not fill them.
        List<String> nullCols = new ArrayList<>();
        for (int i = 0; i < t.columns().size(); i++) {
            if (r.values()[i] == null && !t.generated().get(i)) {
                nullCols.add(KitData.quote(t.columns().get(i)));
            }
        }
        cols.addAll(nullCols);
        for (int i = 0; i < nullCols.size(); i++) {
            vals.add("NULL");
        }
        return "INSERT INTO "
                + KitData.quote(t.table())
                + " ("
                + wrap(cols)
                + ")\nVALUES ("
                + wrap(vals)
                + ");";
    }

    private static String where(TableRows t, Row r, Vendor v, Lobs lobs) {
        List<String> parts = new ArrayList<>();
        for (int i : t.pk()) {
            Kind k = t.kinds().get(i);
            if (k == Kind.CLOB || k == Kind.BLOB) {
                if (v == Vendor.ORACLE) {
                    throw new IllegalStateException(
                            t.table() + ": a row without a primary key holds a LOB");
                }
            }
            String col = KitData.quote(t.columns().get(i));
            String val = r.values()[i];
            parts.add(
                    val == null
                            ? col + " IS NULL"
                            : col + " = " + literal(k, val, KitData.FIXED, v, lobs));
        }
        return "\nWHERE " + String.join("\n  AND ", parts);
    }

    /** Joins items with ", ", breaking the line before it passes 1,000 characters. */
    private static String wrap(List<String> items) {
        StringBuilder out = new StringBuilder();
        int line = 0;
        for (int i = 0; i < items.size(); i++) {
            String item = items.get(i);
            String sep = i == 0 ? "" : ", ";
            int firstLine = item.indexOf('\n') < 0 ? item.length() : item.indexOf('\n');
            if (line + sep.length() + firstLine > 1000 && line > 0) {
                out.append(",\n    ");
                line = 4;
            } else {
                out.append(sep);
                line += sep.length();
            }
            out.append(item);
            int nl = item.lastIndexOf('\n');
            line = nl < 0 ? line + item.length() : item.length() - nl - 1;
        }
        return out.toString();
    }

    /** PL/SQL statements a row needs before its INSERT (LOBs too long for one literal). */
    static final class Lobs {
        final List<String> pre = new ArrayList<>();
        int clobs;
        int blobs;
    }

    /**
     * The literal of a canonical cell value, per vendor.
     *
     * @param clock {@link KitData#FIXED}, {@link KitData#MONTHLY} or {@link KitData#FISCAL}
     */
    static String literal(Kind kind, String value, int clock, Vendor v, Lobs lobs) {
        return switch (kind) {
            case NUMBER -> new BigDecimal(value).toPlainString();
            case BOOLEAN -> v == Vendor.H2 ? value : ("TRUE".equals(value) ? "1" : "0");
            case STRING ->
                    clock == KitData.FIXED ? text(value, v, false, lobs) : period(value, clock, v);
            case CLOB -> text(value, v, true, lobs);
            case BLOB, RAW -> bytes(value, v, kind == Kind.BLOB, lobs);
            case DATE, TIMESTAMP -> dateTime(kind, value, clock, v);
        };
    }

    /** The month shift of a clock class: k months, or 12 per financial year. */
    private static String months(int clock, Vendor v) {
        if (v == Vendor.H2) {
            return clock == KitData.FISCAL ? "12 * @KIT_FY" : "@KIT_SHIFT";
        }
        return clock == KitData.FISCAL ? "12 * f" : "k";
    }

    private static String dateTime(Kind kind, String value, int clock, Vendor v) {
        String text = trimNanos(value);
        String lit;
        if (v == Vendor.H2) {
            lit = "TIMESTAMP '" + text + "'";
            if (clock == KitData.FIXED) {
                return lit;
            }
            String moved = "DATEADD(MONTH, " + months(clock, v) + ", " + lit + ")";
            return KitData.lastDayMidnight(value) ? "LAST_DAY(" + moved + ")" : moved;
        }
        lit =
                kind == Kind.DATE
                        ? "TO_DATE('" + text.substring(0, 19) + "', 'YYYY-MM-DD HH24:MI:SS')"
                        : "TIMESTAMP '" + text + "'";
        // ADD_MONTHS keeps the last day of a month the last day.
        return clock == KitData.FIXED ? lit : "ADD_MONTHS(" + lit + ", " + months(clock, v) + ")";
    }

    /** A period written as text (YYMM, YYYYMM, YYYY-YY), moved like a date of its clock class. */
    private static String period(String value, int clock, Vendor v) {
        String kind = KitData.textPeriod(value);
        if (kind == null) {
            throw new IllegalStateException("not a period: " + value);
        }
        if (kind.equals("FY")) {
            String shift = v == Vendor.H2 ? "@KIT_FY" : "f";
            int start = Integer.parseInt(value.substring(0, 4));
            return "TO_CHAR("
                    + start
                    + " + "
                    + shift
                    + ") || '-' || LPAD(TO_CHAR(MOD("
                    + (start + 1)
                    + " + "
                    + shift
                    + ", 100)), 2, '0')";
        }
        String format = kind.equals("YYMM") ? "YYMM" : "YYYYMM";
        String date = "TO_DATE('" + value + "', '" + format + "')";
        String moved =
                v == Vendor.H2
                        ? "DATEADD(MONTH, " + months(clock, v) + ", " + date + ")"
                        : "ADD_MONTHS(" + date + ", " + months(clock, v) + ")";
        return "TO_CHAR(" + moved + ", '" + format + "')";
    }

    private static String trimNanos(String canonical) {
        String t = canonical;
        while (t.endsWith("0") && t.contains(".")) {
            t = t.substring(0, t.length() - 1);
        }
        return t.endsWith(".") ? t.substring(0, t.length() - 1) : t;
    }

    private static String bytes(String hex, Vendor v, boolean blob, Lobs lobs) {
        if (v == Vendor.H2) {
            return pieces(hex, PIECE * 2).stream()
                    .map(p -> "X'" + p + "'")
                    .reduce((a, b) -> a + " ||\n" + b)
                    .orElse("X''");
        }
        List<String> parts = pieces(hex, PIECE * 2);
        if (parts.size() == 1) {
            return blob ? "TO_BLOB(HEXTORAW('" + hex + "'))" : "HEXTORAW('" + hex + "')";
        }
        if (!blob) {
            throw new IllegalStateException("RAW value longer than " + PIECE + " bytes");
        }
        String var = "b" + (++lobs.blobs);
        if (lobs.blobs > 2) {
            throw new IllegalStateException("more than two long BLOBs in one row");
        }
        lobs.pre.add(var + " := TO_BLOB(HEXTORAW('" + parts.get(0) + "'));");
        for (int i = 1; i < parts.size(); i++) {
            lobs.pre.add(
                    "DBMS_LOB.APPEND(" + var + ", TO_BLOB(HEXTORAW('" + parts.get(i) + "')));");
        }
        return var;
    }

    private static List<String> pieces(String s, int size) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < s.length(); i += size) {
            out.add(s.substring(i, Math.min(s.length(), i + size)));
        }
        if (out.isEmpty()) {
            out.add("");
        }
        return out;
    }

    /**
     * A string literal: printable ASCII as is (quotes doubled); anything else escaped (H2
     * U&amp;'\XXXX', Oracle UNISTR('\XXXX')), so every kit file is ASCII and no literal holds a
     * line break.
     */
    private static String text(String value, Vendor v, boolean clob, Lobs lobs) {
        List<String> parts = new ArrayList<>();
        StringBuilder piece = new StringBuilder();
        // H2: whether the piece holds an escape (then U&'...'); Oracle: whether the run is escaped.
        boolean pieceEscaped = false;
        for (int i = 0; i < value.length(); ) {
            int cp = value.codePointAt(i);
            i += Character.charCount(cp);
            boolean plain = cp >= 0x20 && cp < 0x7F && cp != '\\';
            String unit;
            if (plain) {
                unit = cp == '\'' ? "''" : String.valueOf((char) cp);
            } else if (cp <= 0xFFFF) {
                unit = String.format("\\%04X", cp);
            } else if (v == Vendor.H2) {
                unit = String.format("\\+%06X", cp);
            } else {
                char[] pair = Character.toChars(cp);
                unit = String.format("\\%04X\\%04X", (int) pair[0], (int) pair[1]);
            }
            boolean split =
                    piece.length() > 0
                            && (piece.length() + unit.length() > PIECE
                                    || v == Vendor.ORACLE && pieceEscaped == plain);
            if (split) {
                parts.add(piece(piece.toString(), pieceEscaped, v));
                piece.setLength(0);
                pieceEscaped = false;
            }
            if (!plain) {
                pieceEscaped = true;
            }
            piece.append(unit);
        }
        if (piece.length() > 0 || parts.isEmpty()) {
            parts.add(piece(piece.toString(), pieceEscaped, v));
        }
        if (v == Vendor.H2 || !clob) {
            return String.join(" ||\n", parts);
        }
        if (parts.size() <= 3) {
            return String.join(" ||\n", parts.stream().map(p -> "TO_CLOB(" + p + ")").toList());
        }
        String var = "c" + (++lobs.clobs);
        if (lobs.clobs > 3) {
            throw new IllegalStateException("more than three long CLOBs in one row");
        }
        lobs.pre.add(var + " := TO_CLOB(" + parts.get(0) + ");");
        for (int i = 1; i < parts.size(); i++) {
            lobs.pre.add("DBMS_LOB.APPEND(" + var + ", TO_CLOB(" + parts.get(i) + "));");
        }
        return var;
    }

    private static String piece(String body, boolean escaped, Vendor v) {
        if (!escaped) {
            return "'" + body + "'";
        }
        return v == Vendor.H2 ? "U&'" + body + "'" : "UNISTR('" + body + "')";
    }

    /** Restart statements for every identity column and sequence, above the loaded rows. */
    static List<String> restarts(Stage st, Vendor v) {
        List<String> out = new ArrayList<>();
        Map<String, Long> next = new LinkedHashMap<>();
        for (Table t : st.nextCatalog().tables().values()) {
            for (Column c : t.columns()) {
                if (c.identity() == null) {
                    continue;
                }
                long max = 0;
                TableRows rows = st.next().get(t.name());
                int idx = rows.index(c.name());
                for (Row r : rows.rows().values()) {
                    if (r.values()[idx] != null) {
                        max = Math.max(max, new BigDecimal(r.values()[idx]).longValueExact());
                    }
                }
                long value = Math.max(c.identity().next(), max + 1);
                next.put(t.name() + "." + c.name(), value);
                if (v == Vendor.H2) {
                    out.add(
                            "ALTER TABLE "
                                    + KitData.quote(t.name())
                                    + " ALTER COLUMN "
                                    + KitData.quote(c.name())
                                    + " RESTART WITH "
                                    + value
                                    + ";");
                } else {
                    out.add(
                            "ALTER TABLE "
                                    + KitData.quote(t.name())
                                    + " MODIFY ("
                                    + KitData.quote(c.name())
                                    + " GENERATED "
                                    + c.identity().generation()
                                    + (c.identity().onNull() ? " ON NULL" : "")
                                    + " AS IDENTITY (START WITH LIMIT VALUE));");
                }
            }
        }
        for (Sequence s : st.nextCatalog().sequences()) {
            if (v == Vendor.H2) {
                out.add(
                        "ALTER SEQUENCE "
                                + KitData.quote(s.name())
                                + " RESTART WITH "
                                + s.next()
                                + ";");
            } else {
                out.add(
                        "ALTER SEQUENCE "
                                + KitData.quote(s.name())
                                + " RESTART START WITH "
                                + s.next()
                                + ";");
            }
        }
        return out;
    }
}
