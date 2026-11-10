package in.gov.fci.payroll.platform.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import in.gov.fci.payroll.platform.logging.ExchangeLog.Call;
import in.gov.fci.payroll.platform.logging.ExchangeLog.Options;
import in.gov.fci.payroll.platform.logging.MaskingProperties.FieldRule;
import in.gov.fci.payroll.platform.logging.MaskingProperties.PatternRule;
import in.gov.fci.payroll.platform.logging.MaskingProperties.Strategy;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.event.KeyValuePair;

/**
 * SRS-INT-6, R-INT, R-PII: the generic outbound-call telemetry. One event per attempt with the
 * documented keys, the level by outcome, the body switches, masking and metrics.
 */
class ExchangeLogTest {

    private static final String LOGGER = "FCI_TEST_EXCHANGE";
    private static final String PAN = "ABCPS1234K";
    private static final Call CALL = new Call("API-1", "getEmployee", 2);
    private static final Options ALL = new Options(true, true, true);
    private static final URI URL = URI.create("http://hrms.fci-demo.test/api/emp");

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private static MaskingPolicy policy() {
        return new MaskingPolicy(
                new MaskingProperties(
                        List.of(new FieldRule("pan", Strategy.KEEP_LAST, 4)),
                        List.of(
                                new PatternRule(
                                        "(?<![0-9])[6-9][0-9]{9}(?![0-9])",
                                        Strategy.KEEP_LAST, 3))));
    }

    private static MaskingPolicy noPolicy() {
        return new MaskingPolicy(new MaskingProperties(null, null));
    }

    private ExchangeLog log(boolean bodies, int maxBody) {
        return new ExchangeLog(
                LOGGER,
                "peer",
                "test.client",
                bodies,
                maxBody,
                policy(),
                registry,
                Set.of("login"));
    }

    private ExchangeLog log() {
        return log(true, 1000);
    }

    private static Map<String, String> kv(ILoggingEvent event) {
        Map<String, String> map = new HashMap<>();
        if (event.getKeyValuePairs() != null) {
            for (KeyValuePair pair : event.getKeyValuePairs()) {
                map.put(pair.key, pair.value == null ? null : pair.value.toString());
            }
        }
        return map;
    }

    private Timer timer(String operation, String method, String status, String outcome) {
        return registry.get("test.client.calls")
                .tag("operation", operation)
                .tag("method", method)
                .tag("status", status)
                .tag("outcome", outcome)
                .timer();
    }

    private ILoggingEvent single(LogCapture capture) {
        assertThat(capture.events()).hasSize(1);
        return capture.events().getFirst();
    }

    // ------------------------------------------------------------------ outcome

    @Test
    void SRS_INT_6_outcomeIsOkBelow400AndWithoutFailure() {
        assertThat(ExchangeLog.outcome(null, null)).isEqualTo("OK");
        assertThat(ExchangeLog.outcome(200, null)).isEqualTo("OK");
        assertThat(ExchangeLog.outcome(399, null)).isEqualTo("OK");
    }

    @Test
    void SRS_INT_6_outcomeIsClientErrorFrom400WithoutFailure() {
        assertThat(ExchangeLog.outcome(400, null)).isEqualTo("CLIENT_ERROR");
        assertThat(ExchangeLog.outcome(404, null)).isEqualTo("CLIENT_ERROR");
        // as built: a 5xx reported without a failure text is not a SERVER_ERROR
        assertThat(ExchangeLog.outcome(500, null)).isEqualTo("CLIENT_ERROR");
    }

    @Test
    void SRS_INT_6_outcomeWithAFailureAndAStatusIsServerError() {
        assertThat(ExchangeLog.outcome(500, "HTTP 500")).isEqualTo("SERVER_ERROR");
        assertThat(ExchangeLog.outcome(200, "bad body")).isEqualTo("SERVER_ERROR");
        assertThat(ExchangeLog.outcome(500, "Timeout")).isEqualTo("SERVER_ERROR");
    }

