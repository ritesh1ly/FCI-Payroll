package in.gov.fci.payroll.platform.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.flyway.autoconfigure.FlywayProperties;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * OD-117, NFR start-up: a shared development database set up with the demo data keeps the demo seed
 * versions in its Flyway history. Profile {@code dev} alone tolerates those MISSING versions; no
 * other profile (so never uat or prod) does, and nothing other than the missing status is ignored.
 */
class FlywayDevProfileTest {

    private static final String KEY = "spring.flyway.ignore-migration-patterns";

    /** The documents of application.yaml with their activation profile, in file order. */
    private static List<PropertySource<?>> documents() throws IOException {
        return new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yaml"));
    }

    private static boolean activatedBy(PropertySource<?> document, String profile) {
        Object on = document.getProperty("spring.config.activate.on-profile");
        return on != null && on.toString().equals(profile);
    }

    @Test
    void OD_117_onlyTheDevProfileIgnoresAMissingAppliedMigrationAndNothingElse()
            throws IOException {
        List<PropertySource<?>> withKey =
                documents().stream().filter(d -> d.containsProperty(KEY)).toList();
        assertThat(withKey).hasSize(1);
        assertThat(activatedBy(withKey.getFirst(), "dev")).isTrue();
        assertThat(withKey.getFirst().getProperty(KEY)).isEqualTo("*:missing");
    }

    @Test
    void OD_117_noEnvironmentProfileOfUatOrProdCarriesTheSetting() throws IOException {
        for (PropertySource<?> document : documents()) {
            for (String profile : List.of("uat", "prod", "online", "batch", "oracle", "demo")) {
                if (activatedBy(document, profile)) {
                    assertThat(document.containsProperty(KEY)).as(profile).isFalse();
                }
            }
        }
    }

    @Test
    void OD_117_theDevSettingBindsIntoSpringBootsFlywayProperties() throws IOException {
        PropertySource<?> dev =
                documents().stream().filter(d -> activatedBy(d, "dev")).findFirst().orElseThrow();
        StandardEnvironment environment = new StandardEnvironment();
        MutablePropertySources sources = environment.getPropertySources();
        sources.addFirst(dev);
        FlywayProperties bound =
                new Binder(ConfigurationPropertySources.get(environment))
                        .bind("spring.flyway", Bindable.of(FlywayProperties.class))
                        .orElseThrow(() -> new AssertionError("spring.flyway did not bind"));
        assertThat(bound.getIgnoreMigrationPatterns()).containsExactly("*:missing");
    }

    @Test
    void OD_117_flywayAcceptsAHistoryRowWithoutAFileOnlyWithThePattern() throws Exception {
        String url =
                "jdbc:h2:mem:od117"
                        + UUID.randomUUID().toString().replace("-", "")
                        + ";DB_CLOSE_DELAY=-1";
        try (Connection keep = DriverManager.getConnection(url, "sa", "");
                Statement st = keep.createStatement()) {
            Flyway seeded =
                    Flyway.configure()
                            .dataSource(url, "sa", "")
                            .locations("classpath:in/gov/fci/payroll/platform/config/od117")
                            .load();
            seeded.migrate();
            // The demo seed version of the shared database: in the history, not on this classpath.
            st.execute(
                    "INSERT INTO \"flyway_schema_history\" (\"installed_rank\", \"version\","
                        + " \"description\", \"type\", \"script\", \"checksum\", \"installed_by\","
                        + " \"execution_time\", \"success\") VALUES (99, '77.9', 'demo lwp"
                        + " components', 'SQL', 'V077_9__demo_lwp_components.sql', 1, 'sa', 1,"
                        + " TRUE)");
            Flyway strict =
                    Flyway.configure()
                            .dataSource(url, "sa", "")
                            .locations("classpath:in/gov/fci/payroll/platform/config/od117")
                            .load();
            assertThatThrownBy(strict::validate)
                    .isInstanceOf(FlywayValidateException.class)
                    .hasMessageContaining("not resolved locally");
            Flyway dev =
                    Flyway.configure()
                            .dataSource(url, "sa", "")
                            .locations("classpath:in/gov/fci/payroll/platform/config/od117")
                            .ignoreMigrationPatterns("*:missing")
                            .load();
            dev.validate();
            assertThat(dev.migrate().migrationsExecuted).isZero();
        }
    }
}
