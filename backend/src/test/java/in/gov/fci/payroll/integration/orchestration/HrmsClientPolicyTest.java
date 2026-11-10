package in.gov.fci.payroll.integration.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import in.gov.fci.payroll.integration.api.HrmsApi;
import in.gov.fci.payroll.integration.api.InterfaceIntake;
import in.gov.fci.payroll.integration.api.InterfaceSettings.Settings;
import in.gov.fci.payroll.integration.business.HrmsContractCatalog;
import in.gov.fci.payroll.integration.config.HrmsApiProperties;
import in.gov.fci.payroll.integration.config.HrmsApiProperties.ApiUrl;
import in.gov.fci.payroll.integration.config.HrmsApiProperties.Call;
import in.gov.fci.payroll.integration.config.HrmsApiProperties.Limit;
import in.gov.fci.payroll.integration.config.HrmsApiProperties.Pool;
import in.gov.fci.payroll.integration.config.HrmsClientProperties;
import in.gov.fci.payroll.integration.config.HrmsCredentialsProperties;
import in.gov.fci.payroll.integration.config.HrmsLogProperties;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.LogCapture;
import in.gov.fci.payroll.platform.logging.MaskingPolicy;
import in.gov.fci.payroll.platform.logging.MaskingProperties;
import in.gov.fci.payroll.platform.logging.MaskingProperties.FieldRule;
import in.gov.fci.payroll.platform.logging.MaskingProperties.Strategy;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * SRS-INT-4, SRS-INT-6, R-INT, R-PII: the per-API behaviour of the HRMS client - the call policy
 * (own timeouts over the defaults over the interface limit), the per-API connection pools and their
 * isolation, the robustness of the token renewal (which refusals renew, how often, a storm of
 * callers meeting one expired token, an expiry before the call), and the telemetry (timer and
 * counters, the structured exchange events, masking of the bodies, the withheld login). Against a
 * JDK HTTP server.
 */
class HrmsClientPolicyTest {

    private static final String KEY = "test-only-api-key-0003";
    private static final String PASSWORD = "pw-secret-0003";
    private static final HrmsApi STATUS = HrmsApi.GET_OTA_STATUS;
    private static final HrmsApi PUSH = HrmsApi.GET_MEDICAL_CLAIM_STATUS;
    private static final HrmsApi OTA = HrmsApi.REQUEST_OTA;
    private static final String CALLS = "fci.hrms.client.calls";
    private static final String FAILED = "INT-502-CALL-FAILED";
    private static final String LOGGER = "FCI_HRMS_EXCHANGE";

    /** What the server answers. */
    private record Answer(int status, String body) {}

    /** One request as the server saw it. */
    private record Seen(
            String method,
            String path,
            String apiKey,
            String authorization,
            String body,
            String key) {}

    private HttpServer server;
    private final List<Seen> seen = new CopyOnWriteArrayList<>();
    private final AtomicInteger tokenCalls = new AtomicInteger();
    private final AtomicInteger apiCalls = new AtomicInteger();
    private volatile Function<Seen, Answer> apiAnswer =
            s -> new Answer(200, "{\"status\":\"success\"}");
    private volatile Function<Seen, Long> delay = s -> 0L;
    private volatile Supplier<Answer> tokenAnswer;
    private volatile long tokenDelayMillis;
    private volatile String tokenPrefix = "T";
    private final List<HrmsClient> clients = new ArrayList<>();
    private MutableClock clock;
    private SimpleMeterRegistry registry;
    private LogCapture capture;
    private HrmsApiProperties lastProps;
    private HrmsClientProperties lastClientProps;

    /** A clock the test moves. */
    private static final class MutableClock extends Clock {
        private volatile Instant now = Instant.parse("2026-11-06T04:00:00Z");

