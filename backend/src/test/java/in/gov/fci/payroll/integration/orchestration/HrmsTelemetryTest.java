package in.gov.fci.payroll.integration.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import in.gov.fci.payroll.integration.config.HrmsApiProperties.PoolSpec;
import in.gov.fci.payroll.integration.config.HrmsApiProperties.Resolved;
import in.gov.fci.payroll.integration.config.HrmsLogProperties;
import in.gov.fci.payroll.platform.logging.ExchangeLog;
import in.gov.fci.payroll.platform.logging.LogCapture;
import in.gov.fci.payroll.platform.logging.MaskingPolicy;
import in.gov.fci.payroll.platform.logging.MaskingProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * SRS-INT-6, R-INT, R-PII: the HRMS adapter over the common exchange log - what is logged follows
 * the call policy, masking cannot be switched off where unmasked logging is not allowed, the token
 * call never shows a body, and the counters carry the {@code fci.hrms.client} prefix.
 */
class HrmsTelemetryTest {

    private static final String LOGGER = "FCI_HRMS_EXCHANGE";
    private static final URI URI_OF = URI.create("https://hrms.example.invalid/request-ota?a=1");
    private static final String REQUEST = "{\"password\":\"hunter2-request\"}";
    private static final String RESPONSE = "{\"password\":\"hunter2-response\"}";
    private static final PoolSpec POOL = new PoolSpec("shared", 1, 1, 1L, 1L, 1L, 1L, false);

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private static MaskingPolicy policy() {
        return new MaskingPolicy(
                new MaskingProperties(
                        List.of(
                                new MaskingProperties.FieldRule(
                                        "password", MaskingProperties.Strategy.FULL, null)),
                        null));
    }

    private static Resolved resolved(boolean mask, boolean req, boolean res) {
        return new Resolved(1000, 1000, 1000, mask, req, res, POOL);
    }

    private HrmsTelemetry telemetry(boolean bodies, boolean allowUnmasked) {
        return new HrmsTelemetry(
                new HrmsLogProperties(bodies, null), policy(), registry, allowUnmasked);
    }

    private static Map<String, String> fields(ILoggingEvent event) {
        return event.getKeyValuePairs().stream()
                .collect(Collectors.toMap(kv -> kv.key, kv -> String.valueOf(kv.value)));
    }

    private Map<String, String> log(
            HrmsTelemetry telemetry, String api, int status, Resolved resolved) {
        try (LogCapture capture = LogCapture.of(LOGGER, Level.INFO)) {
            telemetry.exchange(
                    new HrmsTelemetry.Ctx("IF-1", api, 2),
                    HttpMethod.POST,
                    URI_OF,
                    15,
                    REQUEST,
                    new HrmsClient.Response(status, RESPONSE, 1),
                    null,
                    resolved);
            assertThat(capture.events()).hasSize(1);
            return fields(capture.events().get(0));
        }
    }

    @Test
    void SRS_INT_6_anAttemptIsLoggedWithItsInterfaceApiAttemptAndTimed() {
        Map<String, String> f =
                log(telemetry(true, true), "request_ota", 200, resolved(true, true, true));
        assertThat(f)
                .containsEntry("peer.system", "hrms")
                .containsEntry("peer.interface", "IF-1")
                .containsEntry("peer.operation", "request_ota")
                .containsEntry("peer.attempt", "2")
                .containsEntry("peer.outcome", "OK")
                .containsEntry("http.request.method", "POST")
                .containsEntry("http.response.status_code", "200");
        assertThat(
                        registry.get("fci.hrms.client.calls")
                                .tag("operation", "request_ota")
                                .tag("method", "POST")
                                .tag("status", "200")
                                .tag("outcome", "OK")
                                .timer()
                                .count())
                .isEqualTo(1);
    }

    @Test
    void R_PII_bodiesAreMaskedWhenTheCallSaysMask() {
        Map<String, String> f =
                log(telemetry(true, true), "request_ota", 200, resolved(true, true, true));
        assertThat(f.get("peer.request.body")).isNotNull().doesNotContain("hunter2");
        assertThat(f.get("peer.response.body")).isNotNull().doesNotContain("hunter2");
    }

    @Test
    void R_PII_bodiesAreShownAsTheyAreWhenMaskIsOffAndUnmaskedIsAllowed() {
        Map<String, String> f =
                log(telemetry(true, true), "request_ota", 200, resolved(false, true, true));
        assertThat(f.get("peer.request.body")).contains("hunter2-request");
        assertThat(f.get("peer.response.body")).contains("hunter2-response");
    }

