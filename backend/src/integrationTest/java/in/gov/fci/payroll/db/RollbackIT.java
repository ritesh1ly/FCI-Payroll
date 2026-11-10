package in.gov.fci.payroll.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.iam.persistence.V009__Bootstrap_system_administrator;
import in.gov.fci.payroll.platform.config.ApiProperties;
import in.gov.fci.payroll.platform.persistence.V022__Align_pay_pattern_with_target_ddl;
import in.gov.fci.payroll.platform.persistence.V029_1__Spring_batch_6_job_instance_sequence;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Every H2 rollback script runs and matches its migration (review 2026-10-24, B2). A fresh H2
 * database in the application's mode takes every migration and every demo seed, exactly as a
 * deployment does; then every rollback of {@code db/rollback/h2} is applied newest first, as a DBA
 * would undo a release. A rollback that no longer matches its migration (a renamed constraint, a
 * forgotten table, a row the script cannot delete) fails here with its name; at the end nothing of
 * the application's schema is left. Nothing here touches the application's own database.
 */
class RollbackIT {

    private static final Pattern VERSION = Pattern.compile("^U(\\d+(?:_\\d+)*)__");

    /** The version of a rollback file as a comparable list (U034_1 → [34, 1]). */
    static List<Integer> version(Path file) {
        Matcher m = VERSION.matcher(file.getFileName().toString());
        if (!m.find()) {
            throw new IllegalArgumentException("Not a rollback script: " + file);
        }
        return Stream.of(m.group(1).split("_")).map(Integer::valueOf).toList();
    }

    /**
     * Whether a rollback undoes a script of the screen demo data set (U960_x, db/demo-screens;
     * OD-141 D-screens, docs/db/INSTALL_KIT.md).
     */
    static boolean isScreens(Path file) {
        return version(file).get(0) == 960;
    }

