package in.gov.fci.payroll.db.kit;

import in.gov.fci.payroll.db.kit.Catalog.Vendor;
import in.gov.fci.payroll.iam.persistence.V009__Bootstrap_system_administrator;
import in.gov.fci.payroll.platform.config.ApiProperties;
import in.gov.fci.payroll.platform.persistence.V022__Align_pay_pattern_with_target_ddl;
import in.gov.fci.payroll.platform.persistence.V029_1__Spring_batch_6_job_instance_sequence;
import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.migration.JavaMigration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Builds a schema with Flyway exactly as the application does for a profile (application.yaml
 * {@code spring.flyway.locations}), the source the install kit is generated from and compared with.
 * The three Java migrations are given by hand, as RollbackIT does: V022 and V029_1 always, V009
 * (first System Administrator) only where the kit can reproduce it (see {@link Profile}).
 */
final class KitBuild {

    /** Years a "shifted" build moves the database clock: 28 keeps weekdays and leap days. */
    static final int SHIFT_YEARS = 28;

    /**
     * The bootstrap password of a generator build; V009 hashes it only on a build without an admin.
     */
    private static final String BUILD_ONLY_PASSWORD = "Kit-Generator-Build-Only-0";

    /** Words that read the database clock in the migrations and seeds (outside literals). */
    private static final Pattern CLOCK =
            Pattern.compile(
                    "\\b(CURRENT_TIMESTAMP|CURRENT_DATE|SYSTIMESTAMP|SYSDATE|LOCALTIMESTAMP)\\b"
                            + "(\\s*\\(\\s*\\d+\\s*\\))?",
                    Pattern.CASE_INSENSITIVE);

    private KitBuild() {}

    /**
     * The data sets of the kit, each one the application's Flyway profile (application.yaml): its
     * locations, and whether the first System Administrator (V009) is part of it.
     */
    enum Profile {
        /**
         * Every environment: schema and reference rows ({@code db/migration/<vendor>}, {@code
         * db/migration/common}). V009 is NOT run: it hashes the deployment's own bootstrap
         * password, which a static script cannot hold; the application applies it at its first
         * start (INSTALL_KIT.md "Production").
         */
        REFERENCE(false),
        /**
         * Profile {@code demo}: + {@code db/seed}. V009 runs and does nothing (the seed has one).
         */
        DEMO(true),
        /** Profile {@code edge}: + {@code db/edge/<vendor>} (EDGE_DATA.md). */
        EDGE(true),
        /**
         * Profile {@code screens} (OD-141 D-screens, owner 2026-11-09; INSTALL_KIT.md): the screen
         * demo data, + {@code db/demo-screens/common} and {@code db/demo-screens/<vendor>} on top
         * of DEMO (a sibling of EDGE, never after it), the demo-evaluation seeds kept.
         */
        SCREENS(true);

        final boolean bootstrap;

        Profile(boolean bootstrap) {
            this.bootstrap = bootstrap;
        }

        /**
         * The data set this one is applied on top of (its data script runs after the parent's):
         * none for REFERENCE, REFERENCE for DEMO, DEMO for EDGE and for SCREENS. The kit's data
         * scripts form a tree, not a line: 05 (edge) and 05b (screens) both follow 04 (demo).
         */
        Profile parent() {
            return switch (this) {
                case REFERENCE -> null;
                case DEMO -> REFERENCE;
                case EDGE, SCREENS -> DEMO;
            };
        }

        /**
         * The Flyway placeholders of the profile, as application.yaml sets them: the
         * demo-evaluation seeds (OD-141 / OD-142) apply with demo and screens and stay out of the
         * edge data set.
         */
        Map<String, String> placeholders() {
            return Map.of("demo_evaluation", this == DEMO || this == SCREENS ? "Y" : "N");
        }

        List<String> folders(Vendor vendor) {
            String v = vendor.name().toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>(List.of("migration/" + v, "migration/common"));
            if (this != REFERENCE) {
                out.add("seed");
            }
            if (this == EDGE) {
                out.add("edge/" + v);
            }
            if (this == SCREENS) {
                out.add("demo-screens/common");
                out.add("demo-screens/" + v);
            }
            return out;
        }