    @Test
    void R_PII_maskIsOffOnlyWhenBothTheCallAndTheEnvironmentAllowIt() {
        Map<String, String> f =
                log(telemetry(true, false), "request_ota", 200, resolved(false, true, true));
        assertThat(f.get("peer.request.body")).isNotNull().doesNotContain("hunter2");
        assertThat(f.get("peer.response.body")).isNotNull().doesNotContain("hunter2");
        assertThat(f.get("url.query")).isNotNull();
    }

    @Test
    void SRS_INT_6_theRequestAndResponseBodiesFollowTheirOwnFlags() {
        HrmsTelemetry t = telemetry(true, true);
        Map<String, String> onlyRequest = log(t, "request_ota", 200, resolved(true, true, false));
        assertThat(onlyRequest)
                .containsKey("peer.request.body")
                .doesNotContainKey("peer.response.body");
        Map<String, String> onlyResponse = log(t, "request_ota", 200, resolved(true, false, true));
        assertThat(onlyResponse)
                .containsKey("peer.response.body")
                .doesNotContainKey("peer.request.body");
        Map<String, String> neither = log(t, "request_ota", 200, resolved(true, false, false));
        assertThat(neither)
                .doesNotContainKey("peer.request.body")
                .doesNotContainKey("peer.response.body");
    }

    @Test
    void SRS_INT_6_theLogSettingsMasterSwitchHidesBodiesWhateverTheCallSays() {
        Map<String, String> f =
                log(telemetry(false, true), "request_ota", 200, resolved(false, true, true));
        assertThat(f)
                .doesNotContainKey("peer.request.body")
                .doesNotContainKey("peer.response.body");
    }

    @Test
    void R_PII_theTokenCallNeverShowsABody() {
        Map<String, String> f =
                log(telemetry(true, true), "token", 200, resolved(false, true, true));
        assertThat(f)
                .containsEntry("peer.request.body", ExchangeLog.WITHHELD)
                .containsEntry("peer.response.body", ExchangeLog.WITHHELD);
    }

    @Test
    void SRS_INT_6_aFailureWithoutAnAnswerIsLoggedAsAWarningWithItsReason() {
        HrmsTelemetry t = telemetry(true, true);
        try (LogCapture capture = LogCapture.of(LOGGER, Level.INFO)) {
            t.exchange(
                    new HrmsTelemetry.Ctx("IF-1", "request_ota", 1),
                    HttpMethod.GET,
                    URI_OF,
                    30,
                    null,
                    null,
                    "TimeoutException",
                    resolved(true, true, true));
            ILoggingEvent event = capture.events().get(0);
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(fields(event))
                    .containsEntry("peer.outcome", "TIMEOUT")
                    .containsEntry("peer.failure", "TimeoutException")
                    .doesNotContainKey("http.response.status_code");
        }
        assertThat(
                        registry.get("fci.hrms.client.calls")
                                .tag("status", "none")
                                .tag("outcome", "TIMEOUT")
                                .timer()
                                .count())
                .isEqualTo(1);
    }

    @Test
    void R_INT_theRetryCounterIsTaggedWithTheApi() {
        HrmsTelemetry t = telemetry(true, true);
        t.retry("request_ota");
        t.retry("request_ota");
        t.retry("get_ota_status");
        assertThat(
                        registry.get("fci.hrms.client.retries")
                                .tag("api", "request_ota")
                                .counter()
                                .count())
                .isEqualTo(2.0);
        assertThat(
                        registry.get("fci.hrms.client.retries")
                                .tag("api", "get_ota_status")
                                .counter()
                                .count())
                .isEqualTo(1.0);
    }

    @Test
    void R_INT_theBulkheadRefusalCounterIsTaggedWithTheApi() {
        HrmsTelemetry t = telemetry(true, true);
        t.bulkheadRefused("request_ota");
        assertThat(
                        registry.get("fci.hrms.client.bulkhead.refused")
                                .tag("api", "request_ota")
                                .counter()
                                .count())
                .isEqualTo(1.0);
    }

    @Test
    void R_INT_theTokenCounterIsTaggedWithTheResult() {
        HrmsTelemetry t = telemetry(true, true);
        t.token("fetched");
        t.token("renewed");
        t.token("renewed");
        t.token("refused");
        assertThat(registry.get("fci.hrms.client.token").tag("result", "fetched").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get("fci.hrms.client.token").tag("result", "renewed").counter().count())
                .isEqualTo(2.0);
        assertThat(registry.get("fci.hrms.client.token").tag("result", "refused").counter().count())
                .isEqualTo(1.0);
    }
}
