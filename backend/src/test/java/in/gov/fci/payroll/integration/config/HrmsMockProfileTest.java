package in.gov.fci.payroll.integration.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.gov.fci.payroll.integration.api.HrmsApi;
import in.gov.fci.payroll.platform.config.YesNoBooleanConverter;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.convert.support.DefaultConversionService;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * SRS-INT-1, SRS-INT-6, R-INT: the {@code hrms-mock} profile document of the REAL {@code
 * application.yaml} binds, validates clean and sets every in-scope API for Payroll to call. A Y / N
 * flag in that file once stopped the start because Spring does not read a single letter as a
 * boolean; this binds the shipped file with the project's converter, so the file and the converter
 * cannot drift apart.
 */
class HrmsMockProfileTest {

    private static DefaultConversionService conversion() {
        DefaultConversionService conversion = new DefaultConversionService();
        conversion.addConverter(new YesNoBooleanConverter());
        return conversion;
    }

    /** The document of application.yaml activated by profile hrms-mock. */
    private static PropertySource<?> document() throws IOException {
        return new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yaml")).stream()
                        .filter(
                                d ->
                                        "hrms-mock"
                                                .equals(
                                                        d.getProperty(
                                                                "spring.config.activate.on-profile")))
                        .findFirst()
                        .orElseThrow();
    }

    private static HrmsApiProperties bound() throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        MutablePropertySources sources = environment.getPropertySources();
        sources.addFirst(document());
        return new Binder(ConfigurationPropertySources.get(environment), null, conversion())
                .bind("fci.integration.hrms", HrmsApiProperties.class)
                .orElseThrow(() -> new AssertionError("fci.integration.hrms did not bind"));
    }

    @Test
    void SRS_INT_6_theShippedMockProfileBindsAndValidatesWithoutErrorsOrWarnings()
            throws IOException {
        HrmsApiProperties props = bound();
        assertThat(props.errors()).isEmpty();
        // Owner 2026-11-06: connect 10 s, read 60 s, whole call 30 s. The whole call ends first, so
        // the read time
        // can never be reached: the start-up check says so (a warning, not an error).
        assertThat(props.warnings())
                .containsExactly(
                        "defaults: read-timeout-ms is longer than timeout-ms and can never be"
                                + " reached");
    }

    @Test
    void SRS_INT_1_everyInScopeApiIsSetForPayrollToCall() throws IOException {
        HrmsApiProperties props = bound();
        for (HrmsApi api : HrmsApi.values()) {
            HrmsApiProperties.ApiUrl entry = props.api().get(api.code());
            assertThat(entry).as(api.code()).isNotNull();
            assertThat(entry.inbound()).as(api.code()).isFalse();
            assertThat(props.outboundUrl(api)).as(api.code()).isPresent();
            assertThat(props.limit(api)).as(api.code()).isPresent();
        }
        assertThat(props.api()).hasSize(HrmsApi.values().length);
        assertThat(props.limit("connection_test")).isPresent();
    }

    @Test
    void R_INT_theDefaultCallPolicyOfTheProfileReadsAsYAndN() throws IOException {
        HrmsApiProperties.Resolved resolved =
                bound().resolve(
                                "get_ota_status",
                                30,
                                new HrmsClientProperties(
                                        null, null, null, null, null, null, null, null, null,
                                        null));
        assertThat(resolved.connectTimeoutMs()).isEqualTo(10000);
        assertThat(resolved.readTimeoutMs()).isEqualTo(60000);
        assertThat(resolved.timeoutMs()).isEqualTo(30000);
        assertThat(resolved.maskData()).isTrue();
        assertThat(resolved.logRequest()).isTrue();
        assertThat(resolved.logResponse()).isTrue();
        assertThat(resolved.pool().key()).isEqualTo("shared");
    }

    @Test
    void R_INT_theAdministratorConsoleIsOnOnlyInThisProfile() throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(document());
        HrmsConsoleProperties console =
                new Binder(ConfigurationPropertySources.get(environment), null, conversion())
                        .bind("fci.integration.hrms.console", HrmsConsoleProperties.class)
                        .orElseThrow(() -> new AssertionError("console did not bind"));
        assertThat(console.on()).isTrue();
    }

    @Test
    void R_INT_theFlagConverterReadsEveryFormAndRefusesATypo() {
        YesNoBooleanConverter converter = new YesNoBooleanConverter();
        for (String yes : List.of("Y", "y", "yes", "YES", "true", "TRUE", "on", "1", " Y ")) {
            assertThat(converter.convert(yes)).as(yes).isTrue();
        }
        for (String no : List.of("N", "n", "no", "NO", "false", "FALSE", "off", "0", " n ")) {
            assertThat(converter.convert(no)).as(no).isFalse();
        }
        assertThat(converter.convert("")).isNull();
        assertThat(converter.convert("   ")).isNull();
        assertThatThrownBy(() -> converter.convert("maybe"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'maybe' is not a flag");
        assertThatThrownBy(() -> converter.convert("yy"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
