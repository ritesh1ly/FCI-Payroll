package in.gov.fci.payroll.db.kit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.gov.fci.payroll.db.kit.Catalog.Column;
import in.gov.fci.payroll.db.kit.Catalog.Constraint;
import in.gov.fci.payroll.db.kit.Catalog.Kind;
import in.gov.fci.payroll.db.kit.Catalog.Table;
import in.gov.fci.payroll.db.kit.KitData.Row;
import in.gov.fci.payroll.db.kit.KitData.TableRows;
import in.gov.fci.payroll.db.kit.KitOrder.TableOrder;
import in.gov.fci.payroll.db.kit.KitPlan.Op;
import in.gov.fci.payroll.db.kit.KitSteps.Step;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/**
 * The database install kit (docs/db/INSTALL_KIT.md): the order validators on hand-made schemas
 * (always), and the proof on the real schema (opt-in, {@code FCI_INSTALL_KIT=1}, about 12 minutes):
 * the kit generated from the Flyway builds runs on H2 on an empty, a full, a half-built and a
 * Flyway-built schema, twice in a row, and equals the Flyway build object by object and row by row.
 */
class InstallKitIT {

    // ------------------------------------------------------------- validators (synthetic schema)

    private static Column col(String name, boolean nullable) {
        return new Column(name, "BIGINT", Kind.NUMBER, nullable, null, null, false);
    }

    private static Constraint pk(String table) {
        return new Constraint(
                "PK_" + table,
                'P',
                table,
                List.of("ID"),
                null,
                List.of(),
                false,
                "ALTER TABLE \""
                        + table
                        + "\" ADD CONSTRAINT \"PK_"
                        + table
                        + "\" PRIMARY KEY(\"ID\");");
    }

    private static Constraint fk(String name, String child, String column, String parent) {
        return new Constraint(
                name,
                'R',
                child,
                List.of(column),
                parent,
                List.of("ID"),
                false,
                "ALTER TABLE \""
                        + child
                        + "\" ADD CONSTRAINT \""
                        + name
                        + "\" FOREIGN KEY(\""
                        + column
                        + "\") REFERENCES \""
                        + parent
                        + "\"(\"ID\");");
    }

    /** PARENT <- CHILD, CHILD.PARENT_ID; A <-> B (a cycle); NODE.UP_ID -> NODE (self reference). */
    static Catalog synthetic() {
        Map<String, Table> tables = new TreeMap<>();
        tables.put(
                "PARENT",
                new Table(
                        "PARENT",
                        List.of(col("ID", false)),
                        "CREATE TABLE \"PARENT\"(\"ID\" BIGINT);",
                        List.of()));
        tables.put(
                "CHILD",
                new Table(
                        "CHILD",
                        List.of(col("ID", false), col("PARENT_ID", false)),
                        "CREATE TABLE \"CHILD\"(\"ID\" BIGINT, \"PARENT_ID\" BIGINT);",
                        List.of()));
        tables.put(
                "A",
                new Table(
                        "A",
                        List.of(col("ID", false), col("B_ID", true)),
                        "CREATE TABLE \"A\"(...);",
                        List.of()));
        tables.put(
                "B",
                new Table(
                        "B",
                        List.of(col("ID", false), col("A_ID", false)),
                        "CREATE TABLE \"B\"(...);",
                        List.of()));
        tables.put(
                "NODE",
                new Table(
                        "NODE",
                        List.of(col("ID", false), col("UP_ID", true)),
                        "CREATE TABLE \"NODE\"(...);",
                        List.of()));
        List<Constraint> keys =
                new ArrayList<>(
                        List.of(
                                pk("PARENT"),
                                pk("CHILD"),
                                pk("A"),
                                pk("B"),
                                pk("NODE"),
                                fk("FK_CHILD_PARENT", "CHILD", "PARENT_ID", "PARENT"),
                                fk("FK_A_B", "A", "B_ID", "B"),
                                fk("FK_B_A", "B", "A_ID", "A"),
                                fk("FK_NODE_UP", "NODE", "UP_ID", "NODE")));
        return new Catalog(
                Catalog.Vendor.H2,
                tables,
                keys,
                List.of(),
                Map.of(),
                List.of(),
                List.of(),
                List.of());
    }