        void advance(long seconds) {
            now = now.plusSeconds(seconds);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** The settings of one client under test. */
    private final class Cfg {
        final Map<String, ApiUrl> urls = new HashMap<>();
        final Map<String, Limit> limits = new HashMap<>();
        Call defaults;
        HrmsClientProperties props = props(10, 10, 2, "STATIC-BEARER");
        HrmsCredentialsProperties login = new HrmsCredentialsProperties(null, null, null, null);
        // A generous call budget (30 s) where the timeout is not under test: a cold client's first
        // exchange on a loaded machine once took over 5 s (INT-502-CALL-FAILED, TimeoutException).
        Settings settings = settings(0, 30);
        boolean allowUnmasked = true;
        HrmsLogProperties logging = new HrmsLogProperties(true, 1500);

        Cfg() {
            urls.put(
                    HrmsApi.TOKEN.code(),
                    new ApiUrl(HrmsApi.TOKEN.collectionPath(), "OUTBOUND", null));
            limits.put(HrmsApi.TOKEN.code(), new Limit(4, 2000));
        }

        Cfg api(HrmsApi api, Call call) {
            return api(api, call, new Limit(8, 2000));
        }

        Cfg api(HrmsApi api, Call call, Limit limit) {
            urls.put(api.code(), new ApiUrl(api.collectionPath(), "OUTBOUND", call));
            limits.put(api.code(), limit);
            return this;
        }

        Cfg apiAt(HrmsApi api, String fullUrl, Call call) {
            urls.put(api.code(), new ApiUrl(fullUrl, "OUTBOUND", call));
            limits.put(api.code(), new Limit(8, 2000));
            return this;
        }

        Cfg withLogin(Integer renewals) {
            props = props(20, 20, 5, null);
            login = new HrmsCredentialsProperties("payroll", PASSWORD, null, 60, renewals);
            return this;
        }
    }

    private Supplier<Answer> issuing(String lifetimeJson) {
        return () -> {
            int n = tokenCalls.get();
            return new Answer(
                    200,
                    "{\"status\":\"success\",\"data\":{\"token\":\""
                            + tokenPrefix
                            + n
                            + "\""
                            + lifetimeJson
                            + "}}");
        };
    }

    @BeforeEach
    void start() throws IOException {
        clock = new MutableClock();
        tokenAnswer = issuing(",\"expires_in\":3600");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", this::handle);
        server.start();
        capture = LogCapture.of(LOGGER, Level.INFO);
    }

    @AfterEach
    void stop() {
        capture.close();
        try {
            server.stop(0);
        } catch (RuntimeException alreadyStopped) {
            // a test may have stopped the server on purpose
        }
        clients.forEach(HrmsClient::close);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String path = exchange.getRequestURI().getPath();
        Seen request =
                new Seen(
                        exchange.getRequestMethod(),
                        path,
                        exchange.getRequestHeaders().getFirst("api-key"),
                        exchange.getRequestHeaders().getFirst("Authorization"),
                        body,
                        exchange.getRequestHeaders().getFirst("Idempotency-Key"));
        Answer answer;
        if (path.equals("/token")) {
            tokenCalls.incrementAndGet();
            seen.add(request);
            sleep(tokenDelayMillis);
            answer = tokenAnswer.get();
        } else {
            apiCalls.incrementAndGet();
            seen.add(request);
            sleep(delay.apply(request));
            answer = apiAnswer.apply(request);
        }
        byte[] bytes = answer.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(answer.status(), bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    private static void sleep(long millis) {
        if (millis > 0) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static Settings settings(int retries, int timeoutSeconds) {
        return new Settings(
                "X",
                retries,
                0,
                "FIXED",
                timeoutSeconds,
                false,
                null,
                null,
                null,
                LocalDate.now(),
                true);
    }

    private static HrmsClientProperties props(
            int connections, int pending, int pendingSeconds, String staticBearer) {
        return new HrmsClientProperties(
                connections, pending, pendingSeconds, 30, 60, 1, KEY, null, staticBearer, null);
    }

    private static Call timeouts(Integer connect, Integer read, Integer whole) {
        return new Call(connect, read, whole, null, null, null, null);
    }

    private static boolean isApi(Seen s, HrmsApi api) {
        return s.path().equals("/" + api.collectionPath().replaceAll("^/+", ""));
    }

    private HrmsClient build(Cfg cfg) {
        InterfaceIntake intake = mock(InterfaceIntake.class);
        when(intake.open(anyString(), anyString(), any(), any())).thenReturn(7L);
        IntegrationStore store = mock(IntegrationStore.class);
        when(store.interfaceOf(anyString()))
                .thenAnswer(
                        i ->
                                new IntegrationStore.Registered(
                                        1L, i.getArgument(0), "HRMS", "OUTBOUND"));
        registry = new SimpleMeterRegistry();
        MaskingPolicy masking =
                new MaskingPolicy(
                        new MaskingProperties(
                                List.of(
                                        new FieldRule("mobile", Strategy.KEEP_LAST, 3),
                                        new FieldRule("password", Strategy.FULL, null)),
                                null));
        lastProps = new HrmsApiProperties(url(), cfg.urls, cfg.limits, null, cfg.defaults);
        lastClientProps = cfg.props;
        HrmsClient c =
                new HrmsClient(
                        lastProps,
                        cfg.props,
                        cfg.login,
                        HrmsContractCatalog.load(),
                        code -> Optional.of(cfg.settings),
                        intake,
                        store,
                        mock(FailureAlerts.class),
                        mock(HrmsRefusals.class),
                        clock,
                        new HrmsTelemetry(cfg.logging, masking, registry, cfg.allowUnmasked));
        clients.add(c);
        return c;
    }

    /**
     * The warm-up is test setup, not a call under test: a cold client's first exchange on a loaded
     * machine (the full suite beside a running stack) can exceed a short call budget and fail as
     * INT-502-CALL-FAILED (TimeoutException); it is tried up to three times.
     */
    private static void warmOne(HrmsClient c, HrmsApi api) {
        for (int attempt = 1; ; attempt++) {
            try {
                c.send(api, Map.of(), null, "WARM-" + api.code() + "-" + attempt);
                return;
            } catch (in.gov.fci.payroll.platform.error.FciException e) {
                if (attempt == 3) {
                    throw e;
                }
            }
        }
    }

    /** The first call of a JVM loads Netty: warm up before timing anything. */
    private void warm(HrmsClient c, HrmsApi... apis) {
        Function<Seen, Long> before = delay;
        delay = s -> 0L;
        for (HrmsApi api : apis) {
            warmOne(c, api);
        }
        delay = before;
        seen.clear();
        apiCalls.set(0);
        capture.events();
    }

    private void awaitSeen(String key) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (seen.stream().noneMatch(r -> key.equals(r.key())) && System.nanoTime() < until) {
            Thread.sleep(10);
        }
        assertThat(seen).anyMatch(r -> key.equals(r.key()));
    }

    private static long millisSince(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    // ------------------------------------------------------------------------------- timeouts

    @Test
    void SRS_INT_6_anApiWithItsOwnTimeoutFailsFastWhileAnotherWithNoneWaitsForTheSlowServer() {
        Cfg cfg = new Cfg().api(STATUS, timeouts(null, null, 600)).api(PUSH, null);
        HrmsClient c = build(cfg);
        warm(c, STATUS, PUSH);
        delay = s -> 1500L;
        long started = System.nanoTime();
        assertThatThrownBy(() -> c.send(STATUS, Map.of(), null, "TO-1"))
                .isInstanceOf(FciException.class)
                .hasMessage(FAILED);
        assertThat(millisSince(started)).isBetween(500L, 2500L);
        assertThat(c.send(PUSH, Map.of(), null, "TO-2").status()).isEqualTo(200);
    }

    @Test
    void SRS_INT_6_aReadTimeoutShorterThanTheServersDelayFailsTheCall() {
        Cfg cfg = new Cfg().api(STATUS, timeouts(null, 400, null));
        HrmsClient c = build(cfg);
        warm(c, STATUS);
        delay = s -> 1500L;
        long started = System.nanoTime();
        assertThatThrownBy(() -> c.send(STATUS, Map.of(), null, "RT-1"))
                .isInstanceOf(FciException.class)
                .hasMessage(FAILED);
        assertThat(millisSince(started)).isBetween(300L, 2500L);
    }

    @Test
    void SRS_INT_6_aConnectTimeoutIsAppliedAndAnUnreachablePeerFailsFast() {
        Cfg cfg =
                new Cfg()
                        .apiAt(
                                STATUS,
                                "http://10.255.255.1:81/get-ota-status",
                                timeouts(300, null, null));
        HrmsClient c = build(cfg);
        assertThat(lastProps.resolve(STATUS.code(), 5, lastClientProps).connectTimeoutMs())
                .isEqualTo(300);
        long started = System.nanoTime();
        assertThatThrownBy(() -> c.send(STATUS, Map.of(), null, "CT-1"))
                .isInstanceOf(FciException.class)
                .hasMessage(FAILED);
        assertThat(millisSince(started)).isLessThan(2500L);
        assertThat(seen).isEmpty();
    }

    @Test
    void SRS_INT_6_aStoppedServerFailsFastWithinTheConnectTimeout() throws IOException {
        Cfg cfg = new Cfg().api(STATUS, timeouts(500, null, null));
        HrmsClient c = build(cfg);
        warm(c, STATUS);
        server.stop(0);
        long started = System.nanoTime();
        assertThatThrownBy(() -> c.send(STATUS, Map.of(), null, "CT-2"))
                .isInstanceOf(FciException.class)
                .hasMessage(FAILED);
        assertThat(millisSince(started)).isLessThan(2500L);
    }

    @Test
    void SRS_INT_6_theDefaultsTimeoutAppliesToAnApiWithoutItsOwnAndTheOwnOverridesIt() {
        Cfg cfg = new Cfg().api(STATUS, null).api(PUSH, timeouts(null, null, 3000));
        cfg.defaults = timeouts(null, null, 600);
        HrmsClient c = build(cfg);
        assertThat(lastProps.resolve(STATUS.code(), 5, lastClientProps).timeoutMs()).isEqualTo(600);
        assertThat(lastProps.resolve(PUSH.code(), 5, lastClientProps).timeoutMs()).isEqualTo(3000);
        assertThat(lastProps.resolve("other", 5, lastClientProps).timeoutMs()).isEqualTo(600);
        warm(c, STATUS, PUSH);
        delay = s -> 1500L;
        long started = System.nanoTime();
        assertThatThrownBy(() -> c.send(STATUS, Map.of(), null, "DF-1"))
                .isInstanceOf(FciException.class)
                .hasMessage(FAILED);
        assertThat(millisSince(started)).isBetween(500L, 2500L);
        assertThat(c.send(PUSH, Map.of(), null, "DF-2").status()).isEqualTo(200);
    }

    @Test
    void SRS_INT_6_withoutAnyPolicyTheInterfaceTimeLimitIsEveryTimeout() {
        Cfg cfg = new Cfg().api(STATUS, null);
        cfg.settings = settings(0, 7);
        build(cfg);
        HrmsApiProperties.Resolved r = lastProps.resolve(STATUS.code(), 7, lastClientProps);
        assertThat(r.connectTimeoutMs()).isEqualTo(7000);
        assertThat(r.readTimeoutMs()).isEqualTo(7000);
        assertThat(r.timeoutMs()).isEqualTo(7000);
        assertThat(r.pool().key()).isEqualTo("shared");
        assertThat(r.maskData() && r.logRequest() && r.logResponse()).isTrue();
    }

    // ------------------------------------------------------------------------------------ pools

    @Test
    void R_INT_anApisOwnExhaustedPoolFailsItsCallsWhileAnotherApiOnTheSharedPoolIsServed()
            throws Exception {
        Cfg cfg =
                new Cfg()
                        .api(
                                STATUS,
                                new Call(
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        new Pool(1, 1, 200, null, null, null, null)))
                        .api(PUSH, null);
        cfg.settings = settings(0, 6);
        HrmsClient c = build(cfg);
        warm(c, STATUS, PUSH);
        delay = s -> isApi(s, STATUS) ? 2500L : 0L;
        ExecutorService exec = Executors.newFixedThreadPool(2);
        try {
            Future<HrmsClient.Response> holder =
                    exec.submit(() -> c.send(STATUS, Map.of(), null, "H-1"));
            awaitSeen("H-1");
            long started = System.nanoTime();
            assertThatThrownBy(() -> c.send(STATUS, Map.of(), null, "H-2"))
                    .isInstanceOf(FciException.class)
                    .hasMessage(FAILED);
            assertThat(millisSince(started)).isLessThan(2000L);
            long other = System.nanoTime();
            assertThat(c.send(PUSH, Map.of(), null, "P-1").status()).isEqualTo(200);
            assertThat(millisSince(other)).isLessThan(2000L);
            assertThat(holder.get(10, TimeUnit.SECONDS).status()).isEqualTo(200);
        } finally {
            exec.shutdownNow();
        }
        assertThat(seen.stream().map(Seen::key)).doesNotContain("H-2");
    }

    @Test
    void R_INT_twoApisWithoutTheirOwnPoolShareOneAndTheSecondWaitsForTheFirst() throws Exception {
        Cfg cfg = new Cfg().api(STATUS, null).api(PUSH, null);
        cfg.props = props(1, 1, 1, "STATIC-BEARER");
        cfg.settings = settings(0, 6);
        HrmsClient c = build(cfg);
        assertThat(lastProps.resolve(STATUS.code(), 6, lastClientProps).pool().key())
                .isEqualTo("shared")
                .isEqualTo(lastProps.resolve(PUSH.code(), 6, lastClientProps).pool().key());
        warm(c, STATUS);
        delay = s -> isApi(s, STATUS) ? 2500L : 0L;
        ExecutorService exec = Executors.newFixedThreadPool(1);
        try {
            Future<HrmsClient.Response> holder =
                    exec.submit(() -> c.send(STATUS, Map.of(), null, "S-1"));
            awaitSeen("S-1");
            long started = System.nanoTime();
            assertThatThrownBy(() -> c.send(PUSH, Map.of(), null, "S-2"))
                    .isInstanceOf(FciException.class)
                    .hasMessage(FAILED);
            assertThat(millisSince(started)).isBetween(700L, 2300L);
            assertThat(holder.get(10, TimeUnit.SECONDS).status()).isEqualTo(200);
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    void R_INT_poolKeysFollowOwnThenDefaultsThenSharedAndLeasingOrderIsAccepted() {
        Pool lifo = new Pool(2, 3, 100, 5, 60, 10, "LIFO");
        Cfg cfg =
                new Cfg()
                        .api(STATUS, new Call(null, null, null, null, null, null, lifo))
                        .api(
                                PUSH,
                                new Call(
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        new Pool(null, null, null, null, null, null, "fifo")))
                        .api(OTA, null);
        cfg.defaults =
                new Call(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        new Pool(7, null, null, null, null, null, "LIFO"));
        HrmsClient c = build(cfg);
        HrmsApiProperties.PoolSpec own =
                lastProps.resolve(STATUS.code(), 5, lastClientProps).pool();
        assertThat(own.key()).isEqualTo("api-" + STATUS.code());
        assertThat(own.maxConnections()).isEqualTo(2);
        assertThat(own.pendingAcquireMaxCount()).isEqualTo(3);
        assertThat(own.pendingAcquireTimeoutMs()).isEqualTo(100L);
        assertThat(own.lifo()).isTrue();
        HrmsApiProperties.PoolSpec second =
                lastProps.resolve(PUSH.code(), 5, lastClientProps).pool();
        assertThat(second.key()).isEqualTo("api-" + PUSH.code());
        assertThat(second.lifo()).as("own leasing FIFO-in-lower-case, not the defaults'").isFalse();
        HrmsApiProperties.PoolSpec common =
                lastProps.resolve(OTA.code(), 5, lastClientProps).pool();
        assertThat(common.key()).isEqualTo("defaults");
        assertThat(common.maxConnections()).isEqualTo(7);
        assertThat(common.lifo()).isTrue();
        assertThat(lastProps.errors()).isEmpty();
        HrmsClient.validate(lastProps);
        assertThat(c.send(STATUS, Map.of(), null, "L-1").status()).isEqualTo(200);
        assertThat(c.send(STATUS, Map.of(), null, "L-2").status()).isEqualTo(200);
        assertThat(c.send(PUSH, Map.of(), null, "L-3").status()).isEqualTo(200);
        assertThat(c.send(OTA, null, "{\"a\":1}", "L-4").status()).isEqualTo(200);
    }

    @Test
    void R_INT_anUnknownLeasingOrderStopsTheStart() {
        Cfg cfg =
                new Cfg()
                        .api(
                                STATUS,
                                new Call(
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        new Pool(null, null, null, null, null, null, "RANDOM")));
        build(cfg);
        assertThat(lastProps.errors()).anyMatch(e -> e.contains("FIFO or LIFO"));
        assertThatThrownBy(() -> HrmsClient.validate(lastProps))
                .isInstanceOf(IllegalStateException.class);
    }

    // ----------------------------------------------------------------------------------- tokens

    private HrmsClient tokenClient(Integer renewals) {
        Cfg cfg = new Cfg().withLogin(renewals).api(STATUS, null);
        return build(cfg);
    }

    private static Answer expired(String code) {
        return new Answer(401, "{\"status\":\"error\",\"error_code\":\"" + code + "\"}");
    }

    @Test
    void SRS_INT_4_a403TokenExpiredRenewsTheTokenAndRepeatsTheCall() {
        apiAnswer =
                s ->
                        "Bearer T1".equals(s.authorization())
                                ? new Answer(403, "{\"error_code\":\"TOKEN_EXPIRED\"}")
                                : new Answer(200, "{\"status\":\"success\"}");
        HrmsClient c = tokenClient(null);
        assertThat(c.send(STATUS, Map.of(), null, "R-1").status()).isEqualTo(200);
        assertThat(tokenCalls.get()).isEqualTo(2);
        assertThat(apiCalls.get()).isEqualTo(2);
    }

    @Test
    void SRS_INT_4_a403UserInactiveDoesNotRenewTheToken() {
        apiAnswer = s -> new Answer(403, "{\"error_code\":\"USER_INACTIVE\"}");
        HrmsClient c = tokenClient(null);
        assertThat(c.send(STATUS, Map.of(), null, "R-2").status()).isEqualTo(403);
        assertThat(tokenCalls.get()).isEqualTo(1);
        assertThat(apiCalls.get()).isEqualTo(1);
    }

    @Test
    void SRS_INT_4_aRefusedApiKeyIsTheAnswerAndFetchesNoNewToken() {
        for (String code : List.of("API_KEY_MISSING", "INVALID_API_KEY")) {
            tokenCalls.set(0);
            apiCalls.set(0);
            apiAnswer = s -> expired(code);
            HrmsClient c = tokenClient(null);
            HrmsClient.Response r = c.send(STATUS, Map.of(), null, "R-3");
            assertThat(r.status()).as(code).isEqualTo(401);
            assertThat(r.body()).contains(code);
            assertThat(tokenCalls.get()).as(code + " token calls").isEqualTo(1);
            assertThat(apiCalls.get()).as(code + " api calls").isEqualTo(1);
        }
    }

    @Test
    void SRS_INT_4_noRenewalsConfiguredReturnsThe401WithoutASecondTokenCall() {
        apiAnswer = s -> expired("TOKEN_REVOKED");
        HrmsClient c = tokenClient(0);
        assertThat(c.send(STATUS, Map.of(), null, "R-4").status()).isEqualTo(401);
        assertThat(tokenCalls.get()).isEqualTo(1);
        assertThat(apiCalls.get()).isEqualTo(1);
    }

    @Test
    void SRS_INT_4_oneRenewalConfiguredCostsExactlyOneRenewal() {
        apiAnswer = s -> expired("TOKEN_REVOKED");
        HrmsClient c = tokenClient(1);
        assertThat(c.send(STATUS, Map.of(), null, "R-5").status()).isEqualTo(401);
        assertThat(tokenCalls.get()).isEqualTo(2);
        assertThat(apiCalls.get()).isEqualTo(2);
    }

    @Test
    void SRS_INT_4_theDefaultTwoRenewalsMeanOneLoginPlusTwoRenewalsAndThreeApiCalls() {
        apiAnswer = s -> expired("TOKEN_REVOKED");
        HrmsClient c = tokenClient(null);
        assertThat(c.send(STATUS, Map.of(), null, "R-6").status()).isEqualTo(401);
        assertThat(tokenCalls.get()).isEqualTo(3);
        assertThat(apiCalls.get()).isEqualTo(3);
    }

    @Test
    void SRS_INT_4_theNumberOfRenewalsIsCappedAtFive() {
        apiAnswer = s -> expired("TOKEN_REVOKED");
        HrmsClient c = tokenClient(9);
        assertThat(c.send(STATUS, Map.of(), null, "R-7").status()).isEqualTo(401);
        assertThat(tokenCalls.get()).isEqualTo(6);
        assertThat(apiCalls.get()).isEqualTo(6);
    }

    @Test
    void SRS_INT_4_aStormOnOneExpiredTokenCostsExactlyOneAdditionalTokenFetch() throws Exception {
        HrmsClient c = tokenClient(null);
        assertThat(c.send(STATUS, Map.of(), null, "ST-0").status()).isEqualTo(200);
        assertThat(tokenCalls.get()).isEqualTo(1);
        apiAnswer =
                s ->
                        "Bearer T1".equals(s.authorization())
                                ? expired("TOKEN_EXPIRED")
                                : new Answer(200, "{\"status\":\"success\"}");
        tokenDelayMillis = 200;
        int threads = 8;
        ExecutorService exec = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                int n = i;
                results.add(
                        exec.submit(
                                () -> {
                                    go.await();
                                    return c.send(STATUS, Map.of(), null, "ST-" + (n + 1)).status();
                                }));
            }
            go.countDown();
            for (Future<Integer> f : results) {
                assertThat(f.get(20, TimeUnit.SECONDS)).isEqualTo(200);
            }
        } finally {
            exec.shutdownNow();
        }
        assertThat(tokenCalls.get()).as("one login plus one shared renewal").isEqualTo(2);
    }

    @Test
    void SRS_INT_4_anIntermittent401ForTheCurrentTokenIsSeamlessWithOneRenewal() {
        AtomicInteger n = new AtomicInteger();
        apiAnswer =
                s ->
                        n.getAndIncrement() == 0
                                ? expired("INVALID_TOKEN")
                                : new Answer(200, "{\"status\":\"success\"}");
        HrmsClient c = tokenClient(null);
        assertThat(c.send(STATUS, Map.of(), null, "IN-1").status()).isEqualTo(200);
        assertThat(tokenCalls.get()).isEqualTo(2);
        assertThat(apiCalls.get()).isEqualTo(2);
        assertThat(seen.stream().filter(s -> !s.path().equals("/token")))
                .extracting(Seen::authorization)
                .containsExactly("Bearer T1", "Bearer T2");
    }

    @Test
    void SRS_INT_4_aTokenPastItsScheduledExpiryIsFetchedBeforeTheCallAndNo401IsEverSeen() {
        tokenAnswer = issuing(",\"expires_in\":120");
        AtomicInteger refused = new AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean staleRefused =
                new java.util.concurrent.atomic.AtomicBoolean();
        apiAnswer =
                s -> {
                    if (staleRefused.get() && "Bearer T1".equals(s.authorization())) {
                        refused.incrementAndGet();
                        return expired("TOKEN_EXPIRED");
                    }
                    return new Answer(200, "{\"status\":\"success\"}");
                };
        HrmsClient c = tokenClient(null);
        assertThat(c.send(STATUS, Map.of(), null, "SC-1").status()).isEqualTo(200);
        clock.advance(61);
        staleRefused.set(true);
        assertThat(c.send(STATUS, Map.of(), null, "SC-2").status()).isEqualTo(200);
        assertThat(refused.get()).isZero();
        assertThat(tokenCalls.get()).isEqualTo(2);
        assertThat(seen.stream().filter(s -> !s.path().equals("/token")))
                .extracting(Seen::authorization)
                .containsExactly("Bearer T1", "Bearer T2");
    }

    @Test
    void SRS_INT_6_aTokenEndpointThatFailsOnceIsRetriedAndTheBusinessCallIsSeamless() {
        tokenAnswer =
                () ->
                        tokenCalls.get() == 1
                                ? new Answer(500, "{\"error_code\":\"INTERNAL_SERVER_ERROR\"}")
                                : issuing(",\"expires_in\":3600").get();
        Cfg cfg = new Cfg().withLogin(null).api(STATUS, null);
        cfg.settings = settings(1, 5);
        HrmsClient c = build(cfg);
        assertThat(c.send(STATUS, Map.of(), null, "TF-1").status()).isEqualTo(200);
        assertThat(tokenCalls.get()).isEqualTo(2);
        assertThat(apiCalls.get()).isEqualTo(1);
    }

    @Test
    void SRS_INT_6_theTokenCounterCountsFetchedRenewedAndRefused() {
        apiAnswer =
                s ->
                        "Bearer T1".equals(s.authorization())
                                ? expired("TOKEN_EXPIRED")
                                : new Answer(200, "{\"status\":\"success\"}");
        tokenAnswer =
                () ->
                        tokenCalls.get() == 2
                                ? new Answer(401, "{\"error_code\":\"INVALID_CREDENTIALS\"}")
                                : issuing(",\"expires_in\":3600").get();
        HrmsClient c = tokenClient(null);
        assertThatThrownBy(() -> c.send(STATUS, Map.of(), null, "TK-1"))
                .isInstanceOf(FciException.class)
                .hasMessage(FAILED);
        assertThat(registry.get("fci.hrms.client.token").tag("result", "fetched").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get("fci.hrms.client.token").tag("result", "renewed").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get("fci.hrms.client.token").tag("result", "refused").counter().count())
                .isEqualTo(1.0);
    }

    // -------------------------------------------------------------------------------- telemetry

    private static Object kv(ILoggingEvent e, String key) {
        if (e.getKeyValuePairs() == null) {
            return null;
        }
        return e.getKeyValuePairs().stream()
                .filter(p -> key.equals(p.key))
                .map(p -> p.value)
                .findFirst()
                .orElse(null);
    }

    /**
     * The per-attempt exchange events (not the counter events) of this test's thread: the exchange
     * logger is shared by the parallel test classes of the fork, and every log assertion here calls
     * the client on the test thread.
     */
    private List<ILoggingEvent> exchanges() {
        String self = Thread.currentThread().getName();
        return capture.events().stream()
                .filter(e -> kv(e, "peer.operation") != null)
                .filter(e -> self.equals(e.getThreadName()))
                .toList();
    }

    private ILoggingEvent lastExchangeOf(HrmsApi api) {
        List<ILoggingEvent> all =
                exchanges().stream()
                        .filter(e -> api.code().equals(kv(e, "peer.operation")))
                        .toList();
        assertThat(all).isNotEmpty();
        return all.getLast();
    }

    @Test
    void SRS_INT_6_theCallTimerCarriesOperationMethodStatusAndOutcomeForEveryKindOfResult() {
        Cfg cfg = new Cfg().api(STATUS, null).api(PUSH, timeouts(null, null, 400));
        HrmsClient c = build(cfg);
        warm(c, STATUS, PUSH);
        registry.clear();
        assertThat(c.send(STATUS, Map.of(), null, "M-1").status()).isEqualTo(200);
        apiAnswer = s -> new Answer(404, "{\"error_code\":\"NOT_FOUND\"}");
        assertThat(c.send(STATUS, Map.of(), null, "M-2").status()).isEqualTo(404);
        apiAnswer = s -> new Answer(500, "{\"error_code\":\"INTERNAL_SERVER_ERROR\"}");
        assertThatThrownBy(() -> c.send(STATUS, Map.of(), null, "M-3")).hasMessage(FAILED);
        apiAnswer = s -> new Answer(200, "{}");
        delay = s -> isApi(s, PUSH) ? 1500L : 0L;
        assertThatThrownBy(() -> c.send(PUSH, Map.of(), null, "M-4")).hasMessage(FAILED);
        String op = STATUS.code();
        assertThat(timer(op, "GET", "200", "OK")).isEqualTo(1L);
        assertThat(timer(op, "GET", "404", "CLIENT_ERROR")).isEqualTo(1L);
        assertThat(timer(op, "GET", "500", "SERVER_ERROR")).isEqualTo(1L);
        assertThat(timer(PUSH.code(), "GET", "none", "TIMEOUT")).isEqualTo(1L);
    }

    private long timer(String operation, String method, String status, String outcome) {
        return registry.get(CALLS)
                .tags(
                        "operation", operation,
                        "method", method,
                        "status", status,
                        "outcome", outcome)
                .timer()
                .count();
    }

    @Test
    void SRS_INT_6_aRetried5xxCountsARetryAndLogsAttemptTwoWithStatusAndDuration() {
        AtomicInteger n = new AtomicInteger();
        apiAnswer =
                s ->
                        n.getAndIncrement() == 0
                                ? new Answer(500, "{\"error_code\":\"INTERNAL_SERVER_ERROR\"}")
                                : new Answer(200, "{\"status\":\"success\"}");
        delay = s -> 300L;
        Cfg cfg = new Cfg().api(STATUS, null);
        cfg.settings = settings(1, 5);
        HrmsClient c = build(cfg);
        HrmsClient.Response r = c.send(STATUS, Map.of(), null, "RE-1");
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.attempts()).isEqualTo(2);
        assertThat(
                        registry.get("fci.hrms.client.retries")
                                .tag("api", STATUS.code())
                                .counter()
                                .count())
                .isEqualTo(1.0);
        List<ILoggingEvent> events =
                exchanges().stream()
                        .filter(e -> STATUS.code().equals(kv(e, "peer.operation")))
                        .toList();
        assertThat(events).hasSize(2);
        assertThat(kv(events.get(0), "peer.attempt")).isEqualTo(1);
        assertThat(kv(events.get(0), "http.response.status_code")).isEqualTo(500);
        assertThat(events.get(0).getLevel()).isEqualTo(Level.WARN);
        assertThat(kv(events.get(1), "peer.attempt")).isEqualTo(2);
        assertThat(kv(events.get(1), "http.response.status_code")).isEqualTo(200);
        assertThat(kv(events.get(1), "peer.outcome")).isEqualTo("OK");
        assertThat(((Number) kv(events.get(1), "peer.duration_ms")).longValue())
                .isGreaterThanOrEqualTo(250L);
    }

    @Test
    void R_INT_aFullBulkheadIsCountedAsRefused() throws Exception {
        Cfg cfg = new Cfg().api(STATUS, null, new Limit(1, 0));
        HrmsClient c = build(cfg);
        warm(c, STATUS);
        delay = s -> 800L;
        ExecutorService exec = Executors.newFixedThreadPool(1);
        try {
            Future<HrmsClient.Response> holder =
                    exec.submit(() -> c.send(STATUS, Map.of(), null, "BH-1"));
            awaitSeen("BH-1");
            assertThatThrownBy(() -> c.send(STATUS, Map.of(), null, "BH-2"))
                    .isInstanceOf(FciException.class)
                    .hasMessage("INT-503-BULKHEAD-FULL");
            assertThat(holder.get(10, TimeUnit.SECONDS).status()).isEqualTo(200);
        } finally {
            exec.shutdownNow();
        }
        assertThat(
                        registry.get("fci.hrms.client.bulkhead.refused")
                                .tag("api", STATUS.code())
                                .counter()
                                .count())
                .isEqualTo(1.0);
    }

    private static final String REQUEST_BODY =
            "{\"request_id\":\"7\",\"mobile\":\"9876543210\",\"password\":\"hunter2-pass\"}";
    private static final String RESPONSE_BODY =
            "{\"status\":\"success\",\"mobile\":\"9123456789\"}";

    private ILoggingEvent postOta(Call call, boolean allowUnmasked) {
        apiAnswer = s -> new Answer(200, RESPONSE_BODY);
        Cfg cfg = new Cfg().api(OTA, call);
        cfg.allowUnmasked = allowUnmasked;
        HrmsClient c = build(cfg);
        assertThat(c.send(OTA, null, REQUEST_BODY, "PO-1").status()).isEqualTo(200);
        return lastExchangeOf(OTA);
    }

    @Test
    void R_PII_theRequestAndResponseBodiesOfABusinessCallAreLoggedMasked() {
        ILoggingEvent e = postOta(null, true);
        String request = (String) kv(e, "peer.request.body");
        String response = (String) kv(e, "peer.response.body");
        assertThat(request).contains("*******210").contains("\"password\":\"***\"");
        assertThat(request).doesNotContain("9876543210").doesNotContain("hunter2-pass");
        assertThat(response).contains("*******789").doesNotContain("9123456789");
        assertThat(kv(e, "http.request.method")).isEqualTo("POST");
        assertThat(kv(e, "peer.operation")).isEqualTo(OTA.code());
    }

    @Test
    void R_PII_logRequestOffOmitsTheRequestBodyAndKeepsTheResponse() {
        ILoggingEvent e = postOta(new Call(null, null, null, null, false, null, null), true);
        assertThat(kv(e, "peer.request.body")).isNull();
        assertThat((String) kv(e, "peer.response.body")).contains("*******789");
    }

    @Test
    void R_PII_logResponseOffOmitsTheResponseBodyAndKeepsTheRequest() {
        ILoggingEvent e = postOta(new Call(null, null, null, null, null, false, null), true);
        assertThat(kv(e, "peer.response.body")).isNull();
        assertThat((String) kv(e, "peer.request.body")).contains("*******210");
    }

    @Test
    void R_PII_maskDataOffShowsTheRawBodiesWhereUnmaskedIsAllowed() {
        ILoggingEvent e = postOta(new Call(null, null, null, false, null, null, null), true);
        assertThat((String) kv(e, "peer.request.body"))
                .contains("9876543210")
                .contains("hunter2-pass");
        assertThat((String) kv(e, "peer.response.body")).contains("9123456789");
    }

    @Test
    void R_PII_maskDataOffIsIgnoredWhereUnmaskedIsNotAllowed() {
        ILoggingEvent e = postOta(new Call(null, null, null, false, null, null, null), false);
        assertThat((String) kv(e, "peer.request.body"))
                .contains("*******210")
                .doesNotContain("9876543210")
                .doesNotContain("hunter2-pass");
        assertThat((String) kv(e, "peer.response.body")).doesNotContain("9123456789");
    }

    @Test
    void R_PII_theLoginBodiesAreWithheldAndNoSecretAppearsInAnyLoggedValueOrMessage() {
        tokenPrefix = "SECRET-TOKEN-";
        apiAnswer =
                s ->
                        ("Bearer " + tokenPrefix + "1").equals(s.authorization())
                                ? expired("TOKEN_EXPIRED")
                                : new Answer(200, "{\"status\":\"success\"}");
        HrmsClient c = tokenClient(null);
        assertThat(c.send(STATUS, Map.of(), null, "WH-1").status()).isEqualTo(200);
        assertThat(tokenCalls.get()).isEqualTo(2);
        List<ILoggingEvent> tokenEvents =
                exchanges().stream()
                        .filter(e -> HrmsApi.TOKEN.code().equals(kv(e, "peer.operation")))
                        .toList();
        assertThat(tokenEvents).hasSize(2);
        for (ILoggingEvent e : tokenEvents) {
            assertThat(kv(e, "peer.request.body")).isEqualTo("[withheld]");
            assertThat(kv(e, "peer.response.body")).isEqualTo("[withheld]");
        }
        List<ILoggingEvent> all = capture.events();
        assertThat(all).isNotEmpty();
        for (ILoggingEvent e : all) {
            List<String> shown = new ArrayList<>();
            shown.add(e.getFormattedMessage());
            shown.add(e.getMessage());
            if (e.getKeyValuePairs() != null) {
                e.getKeyValuePairs()
                        .forEach(
                                p -> {
                                    shown.add(p.key);
                                    shown.add(String.valueOf(p.value));
                                });
            }
            for (String text : shown) {
                assertThat(text)
                        .doesNotContain(PASSWORD)
                        .doesNotContain("SECRET-TOKEN")
                        .doesNotContain(KEY);
            }
        }
    }
}
