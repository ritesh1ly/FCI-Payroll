package in.gov.fci.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.iam.persistence.V009__Bootstrap_system_administrator;
import in.gov.fci.payroll.platform.config.ApiProperties;
import in.gov.fci.payroll.platform.persistence.V022__Align_pay_pattern_with_target_ddl;
import in.gov.fci.payroll.platform.persistence.V029_1__Spring_batch_6_job_instance_sequence;
import java.io.IOException;
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
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The screen demo data set (profile screens; OD-141 D-screens, owner 2026-11-09;
 * docs/db/INSTALL_KIT.md): its own data set like edge, but ON TOP of the full demo data with the
 * demo-evaluation seeds (V082_20 .. V082_39, placeholder {@code demo_evaluation} 'Y'). Proves that
 * every V960 script applies on a fresh H2 database above the demo seed (so each row meets the DDL's
 * constraints and triggers) and the application starts and signs in on it; that every V960 script
 * has its H2 and Oracle rollback; and that the H2 rollbacks, newest first, leave exactly the rows
 * per table of a plain demo build.
 *
 * <p>Opt-in (two extra full migrations and an application context):
 *
 * <pre>
 *   FCI_SCREENS_DATA=1 ./gradlew :backend:integrationTest --tests "in.gov.fci.payroll.ScreensDataSetIT" --rerun
 * </pre>
 *
 * The suites' {@code demo_evaluation} 'N' (application-it.yaml) is overridden to 'Y' here, as the
 * screens profile sets it in application.yaml.
 */
@ActiveProfiles("screens")
@TestPropertySource(properties = "spring.flyway.placeholders.demo_evaluation=Y")
@EnabledIfEnvironmentVariable(named = "FCI_SCREENS_DATA", matches = "1|true|yes")
class ScreensDataSetIT extends ApiTestSupport {

    private static final Pattern VERSION = Pattern.compile("^[VU](\\d+(?:_\\d+)*)__");

    private static final Path DB = Path.of("..", "db").toAbsolutePath().normalize();

    @Autowired Flyway flyway;