    private static TableRows rows(Catalog c, String table, String[]... values) {
        Table t = c.tables().get(table);
        LinkedHashMap<String, Row> rows = new LinkedHashMap<>();
        for (String[] v : values) {
            String key = KitData.key(v, new int[] {0});
            rows.put(key, new Row(key, v, new BitSet()));
        }
        return new TableRows(
                table,
                t.columns().stream().map(Column::name).toList(),
                t.columns().stream().map(Column::kind).toList(),
                new int[] {0},
                rows,
                new BitSet());
    }

    static Map<String, TableRows> data(Catalog c) {
        Map<String, TableRows> d = new TreeMap<>();
        d.put("PARENT", rows(c, "PARENT", new String[] {"1"}));
        d.put("CHILD", rows(c, "CHILD", new String[] {"10", "1"}));
        d.put("A", rows(c, "A", new String[] {"1", "1"}));
        d.put("B", rows(c, "B", new String[] {"1", "1"}));
        // 3 -> 2 -> 1: key order 1, 2, 3 is already a level order; 0 -> 3 is not.
        d.put(
                "NODE",
                rows(
                        c,
                        "NODE",
                        new String[] {"0", "3"},
                        new String[] {"1", null},
                        new String[] {"2", "1"},
                        new String[] {"3", "2"}));
        return d;
    }

    @Test
    void tables_are_ordered_parents_first_and_a_cycle_is_broken_on_one_key() {
        Catalog c = synthetic();
        TableOrder order = KitOrder.tables(c);
        assertThat(order.tables().indexOf("PARENT")).isLessThan(order.tables().indexOf("CHILD"));
        assertThat(order.cycles()).containsExactly(Set.of("A", "B"));
        assertThat(order.deferred()).hasSize(1);
        assertThat(order.selfReferencing()).containsExactly("NODE");
    }

    @Test
    void ddl_validator_refuses_a_foreign_key_before_its_tables_and_keys() {
        Catalog c = synthetic();
        List<Step> ddl = KitSteps.ddl(c, KitOrder.tables(c), new KitWriter.H2());
        assertThat(KitSteps.validateDdl(ddl)).isEqualTo(ddl.size());
        List<Step> broken = new ArrayList<>(ddl);
        Step fkStep =
                broken.stream()
                        .filter(s -> s.section().equals("Foreign keys"))
                        .findFirst()
                        .orElseThrow();
        broken.remove(fkStep);
        broken.addFirst(fkStep);
        assertThatThrownBy(() -> KitSteps.validateDdl(broken))
                .hasMessageContaining("before it is created");
    }

    @Test
    void drop_validator_refuses_a_parent_dropped_before_its_child() {
        Catalog c = synthetic();
        List<Step> drop = KitSteps.drop(c, KitOrder.tables(c), new KitWriter.H2());
        assertThat(KitSteps.validateDrop(drop)).isEqualTo(drop.size());
        List<Step> broken = new ArrayList<>(drop);
        Step parent =
                broken.stream()
                        .filter(s -> s.creates().contains("T:PARENT"))
                        .findFirst()
                        .orElseThrow();
        broken.remove(parent);
        broken.addFirst(parent);
        assertThatThrownBy(() -> KitSteps.validateDrop(broken))
                .hasMessageContaining("still exists");
    }

    @Test
    void data_plan_inserts_parents_first_and_rows_of_a_self_reference_by_level() {
        Catalog c = synthetic();
        TableOrder order = KitOrder.tables(c);
        Map<String, TableRows> next = data(c);
        List<Op> ops = KitPlan.plan(c, order, Map.of(), next, Duration.ZERO);
        KitPlan.Summary s =
                KitPlan.validate(c, Map.of(), next, ops, order.deferred(), Duration.ZERO);
        assertThat(s.inserts()).containsEntry("NODE", 4).containsEntry("CHILD", 1);
        List<String> nodes =
                ops.stream()
                        .filter(o -> o.table().equals("NODE"))
                        .map(o -> o.row().values()[0])
                        .toList();
        assertThat(nodes).containsExactly("1", "2", "3", "0");
    }

    @Test
    void data_validator_refuses_a_child_row_before_its_parent_row() {
        Catalog c = synthetic();
        TableOrder order = KitOrder.tables(c);
        Map<String, TableRows> next = data(c);
        List<Op> ops = new ArrayList<>(KitPlan.plan(c, order, Map.of(), next, Duration.ZERO));
        Op child = ops.stream().filter(o -> o.table().equals("CHILD")).findFirst().orElseThrow();
        ops.remove(child);
        ops.addFirst(child);
        assertThatThrownBy(
                        () ->
                                KitPlan.validate(
                                        c, Map.of(), next, ops, order.deferred(), Duration.ZERO))
                .hasMessageContaining("not inserted yet");
    }

