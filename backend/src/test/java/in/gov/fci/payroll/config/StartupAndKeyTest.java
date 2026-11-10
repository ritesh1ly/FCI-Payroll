package in.gov.fci.payroll.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.gov.fci.payroll.platform.config.ApiProperties;
import in.gov.fci.payroll.platform.config.StartupGuards;
import in.gov.fci.payroll.platform.security.KeyMaterial;
import java.time.Duration;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * Start-up guards (unsafe configuration refused in uat / prod, ADR-0013) and the symmetric key
 * material.
 */
class StartupAndKeyTest {

    private static final String KEY32 = Base64.getEncoder().encodeToString(new byte[32]);

    private static ApiProperties properties(ApiProperties.Secrets secrets) {
        return properties(secrets, ApiProperties.Verification.Y);
    }

    private static ApiProperties properties(
            ApiProperties.Secrets secrets, ApiProperties.Verification otp) {
        return properties(secrets, otp, true);
    }

    private static ApiProperties properties(
            ApiProperties.Secrets secrets, ApiProperties.Verification otp, boolean atLogin) {
        return new ApiProperties(
                new ApiProperties.Password(
                        5, Duration.ofDays(90), "classpath:security/common-passwords.txt"),
                new ApiProperties.Lockout(5, Duration.ofMinutes(15)),
                new ApiProperties.Session(
                        Duration.ofMinutes(15),
                        Duration.ofMinutes(10),
                        Duration.ofHours(8),
                        Duration.ofMinutes(15),
                        "FCISESSION",
                        true,
                        false),
                new ApiProperties.RateLimits(10, 5, 5, 3, 10, 8, 20, 600),
                new ApiProperties.Otp(Duration.ofMinutes(10), 5, 5, 3, otp, atLogin, true),
                new ApiProperties.Audit(31),
                new ApiProperties.Idempotency(Duration.ofHours(24)),
                secrets,
                new ApiProperties.Bootstrap("sysadmin@fci.gov.in", null),
                new ApiProperties.Payroll(YearMonth.of(2026, 7), "OPEN"),
                new ApiProperties.Demo(false),
                new ApiProperties.I18n("en", "FCI_LOCALE"),
                ZoneId.of("Asia/Kolkata"),
                true);
    }

    private static ApiProperties.Secrets secrets(boolean ephemeral, String key) {
        return new ApiProperties.Secrets(key, key, ephemeral);
    }

    /** The servers' database (profile oracle); a test that needs another URL sets it. */
    private static final String ORACLE_URL = "jdbc:oracle:thin:@//fci-db:1521/FCIPAY";