    @Test
    void OD_141_everyScreensScriptAppliesAboveTheDemoDataWithTheDemoEvaluationSeeds()
            throws IOException {
        assertThat(flyway.getConfiguration().getPlaceholders())
                .containsEntry("demo_evaluation", "Y");
        // The demo-evaluation seeds are part of the base (V082_20: the test office DT001).
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM org_location WHERE location_code = 'DT001'",
                                Integer.class))
                .isOne();
        List<String> applied =
                jdbc.queryForList(
                        "SELECT \"version\" FROM \"flyway_schema_history\" WHERE \"version\" LIKE"
                                + " '960%' AND \"success\" = TRUE ORDER BY \"installed_rank\"",
                        String.class);
        assertThat(applied).isNotEmpty().allMatch(v -> v.startsWith("960"));
        assertThat(new TreeSet<>(applied.stream().map(v -> v.replace('.', '_')).toList()))
                .as("every script of db/demo-screens/common and db/demo-screens/h2 is applied")
                .isEqualTo(versions(screensScripts("h2")));
    }

    @Test
    void OD_141_theApplicationStartsAndADemoUserSignsInOnTheScreensData() throws Exception {
        Session maker = login("maker.delhi@fci-demo.test", "PAYROLL_MAKER", "DH001");
        MvcResult r = mvc.perform(userGet(maker, "/v1/employees?size=20")).andReturn();
        assertThat(r.getResponse().getStatus())
                .as(r.getResponse().getContentAsString())
                .isEqualTo(200);
    }

    /** Every V960 script has its rollback in db/rollback/h2 and db/rollback/oracle. */
    @Test
    void OD_141_everyScreensScriptHasItsH2AndOracleRollback() throws IOException {
        for (String vendor : List.of("h2", "oracle")) {
            assertThat(versions(rollbacks(vendor)))
                    .as("db/rollback/" + vendor + "/U960_* against the V960 scripts for " + vendor)
                    .isEqualTo(versions(screensScripts(vendor)));
        }
    }

    /**
     * A screens database (demo, demo-evaluation seeds, V960) rolled back by the U960 H2 rollbacks,
     * newest first, holds exactly the rows per table of a plain demo database ('Y').
     */
    @Test
    void OD_141_theScreensRollbacksLeaveThePlainDemoData() throws IOException, SQLException {
        List<Path> ours = new ArrayList<>(rollbacks("h2"));
        ours.sort(Comparator.comparing(ScreensDataSetIT::version, ScreensDataSetIT::compare));
        List<Path> newestFirst = ours.reversed();
        assertThat(newestFirst).as("the rollbacks of V960").isNotEmpty();
        DriverManagerDataSource demo = build("screens_it_demo", false);
        DriverManagerDataSource screens = build("screens_it_screens", true);
        try (Connection a = demo.getConnection();
                Connection b = screens.getConnection()) {
            for (Path script : newestFirst) {
                ScriptUtils.executeSqlScript(
                        b,
                        new ByteArrayResource(
                                Files.readAllBytes(script), script.getFileName().toString()));
            }
            assertThat(counts(b)).as("rows per table after the rollbacks").isEqualTo(counts(a));
            shutdown(a);
            shutdown(b);
        }
    }

    /** The V960 scripts the screens profile applies on {@code vendor}: common + the vendor's. */
    private static List<Path> screensScripts(String vendor) throws IOException {
        List<Path> out = new ArrayList<>();
        for (String folder : List.of("common", vendor)) {
            Path dir = DB.resolve("demo-screens").resolve(folder);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(p -> p.getFileName().toString().matches("V960_.*\\.sql"))
                        .forEach(out::add);
            }
        }
        return out;
    }

    private static List<Path> rollbacks(String vendor) throws IOException {
        try (Stream<Path> files = Files.list(DB.resolve("rollback").resolve(vendor))) {
            return files.filter(p -> p.getFileName().toString().matches("U960_.*\\.sql")).toList();
        }
    }

    private static TreeSet<String> versions(List<Path> files) {
        TreeSet<String> out = new TreeSet<>();
        for (Path f : files) {
            out.add(String.join("_", version(f).stream().map(String::valueOf).toList()));
        }
        return out;
    }

    private static List<Integer> version(Path file) {
        Matcher m = VERSION.matcher(file.getFileName().toString());
        if (!m.find()) {
            throw new IllegalArgumentException("Not a versioned script: " + file);
        }
        return Stream.of(m.group(1).split("_")).map(Integer::valueOf).toList();
    }

    private static int compare(List<Integer> a, List<Integer> b) {
        for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
            int x = i < a.size() ? a.get(i) : 0;
            int y = i < b.size() ? b.get(i) : 0;
            if (x != y) {
                return Integer.compare(x, y);
            }
        }
        return 0;
    }

    /**
     * A demo database (migrations, db/seed, demo_evaluation 'Y') as the application's Flyway builds
     * it, with the screens locations when {@code screens}.
     */
    private static DriverManagerDataSource build(String name, boolean screens) {
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:"
                                + name
                                + "_"
                                + UUID.randomUUID().toString().replace("-", "")
                                + ";MODE=Oracle;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        dataSource.setDriverClassName("org.h2.Driver");
        ApiProperties properties = mock(ApiProperties.class);
        when(properties.bootstrap())
                .thenReturn(
                        new ApiProperties.Bootstrap("sysadmin@fci.gov.in", "Screens-IT-Passw0rd!"));
        List<String> locations =
                new ArrayList<>(
                        List.of(
                                "classpath:db/migration/h2",
                                "classpath:db/migration/common",
                                "classpath:db/seed"));
        if (screens) {
            // As application.yaml's screens profile: both folders named, a missing one skipped.
            locations.add("classpath:db/demo-screens/common");
            locations.add("classpath:db/demo-screens/h2");
        }
        Flyway.configure()
                .dataSource(dataSource)
                .locations(locations.toArray(String[]::new))
                .javaMigrations(
                        new V009__Bootstrap_system_administrator(
                                new BCryptPasswordEncoder(4), properties),
                        new V022__Align_pay_pattern_with_target_ddl(),
                        new V029_1__Spring_batch_6_job_instance_sequence())
                .placeholders(Map.of("demo_evaluation", "Y"))
                .outOfOrder(true)
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

    private static void shutdown(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute("SHUTDOWN");
        }
    }
}