    @Test
    void data_validator_refuses_a_parent_deleted_while_a_child_still_references_it() {
        Catalog c = synthetic();
        TableOrder order = KitOrder.tables(c);
        Map<String, TableRows> prev = data(c);
        Map<String, TableRows> next = new TreeMap<>(prev);
        next.put("PARENT", rows(c, "PARENT"));
        next.put("CHILD", rows(c, "CHILD"));
        List<Op> ops = new ArrayList<>(KitPlan.plan(c, order, prev, next, Duration.ZERO));
        assertThat(ops).extracting(Op::table).containsExactly("CHILD", "PARENT");
        java.util.Collections.reverse(ops);
        assertThatThrownBy(
                        () -> KitPlan.validate(c, prev, next, ops, order.deferred(), Duration.ZERO))
                .hasMessageContaining("still referenced by CHILD");
    }

    @Test
    void the_validator_requires_the_end_state_to_be_the_later_data_set() {
        Catalog c = synthetic();
        TableOrder order = KitOrder.tables(c);
        Map<String, TableRows> next = data(c);
        List<Op> ops = new ArrayList<>(KitPlan.plan(c, order, Map.of(), next, Duration.ZERO));
        ops.removeIf(o -> o.table().equals("NODE") && o.row().values()[0].equals("0"));
        assertThatThrownBy(
                        () ->
                                KitPlan.validate(
                                        c, Map.of(), next, ops, order.deferred(), Duration.ZERO))
                .hasMessageContaining("NODE: 3 rows after the script, expected 4");
    }

    @Test
    void a_cycle_of_rows_in_one_table_cannot_be_ordered_and_fails_generation() {
        Catalog c = synthetic();
        Map<String, TableRows> next = data(c);
        next.put("NODE", rows(c, "NODE", new String[] {"1", "2"}, new String[] {"2", "1"}));
        assertThatThrownBy(() -> KitPlan.plan(c, KitOrder.tables(c), Map.of(), next, Duration.ZERO))
                .hasMessageContaining("reference each other in a cycle");
    }

    @Test
    void oracle_literals_stay_ascii_and_short_and_relative_dates_follow_today() {
        KitWriter.Lobs lobs = new KitWriter.Lobs();
        String hindi =
                KitWriter.literal(
                        Kind.STRING, "गैंग 'x'", KitData.FIXED, Catalog.Vendor.ORACLE, lobs);
        assertThat(hindi).isEqualTo("UNISTR('\\0917\\0948\\0902\\0917') ||\n' ''x'''");
        assertThat(KitWriter.literal(Kind.STRING, "ग", KitData.FIXED, Catalog.Vendor.H2, lobs))
                .isEqualTo("U&'\\0917'");
        assertThat(
                        KitWriter.literal(
                                Kind.DATE,
                                "2026-03-31 00:00:00.000000000",
                                KitData.MONTHLY,
                                Catalog.Vendor.H2,
                                lobs))
                .isEqualTo("LAST_DAY(DATEADD(MONTH, @KIT_SHIFT, TIMESTAMP '2026-03-31 00:00:00'))");
        assertThat(
                        KitWriter.literal(
                                Kind.DATE,
                                "2026-03-30 10:00:00.000000000",
                                KitData.FISCAL,
                                Catalog.Vendor.ORACLE,
                                lobs))
                .isEqualTo(
                        "ADD_MONTHS(TO_DATE('2026-03-30 10:00:00', 'YYYY-MM-DD HH24:MI:SS'), 12 *"
                                + " f)");
        assertThat(
                        KitWriter.literal(
                                Kind.STRING, "2611", KitData.MONTHLY, Catalog.Vendor.ORACLE, lobs))
                .isEqualTo("TO_CHAR(ADD_MONTHS(TO_DATE('2611', 'YYMM'), k), 'YYMM')");
        assertThat(
                        KitWriter.literal(
                                Kind.STRING, "2026-27", KitData.FISCAL, Catalog.Vendor.H2, lobs))
                .isEqualTo(
                        "TO_CHAR(2026 + @KIT_FY) || '-' || LPAD(TO_CHAR(MOD(2027 + @KIT_FY, 100)),"
                                + " 2, '0')");
        assertThat(KitData.shiftText("2611", 28, 1)).isEqualTo("5412");
        assertThat(KitData.shiftText("2026-27", 28, 0)).isEqualTo("2054-55");
        assertThat(KitData.shiftText("ORD/2026/11", 28, 0)).isNull();
        String longText = "a".repeat(5000);
        String clob =
                KitWriter.literal(Kind.CLOB, longText, KitData.FIXED, Catalog.Vendor.ORACLE, lobs);
        assertThat(clob).isEqualTo("c1");
        assertThat(lobs.pre).allSatisfy(line -> assertThat(line.length()).isLessThan(1000));
    }