    @Test
    void SRS_INT_6_outcomeWithAFailureAndNoStatusIsTimeoutOrIoError() {
        assertThat(ExchangeLog.outcome(null, "Timeout")).isEqualTo("TIMEOUT");
        assertThat(ExchangeLog.outcome(null, "java.net.SocketTimeoutException: Read Timeout"))
                .isEqualTo("TIMEOUT");
        assertThat(ExchangeLog.outcome(null, "timeout")).isEqualTo("IO_ERROR");
        assertThat(ExchangeLog.outcome(null, "Connection reset")).isEqualTo("IO_ERROR");
        assertThat(ExchangeLog.outcome(null, "")).isEqualTo("IO_ERROR");
    }

    // ------------------------------------------------------------------ the event

    @Test
    void SRS_INT_6_anOkAttemptWritesOneInfoEventWithEveryKey() {
        String request = "{\"n\":\"é\"}";
        String response = "{\"pan\":\"" + PAN + "\"}";
        try (LogCapture capture = LogCapture.of(LOGGER, Level.INFO)) {
            log().exchange(
                            CALL,
                            "GET",
                            URI.create("http://hrms.fci-demo.test/api/emp?pan=" + PAN + "&n=1"),
                            12L,
                            request,
                            200,
                            response,
                            null,
                            ALL);
            ILoggingEvent event = single(capture);
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(event.getFormattedMessage())
                    .isEqualTo("PEER GET /api/emp -> 200 in 12 ms (attempt 2, OK)");
            Map<String, String> expected = new HashMap<>();
            expected.put("peer.system", "peer");
            expected.put("peer.interface", "API-1");
            expected.put("peer.operation", "getEmployee");
            expected.put("peer.attempt", "2");
            expected.put("peer.outcome", "OK");
            expected.put("http.request.method", "GET");
            expected.put("url.path", "/api/emp");
            expected.put("url.query", "pan=******234K&n=1");
            expected.put("http.response.status_code", "200");
            expected.put("peer.duration_ms", "12");
            // the e-acute is two bytes in UTF-8: 9 characters, 10 bytes
            expected.put("http.request.body.bytes", "10");
            expected.put("http.response.body.bytes", "20");
            expected.put("peer.request.body", "{\"n\":\"é\"}");
            expected.put("peer.response.body", "{\"pan\":\"******234K\"}");
            assertThat(kv(event)).containsExactlyInAnyOrderEntriesOf(expected);
        }
    }

    @Test
    void SRS_INT_6_aClientErrorIsInfoWithoutAFailureKey() {
        try (LogCapture capture = LogCapture.of(LOGGER, Level.INFO)) {
            log().exchange(CALL, "GET", URL, 5L, null, 404, "nope", null, ALL);
            ILoggingEvent event = single(capture);
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(event.getFormattedMessage())
                    .isEqualTo("PEER GET /api/emp -> 404 in 5 ms (attempt 2, CLIENT_ERROR)");
            assertThat(kv(event))
                    .containsEntry("peer.outcome", "CLIENT_ERROR")
                    .containsEntry("http.response.status_code", "404")
                    .containsEntry("http.request.body.bytes", "0")
                    .containsEntry("http.response.body.bytes", "4")
                    .doesNotContainKey("peer.failure");
        }
    }

