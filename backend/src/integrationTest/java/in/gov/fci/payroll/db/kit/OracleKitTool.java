package in.gov.fci.payroll.db.kit;

import in.gov.fci.payroll.db.kit.Catalog.Vendor;
import in.gov.fci.payroll.db.kit.KitBuild.Profile;
import in.gov.fci.payroll.db.kit.KitData.Row;
import in.gov.fci.payroll.db.kit.KitData.TableRows;
import in.gov.fci.payroll.db.kit.KitGenerator.Built;
import in.gov.fci.payroll.db.kit.KitSteps.Step;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * The Oracle side of the install kit (docs/db/INSTALL_KIT.md "Oracle").
 *
 * <pre>
 *   generate  each profile is migrated by Flyway into an EMPTY scratch schema, read back
 *             (dictionary + DBMS_METADATA + rows) and dropped again; the kit is written to
 *             db/install/oracle. Dates computed from today are found with the H2 builds.
 *   check     (as an EMPTY schema PAYROLL: the kit writes into PAYROLL, OD-160)
 *             Flyway build vs kit build of the same scratch schema, compared (equivalence), the kit
 *             run twice, the edge run twice, the screens run (OD-141 D-screens) twice, the production path (kit
 *             reference run + V009 by Flyway); the schema is left empty.
 *   fallback  no connection: the H2 builds + a static replay of db/migration/oracle (marked FALLBACK).
 * </pre>
 *
 * The connection comes only from environment variables, never from a file of the repository: {@code
 * FCI_KIT_ORACLE_URL} (e.g. {@code
 * jdbc:oracle:thin:@fcipay_medium?TNS_ADMIN=C:/.../Wallet_FCIPAY}), {@code FCI_KIT_ORACLE_USER},
 * {@code FCI_KIT_ORACLE_PASSWORD_FILE} (a file holding only the password; the tool never prints
 * it), and {@code FCI_KIT_ORACLE_CONFIRM_SCRATCH} set to the same user name: the tool creates and
 * drops objects in that schema, and refuses one that is not empty.
 */
final class OracleKitTool {

    private OracleKitTool() {}

    static void main(String[] args) throws Exception {
        String mode =
                KitGenerator.arg(
                        args,
                        "--mode",
                        System.getenv("FCI_KIT_ORACLE_URL") == null ? "fallback" : "generate");
        Path db = Path.of(KitGenerator.arg(args, "--db", "../db")).toAbsolutePath().normalize();
        Path out =
                Path.of(KitGenerator.arg(args, "--out", db.resolve("install").toString()))
                        .toAbsolutePath()
                        .normalize();
        Path scratch = Files.createTempDirectory("fci-kit-oracle-");
        List<Profile> profiles = KitGenerator.ALL;
        switch (mode) {
            case "fallback" -> {
                List<Built> h2 =
                        KitGenerator.buildH2(
                                db, scratch, profiles, !List.of(args).contains("--no-shift"));
                try {
                    fallback(h2, db, out.resolve("oracle"));
                } finally {
                    KitGenerator.close(new KitGenerator.Kit(Vendor.H2, Map.of(), h2, null));
                }
            }
            case "generate" -> generate(db, out.resolve("oracle"), scratch, profiles);
            case "check" -> {
                int failed = check(db, out.resolve("oracle"));
                System.out.println(
                        failed == 0
                                ? "ORACLE EQUIVALENCE: ALL PASSED"
                                : "ORACLE EQUIVALENCE: " + failed + " FAILED");
                if (failed > 0) {
                    System.exit(1);
                }
            }
            default -> throw new IllegalArgumentException("--mode generate | check | fallback");
        }
    }

