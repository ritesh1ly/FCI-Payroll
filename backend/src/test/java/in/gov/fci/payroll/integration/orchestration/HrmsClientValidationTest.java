package in.gov.fci.payroll.integration.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import in.gov.fci.payroll.integration.config.HrmsApiProperties;
import in.gov.fci.payroll.integration.config.HrmsApiProperties.ApiUrl;
import in.gov.fci.payroll.integration.config.HrmsApiProperties.Call;
import in.gov.fci.payroll.integration.config.HrmsApiProperties.Pool;
import in.gov.fci.payroll.platform.logging.LogCapture;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * SRS-INT-6, SRS-INT-16, R-INT, R-PII: the start-up checks of the HRMS client settings, the rule
 * for where unmasked logging is allowed, and how a refused bearer token is told from a refused
 * api-key.
 */
class HrmsClientValidationTest {

    private static HrmsApiProperties apis(String code, Call call) {
        return new HrmsApiProperties(null, Map.of(code, new ApiUrl("p", null, call)));
    }

    private static Call timeouts(Integer connect, Integer read, Integer timeout) {
        return new Call(connect, read, timeout, null, null, null, null);
    }

    // ---- validate ----

    @Test
    void R_INT_validSettingsPassWithoutAWarning() {
        try (LogCapture capture = LogCapture.of(HrmsClient.class, Level.WARN)) {
            assertThatCode(() -> HrmsClient.validate(apis("request_ota", timeouts(1, 2, 3))))
                    .doesNotThrowAnyException();
            assertThatCode(() -> HrmsClient.validate(new HrmsApiProperties(null, null)))
                    .doesNotThrowAnyException();
            assertThat(capture.events()).isEmpty();
        }
    }

    @Test
    void R_INT_aMisspeltApiCodeStopsTheStartAndIsNamed() {
        assertThatThrownBy(() -> HrmsClient.validate(apis("not_an_api", null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(
                        "invalid fci.integration.hrms settings: api.not_an_api is not an HRMS API"
                                + " code");
    }

    @Test
    void R_INT_everyErrorIsListedInTheMessage() {
        HrmsApiProperties props =
                new HrmsApiProperties(
                        null,
                        Map.of(
                                "bogus_one",
                                new ApiUrl("p", null, timeouts(0, null, null)),
                                "request_ota",
                                new ApiUrl(
                                        "p",
                                        null,
                                        new Call(
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                new Pool(
                                                        null, null, null, null, null, null,
                                                        "RANDOM")))));
        assertThatThrownBy(() -> HrmsClient.validate(props))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("invalid fci.integration.hrms settings: ")
                .hasMessageContaining("api.bogus_one is not an HRMS API code")
                .hasMessageContaining("api.bogus_one.call.connect-timeout-ms must be 1 or more")
                .hasMessageContaining("pool.leasing must be FIFO or LIFO, not 'RANDOM'")
                .hasMessageContaining("; ");
    }

    @Test
    void R_INT_aSuspiciousSettingOnlyWarnsAndTheStartGoesOn() {
        try (LogCapture capture = LogCapture.of(HrmsClient.class, Level.WARN)) {
            HrmsClient.validate(apis("request_ota", timeouts(500, null, 400)));
            assertThat(capture.messages())
                    .containsExactly(
                            "fci.integration.hrms: api.request_ota.call: connect-timeout-ms"
                                    + " is longer than timeout-ms and can never be reached");
            assertThat(capture.events().get(0).getLevel()).isEqualTo(Level.WARN);
        }
    }

    // ---- unmaskedAllowed ----

    private static MockEnvironment env(String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        return environment;
    }

    @Test
    void R_PII_unmaskedLoggingIsAllowedOnDevLocalAndDemo() {
        assertThat(HrmsClient.unmaskedAllowed(env("dev"))).isTrue();
        assertThat(HrmsClient.unmaskedAllowed(env("local"))).isTrue();
        assertThat(HrmsClient.unmaskedAllowed(env("demo"))).isTrue();
        assertThat(HrmsClient.unmaskedAllowed(env("local", "demo"))).isTrue();
    }

    @Test
    void R_PII_unmaskedLoggingIsRefusedOnProdUatAndWithNoProfile() {
        assertThat(HrmsClient.unmaskedAllowed(env("prod"))).isFalse();
        assertThat(HrmsClient.unmaskedAllowed(env("uat"))).isFalse();
        assertThat(HrmsClient.unmaskedAllowed(env())).isFalse();
    }

    @Test
    void R_PII_prodWinsOverDevWhenBothAreActive() {
        assertThat(HrmsClient.unmaskedAllowed(env("dev", "prod"))).isFalse();
        assertThat(HrmsClient.unmaskedAllowed(env("prod", "demo"))).isFalse();
    }

    // ---- tokenRefused ----

    private static HrmsClient.Response answer(int status, String body) {
        return new HrmsClient.Response(status, body, 1);
    }

    private static String error(String code) {
        return "{\"status\":\"error\",\"error_code\":\"" + code + "\"}";
    }

    @Test
    void SRS_INT_4_a401IsATokenRefusalWhateverTheBody() {
        assertThat(HrmsClient.tokenRefused(answer(401, ""))).isTrue();
        assertThat(HrmsClient.tokenRefused(answer(401, null))).isTrue();
        assertThat(HrmsClient.tokenRefused(answer(401, "not json"))).isTrue();
        assertThat(HrmsClient.tokenRefused(answer(401, "{}"))).isTrue();
        assertThat(HrmsClient.tokenRefused(answer(401, error("TOKEN_EXPIRED")))).isTrue();
        assertThat(HrmsClient.tokenRefused(answer(401, error("SOMETHING_ELSE")))).isTrue();
    }

    @Test
    void SRS_INT_4_aRefusedApiKeyIsNotATokenRefusal() {
        assertThat(HrmsClient.tokenRefused(answer(401, error("INVALID_API_KEY")))).isFalse();
        assertThat(HrmsClient.tokenRefused(answer(401, error("API_KEY_MISSING")))).isFalse();
        assertThat(HrmsClient.tokenRefused(answer(403, error("API_KEY_DISABLED")))).isFalse();
    }

    @Test
    void SRS_INT_4_a403IsATokenRefusalOnlyWithATokenErrorCode() {
        assertThat(HrmsClient.tokenRefused(answer(403, error("TOKEN_EXPIRED")))).isTrue();
        assertThat(HrmsClient.tokenRefused(answer(403, error("TOKEN_REVOKED")))).isTrue();
        assertThat(HrmsClient.tokenRefused(answer(403, error("USER_INACTIVE")))).isFalse();
        assertThat(HrmsClient.tokenRefused(answer(403, error("NOT_A_TOKEN_CODE")))).isFalse();
        assertThat(HrmsClient.tokenRefused(answer(403, "{}"))).isFalse();
        assertThat(HrmsClient.tokenRefused(answer(403, "not json"))).isFalse();
        assertThat(HrmsClient.tokenRefused(answer(403, ""))).isFalse();
        assertThat(HrmsClient.tokenRefused(answer(403, null))).isFalse();
    }

    @Test
    void SRS_INT_4_otherStatusesAreNeverATokenRefusal() {
        for (int status : new int[] {200, 201, 400, 404, 409, 422, 500, 503}) {
            assertThat(HrmsClient.tokenRefused(answer(status, error("TOKEN_EXPIRED"))))
                    .as(String.valueOf(status))
                    .isFalse();
            assertThat(HrmsClient.tokenRefused(answer(status, ""))).isFalse();
        }
    }
}
