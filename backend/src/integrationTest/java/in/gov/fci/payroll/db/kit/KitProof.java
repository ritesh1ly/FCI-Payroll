package in.gov.fci.payroll.db.kit;

import in.gov.fci.payroll.db.kit.KitData.TableRows;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runs kit files on H2 the way a DBA does (RUNSCRIPT) and compares the result with a Flyway build.
 * Used by {@link InstallKitIT}; {@link #main} runs the same proof without JUnit.
 */
final class KitProof {

    /** Timestamps of "now" differ by the time between the Flyway build and the kit run. */
    static final Duration TOLERANCE = Duration.ofMinutes(30);

    /** The one row the production path lacks (INSTALL_KIT.md "Java migrations"; PENDING KIT-1). */
    static final String KNOWN_V009_DIFFERENCE = "rows of APPROVAL_DECISION: Flyway 1, kit 0";

    private KitProof() {}

    /** The build of {@code p} in {@code kit}. */
    static KitGenerator.Built built(KitGenerator.Kit kit, KitBuild.Profile p) {
        return kit.builds().stream()
                .filter(b -> b.profile() == p)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("the kit has no " + p + " build"));
    }

    /** The data set's name as the kit writes it (install_<name>.sql): edge, screens ... */
    static String name(KitGenerator.Built b) {
        return b.profile().name().toLowerCase(java.util.Locale.ROOT);
    }

    /** RUNSCRIPT each file of {@code dir} in order. */
    static void run(Connection c, Path dir, String... files) throws SQLException {
        for (String f : files) {
            runScript(c, dir.resolve(f));
        }
    }

    static void runScript(Connection c, Path file) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute(
                    "RUNSCRIPT FROM '" + file.toAbsolutePath().toString().replace('\\', '/') + "'");
        }
    }

    /**
     * Runs only the first {@code statements} statements of an H2 kit file: a kit that stopped
     * midway. Statements are the file's ";"-terminated units (the kit never ends a line with ";"
     * inside a literal).
     */
    static void runPart(Connection c, Path file, int statements, Path scratch)
            throws IOException, SQLException {
        List<String> units = h2Statements(Files.readString(file, StandardCharsets.US_ASCII));
        Path part = scratch.resolve("part_" + file.getFileName());
        Files.writeString(
                part,
                String.join("\n", units.subList(0, Math.min(statements, units.size()))) + "\n",
                StandardCharsets.US_ASCII);
        runScript(c, part);
    }

    static List<String> h2Statements(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder b = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            if (b.isEmpty() && (line.isBlank() || line.startsWith("--"))) {
                continue;
            }
            b.append(line).append('\n');
            if (line.endsWith(";")) {
                out.add(b.toString());
                b.setLength(0);
            }
        }
        return out;
    }

    /** Catalog and rows of a connection's schema. */
    record Snapshot(Catalog catalog, Map<String, TableRows> rows) {}

    static Snapshot snapshot(Connection c) throws SQLException {
        Catalog catalog = H2Catalog.read(c);
        return new Snapshot(catalog, KitData.read(c, catalog));
    }

    /** Objects left in the schema (tables, views, sequences, triggers). */
    static List<String> objects(Connection c) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Statement s = c.createStatement();
                ResultSet rs =
                        s.executeQuery(
                                "SELECT TABLE_TYPE || ' ' || TABLE_NAME FROM"
                                    + " INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = SCHEMA()"
                                    + " UNION ALL SELECT 'SEQUENCE ' || SEQUENCE_NAME FROM"
                                    + " INFORMATION_SCHEMA.SEQUENCES WHERE SEQUENCE_SCHEMA ="
                                    + " SCHEMA() UNION ALL SELECT 'TRIGGER ' || TRIGGER_NAME FROM"
                                    + " INFORMATION_SCHEMA.TRIGGERS WHERE TRIGGER_SCHEMA = SCHEMA()"
                                    + " ORDER BY 1")) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }

    static List<String> compare(KitGenerator.Built flyway, Snapshot kit, boolean rows) {
        return KitCompare.compare(
                flyway.catalog(), flyway.rows(), kit.catalog(), kit.rows(), rows, TOLERANCE);
    }

    /** Whether an UPDATE of the append-only audit trail is refused (its trigger is in place). */
    static boolean auditAppendOnly(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.executeUpdate(
                    "UPDATE AUDIT_EVENT SET REASON = REASON WHERE AUDIT_ID = (SELECT MIN(AUDIT_ID)"
                            + " FROM AUDIT_EVENT)");
            return false;
        } catch (SQLException refused) {
            return true;
        }
    }

    /** Runs the proof outside JUnit: {@code KitProof --db <repo>/db [--no-shift]}. */
    public static void main(String[] args) throws Exception {
        Path db = Path.of(KitGenerator.arg(args, "--db", "../db")).toAbsolutePath().normalize();
        boolean shift = !List.of(args).contains("--no-shift");
        Path scratch = Files.createTempDirectory("fci-kit-proof-");
        KitGenerator.Kit kit = KitGenerator.generateH2(db, scratch, KitGenerator.ALL, shift);
        Path dir = scratch.resolve("h2");
        KitGenerator.write(kit, dir);
        List<String> failures = new ArrayList<>();
        try {
            for (String result : proveAll(kit, dir, db, scratch)) {
                System.out.println(result);
                if (result.startsWith("FAIL")) {
                    failures.add(result);
                }
            }
        } finally {
            KitGenerator.close(kit);
        }
        System.out.println(
                failures.isEmpty() ? "ALL PROOFS PASSED" : failures.size() + " PROOFS FAILED");
        if (!failures.isEmpty()) {
            System.exit(1);
        }
    }

    /**
     * Every proof of INSTALL_KIT.md "Proof", one line each (PASS / FAIL). Each data set runs in the
     * kit order (owner 2026-11-09): 1 drop, 2 DDL, 3 DML (reference, demo, then edge or screens), 4
     * compile. The data sets on top of demo (edge; screens, OD-141 D-screens) are each proved on
     * top of a demo run, on a fresh database, a second time on the same one, and dropped.
     */
    static List<String> proveAll(KitGenerator.Kit kit, Path dir, Path db, Path scratch)
            throws Exception {
        List<String> out = new ArrayList<>();
        KitGenerator.Built ref = built(kit, KitBuild.Profile.REFERENCE);
        KitGenerator.Built demo = built(kit, KitBuild.Profile.DEMO);
        List<KitGenerator.Built> overlays =
                kit.builds().stream()
                        .filter(b -> b.profile().parent() == KitBuild.Profile.DEMO)
                        .toList();
        String[] demoRun = KitFiles.run(KitBuild.Profile.DEMO);
        try (Connection c = KitGenerator.h2("kit_proof_" + System.nanoTime()).getConnection()) {
            run(c, dir, KitFiles.DROP);
            out.add(
                    result(
                            "1 drop on an EMPTY schema",
                            objects(c).isEmpty() ? List.of() : objects(c)));
            run(c, dir, demoRun);
            out.add(
                    result(
                            "demo: fresh run "
                                    + String.join(" ", demoRun)
                                    + " equals Flyway demo (schema, constraints, indexes, views,"
                                    + " triggers, rows)",
                            compare(demo, snapshot(c), true)));
            out.add(
                    result(
                            "5 append-only trigger active again after the load",
                            auditAppendOnly(c)
                                    ? List.of()
                                    : List.of("UPDATE of AUDIT_EVENT was accepted")));
            run(c, dir, demoRun);
            out.add(
                    result(
                            "demo: second run on the same schema (drop, then recreate) equals"
                                    + " Flyway demo",
                            compare(demo, snapshot(c), true)));
            for (KitGenerator.Built overlay : overlays) {
                // The data set on top of a demo schema: its data script (05 edge, 05b screens),
                // then the compile step again. Each starts from a demo schema: the first from the
                // second demo run above, the next ones from a demo run of their own.
                String set = name(overlay);
                String dml = KitFiles.dml(overlay.profile());
                if (overlay != overlays.getFirst()) {
                    run(c, dir, demoRun);
                }
                run(c, dir, dml, KitFiles.COMPILE);
                out.add(
                        result(
                                set
                                        + ": "
                                        + dml.substring(0, dml.indexOf('_'))
                                        + " and 06 after a demo run equal Flyway "
                                        + set,
                                compare(overlay, snapshot(c), true)));
            }
            run(c, dir, KitFiles.DROP);
            out.add(result("1 drop on a FULL kit schema", objects(c)));
        }
        for (KitGenerator.Built overlay : overlays) {
            String set = name(overlay);
            String[] setRun = KitFiles.run(overlay.profile());
            try (Connection c =
                    KitGenerator.h2("kit_proof_" + set + "_" + System.nanoTime()).getConnection()) {
                run(c, dir, setRun);
                out.add(
                        result(
                                set
                                        + ": fresh run "
                                        + String.join(" ", setRun)
                                        + " equals Flyway "
                                        + set,
                                compare(overlay, snapshot(c), true)));
                run(c, dir, setRun);
                out.add(
                        result(
                                set
                                        + ": second run on the same schema (drop, then recreate)"
                                        + " equals Flyway "
                                        + set,
                                compare(overlay, snapshot(c), true)));
                run(c, dir, KitFiles.DROP);
                out.add(result("1 drop on a FULL " + set + " kit schema", objects(c)));
            }
        }
        try (Connection c = KitGenerator.h2("kit_half_" + System.nanoTime()).getConnection()) {
            int n =
                    h2Statements(
                                    Files.readString(
                                            dir.resolve(KitFiles.DDL), StandardCharsets.US_ASCII))
                            .size();
            runPart(c, dir.resolve(KitFiles.DDL), n / 2, scratch);
            int objectsHalf = objects(c).size();
            run(c, dir, KitFiles.DROP);
            out.add(
                    result(
                            "1 drop on a HALF-BUILT schema (02 stopped after "
                                    + n / 2
                                    + " of "
                                    + n
                                    + " statements, "
                                    + objectsHalf
                                    + " objects)",
                            objects(c)));
            run(c, dir, KitFiles.DDL);
            int m =
                    h2Statements(
                                    Files.readString(
                                            dir.resolve(KitFiles.DML_REFERENCE),
                                            StandardCharsets.US_ASCII))
                            .size();
            runPart(c, dir.resolve(KitFiles.DML_REFERENCE), m / 2, scratch);
            run(c, dir, KitFiles.DROP);
            out.add(result("1 drop on a schema whose 03 stopped midway", objects(c)));
            run(c, dir, KitFiles.DDL, KitFiles.DML_REFERENCE);
            int d =
                    h2Statements(
                                    Files.readString(
                                            dir.resolve(KitFiles.DML_DEMO),
                                            StandardCharsets.US_ASCII))
                            .size();
            runPart(c, dir.resolve(KitFiles.DML_DEMO), d / 2, scratch);
            run(c, dir, KitFiles.DROP);
            out.add(
                    result(
                            "1 drop on a schema whose 04 stopped midway (triggers and cycle keys"
                                    + " off)",
                            objects(c)));
            String[] refRun = KitFiles.run(KitBuild.Profile.REFERENCE);
            run(c, dir, refRun);
            out.add(
                    result(
                            "reference: run "
                                    + String.join(" ", refRun)
                                    + " equals Flyway reference (rows included)",
                            compare(ref, snapshot(c), true)));
            int applied =
                    KitBuild.migrateRemaining(
                            KitGenerator.h2Of(c),
                            Catalog.Vendor.H2,
                            KitBuild.Profile.REFERENCE,
                            db);
            KitGenerator.Built refWithAdmin =
                    KitGenerator.buildH2(
                                    db, scratch, List.of(KitBuild.Profile.REFERENCE), false, true)
                            .get(0);
            List<String> diff = new ArrayList<>(compare(refWithAdmin, snapshot(c), false));
            if (applied != 1) {
                diff.add(
                        "Flyway applied "
                                + applied
                                + " migrations on the kit schema, expected 1 (V009)");
            }
            // Known, documented (INSTALL_KIT.md "Java migrations"): in a Flyway run V009 comes
            // before V014_1, which gives every decided approval its approval_decision row; V009
            // applied after the kit has no later migration to do that for its bootstrap approval.
            boolean known = diff.remove(KNOWN_V009_DIFFERENCE);
            out.add(
                    result(
                            "6 production path: kit reference run, then Flyway applies only V009;"
                                    + " equals Flyway with V009"
                                    + (known
                                            ? " (except the known: " + KNOWN_V009_DIFFERENCE + ")"
                                            : ""),
                            diff));
            dropDatabase(refWithAdmin);
        }
        // A schema Flyway built (the application's own) is dropped completely too.
        try (Connection c = java.sql.DriverManager.getConnection(ref.url(), "sa", "")) {
            run(c, dir, KitFiles.DROP);
            out.add(result("1 drop on a FULL schema built by Flyway", objects(c)));
        }
        return out;
    }

    /**
     * The committed one-run H2 setup ({@code db/h2-setup}, generated by
     * db/tools/oracle/build_setup_script.py over the committed {@code db/install/h2}): each data
     * set's init script on a fresh database, then again on the same database, each compared with
     * the Flyway build. The scripts name their files relative to the repository root (RUNSCRIPT
     * resolves against the H2 process's working directory), so they run here from copies whose
     * RUNSCRIPT paths are made absolute; nothing else is changed.
     *
     * @param flyway the Flyway build of each data set to compare with
     */
    static List<String> proveSetup(Path repo, Map<KitBuild.Profile, KitGenerator.Built> flyway)
            throws IOException, SQLException {
        List<String> out = new ArrayList<>();
        Path copy = Files.createTempDirectory("fci-h2-setup-");
        String root = slashes(repo.toAbsolutePath()) + "/";
        List<Path> sources = new ArrayList<>();
        try (var files = Files.list(repo.resolve("db").resolve("h2-setup"))) {
            sources.addAll(files.toList());
        }
        try (var files = Files.list(repo.resolve("db").resolve("install").resolve("h2"))) {
            sources.addAll(
                    files.filter(f -> f.getFileName().toString().startsWith("install_")).toList());
        }
        for (Path f : sources) {
            String text = Files.readString(f, StandardCharsets.US_ASCII);
            Files.writeString(
                    copy.resolve(f.getFileName()),
                    text.replace(
                                    "RUNSCRIPT FROM 'db/h2-setup/",
                                    "RUNSCRIPT FROM '" + slashes(copy) + "/")
                            .replace("RUNSCRIPT FROM 'db/", "RUNSCRIPT FROM '" + root + "db/"),
                    StandardCharsets.US_ASCII);
        }
        for (Map.Entry<KitBuild.Profile, KitGenerator.Built> e : flyway.entrySet()) {
            String set = e.getKey().name().toLowerCase(java.util.Locale.ROOT);
            Path init = copy.resolve("fcipay_h2_init_" + set + ".sql");
            if (!Files.exists(init)) {
                out.add("FAIL h2-setup: db/h2-setup/fcipay_h2_init_" + set + ".sql is missing");
                continue;
            }
            // The committed kit run by its own wrapper: the values to compare row by row (a value
            // the kit fixed at its generation, such as a "now" timestamp in a key, matches it).
            Snapshot kitRun;
            try (Connection c =
                    KitGenerator.h2("kit_setup_ref_" + set + "_" + System.nanoTime())
                            .getConnection()) {
                runScript(c, copy.resolve("install_" + set + ".sql"));
                kitRun = snapshot(c);
                KitGenerator.shutdown(c);
            }
            try (Connection c =
                    KitGenerator.h2("kit_setup_" + set + "_" + System.nanoTime()).getConnection()) {
                for (String run : List.of("fresh database", "second run on the same database")) {
                    runScript(c, init);
                    Snapshot setup = snapshot(c);
                    out.add(
                            result(
                                    "h2-setup "
                                            + set
                                            + ", "
                                            + run
                                            + ": equals Flyway "
                                            + set
                                            + " (objects and row count of every table)",
                                    compare(e.getValue(), setup, false)));
                    out.add(
                            result(
                                    "h2-setup "
                                            + set
                                            + ", "
                                            + run
                                            + ": every row value equals the committed kit run"
                                            + " (install_"
                                            + set
                                            + ".sql)",
                                    KitCompare.compare(
                                            kitRun.catalog(),
                                            kitRun.rows(),
                                            setup.catalog(),
                                            setup.rows(),
                                            true,
                                            TOLERANCE)));
                }
            }
        }
        return out;
    }

    private static String slashes(Path p) {
        return p.toString().replace('\\', '/');
    }

    private static void dropDatabase(KitGenerator.Built b) throws SQLException {
        try (Connection c = java.sql.DriverManager.getConnection(b.url(), "sa", "")) {
            KitGenerator.shutdown(c);
        }
    }

    static String result(String what, List<String> differences) {
        if (differences.isEmpty()) {
            return "PASS " + what;
        }
        StringBuilder b =
                new StringBuilder("FAIL " + what + ": " + differences.size() + " differences");
        differences.stream().limit(15).forEach(d -> b.append("\n    ").append(d));
        return b.toString();
    }

    /** Tables present in both snapshots (diagnostics). */
    static Set<String> tables(Snapshot s) {
        return s.catalog().tables().keySet();
    }
}