    /** The fallback kit from the H2 builds; also writes the H2 / Oracle drift report. */
    static void fallback(List<Built> h2, Path db, Path dir) throws Exception {
        OracleFallback.Replay replay = OracleFallback.replay(db);
        List<String> drift = new ArrayList<>();
        List<Built> converted = new ArrayList<>();
        for (Built b : h2) {
            List<String> d = converted.isEmpty() ? drift : new ArrayList<>();
            Catalog c = OracleFallback.convert(b.catalog(), replay, d);
            converted.add(
                    new Built(
                            b.profile(),
                            b.url(),
                            c,
                            OracleFallback.project(b.rows(), c),
                            b.clock(),
                            b.history()));
        }
        Map<String, String> files =
                KitGenerator.generate(
                        Vendor.ORACLE,
                        converted,
                        converted,
                        KitGenerator.meta(
                                "FALLBACK: H2 Flyway build + static replay of db/migration/oracle",
                                true));
        files.put(
                "DRIFT.txt",
                "H2 / Oracle drift found by the static replay of db/migration/oracle against the H2"
                        + " Flyway build (INSTALL_KIT.md \"H2 and Oracle drift\"); "
                        + drift.size()
                        + " lines\n"
                        + String.join("\n", drift)
                        + "\n");
        KitGenerator.write(files, dir);
        System.out.println(
                "FALLBACK Oracle kit written to " + dir + " (" + drift.size() + " drift lines)");
    }

    // ------------------------------------------------------------------------- connection

    static DriverManagerDataSource dataSource() throws Exception {
        String url = env("FCI_KIT_ORACLE_URL");
        String user = env("FCI_KIT_ORACLE_USER");
        String confirm = env("FCI_KIT_ORACLE_CONFIRM_SCRATCH");
        if (!confirm.equalsIgnoreCase(user)) {
            throw new IllegalStateException(
                    "FCI_KIT_ORACLE_CONFIRM_SCRATCH must name the scratch schema user: this"
                            + " tool creates and drops objects in it");
        }
        String password =
                Files.readString(
                                Path.of(env("FCI_KIT_ORACLE_PASSWORD_FILE")),
                                StandardCharsets.UTF_8)
                        .strip();
        DriverManagerDataSource ds = new DriverManagerDataSource(url, user, password);
        ds.setDriverClassName("oracle.jdbc.OracleDriver");
        Properties p = new Properties();
        p.setProperty("oracle.jdbc.defaultRowPrefetch", "500");
        ds.setConnectionProperties(p);
        return ds;
    }