    @Test
    void SRS_INT_6_aServerErrorIsWarnWithStatusAndFailure() {
        try (LogCapture capture = LogCapture.of(LOGGER, Level.INFO)) {
            log().exchange(CALL, "POST", URL, 30L, "{}", 500, "boom", "HTTP 500", ALL);
            ILoggingEvent event = single(capture);
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage())
                    .isEqualTo("PEER POST /api/emp -> 500 in 30 ms (attempt 2, SERVER_ERROR)");
            assertThat(kv(event))
                    .containsEntry("peer.outcome", "SERVER_ERROR")
                    .containsEntry("http.request.method", "POST")
                    .containsEntry("http.response.status_code", "500")
                    .containsEntry("http.response.body.bytes", "4")
                    .containsEntry("peer.failure", "HTTP 500");
        }
    }

    @Test
    void SRS_INT_6_aTimeoutIsWarnWithoutAnyStatusKey() {
        try (LogCapture capture = LogCapture.of(LOGGER, Level.INFO)) {
            log().exchange(CALL, "GET", URL, 8000L, "{}", null, null, "ReadTimeoutException", ALL);
            ILoggingEvent event = single(capture);
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage())
                    .isEqualTo("PEER GET /api/emp -> no answer in 8000 ms (attempt 2, TIMEOUT)");
            assertThat(kv(event))
                    .containsEntry("peer.outcome", "TIMEOUT")
                    .containsEntry("peer.failure", "ReadTimeoutException")
                    .containsEntry("http.request.body.bytes", "2")
                    .doesNotContainKeys("http.response.status_code", "http.response.body.bytes");
        }
    }

    @Test
    void SRS_INT_6_anIoErrorIsWarn() {
        try (LogCapture capture = LogCapture.of(LOGGER, Level.INFO)) {
            log().exchange(CALL, "GET", URL, 3L, null, null, null, "Connection reset", ALL);
            ILoggingEvent event = single(capture);
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(kv(event)).containsEntry("peer.outcome", "IO_ERROR");
            assertThat(event.getFormattedMessage())
                    .isEqualTo("PEER GET /api/emp -> no answer in 3 ms (attempt 2, IO_ERROR)");
        }
    }

    @Test
    void SRS_INT_6_aQueryThatIsAbsentGivesNoQueryKey() {
        try (LogCapture capture = LogCapture.of(LOGGER, Level.INFO)) {
            log().exchange(CALL, "GET", URL, 1L, null, 200, null, null, ALL);
            assertThat(kv(single(capture))).doesNotContainKey("url.query");
        }
    }

    @Test
    void R_PII_aQueryIsMaskedByParameterNameAndWithMaskOffShownAsIs() {
        URI uri = URI.create("http://hrms.fci-demo.test/api/emp?pan=" + PAN);
        try (LogCapture capture = LogCapture.of(LOGGER, Level.INFO)) {
            log().exchange(CALL, "GET", uri, 1L, null, 200, null, null, ALL);
            log().exchange(
                            CALL,
                            "GET",
                            uri,
                            1L,
                            null,
                            200,
                            null,
                            null,
                            new Options(true, true, false));
            assertThat(kv(capture.events().get(0))).containsEntry("url.query", "pan=******234K");
            assertThat(kv(capture.events().get(1))).containsEntry("url.query", "pan=" + PAN);
        }
    }

    @Test
    void R_PII_aQueryWithNoPolicyIsWithheld() {
        ExchangeLog bare =
                new ExchangeLog(
                        LOGGER, "peer", "test.client", true, 1000, noPolicy(), registry, Set.of());
        try (LogCapture capture = LogCapture.of(LOGGER, Level.INFO)) {
            bare.exchange(
                    CALL,
                    "GET",
                    URI.create("http://h/p?pan=" + PAN),
                    1L,
                    null,
                    200,
                    null,
                    null,
                    ALL);
            assertThat(kv(single(capture))).containsEntry("url.query", ExchangeLog.NO_POLICY);
        }
    }

    @Test
    void SRS_INT_6_theQueryAndPathAreCutAtTheirLimits() {
        ExchangeLog exchange = log();
        assertThat(exchange.query("a=" + "x".repeat(298), false))
                .hasSize(300)
                .doesNotEndWith("...");
        assertThat(exchange.query("a=" + "x".repeat(299), false))
                .isEqualTo("a=" + "x".repeat(298) + "...");
        // masking first, then the cut
        assertThat(exchange.query("q=" + "y".repeat(400), true))
                .isEqualTo("q=" + "y".repeat(298) + "...");
        String exactPath = "/" + "a".repeat(199);
        String longPath = "/" + "a".repeat(200);
        try (LogCapture capture = LogCapture.of(LOGGER, Level.INFO)) {
            exchange.exchange(
                    CALL,
                    "GET",
                    URI.create("http://h" + exactPath),
                    1L,
                    null,
                    200,
                    null,
                    null,
                    ALL);
            exchange.exchange(
                    CALL, "GET", URI.create("http://h" + longPath), 1L, null, 200, null, null, ALL);
            assertThat(kv(capture.events().get(0))).containsEntry("url.path", exactPath);
            assertThat(kv(capture.events().get(1)))
                    .containsEntry("url.path", longPath.substring(0, 200) + "...");
            assertThat(capture.events().get(1).getFormattedMessage())
                    .contains(" " + longPath.substring(0, 200) + "... -> 200");
        }
    }

    @Test
    void SRS_INT_6_controlCharactersInQueryPathFailureAndBodyAreEncoded() {
        ExchangeLog exchange = log();
        assertThat(exchange.query("a=\n\r\tb", false)).isEqualTo("a=\\n\\r\\tb");
        try (LogCapture capture = LogCapture.of(LOGGER, Level.INFO)) {
            exchange.exchange(
                    CALL,
                    "GET",
                    URI.create("http://h/a%0Ab"),
                    1L,
                    "x\ny",
                    null,
                    null,
                    "line1\r\nline2\tz",
                    ALL);
            ILoggingEvent event = single(capture);
            assertThat(kv(event))
                    .containsEntry("url.path", "/a\\nb")
                    .containsEntry("peer.failure", "line1\\r\\nline2\\tz")
                    .containsEntry("peer.request.body", "x\\ny");
            assertThat(event.getFormattedMessage()).contains("/a\\nb");
        }
    }

    @Test
    void SRS_INT_6_aLongFailureIsCutAtTwoHundredCharacters() {
        try (LogCapture capture = LogCapture.of(LOGGER, Level.INFO)) {
            log().exchange(CALL, "GET", URL, 1L, null, null, null, "e".repeat(200), ALL);
            log().exchange(CALL, "GET", URL, 1L, null, null, null, "e".repeat(201), ALL);
            assertThat(kv(capture.events().get(0))).containsEntry("peer.failure", "e".repeat(200));
            assertThat(kv(capture.events().get(1)))
                    .containsEntry("peer.failure", "e".repeat(200) + "...");
        }
    }

    @Test
    void SRS_INT_6_requestBodyBytesAreCountedInUtf8() {
        try (LogCapture capture = LogCapture.of(LOGGER, Level.INFO)) {
            log().exchange(CALL, "POST", URL, 1L, "ééa", 200, "€", null, ALL);
            assertThat(kv(single(capture)))
                    .containsEntry("http.request.body.bytes", "5")
                    .containsEntry("http.response.body.bytes", "3");
        }
    }

    // ------------------------------------------------------------------ bodies

    private static final String REQ = "{\"pan\":\"" + PAN + "\"}";
    private static final String RES = "call 9876543210";

    @Test
    void R_PII_theMasterSwitchOffLogsNoBodyWhateverTheOptions() {
        try (LogCapture capture = LogCapture.of(LOGGER, Level.INFO)) {
            log(false, 1000).exchange(CALL, "POST", URL, 1L, REQ, 200, RES, null, ALL);
            assertThat(kv(single(capture)))
                    .doesNotContainKeys("peer.request.body", "peer.response.body")
                    .containsKey("http.request.body.bytes");
        }
    }

    @Test
    void R_PII_theOptionsChooseWhichBodiesAreLogged() {
        try (LogCapture capture = LogCapture.of(LOGGER, Level.INFO)) {
            ExchangeLog exchange = log();
            exchange.exchange(
                    CALL, "POST", URL, 1L, REQ, 200, RES, null, new Options(true, false, true));
            exchange.exchange(
                    CALL, "POST", URL, 1L, REQ, 200, RES, null, new Options(false, true, true));
            exchange.exchange(
                    CALL, "POST", URL, 1L, REQ, 200, RES, null, new Options(true, true, true));
            exchange.exchange(
                    CALL, "POST", URL, 1L, REQ, 200, RES, null, new Options(false, false, true));
            List<ILoggingEvent> events = capture.events();
            assertThat(events).hasSize(4);
            assertThat(kv(events.get(0)))
                    .containsEntry("peer.request.body", "{\"pan\":\"******234K\"}")
                    .doesNotContainKey("peer.response.body");
            assertThat(kv(events.get(1)))
                    .containsEntry("peer.response.body", "call *******210")
                    .doesNotContainKey("peer.request.body");
            assertThat(kv(events.get(2)))
                    .containsEntry("peer.request.body", "{\"pan\":\"******234K\"}")
                    .containsEntry("peer.response.body", "call *******210");
            assertThat(kv(events.get(3)))
                    .doesNotContainKeys("peer.request.body", "peer.response.body");
        }
    }

    @Test
    void R_PII_withMaskOffTheBodyIsShownAsIsStillCutAndEncoded() {
        try (LogCapture capture = LogCapture.of(LOGGER, Level.INFO)) {
            ExchangeLog exchange = log(true, 20);
            exchange.exchange(
                    CALL,
                    "POST",
                    URL,
                    1L,
                    "{\n \"pan\":\"" + PAN + "\"}",
                    200,
                    "0123456789012345678901234",
                    null,
                    new Options(true, true, false));
            assertThat(kv(single(capture)))
                    .containsEntry("peer.request.body", "{\\n \"pan\":\"ABCPS1234K...")
                    .containsEntry("peer.response.body", "01234567890123456789...");
        }
    }

    @Test
    void R_PII_aBodyOfExactlyTheMaximumIsNotCutAndOneMoreIs() {
        ExchangeLog exchange = log(true, 10);
        assertThat(exchange.body("0123456789", false, false)).isEqualTo("0123456789");
        assertThat(exchange.body("01234567890", false, false)).isEqualTo("0123456789...");
        // masking first, then the cut: the masked JSON {"pan":"******234K"} is 20 characters
        assertThat(exchange.body(REQ, false, true)).isEqualTo("{\"pan\":\"**...");
    }

    @Test
    void R_PII_aMaskedBodyIsEncodedAfterMasking() {
        assertThat(log().body("line1\nline2 9876543210", false, true))
                .isEqualTo("line1\\nline2 *******210");
    }

    @Test
    void R_PII_withMaskOnAndNoPolicyTheBodyIsWithheld() {
        ExchangeLog bare =
                new ExchangeLog(
                        LOGGER, "peer", "test.client", true, 1000, noPolicy(), registry, Set.of());
        assertThat(bare.body(REQ, false, true)).isEqualTo(ExchangeLog.NO_POLICY);
        assertThat(bare.body(REQ, false, false)).isEqualTo(REQ);
        assertThat(bare.query("a=1", true)).isEqualTo(ExchangeLog.NO_POLICY);
        assertThat(bare.query("a=1", false)).isEqualTo("a=1");
        assertThat(ExchangeLog.NO_POLICY).isEqualTo("[withheld: no masking rules configured]");
        assertThat(ExchangeLog.WITHHELD).isEqualTo("[withheld]");
        try (LogCapture capture = LogCapture.of(LOGGER, Level.INFO)) {
            bare.exchange(CALL, "POST", URL, 1L, REQ, 200, RES, null, ALL);
            assertThat(kv(single(capture)))
                    .containsEntry("peer.request.body", ExchangeLog.NO_POLICY)
                    .containsEntry("peer.response.body", ExchangeLog.NO_POLICY);
        }
    }

    @Test
    void R_PII_aWithheldOperationNeverShowsABodyWhateverTheOptions() {
        Call login = new Call("API-0", "login", 1);
        try (LogCapture capture = LogCapture.of(LOGGER, Level.INFO)) {
            ExchangeLog exchange = log();
            exchange.exchange(login, "POST", URL, 1L, REQ, 200, RES, null, ALL);
            exchange.exchange(
                    login, "POST", URL, 1L, REQ, 200, RES, null, new Options(true, true, false));
            for (ILoggingEvent event : capture.events()) {
                assertThat(kv(event))
                        .containsEntry("peer.request.body", ExchangeLog.WITHHELD)
                        .containsEntry("peer.response.body", ExchangeLog.WITHHELD);
            }
            assertThat(capture.events()).hasSize(2);
        }
    }

    @Test
    void R_PII_aWithheldOperationStillHasNullForAnEmptyBodyAndOthersAreNotWithheld() {
        ExchangeLog exchange = log();
        assertThat(exchange.body(null, true, true)).isNull();
        assertThat(exchange.body("", true, true)).isNull();
        assertThat(exchange.body("x", true, true)).isEqualTo(ExchangeLog.WITHHELD);
        assertThat(exchange.body("x", true, false)).isEqualTo(ExchangeLog.WITHHELD);
        assertThat(exchange.body("x", false, true)).isEqualTo("x");
        try (LogCapture capture = LogCapture.of(LOGGER, Level.INFO)) {
            exchange.exchange(CALL, "GET", URL, 1L, REQ, 200, RES, null, ALL);
            assertThat(kv(single(capture)).get("peer.request.body"))
                    .isNotEqualTo(ExchangeLog.WITHHELD);
        }
    }

    @Test
    void R_PII_aNullOrEmptyBodyLogsANullValue() {
        try (LogCapture capture = LogCapture.of(LOGGER, Level.INFO)) {
            log().exchange(CALL, "GET", URL, 1L, null, 200, "", null, ALL);
            Map<String, String> map = kv(single(capture));
            assertThat(map).containsKeys("peer.request.body", "peer.response.body");
            assertThat(map.get("peer.request.body")).isNull();
            assertThat(map.get("peer.response.body")).isNull();
        }
        assertThat(log().body(null, false, false)).isNull();
        assertThat(log().body("", false, false)).isNull();
        assertThat(log().body(null, false, true)).isNull();
    }

    // ------------------------------------------------------------------ levels and metrics

    @Test
    void SRS_INT_6_theTimerIsRecordedForEveryAttemptWithItsTags() {
        ExchangeLog exchange = log();
        exchange.exchange(CALL, "GET", URL, 12L, null, 200, null, null, ALL);
        exchange.exchange(CALL, "GET", URL, 8L, null, 200, null, null, ALL);
        exchange.exchange(CALL, "GET", URL, 5L, null, 404, null, null, ALL);
        exchange.exchange(CALL, "POST", URL, 7L, null, 500, null, "HTTP 500", ALL);
        exchange.exchange(CALL, "GET", URL, 9L, null, null, null, "Timeout", ALL);
        exchange.exchange(CALL, "GET", URL, 4L, null, null, null, "reset", ALL);

        Timer ok = timer("getEmployee", "GET", "200", "OK");
        assertThat(ok.count()).isEqualTo(2);
        assertThat(ok.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(20.0);
        assertThat(timer("getEmployee", "GET", "404", "CLIENT_ERROR").count()).isEqualTo(1);
        assertThat(timer("getEmployee", "POST", "500", "SERVER_ERROR").count()).isEqualTo(1);
        Timer timeout = timer("getEmployee", "GET", "none", "TIMEOUT");
        assertThat(timeout.count()).isEqualTo(1);
        assertThat(timeout.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(9.0);
        assertThat(timer("getEmployee", "GET", "none", "IO_ERROR").count()).isEqualTo(1);
        assertThat(registry.find("test.client.calls").timers()).hasSize(5);
    }

    @Test
    void SRS_INT_6_theTimerIsRecordedAndNothingIsWrittenWhenTheLoggerIsOff() {
        try (LogCapture capture = LogCapture.of(LOGGER, Level.OFF)) {
            ExchangeLog exchange = log();
            exchange.exchange(CALL, "GET", URL, 12L, null, 200, null, null, ALL);
            exchange.exchange(CALL, "GET", URL, 9L, null, null, null, "Timeout", ALL);
            assertThat(capture.events()).isEmpty();
        }
        assertThat(timer("getEmployee", "GET", "200", "OK").count()).isEqualTo(1);
        assertThat(timer("getEmployee", "GET", "none", "TIMEOUT").count()).isEqualTo(1);
    }

    @Test
    void SRS_INT_6_aWarnOnlyLoggerSuppressesInfoEventsButKeepsWarnEvents() {
        try (LogCapture capture = LogCapture.of(LOGGER, Level.WARN)) {
            ExchangeLog exchange = log();
            exchange.exchange(CALL, "GET", URL, 1L, null, 200, null, null, ALL);
            exchange.exchange(CALL, "GET", URL, 1L, null, 404, null, null, ALL);
            exchange.exchange(CALL, "GET", URL, 1L, null, 500, null, "HTTP 500", ALL);
            assertThat(capture.events()).hasSize(1);
            assertThat(kv(capture.events().getFirst()))
                    .containsEntry("peer.outcome", "SERVER_ERROR");
        }
        assertThat(registry.find("test.client.calls").timers()).hasSize(3);
    }

    @Test
    void SRS_INT_6_anErrorOnlyLoggerSuppressesEveryEventButCountsTheCalls() {
        try (LogCapture capture = LogCapture.of(LOGGER, Level.ERROR)) {
            log().exchange(CALL, "GET", URL, 1L, null, 500, null, "HTTP 500", ALL);
            assertThat(capture.events()).isEmpty();
        }
        assertThat(timer("getEmployee", "GET", "500", "SERVER_ERROR").count()).isEqualTo(1);
    }

    @Test
    void SRS_INT_6_countIncrementsTheTaggedCounterAndLogsAnInfoEvent() {
        try (LogCapture capture = LogCapture.of(LOGGER, Level.INFO)) {
            ExchangeLog exchange = log();
            exchange.count("retry", "reason", "timeout");
            ILoggingEvent event = single(capture);
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(event.getFormattedMessage()).isEqualTo("PEER retry timeout");
            assertThat(kv(event))
                    .containsExactlyInAnyOrderEntriesOf(
                            Map.of("peer.system", "peer", "peer.retry", "timeout"));
            assertThat(registry.get("test.client.retry").tag("reason", "timeout").counter().count())
                    .isEqualTo(1.0);
            exchange.count("retry", "reason", "timeout");
            exchange.count("retry", "reason", "reset");
            assertThat(registry.get("test.client.retry").tag("reason", "timeout").counter().count())
                    .isEqualTo(2.0);
            assertThat(registry.get("test.client.retry").tag("reason", "reset").counter().count())
                    .isEqualTo(1.0);
            assertThat(capture.events()).hasSize(3);
        }
    }

    @Test
    void SRS_INT_6_countStillCountsWhenTheLoggerIsOff() {
        try (LogCapture capture = LogCapture.of(LOGGER, Level.OFF)) {
            log().count("token", "state", "refreshed");
            assertThat(capture.events()).isEmpty();
        }
        assertThat(registry.get("test.client.token").tag("state", "refreshed").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void SRS_INT_6_theLoggerNameComesFromTheConstructor() {
        assertThat(LoggerFactory.getLogger(LOGGER).getName()).isEqualTo("FCI_TEST_EXCHANGE");
        try (LogCapture capture = LogCapture.of("FCI_OTHER_EXCHANGE", Level.INFO)) {
            log().exchange(CALL, "GET", URL, 1L, null, 200, null, null, ALL);
            assertThat(capture.events()).isEmpty();
        }
    }
}
