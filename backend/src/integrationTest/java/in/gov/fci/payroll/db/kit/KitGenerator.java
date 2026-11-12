package in.gov.fci.payroll.db.kit;

import in.gov.fci.payroll.db.kit.Catalog.Vendor;
import in.gov.fci.payroll.db.kit.KitBuild.Profile;
import in.gov.fci.payroll.db.kit.KitData.ClockReport;
import in.gov.fci.payroll.db.kit.KitData.TableRows;
import in.gov.fci.payroll.db.kit.KitOrder.TableOrder;
import in.gov.fci.payroll.db.kit.KitPlan.Op;
import in.gov.fci.payroll.db.kit.KitPlan.Summary;
import in.gov.fci.payroll.db.kit.KitSteps.Step;
import in.gov.fci.payroll.db.kit.KitWriter.Meta;
import in.gov.fci.payroll.db.kit.KitWriter.Stage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;

/**
 * Generates the database install kit ({@code db/install/<vendor>/}) from the Flyway end state, so
 * the kit never drifts from the migrations (docs/db/INSTALL_KIT.md). H2: each data set is migrated
 * by Flyway into a fresh in-memory H2 database and read back from its dictionary and rows; Oracle:
 * {@link OracleKitTool}.
 *
 * <pre>
 *   ./gradlew :backend:installKit                      # H2 kit into db/install/h2
 *   ./gradlew :backend:installKit -Pfci.kit.vendor=oracle   # Oracle kit (connection: OracleKitTool)
 * </pre>
 *
 * A Java tool of the integrationTest source set, not a Python script, because the schema of record
 * is what Flyway builds, and Flyway needs the project's Java migrations (V009, V022, V029_1) and
 * the H2 trigger classes on the classpath; reading H2's own dictionary afterwards is exact where a
 * text parser of 340 migration files would be a second, drifting implementation of Flyway.
 */
public final class KitGenerator {

    static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    /** The data scripts and the profile each one ends in (step 3, DML, of the kit order). */
    static final List<Object[]> STAGES =
            List.of(
                    new Object[] {
                        KitFiles.DML_REFERENCE,
                        "reference data (every environment)",
                        Profile.REFERENCE
                    },
                    new Object[] {KitFiles.DML_DEMO, "demo data (profile demo)", Profile.DEMO},
                    new Object[] {KitFiles.DML_EDGE, "edge-case data (profile edge)", Profile.EDGE},
                    new Object[] {
                        KitFiles.DML_SCREENS,
                        "screen demo data (profile screens, on top of demo; OD-141 D-screens)",
                        Profile.SCREENS
                    });

    /**
     * Every data set of the kit, parents before children (INSTALL_KIT.md): EDGE and SCREENS are
     * siblings on top of DEMO.
     */
    static final List<Profile> ALL =
            List.of(Profile.REFERENCE, Profile.DEMO, Profile.EDGE, Profile.SCREENS);

    private KitGenerator() {}

    /**
     * One Flyway build of a profile, read back.
     *
     * @param url the JDBC URL of the build (H2 in-memory, kept until {@link #close})
     * @param catalog its dictionary
     * @param rows its rows, with the clock classification
     * @param clock the classification counts
     * @param history rows of flyway_schema_history
     */
    record Built(
            Profile profile,
            String url,
            Catalog catalog,
            Map<String, TableRows> rows,
            ClockReport clock,
            int history) {}

    /**
     * A generated kit.
     *
     * @param files file name to text, in run order
     * @param builds the Flyway builds it was generated from (open H2 databases)
     */
    record Kit(Vendor vendor, Map<String, String> files, List<Built> builds, TableOrder order) {}

    public static void main(String[] args) throws Exception {
        Path db = Path.of(arg(args, "--db", "../db")).toAbsolutePath().normalize();
        Path out =
                Path.of(arg(args, "--out", db.resolve("install").toString()))
                        .toAbsolutePath()
                        .normalize();
        String vendor = arg(args, "--vendor", "h2");
        if (vendor.equals("oracle")) {
            OracleKitTool.main(args);
            return;
        }
        Path scratch = Files.createTempDirectory("fci-kit-");
        boolean shift = !List.of(args).contains("--no-shift");
        Kit kit = generateH2(db, scratch, ALL, shift);
        try {
            write(kit, out.resolve("h2"));
            System.out.println("install kit written to " + out.resolve("h2"));
            if (vendor.equals("all")) {
                // Same H2 builds: the Oracle fallback (no connection; OracleKitTool --mode generate
                // replaces it with the kit of a real Oracle build).
                OracleKitTool.fallback(kit.builds(), db, out.resolve("oracle"));
            }
        } finally {
            close(kit);
        }
    }