    private static String env(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException(
                    "environment variable " + name + " is not set (INSTALL_KIT.md \"Oracle\")");
        }
        return v;
    }

    static void session(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute("ALTER SESSION DISABLE PARALLEL DML");
            s.execute("ALTER SESSION SET TIME_ZONE = 'UTC'");
        }
    }

    static int objectCount(Connection c) throws SQLException {
        return Integer.parseInt(
                H2Catalog.scalar(
                        c,
                        "SELECT COUNT(*) FROM user_objects WHERE object_name NOT LIKE 'BIN$%' AND"
                                + " object_type <> 'LOB'"));
    }

    private static void requireEmpty(Connection c) throws SQLException {
        int n = objectCount(c);
        if (n > 0) {
            throw new IllegalStateException(
                    "schema "
                            + H2Catalog.scalar(c, "SELECT USER FROM dual")
                            + " holds "
                            + n
                            + " objects: the tool needs an EMPTY scratch schema (it never drops"
                            + " what it did not build)");
        }
    }

    /** Drops every object of {@code catalog} with the kit's own 01_drop statements. */
    static void dropAll(Connection c, Catalog catalog) throws SQLException {
        List<Step> drop = KitSteps.drop(catalog, KitOrder.tables(catalog), new KitWriter.Oracle());
        try (Statement s = c.createStatement()) {
            for (Step st : drop) {
                s.execute(st.sql());
            }
            s.execute("PURGE RECYCLEBIN");
        }
    }

    private static Built buildOracle(
            DriverManagerDataSource ds,
            Path db,
            Profile p,
            boolean bootstrap,
            Map<String, TableRows> relativeFrom)
            throws Exception {
        long t = System.nanoTime();
        KitBuild.migrateOracle(ds, p, db, bootstrap);
        try (Connection c = ds.getConnection()) {
            session(c);
            Catalog catalog = OracleCatalog.read(c);
            Map<String, TableRows> rows = KitData.read(c, catalog);
            if (relativeFrom != null) {
                copyRelative(relativeFrom, rows);
            }
            System.out.printf(
                    Locale.ROOT,
                    "Oracle Flyway build %s: %d tables, %d rows, %.0f s%n",
                    p,
                    catalog.tables().size(),
                    rows.values().stream().mapToInt(x -> x.rows().size()).sum(),
                    (System.nanoTime() - t) / 1e9);
            return new Built(
                    p,
                    "oracle",
                    catalog,
                    rows,
                    new KitData.ClockReport(Map.of(), Map.of(), Map.of(), Map.of()),
                    rows.get("flyway_schema_history").rows().size());
        }
    }

    /** The clock classification of the H2 builds, by table, key and column name. */
    static void copyRelative(Map<String, TableRows> h2, Map<String, TableRows> oracle) {
        for (TableRows o : oracle.values()) {
            TableRows h = h2.get(o.table());
            if (h == null) {
                continue;
            }
            for (Row r : o.rows().values()) {
                Row hr = h.rows().get(r.key());
                if (hr == null) {
                    continue;
                }
                for (int bit = hr.relative().nextSetBit(0);
                        bit >= 0;
                        bit = hr.relative().nextSetBit(bit + 1)) {
                    int j = o.columns().indexOf(h.columns().get(bit / 2));
                    if (j >= 0) {
                        r.relative().set(2 * j + bit % 2);
                    }
                }
            }
        }
    }

    static void generate(Path db, Path dir, Path scratch, List<Profile> profiles) throws Exception {
        DriverManagerDataSource ds = dataSource();
        try (Connection c = ds.getConnection()) {
            requireEmpty(c);
        }
        List<Built> h2 = KitGenerator.buildH2(db, scratch, profiles, true);
        List<Built> builds = new ArrayList<>();
        try {
            for (int i = 0; i < profiles.size(); i++) {
                Built b =
                        buildOracle(
                                ds,
                                db,
                                profiles.get(i),
                                profiles.get(i).bootstrap,
                                h2.get(i).rows());
                builds.add(b);
                try (Connection c = ds.getConnection()) {
                    dropAll(c, b.catalog());
                }
            }
        } finally {
            KitGenerator.close(new KitGenerator.Kit(Vendor.H2, Map.of(), h2, null));
        }
        String version;
        try (Connection c = ds.getConnection()) {
            version = c.getMetaData().getDatabaseProductVersion().lines().findFirst().orElse("");
        }
        Map<String, String> files =
                KitGenerator.generate(
                        Vendor.ORACLE,
                        builds,
                        builds,
                        KitGenerator.meta("Flyway on Oracle (" + version + ")", false));
        KitGenerator.write(files, dir);
        System.out.println("Oracle kit written to " + dir);
    }

    /** The equivalence check on Oracle; returns the number of failed checks. */
    static int check(Path db, Path dir) throws Exception {
        // OD-160: the kit writes into the fixed schema PAYROLL, so the Flyway build it is compared
        // with must be PAYROLL's too: the check runs as PAYROLL (an empty scratch PAYROLL schema).
        if (!OracleSchema.SCHEMA.equalsIgnoreCase(env("FCI_KIT_ORACLE_USER"))) {
            throw new IllegalStateException(
                    "check mode: the kit writes into schema "
                            + OracleSchema.SCHEMA
                            + " (OD-160); connect as an EMPTY "
                            + OracleSchema.SCHEMA
                            + " (FCI_KIT_ORACLE_USER and FCI_KIT_ORACLE_CONFIRM_SCRATCH = "
                            + OracleSchema.SCHEMA
                            + ")");
        }
        DriverManagerDataSource ds = dataSource();
        try (Connection c = ds.getConnection()) {
            requireEmpty(c);
        }
        String[] demoRun = KitFiles.run(Profile.DEMO);
        int failed = 0;
        Built demo = buildOracle(ds, db, Profile.DEMO, true, null);
        try (Connection c = ds.getConnection()) {
            session(c);
            dropAll(c, demo.catalog());
            failed +=
                    report(
                            "drop on a FULL schema built by Flyway",
                            objectCount(c) == 0
                                    ? List.of()
                                    : List.of(objectCount(c) + " objects left"));
            run(c, dir, KitFiles.DROP);
            failed +=
                    report(
                            "drop on an EMPTY schema",
                            objectCount(c) == 0 ? List.of() : List.of("objects appeared"));
            run(c, dir, demoRun);
            failed +=
                    report(
                            "kit drop, DDL, DML 03-04, compile equals Flyway demo",
                            compare(demo, c));
            run(c, dir, demoRun);
            failed +=
                    report(
                            "kit demo run a second time on the same schema equals Flyway demo",
                            compare(demo, c));
            run(c, dir, KitFiles.DROP);
            failed +=
                    report(
                            "drop on a FULL kit schema",
                            objectCount(c) == 0 ? List.of() : List.of(objectCount(c) + " left"));
        }
        // The data sets on top of demo, each its own full run, twice: edge (drop, DDL, DML 03, 04,
        // 05, compile) and screens (OD-141 D-screens: drop, DDL, DML 03, 04, 05b, compile).
        for (Profile overlay : List.of(Profile.EDGE, Profile.SCREENS)) {
            String set = overlay.name().toLowerCase(Locale.ROOT);
            Built built = buildOracle(ds, db, overlay, true, null);
            try (Connection c = ds.getConnection()) {
                session(c);
                dropAll(c, built.catalog());
                String[] setRun = KitFiles.run(overlay);
                run(c, dir, setRun);
                failed += report("kit " + set + " run equals Flyway " + set, compare(built, c));
                run(c, dir, setRun);
                failed +=
                        report(
                                "kit "
                                        + set
                                        + " run a second time on the same schema equals Flyway "
                                        + set,
                                compare(built, c));
                run(c, dir, KitFiles.DROP);
            }
        }
        Built ref = buildOracle(ds, db, Profile.REFERENCE, true, null);
        try (Connection c = ds.getConnection()) {
            session(c);
            dropAll(c, ref.catalog());
            run(c, dir, KitFiles.run(Profile.REFERENCE));
            int applied = KitBuild.migrateRemainingOracle(ds, Profile.REFERENCE, db);
            List<String> diff = new ArrayList<>(compareCounts(ref, c));
            diff.remove(KitProof.KNOWN_V009_DIFFERENCE);
            if (applied != 1) {
                diff.add("Flyway applied " + applied + " migrations, expected 1 (V009)");
            }
            failed +=
                    report(
                            "production path: kit reference run, then Flyway applies only V009",
                            diff);
            run(c, dir, KitFiles.DROP);
        }
        return failed;
    }

    private static void run(Connection c, Path dir, String... files) throws Exception {
        for (String f : files) {
            long t = System.nanoTime();
            int n = SqlFiles.runOracle(c, dir.resolve(f));
            System.out.printf(
                    Locale.ROOT,
                    "  ran %s: %d units, %.0f s%n",
                    f,
                    n,
                    (System.nanoTime() - t) / 1e9);
        }
    }

    private static List<String> compare(Built flyway, Connection c) throws SQLException {
        Catalog kit = OracleCatalog.read(c);
        return KitCompare.compare(
                flyway.catalog(),
                flyway.rows(),
                kit,
                KitData.read(c, kit),
                true,
                KitProof.TOLERANCE);
    }

    private static List<String> compareCounts(Built flyway, Connection c) throws SQLException {
        Catalog kit = OracleCatalog.read(c);
        return KitCompare.compare(
                flyway.catalog(),
                flyway.rows(),
                kit,
                KitData.read(c, kit),
                false,
                KitProof.TOLERANCE);
    }

    private static int report(String what, List<String> differences) {
        System.out.println(KitProof.result(what, differences));
        return differences.isEmpty() ? 0 : 1;
    }
}