    static int compare(List<Integer> a, List<Integer> b) {
        for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
            int x = i < a.size() ? a.get(i) : 0;
            int y = i < b.size() ? b.get(i) : 0;
            if (x != y) {
                return Integer.compare(x, y);
            }
        }
        return 0;
    }

    @Test
    void everyH2RollbackRunsNewestFirstAndLeavesNoSchemaBehind() throws IOException, SQLException {
        Path rollbacks = Path.of("..", "db", "rollback", "h2").toAbsolutePath().normalize();
        assertThat(rollbacks).as("the repository's rollback scripts").isDirectory();
        List<Path> scripts;
        try (Stream<Path> files = Files.list(rollbacks)) {
            scripts =
                    files.filter(p -> p.getFileName().toString().endsWith(".sql"))
                            // The screen demo data set (U960, OD-141 D-screens) never shares a
                            // database with the edge set migrated below (StartupGuards): its
                            // rollbacks are proved on a screens database by ScreensDataSetIT.
                            .filter(p -> !isScreens(p))
                            .sorted(Comparator.comparing(RollbackIT::version, RollbackIT::compare))
                            .toList()
                            .reversed();
        }
        assertThat(scripts).isNotEmpty();

        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:rollback_it;MODE=Oracle;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        dataSource.setDriverClassName("org.h2.Driver");
        // The application registers its Java migrations as beans; here they are given by hand.
        ApiProperties properties = mock(ApiProperties.class);
        when(properties.bootstrap())
                .thenReturn(
                        new ApiProperties.Bootstrap(
                                "sysadmin@fci.gov.in", "Rollback-IT-Passw0rd!"));
        Flyway.configure()
                .dataSource(dataSource)
                .locations(
                        "classpath:db/migration/h2",
                        "classpath:db/migration/common",
                        "classpath:db/seed",
                        // The edge-case data set (profile edge): its rollbacks run first.
                        "classpath:db/edge/h2",
                        // The worker clones (profile e2e-parallel): above everything, undone first.
                        "classpath:db/e2e-parallel/h2")
                .javaMigrations(
                        new V009__Bootstrap_system_administrator(
                                new BCryptPasswordEncoder(4), properties),
                        new V022__Align_pay_pattern_with_target_ddl(),
                        new V029_1__Spring_batch_6_job_instance_sequence())
                // As the edge profile: the demo-evaluation seeds stay out (application.yaml).
                .placeholders(Map.of("demo_evaluation", "N"))
                .load()
                .migrate();

        List<String> failures = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            for (Path script : scripts) {
                try {
                    // U009 carries the bootstrap login as a placeholder the DBA fills in (its
                    // header says so): filled in here the same way.
                    String sql =
                            Files.readString(script).replace(":login", "'sysadmin@fci.gov.in'");
                    ScriptUtils.executeSqlScript(
                            connection,
                            new ByteArrayResource(
                                    sql.getBytes(StandardCharsets.UTF_8),
                                    script.getFileName().toString()));
                } catch (RuntimeException failure) {
                    failures.add(
                            script.getFileName()
                                    + ": "
                                    + rootCause(failure)
                                            .getMessage()
                                            .lines()
                                            .findFirst()
                                            .orElse(""));
                }
            }
            assertThat(failures).as("rollbacks that no longer match their migration").isEmpty();
            assertThat(remaining(connection)).as("schema left behind by the rollbacks").isEmpty();
        }
    }

    /**
     * OD-141 / OD-142: the demo-evaluation seeds (V082_20 .. V082_39, Flyway placeholder {@code
     * demo_evaluation}) and their rollbacks. A demo database built with them and then rolled back
     * by their H2 rollbacks, newest first, holds exactly the rows per table of a demo database
     * built without them.
     */
    @Test
    void demoEvaluationSeedsRollBackToTheDemoDataWithoutThem() throws IOException, SQLException {
        Path rollbacks = Path.of("..", "db", "rollback", "h2").toAbsolutePath().normalize();
        List<Path> ours;
        try (Stream<Path> files = Files.list(rollbacks)) {
            ours =
                    files.filter(p -> p.getFileName().toString().endsWith(".sql"))
                            .filter(
                                    p -> {
                                        List<Integer> v = version(p);
                                        return v.size() > 1
                                                && v.get(0) == 82
                                                && v.get(1) >= 20
                                                && v.get(1) <= 39;
                                    })
                            .sorted(Comparator.comparing(RollbackIT::version, RollbackIT::compare))
                            .toList()
                            .reversed();
        }
        assertThat(ours).as("the rollbacks of V082_20 .. V082_39").isNotEmpty();
        DriverManagerDataSource without = demo("rollback_it_without", "N");
        DriverManagerDataSource with = demo("rollback_it_with", "Y");
        try (Connection a = without.getConnection();
                Connection b = with.getConnection()) {
            assertThat(counts(b)).as("the seeds add rows").isNotEqualTo(counts(a));
            for (Path script : ours) {
                ScriptUtils.executeSqlScript(
                        b,
                        new ByteArrayResource(
                                Files.readAllBytes(script), script.getFileName().toString()));
            }
            assertThat(counts(b)).as("rows per table after the rollbacks").isEqualTo(counts(a));
        }
    }

    /** A demo database (migrations and db/seed) with the demo-evaluation placeholder. */
    private static DriverManagerDataSource demo(String name, String evaluation) {
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:"
                                + name
                                + ";MODE=Oracle;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        dataSource.setDriverClassName("org.h2.Driver");
        ApiProperties properties = mock(ApiProperties.class);
        when(properties.bootstrap())
                .thenReturn(
                        new ApiProperties.Bootstrap(
                                "sysadmin@fci.gov.in", "Rollback-IT-Passw0rd!"));
        Flyway.configure()
                .dataSource(dataSource)
                .locations(
                        "classpath:db/migration/h2",
                        "classpath:db/migration/common",
                        "classpath:db/seed")
                .javaMigrations(
                        new V009__Bootstrap_system_administrator(
                                new BCryptPasswordEncoder(4), properties),
                        new V022__Align_pay_pattern_with_target_ddl(),
                        new V029_1__Spring_batch_6_job_instance_sequence())
                .placeholders(Map.of("demo_evaluation", evaluation))
                .load()
                .migrate();
        return dataSource;
    }

    /** Rows per table of PUBLIC, Flyway's history left out. */
    private static Map<String, Long> counts(Connection connection) throws SQLException {
        List<String> tables = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet rs =
                        statement.executeQuery(
                                "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE"
                                        + " TABLE_SCHEMA = 'PUBLIC' AND TABLE_TYPE = 'BASE TABLE'"
                                        + " AND UPPER(TABLE_NAME) <> 'FLYWAY_SCHEMA_HISTORY'")) {
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
        }
        Map<String, Long> out = new TreeMap<>();
        try (Statement statement = connection.createStatement()) {
            for (String table : tables) {
                try (ResultSet rs =
                        statement.executeQuery("SELECT COUNT(*) FROM \"" + table + "\"")) {
                    rs.next();
                    out.put(table, rs.getLong(1));
                }
            }
        }
        return out;
    }

    private static Throwable rootCause(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }

    /** Tables, views and triggers of PUBLIC other than Flyway's own history. */
    private static List<String> remaining(Connection connection) throws SQLException {
        List<String> left = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet rs =
                        statement.executeQuery(
                                "SELECT TABLE_TYPE || ' ' || TABLE_NAME FROM"
                                    + " INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'PUBLIC' AND"
                                    + " TABLE_NAME <> 'flyway_schema_history' AND UPPER(TABLE_NAME)"
                                    + " <> 'FLYWAY_SCHEMA_HISTORY' UNION ALL SELECT 'TRIGGER ' ||"
                                    + " TRIGGER_NAME FROM INFORMATION_SCHEMA.TRIGGERS WHERE"
                                    + " TRIGGER_SCHEMA = 'PUBLIC' ORDER BY 1")) {
            while (rs.next()) {
                left.add(rs.getString(1));
            }
        }
        return left;
    }
}