    private static MockEnvironment env(String... profiles) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profiles);
        env.setProperty("spring.datasource.url", ORACLE_URL);
        return env;
    }

    private static void refused(MockEnvironment env, String message) {
        assertThatThrownBy(
                        () ->
                                new StartupGuards(env, properties(secrets(false, KEY32)))
                                        .afterPropertiesSet())
                .as(String.join(",", env.getActiveProfiles()))
                .hasMessageContaining(message);
    }

    private static void allowed(MockEnvironment env) {
        assertThatCode(
                        () ->
                                new StartupGuards(env, properties(secrets(false, KEY32)))
                                        .afterPropertiesSet())
                .as(String.join(",", env.getActiveProfiles()))
                .doesNotThrowAnyException();
    }

    @Test
    void exactlyOneOfOnlineAndBatchMustBeActive() {
        assertThatThrownBy(
                        () ->
                                new StartupGuards(env("local"), properties(secrets(true, null)))
                                        .afterPropertiesSet())
                .hasMessageContaining("Exactly one of the profiles 'online' and 'batch'");
        assertThatThrownBy(
                        () ->
                                new StartupGuards(
                                                env("local", "online", "batch"),
                                                properties(secrets(true, null)))
                                        .afterPropertiesSet())
                .hasMessageContaining("Exactly one");
    }

    @Test
    void strictProfilesRefuseUnsafeConfigurationWithoutEchoingSecrets() {
        MockEnvironment prod =
                env("prod", "online").withProperty("springdoc.api-docs.enabled", "true");
        StartupGuards guards =
                new StartupGuards(
                        prod, properties(new ApiProperties.Secrets("changeit", null, true)));
        assertThatThrownBy(guards::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ephemeral-keys")
                .hasMessageContaining("audit-hmac-key uses a placeholder")
                .hasMessageContaining("data-encryption-key is required")
                .hasMessageContaining("springdoc")
                .hasMessageNotContaining("changeit");
    }

    @Test
    void theDemoSeedAndLoggedOtpCodesNeverRunInProduction() {
        assertThatThrownBy(
                        () ->
                                new StartupGuards(
                                                env("prod", "demo", "online"),
                                                properties(secrets(false, KEY32)))
                                        .afterPropertiesSet())
                .hasMessageContaining("demo profile");
        MockEnvironment uatCodes =
                env("uat", "online").withProperty("fci.api.notification.log-codes", "true");
        assertThatThrownBy(
                        () ->
                                new StartupGuards(uatCodes, properties(secrets(false, KEY32)))
                                        .afterPropertiesSet())
                .hasMessageContaining("log-codes");
    }

    /**
     * SRS-G-21 (project owner, 2026-10-23): OTP verification of the critical activities may be
     * switched off (N) on development and local machines only; production and a UAT that is not a
     * local demo stack refuse to start with N.
     */
    @Test
    void SRS_G_21_otpVerificationOffIsRefusedInProductionAndInARealUat() {
        ApiProperties off = properties(secrets(false, KEY32), ApiProperties.Verification.N);
        for (String[] profiles :
                new String[][] {{"prod", "online"}, {"uat", "online"}, {"uat", "batch"}}) {
            assertThatThrownBy(() -> new StartupGuards(env(profiles), off).afterPropertiesSet())
                    .as(String.join(",", profiles))
                    .hasMessageContaining("fci.api.otp.required must be Y");
        }
        for (String[] profiles :
                new String[][] {
                    {"local", "online"}, {"dev", "online"}, {"uat", "demo", "online"}, {"online"}
                }) {
            assertThatCode(() -> new StartupGuards(env(profiles), off).afterPropertiesSet())
                    .as(String.join(",", profiles))
                    .doesNotThrowAnyException();
        }
        assertThat(off.otp().verificationRequired()).isFalse();
        assertThat(properties(secrets(false, KEY32)).otp().verificationRequired()).isTrue();
    }

    @Test
    void aSafeUatConfigurationAndRelaxedProfilesPass() {
        assertThatCode(
                        () ->
                                new StartupGuards(
                                                env("uat", "batch"),
                                                properties(secrets(false, KEY32)))
                                        .afterPropertiesSet())
                .doesNotThrowAnyException();
        MockEnvironment local =
                env("local", "online").withProperty("spring.datasource.url", "jdbc:h2:mem:x");
        assertThatCode(
                        () ->
                                new StartupGuards(local, properties(secrets(true, null)))
                                        .afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @Test
    void ephemeralKeysAreGeneratedOnlyWhenAllowedAndGivenKeysMustBe32Bytes() {
        KeyMaterial keys = new KeyMaterial(properties(secrets(true, null)));
        assertThat(keys.auditHmacKey()).hasSize(32);
        assertThat(keys.dataEncryptionKey()).hasSize(32);
        assertThat(keys.auditHmacKey())
                .as("a defensive copy each time")
                .isNotSameAs(keys.auditHmacKey());

        assertThatThrownBy(() -> new KeyMaterial(properties(secrets(false, null))))
                .hasMessageContaining("audit-hmac-key")
                .hasMessageContaining("required");
        String short8 = Base64.getEncoder().encodeToString(new byte[8]);
        assertThatThrownBy(() -> new KeyMaterial(properties(secrets(true, short8))))
                .hasMessageContaining("32 bytes");
        assertThat(new KeyMaterial(properties(secrets(false, KEY32))).auditHmacKey())
                .isEqualTo(new byte[32]);
    }

    /** Resilience runs (owner 2026-10-27): fault injection only on a local demo stack. */
    @Test
    void theChaosMonkeyProfileIsRefusedInProductionAndARealUatButAllowedOnALocalDemoUat() {
        for (MockEnvironment refused :
                List.of(
                        env("prod", "chaos-monkey", "online"),
                        env("uat", "chaos-monkey", "online"))) {
            assertThatThrownBy(
                            () ->
                                    new StartupGuards(refused, properties(secrets(false, KEY32)))
                                            .afterPropertiesSet())
                    .hasMessageContaining("chaos-monkey");
        }
        assertThatCode(
                        () ->
                                new StartupGuards(
                                                env("uat", "demo", "chaos-monkey", "online"),
                                                properties(secrets(false, KEY32)))
                                        .afterPropertiesSet())
                .doesNotThrowAnyException();
        // A local machine (neither prod nor uat) may inject faults too.
        assertThatCode(
                        () ->
                                new StartupGuards(
                                                env("demo", "chaos-monkey", "online"),
                                                properties(secrets(false, KEY32)))
                                        .afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @Test
    void theHrmsMockProfileIsRefusedInProductionAndARealUatButAllowedOnLocalDevAndDemo() {
        for (MockEnvironment refused :
                List.of(env("prod", "hrms-mock", "online"), env("uat", "hrms-mock", "online"))) {
            assertThatThrownBy(
                            () ->
                                    new StartupGuards(refused, properties(secrets(false, KEY32)))
                                            .afterPropertiesSet())
                    .hasMessageContaining("hrms-mock");
        }
        for (MockEnvironment allowed :
                List.of(
                        env("uat", "demo", "hrms-mock", "online"),
                        env("demo", "hrms-mock", "online"),
                        env("dev", "hrms-mock", "online"),
                        env("local", "hrms-mock", "online"))) {
            assertThatCode(
                            () ->
                                    new StartupGuards(allowed, properties(secrets(false, KEY32)))
                                            .afterPropertiesSet())
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void theEdgeDataSetNeedsDemoAndIsNeverWithVolumeOrProduction() {
        for (MockEnvironment refused :
                List.of(
                        env("uat", "edge", "online"),
                        env("uat", "demo", "volume", "edge", "online"),
                        env("prod", "edge", "online"))) {
            assertThatThrownBy(
                            () ->
                                    new StartupGuards(refused, properties(secrets(false, KEY32)))
                                            .afterPropertiesSet())
                    .hasMessageContaining("edge");
        }
        refused(
                env("prod", "oracle", "volume", "online"),
                "volume profile is never allowed with prod");
        allowed(env("uat", "demo", "oracle", "volume", "online"));
        assertThatCode(
                        () ->
                                new StartupGuards(
                                                env("uat", "demo", "edge", "online"),
                                                properties(secrets(false, KEY32)))
                                        .afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    /**
     * OD-141 D-screens (owner 2026-11-09, docs/db/INSTALL_KIT.md): the screen demo data set sits on
     * top of the demo data, on H2 or Oracle; never with volume, edge or production.
     */
    @Test
    void theScreensDataSetNeedsDemoAndIsNeverWithVolumeEdgeOrProduction() {
        for (MockEnvironment refused :
                List.of(
                        env("uat", "screens", "online"),
                        env("uat", "demo", "volume", "screens", "online"),
                        env("uat", "demo", "edge", "screens", "online"),
                        env("prod", "screens", "online"),
                        env("prod", "demo", "screens", "online"))) {
            assertThatThrownBy(
                            () ->
                                    new StartupGuards(refused, properties(secrets(false, KEY32)))
                                            .afterPropertiesSet())
                    .hasMessageContaining("profile");
        }
        refused(
                env("uat", "demo", "oracle", "edge", "screens", "online"),
                "screens profile needs demo and is never allowed with volume, edge or prod");
        allowed(env("uat", "demo", "screens", "online"));
        allowed(env("uat", "demo", "oracle", "screens", "online"));
        allowed(env("uat", "demo", "screens", "e2e-parallel", "online"));
    }

    /** Owner 2026-11-07: the worker clones of parallel E2E lanes never leave a demo H2 stack. */
    @Test
    void theWorkerClonesNeedDemoOnH2AndAreNeverWithVolumeOrProduction() {
        for (MockEnvironment refused :
                List.of(
                        env("uat", "e2e-parallel", "online"),
                        env("uat", "demo", "volume", "e2e-parallel", "online"),
                        env("uat", "demo", "oracle", "e2e-parallel", "online"),
                        env("prod", "demo", "e2e-parallel", "online"),
                        env("prod", "e2e-parallel", "online"))) {
            assertThatThrownBy(
                            () ->
                                    new StartupGuards(refused, properties(secrets(false, KEY32)))
                                            .afterPropertiesSet())
                    .hasMessageContaining("profile");
        }
        allowed(env("uat", "demo", "e2e-parallel", "online"));
    }

    @Test
    void loggedOtpCodesAreRefusedInProductionButAllowedOnALocalDemoUat() {
        MockEnvironment prodCodes =
                env("prod", "online").withProperty("fci.api.notification.log-codes", "true");
        assertThatThrownBy(
                        () ->
                                new StartupGuards(prodCodes, properties(secrets(false, KEY32)))
                                        .afterPropertiesSet())
                .hasMessageContaining("log-codes");
        MockEnvironment demoCodes =
                env("uat", "demo", "online").withProperty("fci.api.notification.log-codes", "true");
        assertThatCode(
                        () ->
                                new StartupGuards(demoCodes, properties(secrets(false, KEY32)))
                                        .afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @Test
    void productionNeedsAPersistentDatabaseWhateverTheH2UrlForm() {
        MockEnvironment oracle =
                env("prod", "online")
                        .withProperty("spring.datasource.url", "jdbc:oracle:thin:@db:1521/FCIPAY");
        assertThatCode(
                        () ->
                                new StartupGuards(oracle, properties(secrets(false, KEY32)))
                                        .afterPropertiesSet())
                .doesNotThrowAnyException();
        // An H2 server serving an in-memory database is no more persistent than the embedded one.
        MockEnvironment served =
                env("prod", "online")
                        .withProperty("spring.datasource.url", "jdbc:h2:tcp://localhost/mem:fci");
        assertThatThrownBy(
                        () ->
                                new StartupGuards(served, properties(secrets(false, KEY32)))
                                        .afterPropertiesSet())
                .hasMessageContaining("H2 database");
    }

    /**
     * The database of each environment (owner 2026-11-01; ADR-0019): DEV, UAT and production run on
     * the on-premises Oracle; H2 only for local test runs and Playwright; the cloud wallet (profile
     * oracle-adb) only on a local machine. A local stack marks itself with fci.local-stack (set by
     * deploy-local.ps1 only); production is never local.
     */
    @Test
    void serversRunOnOracleWhileH2AndTheCloudWalletStayOnLocalMachines() {
        String h2 = "jdbc:h2:tcp://127.0.0.1:9092/mem:fcilekha";
        refused(env("prod", "online").withProperty("spring.datasource.url", h2), "H2 database");
        refused(
                env("prod", "online")
                        .withProperty("spring.datasource.url", h2)
                        .withProperty("fci.local-stack", "true"),
                "H2 database");
        refused(env("uat", "online").withProperty("spring.datasource.url", h2), "H2 database");
        refused(
                env("uat", "demo", "batch").withProperty("spring.datasource.url", h2),
                "H2 database");
        refused(
                env("dev", "online").withProperty("spring.datasource.url", "jdbc:h2:mem:x"),
                "H2 database");
        refused(env("uat", "oracle", "oracle-adb", "online"), "oracle-adb");
        refused(env("dev", "oracle", "oracle-adb", "online"), "oracle-adb");
        refused(env("prod", "oracle", "oracle-adb", "online"), "oracle-adb");
        refused(
                env("prod", "online")
                        .withProperty("spring.datasource.url", "jdbc:postgresql://db/x"),
                "must be an Oracle database");
        refused(
                env("uat", "online").withProperty("spring.datasource.url", ""),
                "must be an Oracle database");

        allowed(
                env("uat", "demo", "online")
                        .withProperty("spring.datasource.url", h2)
                        .withProperty("fci.local-stack", "true"));
        allowed(
                env("uat", "demo", "oracle", "oracle-adb", "online")
                        .withProperty("fci.local-stack", "true"));
        allowed(env("local", "demo", "oracle", "oracle-adb", "online"));
        allowed(env("local", "demo", "online").withProperty("spring.datasource.url", h2));
        allowed(env("demo", "it", "online").withProperty("spring.datasource.url", "jdbc:h2:mem:x"));
        allowed(env("dev", "oracle", "online"));
        allowed(env("uat", "oracle", "batch"));
        allowed(env("prod", "oracle", "online"));
    }

    @Test
    void aBlankSecretCountsAsMissing() {
        assertThatThrownBy(
                        () ->
                                new StartupGuards(
                                                env("uat", "online"),
                                                properties(
                                                        new ApiProperties.Secrets(
                                                                "   ", KEY32, false)))
                                        .afterPropertiesSet())
                .hasMessageContaining("audit-hmac-key is required")
                .hasMessageNotContaining("data-encryption-key");
    }

    /** SRS-G-48 (OD-36): the sign-in OTP is never switched off in production or a real UAT. */
    @Test
    void SRS_G_48_theSignInOtpOffIsRefusedInProductionAndInARealUat() {
        ApiProperties off = properties(secrets(false, KEY32), ApiProperties.Verification.Y, false);
        for (String[] profiles : new String[][] {{"prod", "online"}, {"uat", "online"}}) {
            assertThatThrownBy(() -> new StartupGuards(env(profiles), off).afterPropertiesSet())
                    .as(String.join(",", profiles))
                    .hasMessageContaining("fci.api.otp.at-login must be true");
        }
        for (String[] profiles :
                new String[][] {{"local", "online"}, {"uat", "demo", "online"}, {"online"}}) {
            assertThatCode(() -> new StartupGuards(env(profiles), off).afterPropertiesSet())
                    .as(String.join(",", profiles))
                    .doesNotThrowAnyException();
        }
        assertThat(properties(secrets(false, KEY32)).otp().atLogin()).isTrue();
    }

    /** OD-146: the demo OTP code viewer only on a demo stack; never in production or a real UAT. */
    @Test
    void OD_146_theDemoOtpViewerSwitchIsRefusedOutsideADemoStack() {
        String sw = "fci.api.otp.demo-viewer";
        refused(env("prod", "online").withProperty(sw, "true"), sw);
        refused(env("uat", "online").withProperty(sw, "true"), sw);
        refused(env("dev", "online").withProperty(sw, "true"), sw);
        refused(env("local", "online").withProperty(sw, "true"), sw);
        allowed(env("uat", "demo", "online").withProperty(sw, "true"));
        allowed(env("local", "demo", "online").withProperty(sw, "true"));
        // Off, every profile starts.
        allowed(env("uat", "online").withProperty(sw, "false"));
    }
}