    static String arg(String[] args, String name, String fallback) {
        for (int i = 0; i + 1 < args.length; i++) {
            if (args[i].equals(name)) {
                return args[i + 1];
            }
        }
        return fallback;
    }

    /** A fresh in-memory H2 database in the application's mode (application.yaml). */
    static JdbcDataSource h2(String name) {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL(
                "jdbc:h2:mem:"
                        + name
                        + ";MODE=Oracle;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        return ds;
    }

    /** A data source on the database of an open H2 connection. */
    static JdbcDataSource h2Of(Connection c) throws SQLException {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL(c.getMetaData().getURL());
        ds.setUser("sa");
        ds.setPassword("");
        return ds;
    }

    /** Builds each profile with Flyway (and a clock-shifted twin) and reads it back. */
    static List<Built> buildH2(Path db, Path scratch, List<Profile> profiles, boolean shift)
            throws IOException, SQLException {
        return buildH2(db, scratch, profiles, shift, false);
    }

    /**
     * @param bootstrap also run V009 where the profile does not (the production path of the
     *     equivalence check: the application applies V009 at its first start)
     */
    static List<Built> buildH2(
            Path db, Path scratch, List<Profile> profiles, boolean shift, boolean bootstrap)
            throws IOException, SQLException {
        List<Built> out = new ArrayList<>();
        for (Profile p : profiles) {
            String id = UUID.randomUUID().toString().replace("-", "");
            JdbcDataSource a = h2("kit_" + p.name().toLowerCase(java.util.Locale.ROOT) + "_" + id);
            Instant start = Instant.now();
            KitBuild.migrate(a, Vendor.H2, p, db, null, p.bootstrap || bootstrap);
            Catalog catalog;
            Map<String, TableRows> rows;
            try (Connection c = a.getConnection()) {
                catalog = H2Catalog.read(c);
                rows = KitData.read(c, catalog);
            }
            ClockReport clock = new ClockReport(Map.of(), Map.of(), Map.of(), Map.of());
            if (shift) {
                JdbcDataSource b = h2("kit_shift_" + id);
                KitBuild.migrate(
                        b,
                        Vendor.H2,
                        p,
                        db,
                        scratch.resolve(p.name() + "_" + id),
                        p.bootstrap || bootstrap);
                Map<String, TableRows> shifted;
                try (Connection c = b.getConnection()) {
                    shifted = KitData.read(c, H2Catalog.read(c));
                    shutdown(c);
                }
                JdbcDataSource m = h2("kit_shift_month_" + id);
                KitBuild.migrate(
                        m,
                        Vendor.H2,
                        p,
                        db,
                        scratch.resolve(p.name() + "_m_" + id),
                        1,
                        p.bootstrap || bootstrap);
                Map<String, TableRows> shiftedMonth;
                try (Connection c = m.getConnection()) {
                    shiftedMonth = KitData.read(c, H2Catalog.read(c));
                    shutdown(c);
                }
                Duration tolerance = Duration.between(start, Instant.now()).plusMinutes(2);
                clock =
                        KitData.markRelative(
                                rows,
                                shifted,
                                shiftedMonth,
                                tolerance,
                                List.of("flyway_schema_history"));
            }
            int history = rows.get("flyway_schema_history").rows().size();
            out.add(new Built(p, a.getURL(), catalog, rows, clock, history));
        }
        return out;
    }

    static Kit generateH2(Path db, Path scratch, List<Profile> profiles, boolean shift)
            throws IOException, SQLException {
        List<Built> builds = buildH2(db, scratch, profiles, shift);
        Map<String, String> files =
                generate(
                        Vendor.H2,
                        builds,
                        builds,
                        meta("Flyway on H2 " + org.h2.engine.Constants.VERSION, false));
        return new Kit(Vendor.H2, files, builds, KitOrder.tables(builds.get(0).catalog()));
    }

    static Meta meta(String source, boolean fallback) {
        LocalDate today = LocalDate.now(INDIA);
        int fiscalYear = today.getYear() - (today.getMonthValue() < 4 ? 1 : 0);
        return new Meta(
                today.toString(),
                today.getYear() * 12 + today.getMonthValue(),
                fiscalYear,
                source,
                fallback);
    }

    /**
     * The kit files of a vendor.
     *
     * @param structure the builds whose catalogs give the DDL and counters (the vendor's own)
     * @param data the builds whose rows are loaded (the same, or the H2 builds for the Oracle
     *     fallback)
     */
    static Map<String, String> generate(
            Vendor vendor, List<Built> structure, List<Built> data, Meta meta) {
        Catalog catalog = structure.get(0).catalog();
        for (Built b : structure) {
            List<String> differences =
                    KitCompare.compare(
                            catalog, Map.of(), b.catalog(), Map.of(), false, Duration.ZERO);
            differences.removeIf(d -> d.startsWith("identity ") || d.startsWith("sequence "));
            if (!differences.isEmpty()) {
                throw new IllegalStateException(
                        "the "
                                + b.profile()
                                + " profile changes the schema itself: "
                                + differences);
            }
        }
        TableOrder order = KitOrder.tables(catalog);
        KitSteps.DdlText text = vendor == Vendor.H2 ? new KitWriter.H2() : new KitWriter.Oracle();
        List<Step> drop = KitSteps.drop(catalog, order, text);
        List<Step> ddl = KitSteps.ddl(catalog, order, text);
        KitSteps.validateDrop(drop);
        KitSteps.validateDdl(ddl);
        Map<String, String> files = new LinkedHashMap<>();
        StringBuilder manifest = new StringBuilder();
        manifest.append(manifestHead(vendor, catalog, order, meta, structure.get(0)));
        String v = vendor.name().toLowerCase(java.util.Locale.ROOT);
        files.put(
                KitFiles.DROP,
                vendor == Vendor.H2
                        ? KitWriter.h2Script(
                                header(
                                        vendor,
                                        KitFiles.DROP,
                                        "drops every kit object that exists, children before"
                                                + " parents",
                                        false,
                                        meta),
                                drop)
                        : KitWriter.oracleScript(
                                header(
                                        vendor,
                                        KitFiles.DROP,
                                        "drops every kit object that exists, children before"
                                                + " parents",
                                        false,
                                        meta),
                                drop));
        files.put(
                KitFiles.DDL,
                vendor == Vendor.H2
                        ? KitWriter.h2Script(
                                header(
                                        vendor,
                                        KitFiles.DDL,
                                        "creates every object in its final state",
                                        false,
                                        meta),
                                ddl)
                        : KitWriter.oracleScript(
                                        header(
                                                vendor,
                                                KitFiles.DDL,
                                                "creates every object in its final state",
                                                false,
                                                meta),
                                        ddl)
                                // step 0: the Partitioning option, before any DDL
                                .replace(
                                        KitWriter.ORACLE_SESSION,
                                        KitWriter.ORACLE_SESSION
                                                + KitWriter.ORACLE_PARTITIONING_CHECK));
        for (int i = 0; i < data.size(); i++) {
            Built d = data.get(i);
            Built s = structure.get(i);
            // Each data script starts from its parent's end state (KitFiles: 05 and 05b both
            // follow 04), not from the build listed before it.
            Built parent = parentOf(data, d.profile());
            Map<String, TableRows> prev = parent == null ? Map.of() : parent.rows();
            int historyBefore = parent == null ? 0 : parent.history();
            Object[] stage =
                    STAGES.stream().filter(x -> x[2] == d.profile()).findFirst().orElseThrow();
            List<Op> ops = KitPlan.plan(catalog, order, prev, d.rows(), Duration.ofMinutes(30));
            Summary summary =
                    KitPlan.validate(
                            catalog, prev, d.rows(), ops, order.deferred(), Duration.ofMinutes(30));
            boolean demo = d.profile() != Profile.REFERENCE;
            Stage st =
                    new Stage(
                            (String) stage[0],
                            (String) stage[1],
                            demo,
                            ops,
                            d.rows(),
                            s.catalog(),
                            historyBefore,
                            d.history(),
                            order.deferred());
            String head = header(vendor, st.file(), st.title(), demo, meta);
            files.put(
                    st.file(),
                    vendor == Vendor.H2
                            ? KitWriter.h2Data(head, st, catalog, meta)
                            : KitWriter.oracleData(head, st, catalog, meta));
            manifest.append(manifestStage(st, summary, d));
        }
        // Step 4 runs after the data (owner 2026-11-09: drop, DDL, DML, compile invalid).
        files.put(
                KitFiles.COMPILE,
                vendor == Vendor.H2
                        ? KitWriter.h2Check(
                                header(
                                        vendor,
                                        KitFiles.COMPILE,
                                        "checks the objects of 02_ddl after the data load",
                                        false,
                                        meta),
                                catalog)
                        : KitWriter.oracleCompile(
                                header(
                                        vendor,
                                        KitFiles.COMPILE,
                                        "recompiles invalid objects and checks the counts, after"
                                                + " the data load",
                                        false,
                                        meta),
                                catalog));
        for (Built d : data) {
            List<String> run = List.of(KitFiles.run(d.profile()));
            String name =
                    "install_" + d.profile().name().toLowerCase(java.util.Locale.ROOT) + ".sql";
            files.put(
                    name,
                    vendor == Vendor.H2
                            ? h2Wrapper(name, run, meta, v)
                            : oracleWrapper(name, run, meta, d.profile()));
        }
        if (vendor == Vendor.ORACLE) {
            // OD-160: every object of the Oracle kit is written with the fixed schema PAYROLL, as
            // the last step (OracleSchema: only "PAYROLL." is added, proved per file).
            OracleSchema.Known known = OracleSchema.known(files.get(KitFiles.DDL));
            manifest.append("\nSchema ")
                    .append(OracleSchema.SCHEMA)
                    .append(" (OD-160): names qualified per script\n");
            for (Map.Entry<String, String> f : files.entrySet()) {
                if (!f.getKey().endsWith(".sql")) {
                    continue;
                }
                OracleSchema.Result r = OracleSchema.qualify(f.getValue(), known);
                OracleSchema.audit(f.getKey(), r.text(), known);
                f.setValue(r.text());
                manifest.append("  ")
                        .append(f.getKey())
                        .append(": ")
                        .append(r.counts().values().stream().mapToInt(Integer::intValue).sum())
                        .append(' ')
                        .append(r.counts())
                        .append('\n');
            }
        }
        files.put("MANIFEST.txt", manifest.toString());
        return files;
    }

    /**
     * The build of {@code p}'s parent data set among {@code builds} (null for REFERENCE); a build
     * listed without its parent is refused, as its data script could not be planned.
     */
    static Built parentOf(List<Built> builds, Profile p) {
        Profile parent = p.parent();
        if (parent == null) {
            return null;
        }
        for (Built b : builds) {
            if (b.profile() == parent) {
                return b;
            }
        }
        throw new IllegalArgumentException(
                "the " + p + " data set needs the " + parent + " build before it");
    }

    static String header(Vendor vendor, String file, String title, boolean demo, Meta meta) {
        StringBuilder b = new StringBuilder();
        b.append("-- FCILekha Payroll database install kit (")
                .append(vendor == Vendor.H2 ? "H2, Oracle mode" : "Oracle 19c")
                .append("): ")
                .append(file)
                .append(", ")
                .append(title)
                .append(".\n")
                .append(
                        "-- GENERATED from the Flyway end state by KitGenerator"
                                + " (backend/src/integrationTest/java/in/gov/fci/\n")
                .append(
                        "-- payroll/db/kit): do not edit, regenerate. Flyway (db/migration,"
                                + " db/seed) stays the schema of record.\n")
                .append("-- Source: ")
                .append(meta.source())
                .append(". Usage: docs/db/INSTALL_KIT.md.\n")
                .append("-- Generated on ")
                .append(meta.generatedOn())
                .append(" (India).\n");
        if (meta.fallback()) {
            b.append(
                            "-- FALLBACK: generated WITHOUT an Oracle connection, from the H2"
                                    + " dictionary and the Oracle migration\n")
                    .append(
                            "-- texts (INSTALL_KIT.md \"Oracle fallback\"). Regenerate with the"
                                    + " wallet (OracleKitTool) before a\n")
                    .append("-- real installation.\n");
        }
        if (demo) {
            b.append("-- *** DEMO / TEST DATA ONLY: NEVER RUN IN PRODUCTION OR A REAL UAT. ***\n")
                    .append(
                            "-- It replaces rows of the earlier data script where the profile"
                                    + " changes them.\n");
        }
        return b.append('\n').toString();
    }

    private static String h2Wrapper(String name, List<String> run, Meta meta, String v) {
        StringBuilder b =
                new StringBuilder(
                        header(Vendor.H2, name, "runs the kit in order", run.size() > 4, meta));
        b.append(
                        "-- Run from the repository root (RUNSCRIPT resolves paths against the H2"
                                + " process's working\n")
                .append(
                        "-- directory), connected as the schema owner with the URL options of the"
                                + " application\n")
                .append(
                        "-- (MODE=Oracle;DEFAULT_NULL_ORDERING=HIGH) and fci-h2-triggers.jar on"
                                + " H2's classpath:\n")
                .append("--     RUNSCRIPT FROM 'db/install/h2/")
                .append(name)
                .append("'\n");
        for (String f : run) {
            b.append("-- ").append(step(f)).append('\n');
            b.append("RUNSCRIPT FROM 'db/install/").append(v).append('/').append(f).append("';\n");
        }
        return b.toString();
    }

    /** The step of the kit order a file belongs to (1 DROP, 2 DDL, 3 DML, 4 COMPILE INVALID). */
    static String step(String file) {
        return switch (file) {
            case KitFiles.DROP -> "Step 1 DROP";
            case KitFiles.DDL -> "Step 2 DDL";
            case KitFiles.DML_REFERENCE -> "Step 3 DML: reference data";
            case KitFiles.DML_DEMO -> "Step 3 DML: demo data";
            case KitFiles.DML_EDGE -> "Step 3 DML: edge-case data";
            case KitFiles.DML_SCREENS -> "Step 3 DML: screen demo data";
            case KitFiles.COMPILE -> "Step 4 COMPILE INVALID objects and check the counts";
            default -> throw new IllegalArgumentException("not a kit step: " + file);
        };
    }

    private static String oracleWrapper(String name, List<String> run, Meta meta, Profile p) {
        String log = name.replace(".sql", ".log");
        StringBuilder b =
                new StringBuilder(
                        header(
                                Vendor.ORACLE,
                                name,
                                "runs the kit in order",
                                p != Profile.REFERENCE,
                                meta));
        b.append(
                        "-- Run as the schema owner with SQLcl or SQL*Plus 19c+ (stops at the first"
                                + " error):\n")
                .append("--     sql FCI_PAYROLL@<alias> @db/install/oracle/")
                .append(name)
                .append('\n');
        if (p == Profile.REFERENCE) {
            b.append(
                            "-- Production: then start the application once with"
                                    + " SPRING_FLYWAY_OUT_OF_ORDER=true so Flyway applies\n")
                    .append(
                            "-- V009 (the first System Administrator, hashed from the deployment's"
                                    + " bootstrap password).\n");
        }
        b.append(
                        "WHENEVER SQLERROR EXIT FAILURE ROLLBACK\n"
                                + "WHENEVER OSERROR EXIT FAILURE ROLLBACK\n"
                                + "SET DEFINE OFF\n")
                .append("SPOOL ")
                .append(log)
                .append('\n');
        for (String f : run) {
            b.append("PROMPT === ").append(step(f)).append(" (").append(f).append(") ===\n");
            b.append("@@").append(f).append('\n');
        }
        return b.append("SPOOL OFF\nEXIT SUCCESS\n").toString();
    }

    private static String manifestHead(
            Vendor v, Catalog c, TableOrder order, Meta meta, Built ref) {
        StringBuilder b = new StringBuilder();
        b.append("FCILekha Payroll install kit manifest (")
                .append(v)
                .append("), generated ")
                .append(meta.generatedOn())
                .append(" from ")
                .append(meta.source())
                .append(meta.fallback() ? " [FALLBACK]" : "")
                .append('\n');
        b.append("objects: tables ")
                .append(c.tables().size())
                .append(", columns ")
                .append(c.tables().values().stream().mapToInt(t -> t.columns().size()).sum())
                .append(", identity columns ")
                .append(
                        c.tables().values().stream()
                                .mapToLong(
                                        t ->
                                                t.columns().stream()
                                                        .filter(x -> x.identity() != null)
                                                        .count())
                                .sum())
                .append(", primary keys ")
                .append(c.constraints().stream().filter(k -> k.type() == 'P').count())
                .append(", unique keys ")
                .append(c.constraints().stream().filter(k -> k.type() == 'U').count())
                .append(", checks ")
                .append(c.constraints().stream().filter(k -> k.type() == 'C').count())
                .append(", foreign keys ")
                .append(c.foreignKeys().size())
                .append(", indexes ")
                .append(c.indexes().size())
                .append(", views ")
                .append(c.views().size())
                .append(", triggers ")
                .append(c.triggers().size())
                .append(", sequences ")
                .append(c.sequences().size())
                .append(", code objects ")
                .append(c.code().size())
                .append('\n');
        b.append("table order (parents first): ")
                .append(String.join(" ", order.tables()))
                .append('\n');
        b.append("foreign-key cycles: ")
                .append(order.cycles().isEmpty() ? "none" : order.cycles())
                .append('\n');
        b.append("cycle foreign keys (off during the data load, validated after it): ");
        b.append(
                        order.deferred().isEmpty()
                                ? "none"
                                : String.join(
                                        ", ",
                                        order.deferred().stream()
                                                .map(
                                                        k ->
                                                                k.name()
                                                                        + " "
                                                                        + k.table()
                                                                        + "->"
                                                                        + k.refTable())
                                                .toList()))
                .append('\n');
        b.append("self-referencing tables (rows by level): ")
                .append(order.selfReferencing())
                .append('\n');
        b.append("views in order: ").append(KitSteps.viewOrder(c)).append('\n');
        b.append("triggers (disabled during the data load): ");
        b.append(
                        String.join(
                                ", ",
                                c.triggers().stream()
                                        .map(t -> t.name() + " on " + t.table())
                                        .toList()))
                .append('\n');
        return b.toString();
    }

    private static String manifestStage(Stage st, Summary s, Built d) {
        StringBuilder b =
                new StringBuilder("\n")
                        .append(st.file())
                        .append(" (profile ")
                        .append(d.profile())
                        .append(")\n");
        b.append("  rows after: ")
                .append(d.rows().values().stream().mapToInt(t -> t.rows().size()).sum())
                .append(" in ")
                .append(d.rows().values().stream().filter(t -> !t.rows().isEmpty()).count())
                .append(" tables; flyway_schema_history ")
                .append(d.history())
                .append('\n');
        b.append("  inserts ")
                .append(s.inserts().values().stream().mapToInt(Integer::intValue).sum())
                .append(", deletes ")
                .append(s.deletes().values().stream().mapToInt(Integer::intValue).sum())
                .append('\n');
        if (!s.deletes().isEmpty()) {
            b.append("  rows of the earlier script replaced: ").append(s.deletes()).append('\n');
        }
        b.append("  relative cells moving with today's month (computed from today): ")
                .append(
                        d.clock().relativeCells().values().stream()
                                .mapToInt(Integer::intValue)
                                .sum())
                .append(" in ")
                .append(d.clock().relativeCells().size())
                .append(" columns\n");
        b.append("  relative cells anchored on the financial year: ")
                .append(d.clock().fiscalCells().values().stream().mapToInt(Integer::intValue).sum())
                .append(" in ")
                .append(d.clock().fiscalCells().size())
                .append(" columns ")
                .append(d.clock().fiscalCells().keySet())
                .append('\n');
        b.append("  irregular cells (differ in a clock-shifted build, kept fixed): ")
                .append(d.clock().irregularCells())
                .append('\n');
        b.append("  rows only in one of the two builds: ")
                .append(d.clock().unmatchedRows())
                .append('\n');
        return b.toString();
    }

    static void write(Kit kit, Path dir) throws IOException {
        write(kit.files(), dir);
    }

    static void write(Map<String, String> files, Path dir) throws IOException {
        Files.createDirectories(dir);
        // A kit file of an earlier numbering (00_drop.sql ... before 2026-11-09) is removed, so the
        // folder holds exactly the kit the generator writes now.
        try (var old = Files.list(dir)) {
            for (Path f : old.toList()) {
                String n = f.getFileName().toString();
                boolean kitFile = n.matches("\\d\\d[a-z]?_.*\\.sql|install_.*\\.sql");
                if (kitFile && !files.containsKey(n)) {
                    Files.delete(f);
                }
            }
        }
        for (Map.Entry<String, String> e : files.entrySet()) {
            String text = e.getValue();
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c != '\n' && (c < 0x20 || c > 0x7E)) {
                    throw new IllegalStateException(
                            e.getKey()
                                    + ": not printable ASCII (U+"
                                    + Integer.toHexString(c)
                                    + ") at "
                                    + i
                                    + ": "
                                    + text.substring(
                                            Math.max(0, i - 60), Math.min(text.length(), i + 20)));
                }
            }
            Files.writeString(dir.resolve(e.getKey()), text, StandardCharsets.US_ASCII);
        }
    }

    static void close(Kit kit) throws SQLException {
        for (Built b : kit.builds()) {
            if (b.url().startsWith("jdbc:h2:mem:")) {
                try (Connection c = java.sql.DriverManager.getConnection(b.url(), "sa", "")) {
                    shutdown(c);
                }
            }
        }
    }

    static void shutdown(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute("SHUTDOWN");
        }
    }
}