        /**
         * Whether a folder of {@link #folders} may be missing: the screen demo folders (Flyway
         * skips a missing classpath location, so the application's profile does too). A screens
         * build without them equals the demo build.
         */
        static boolean optional(String folder) {
            return folder.startsWith("demo-screens/");
        }
    }

    /**
     * Migrates {@code dataSource} (an empty schema) for {@code profile}.
     *
     * @param db the repository's {@code db} folder
     * @param shiftTo when not null, a scratch folder: the scripts are copied there with every clock
     *     function moved {@link #SHIFT_YEARS} years ahead, to tell the values a seed computes from
     *     today from fixed ones
     * @param bootstrap whether V009 runs (defaults to the profile's)
     */
    static void migrate(
            DataSource dataSource,
            Vendor vendor,
            Profile profile,
            Path db,
            Path shiftTo,
            boolean bootstrap)
            throws IOException {
        migrate(dataSource, vendor, profile, db, shiftTo, 0, bootstrap);
    }

    /**
     * As {@link #migrate(DataSource, Vendor, Profile, Path, Path, boolean)}, the clock moved {@link
     * #SHIFT_YEARS} years and {@code shiftMonths} months ahead when {@code shiftTo} is given.
     */
    static void migrate(
            DataSource dataSource,
            Vendor vendor,
            Profile profile,
            Path db,
            Path shiftTo,
            int shiftMonths,
            boolean bootstrap)
            throws IOException {
        List<String> locations = new ArrayList<>();
        for (String folder : profile.folders(vendor)) {
            Path source = db.resolve(folder);
            if (!Files.isDirectory(source) && Profile.optional(folder)) {
                continue;
            }
            if (!Files.isDirectory(source)) {
                throw new IllegalStateException("missing Flyway location " + source);
            }
            if (shiftTo != null) {
                Path target = shiftTo.resolve(folder.replace('/', '_'));
                copyShifted(source, target, vendor, shiftMonths);
                source = target;
            }
            locations.add("filesystem:" + source.toAbsolutePath());
        }
        List<JavaMigration> java = new ArrayList<>();
        java.add(new V022__Align_pay_pattern_with_target_ddl());
        java.add(new V029_1__Spring_batch_6_job_instance_sequence());
        if (bootstrap) {
            java.add(bootstrapMigration());
        }
        Flyway.configure()
                .dataSource(dataSource)
                .locations(locations.toArray(String[]::new))
                .javaMigrations(java.toArray(JavaMigration[]::new))
                .placeholders(profile.placeholders())
                .outOfOrder(true)
                .initSql(vendor == Vendor.ORACLE ? ORACLE_SESSION : null)
                .load()
                .migrate();
    }

    /** The application's Oracle session (application-oracle.yml connection-init-sql). */
    static final String ORACLE_SESSION = "ALTER SESSION DISABLE PARALLEL DML";

    static void migrateOracle(DataSource dataSource, Profile profile, Path db, boolean bootstrap)
            throws IOException {
        migrate(dataSource, Vendor.ORACLE, profile, db, null, bootstrap);
    }

    static int migrateRemainingOracle(DataSource dataSource, Profile profile, Path db) {
        return migrateRemaining(dataSource, Vendor.ORACLE, profile, db);
    }

    /**
     * Applies only what the kit leaves to the application: on a kit-built REFERENCE schema that is
     * V009, which the history of 03_dml_reference does not list (INSTALL_KIT.md "Production").
     */
    static int migrateRemaining(DataSource dataSource, Vendor vendor, Profile profile, Path db) {
        List<String> locations = new ArrayList<>();
        for (String folder : profile.folders(vendor)) {
            if (Profile.optional(folder) && !Files.isDirectory(db.resolve(folder))) {
                continue;
            }
            locations.add("filesystem:" + db.resolve(folder).toAbsolutePath());
        }
        return Flyway.configure()
                .dataSource(dataSource)
                .locations(locations.toArray(String[]::new))
                .javaMigrations(
                        new V022__Align_pay_pattern_with_target_ddl(),
                        new V029_1__Spring_batch_6_job_instance_sequence(),
                        bootstrapMigration())
                .placeholders(profile.placeholders())
                .outOfOrder(true)
                .initSql(vendor == Vendor.ORACLE ? ORACLE_SESSION : null)
                .load()
                .migrate()
                .migrationsExecuted;
    }