    @Test
    void the_clock_shift_moves_only_clock_functions_outside_literals_and_comments() {
        String sql =
                "INSERT INTO t VALUES (CURRENT_DATE, 'CURRENT_DATE', SYSTIMESTAMP); --"
                        + " CURRENT_DATE";
        assertThat(KitBuild.shift(sql, Catalog.Vendor.H2))
                .isEqualTo(
                        "INSERT INTO t VALUES (DATEADD(YEAR, 28, CURRENT_DATE), 'CURRENT_DATE',"
                                + " DATEADD(YEAR, 28, SYSTIMESTAMP)); -- CURRENT_DATE");
    }

    /**
     * OD-141 D-screens (INSTALL_KIT.md): the data scripts form a tree, not a line. Edge and screens
     * each follow demo, never each other; screens keeps the demo-evaluation seeds and reads its own
     * folders, of which a missing one is skipped.
     */
    @Test
    void the_screens_data_set_runs_on_top_of_demo_beside_edge_never_after_it() {
        assertThat(KitFiles.run(KitBuild.Profile.SCREENS))
                .containsExactly(
                        "01_drop.sql",
                        "02_ddl.sql",
                        "03_dml_reference.sql",
                        "04_dml_demo.sql",
                        "05b_dml_screens.sql",
                        "06_compile_invalid.sql");
        assertThat(KitFiles.run(KitBuild.Profile.EDGE))
                .containsExactly(
                        "01_drop.sql",
                        "02_ddl.sql",
                        "03_dml_reference.sql",
                        "04_dml_demo.sql",
                        "05_dml_edge.sql",
                        "06_compile_invalid.sql");
        assertThat(KitFiles.run(KitBuild.Profile.REFERENCE))
                .containsExactly(
                        "01_drop.sql",
                        "02_ddl.sql",
                        "03_dml_reference.sql",
                        "06_compile_invalid.sql");
        assertThat(KitBuild.Profile.SCREENS.parent()).isEqualTo(KitBuild.Profile.DEMO);
        assertThat(KitBuild.Profile.EDGE.parent()).isEqualTo(KitBuild.Profile.DEMO);
        assertThat(KitBuild.Profile.REFERENCE.parent()).isNull();
        assertThat(KitBuild.Profile.SCREENS.placeholders()).containsEntry("demo_evaluation", "Y");
        assertThat(KitBuild.Profile.EDGE.placeholders()).containsEntry("demo_evaluation", "N");
        assertThat(KitBuild.Profile.SCREENS.folders(Catalog.Vendor.ORACLE))
                .containsExactly(
                        "migration/oracle",
                        "migration/common",
                        "seed",
                        "demo-screens/common",
                        "demo-screens/oracle");
        assertThat(KitBuild.Profile.optional("demo-screens/h2")).isTrue();
        assertThat(KitBuild.Profile.optional("edge/h2")).isFalse();
        assertThat(KitGenerator.step(KitFiles.DML_SCREENS)).contains("screen demo data");
        // A data set listed without its parent's build cannot be planned.
        assertThatThrownBy(() -> KitGenerator.parentOf(List.of(), KitBuild.Profile.SCREENS))
                .hasMessageContaining("DEMO");
        assertThat(KitGenerator.parentOf(List.of(), KitBuild.Profile.REFERENCE)).isNull();
    }

    @Test
    void oracle_units_split_like_sqlplus() {
        String script =
                "SET DEFINE OFF\n"
                        + "PROMPT x\n"
                        + "CREATE TABLE \"T\" (\"A\" VARCHAR2(5 BYTE) DEFAULT 'a;' NOT NULL);\n"
                        + "BEGIN\n"
                        + "    NULL;\n"
                        + "END;\n"
                        + "/\n"
                        + "COMMENT ON TABLE \"T\" IS 'x;\n"
                        + "y';\n";
        assertThat(SqlFiles.oracleUnits(script))
                .containsExactly(
                        "CREATE TABLE \"T\" (\"A\" VARCHAR2(5 BYTE) DEFAULT 'a;' NOT NULL)",
                        "BEGIN\n    NULL;\nEND;",
                        "COMMENT ON TABLE \"T\" IS 'x;\ny'");
    }