    /** V009 with the generator's build-only password (used only when no admin exists). */
    static JavaMigration bootstrapMigration() {
        try {
            RecordComponent[] components = ApiProperties.class.getRecordComponents();
            Class<?>[] types = new Class<?>[components.length];
            Object[] args = new Object[components.length];
            for (int i = 0; i < components.length; i++) {
                types[i] = components[i].getType();
                if (types[i] == ApiProperties.Bootstrap.class) {
                    args[i] =
                            new ApiProperties.Bootstrap("sysadmin@fci.gov.in", BUILD_ONLY_PASSWORD);
                } else if (types[i] == boolean.class) {
                    args[i] = Boolean.FALSE;
                } else if (types[i] == int.class) {
                    args[i] = 0;
                } else if (types[i] == long.class) {
                    args[i] = 0L;
                } else if (types[i].isPrimitive()) {
                    throw new IllegalStateException("ApiProperties component of type " + types[i]);
                }
            }
            ApiProperties properties =
                    ApiProperties.class.getDeclaredConstructor(types).newInstance(args);
            return new V009__Bootstrap_system_administrator(
                    new BCryptPasswordEncoder(4), properties);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot build V009 for the generator", e);
        }
    }

    private static void copyShifted(Path source, Path target, Vendor vendor, int months)
            throws IOException {
        Files.createDirectories(target);
        List<Path> files;
        try (Stream<Path> s = Files.list(source)) {
            files = s.filter(p -> p.getFileName().toString().endsWith(".sql")).toList();
        }
        for (Path f : files) {
            String text = Files.readString(f, StandardCharsets.UTF_8);
            Files.writeString(
                    target.resolve(f.getFileName()),
                    shift(text, vendor, months),
                    StandardCharsets.UTF_8);
        }
    }

    /**
     * The script with each clock function outside string literals and comments moved {@link
     * #SHIFT_YEARS} years ahead (H2: DATEADD; Oracle: ADD_MONTHS keeps a DATE / interval for a
     * TIMESTAMP).
     */
    static String shift(String sql, Vendor vendor) {
        return shift(sql, vendor, 0);
    }

    /** As {@link #shift(String, Vendor)}, also {@code months} months ahead. */
    static String shift(String sql, Vendor vendor, int months) {
        StringBuilder out = new StringBuilder(sql.length() + 1024);
        int i = 0;
        int n = sql.length();
        int plain = 0;
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '\'' || sql.startsWith("--", i) || sql.startsWith("/*", i)) {
                out.append(shiftPlain(sql.substring(plain, i), vendor, months));
                int end;
                if (c == '\'') {
                    end = i + 1;
                    while (end < n) {
                        if (sql.charAt(end) == '\'') {
                            if (end + 1 < n && sql.charAt(end + 1) == '\'') {
                                end += 2;
                                continue;
                            }
                            break;
                        }
                        end++;
                    }
                    end = Math.min(n, end + 1);
                } else if (c == '-') {
                    end = sql.indexOf('\n', i);
                    end = end < 0 ? n : end;
                } else {
                    end = sql.indexOf("*/", i + 2);
                    end = end < 0 ? n : end + 2;
                }
                out.append(sql, i, end);
                i = end;
                plain = end;
            } else {
                i++;
            }
        }
        out.append(shiftPlain(sql.substring(plain), vendor, months));
        return out.toString();
    }

    private static String shiftPlain(String text, Vendor vendor, int months) {
        Matcher m = CLOCK.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String fn = m.group();
            String moved =
                    vendor == Vendor.H2
                            ? "DATEADD(YEAR, " + SHIFT_YEARS + ", " + fn + ")"
                            : "(" + fn + " + NUMTOYMINTERVAL(" + SHIFT_YEARS + ", 'YEAR'))";
            if (months != 0) {
                moved =
                        vendor == Vendor.H2
                                ? "DATEADD(MONTH, " + months + ", " + moved + ")"
                                : "ADD_MONTHS(" + moved + ", " + months + ")";
            }
            m.appendReplacement(out, Matcher.quoteReplacement(moved));
        }
        m.appendTail(out);
        return out.toString();
    }
}