    // ------------------------------------------------------------------- the proof (opt-in)

    /** The generated kit on the real schema: FCI_INSTALL_KIT=1 (INSTALL_KIT.md "Proof"). */
    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @EnabledIfEnvironmentVariable(named = "FCI_INSTALL_KIT", matches = "1|true|yes")
    class OnTheRealSchema {

        private final Path db = Path.of("..", "db").toAbsolutePath().normalize();
        private KitGenerator.Kit kit;
        private Path dir;

        private Path scratch;

        @BeforeAll
        void generate(@TempDir Path tempDir) throws Exception {
            scratch = tempDir;
            kit = KitGenerator.generateH2(db, scratch, KitGenerator.ALL, true);
            dir = scratch.resolve("h2");
            KitGenerator.write(kit, dir);
        }

        @AfterAll
        void close() throws Exception {
            if (kit != null) {
                KitGenerator.close(kit);
            }
        }

        @Test
        void every_proof_passes_drop_ddl_data_order_rerun_triggers_identities_and_equivalence()
                throws Exception {
            List<String> results = KitProof.proveAll(kit, dir, db, scratch);
            results.forEach(System.out::println);
            assertThat(results).allSatisfy(r -> assertThat(r).startsWith("PASS"));
            assertThat(results).hasSizeGreaterThanOrEqualTo(15);
        }

        /**
         * Owner 2026-11-09: the committed one-run H2 setup (db/h2-setup over db/install/h2), demo,
         * edge and screens (OD-141 D-screens), each on a fresh database and a second time on the
         * same one, equals Flyway (objects and row counts) and the committed kit run (every row
         * value).
         */
        @Test
        void the_committed_h2_setup_runs_demo_edge_and_screens_twice_and_equals_flyway()
                throws Exception {
            Map<KitBuild.Profile, KitGenerator.Built> flyway = new java.util.LinkedHashMap<>();
            for (KitBuild.Profile p :
                    List.of(
                            KitBuild.Profile.DEMO,
                            KitBuild.Profile.EDGE,
                            KitBuild.Profile.SCREENS)) {
                flyway.put(p, KitProof.built(kit, p));
            }
            List<String> results = KitProof.proveSetup(db.getParent(), flyway);
            results.forEach(System.out::println);
            assertThat(results).hasSize(12);
            assertThat(results).allSatisfy(r -> assertThat(r).startsWith("PASS"));
        }

        @Test
        void the_committed_h2_kit_is_the_kit_the_migrations_give_now() throws Exception {
            Path committed = db.resolve("install").resolve("h2");
            assertThat(committed).isDirectory();
            for (Map.Entry<String, String> f : kit.files().entrySet()) {
                Path file = committed.resolve(f.getKey());
                assertThat(file)
                        .as("committed kit file " + f.getKey() + " (./gradlew :backend:installKit)")
                        .exists();
                List<String> committedLines =
                        stable(Files.readString(file, StandardCharsets.US_ASCII)).lines().toList();
                List<String> generatedLines = stable(f.getValue()).lines().toList();
                int at = 0;
                while (at < committedLines.size()
                        && at < generatedLines.size()
                        && committedLines.get(at).equals(generatedLines.get(at))) {
                    at++;
                }
                String first = at < generatedLines.size() ? generatedLines.get(at) : "(end)";
                assertThat(committedLines)
                        .as(
                                f.getKey()
                                        + " is stale from line "
                                        + (at + 1)
                                        + " ("
                                        + first
                                        + "): regenerate with ./gradlew :backend:installKit")
                        .isEqualTo(generatedLines);
            }
        }
    }

    private static final Pattern VOLATILE =
            Pattern.compile(
                    "(?m)^-- Generated on .*$|^FCILekha Payroll install kit manifest.*$|TIMESTAMP"
                            + " '[^']*'|TO_DATE\\('[^']*'|- \\d{5}(;| INTO)|relative date"
                            + " cells.*$|irregular cells.*$|rows only in one of.*$");

    /**
     * A kit file without what changes from day to day (generation date, values computed from
     * today).
     */
    static String stable(String text) {
        return VOLATILE.matcher(text).replaceAll("#");
    }
}
