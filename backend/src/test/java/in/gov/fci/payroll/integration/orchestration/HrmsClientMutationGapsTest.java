package in.gov.fci.payroll.integration.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import in.gov.fci.payroll.integration.api.HrmsApi;
import in.gov.fci.payroll.integration.api.InterfaceIntake;
import in.gov.fci.payroll.integration.api.InterfaceSettings.Settings;
import in.gov.fci.payroll.integration.business.HrmsContractCatalog;
import in.gov.fci.payroll.integration.business.IntegrationErrorCode;
import in.gov.fci.payroll.integration.config.HrmsApiProperties;
import in.gov.fci.payroll.integration.config.HrmsApiProperties.ApiUrl;
import in.gov.fci.payroll.integration.config.HrmsApiProperties.Call;
import in.gov.fci.payroll.integration.config.HrmsApiProperties.Limit;
import in.gov.fci.payroll.integration.config.HrmsApiProperties.Pool;
import in.gov.fci.payroll.integration.config.HrmsClientProperties;
import in.gov.fci.payroll.integration.config.HrmsCredentialsProperties;
import in.gov.fci.payroll.integration.config.HrmsLogProperties;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.MaskingPolicy;
import in.gov.fci.payroll.platform.logging.MaskingProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.netty.resources.ConnectionProvider;

/**
 * SRS-INT-6, SRS-INT-35, R-INT: what a mutation run showed unasserted in the outbound HRMS client -
 * shutdown disposes the pools, the 400 edge of a failed call, the time a call took, the response
 * size limit, the pool name and leasing order, the back-off wait, no retry of a refused login, and
 * the reasons of a refused token request. Against a JDK HTTP server.
 */
class HrmsClientMutationGapsTest {

    private static final String KEY = "test-only-api-key-0004";
    private static final HrmsApi STATUS = HrmsApi.GET_OTA_STATUS;
    private static final HrmsApi PUSH = HrmsApi.GET_MEDICAL_CLAIM_STATUS;

    /** What the server answers. */
    private record Answer(int status, String body) {}

    /** One request as the server saw it. */
    private record Req(String path, int remotePort, int number) {}

    private HttpServer server;
    private final List<Req> seen = new CopyOnWriteArrayList<>();
    private final AtomicInteger tokenCalls = new AtomicInteger();
    private final AtomicInteger apiCalls = new AtomicInteger();
    private volatile Function<Req, Answer> apiAnswer = r -> new Answer(200, "{\"ok\":true}");
    private volatile Function<Req, Answer> tokenAnswer =
            r -> new Answer(200, "{\"data\":{\"token\":\"T1\",\"expires_in\":3600}}");
    private volatile Function<Req, Long> delay = r -> 0L;
    private final List<HrmsClient> clients = new ArrayList<>();
    private InterfaceIntake intake;
    private IntegrationStore store;
    private FailureAlerts alerts;
    private HrmsRefusals refusals;
    private SimpleMeterRegistry registry;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", this::handle);
        server.start();
        intake = mock(InterfaceIntake.class);
        when(intake.open(anyString(), anyString(), any(), any())).thenReturn(7L);
        store = mock(IntegrationStore.class);
        when(store.interfaceOf(anyString()))
                .thenAnswer(
                        i ->
                                new IntegrationStore.Registered(
                                        1L, i.getArgument(0), "HRMS", "OUTBOUND"));
        alerts = mock(FailureAlerts.class);
        refusals = mock(HrmsRefusals.class);
        registry = new SimpleMeterRegistry();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        clients.forEach(HrmsClient::close);
    }

    private void handle(HttpExchange exchange) throws IOException {
        exchange.getRequestBody().readAllBytes();
        String path = exchange.getRequestURI().getPath();
        boolean token = path.equals("/token");
        Req request =
                new Req(
                        path,
                        exchange.getRemoteAddress().getPort(),
                        (token ? tokenCalls : apiCalls).incrementAndGet());
        seen.add(request);
        long wait = token ? 0L : delay.apply(request);
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        Answer answer = token ? tokenAnswer.apply(request) : apiAnswer.apply(request);
        byte[] bytes = answer.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(answer.status(), bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /**
     * The call budget is generous (30 s): no test here is about the timeout, and a cold client's
     * first exchange on a loaded machine (the full suite beside a running stack) once took longer
     * than 5 s, which the client rightly reports as INT-502-CALL-FAILED (TimeoutException).
     */
    private static Settings settings(int retries, int backoffSeconds) {
        return new Settings(
                "X",
                retries,
                backoffSeconds,
                "FIXED",
                30,
                false,
                null,
                null,
                null,
                LocalDate.now(),
                true);
    }

    private static Call plain() {
        return new Call(null, null, null, null, null, null, null);
    }

    private HrmsClient build(
            Settings settings,
            String staticBearer,
            HrmsCredentialsProperties login,
            Map<HrmsApi, Call> apis) {
        Map<String, ApiUrl> urls = new HashMap<>();
        Map<String, Limit> limits = new HashMap<>();
        urls.put(
                HrmsApi.TOKEN.code(), new ApiUrl(HrmsApi.TOKEN.collectionPath(), "OUTBOUND", null));
        limits.put(HrmsApi.TOKEN.code(), new Limit(4, 2000));
        apis.forEach(
                (api, call) -> {
                    urls.put(api.code(), new ApiUrl(api.collectionPath(), "OUTBOUND", call));
                    limits.put(api.code(), new Limit(8, 2000));
                });
        HrmsClient c =
                new HrmsClient(
                        new HrmsApiProperties(url(), urls, limits, null, null),
                        new HrmsClientProperties(
                                5, 10, 2, 30, 60, 1, KEY, null, staticBearer, null),
                        login,
                        HrmsContractCatalog.load(),
                        code -> Optional.of(settings),
                        intake,
                        store,
                        alerts,
                        refusals,
                        Clock.systemUTC(),
                        new HrmsTelemetry(
                                new HrmsLogProperties(false, null),
                                new MaskingPolicy(new MaskingProperties(null, null)),
                                registry,
                                true));
        clients.add(c);
        return c;
    }

    private HrmsClient simple(Map<HrmsApi, Call> apis) {
        return build(
                settings(0, 0),
                "STATIC",
                new HrmsCredentialsProperties(null, null, null, null),
                apis);
    }

    private HrmsClient simple() {
        return simple(Map.of(STATUS, plain()));
    }

    private HrmsClient withLogin(int retries) {
        return build(
                settings(retries, 0),
                null,
                new HrmsCredentialsProperties("payroll", "pw-secret", null, 60),
                Map.of(STATUS, plain()));
    }

    @SuppressWarnings("unchecked")
    private static <T> Map<String, T> field(HrmsClient c, String name) throws Exception {
        Field f = HrmsClient.class.getDeclaredField(name);
        f.setAccessible(true);
        return (Map<String, T>) f.get(c);
    }

    // ------------------------------------------------------------------ shutdown

    @Test
    void SRS_INT_6_closeDisposesThePoolsEmptiesTheMapsAndRefusesFurtherCalls() throws Exception {
        HrmsClient c = simple();
        assertThat(c.send(STATUS, Map.of(), null, "C-1").status()).isEqualTo(200);
        Map<String, ConnectionProvider> pools = field(c, "pools");
        Map<String, Object> webClients = field(c, "webClients");
        assertThat(pools).isNotEmpty();
        assertThat(webClients).isNotEmpty();
        List<ConnectionProvider> held = new ArrayList<>(pools.values());
        assertThat(held).noneMatch(ConnectionProvider::isDisposed);
        c.close();
        assertThat(held).allMatch(ConnectionProvider::isDisposed);
        assertThat(pools).isEmpty();
        assertThat(webClients).isEmpty();
        assertThatThrownBy(() -> c.send(STATUS, Map.of(), null, "C-2"))
                .isInstanceOf(FciException.class)
                .hasMessage("INT-502-CALL-FAILED");
    }

    @Test
    void SRS_INT_6_theRegistryInterfaceOfEachApiKindIsFixed() {
        assertThat(HrmsClient.interfaceOf(HrmsApi.TOKEN)).isEqualTo("HRMS_API_AUTH");
        assertThat(HrmsClient.interfaceOf(HrmsApi.TOKEN)).isEqualTo(HrmsClient.AUTH_INTERFACE);
        assertThat(HrmsClient.interfaceOf(STATUS)).isNotEqualTo(HrmsClient.AUTH_INTERFACE);
    }

    // ------------------------------------------------------------------ the 400 edge

    @Test
    void SRS_INT_6_status400IsAFailedWindowAndAnAlertButStatus399IsNot() {
        apiAnswer = r -> new Answer(400, "{\"error_code\":\"BAD\"}");
        HrmsClient c = simple();
        assertThat(c.send(STATUS, Map.of(), null, "E-400").status()).isEqualTo(400);
        verify(intake).completeCounts(eq(7L), eq(1L), eq(0L), eq(1L), contains("HTTP 400"));
        verify(alerts).raise(any(), any(), any(), any(), contains("HTTP 400"));

        org.mockito.Mockito.clearInvocations(intake, alerts);
        when(intake.open(anyString(), anyString(), any(), any())).thenReturn(7L);
        apiAnswer = r -> new Answer(399, "{\"ok\":true}");
        assertThat(c.send(STATUS, Map.of(), null, "E-399").status()).isEqualTo(399);
        verify(intake).completeCounts(eq(7L), eq(1L), eq(1L), eq(0L), contains("HTTP 399"));
        verify(alerts, never()).raise(any(), any(), any(), any(), anyString());
    }

    // ------------------------------------------------------------------ the time a call took

    @Test
    void SRS_INT_6_theTimerRecordsTheRealMillisecondsOfTheAttempt() {
        HrmsClient c = simple();
        c.send(STATUS, Map.of(), null, "W-0"); // loads Netty before timing
        delay = r -> 300L;
        c.send(STATUS, Map.of(), null, "W-1");
        double max =
                registry.find("fci.hrms.client.calls").timers().stream()
                        .mapToDouble(t -> t.max(TimeUnit.MILLISECONDS))
                        .max()
                        .orElse(0);
        assertThat(max).isBetween(290.0, 4000.0);
        double total =
                registry.find("fci.hrms.client.calls").timers().stream()
                        .mapToDouble(t -> t.totalTime(TimeUnit.MILLISECONDS))
                        .sum();
        assertThat(total).isBetween(290.0, 8000.0);
    }

    // ------------------------------------------------------------------ response size limit

    @Test
    void SRS_INT_6_aResponseAboveTheDefaultCodecLimitButBelowOneMiBIsRead() {
        String big = "{\"pad\":\"" + "a".repeat(400_000) + "\"}";
        apiAnswer = r -> new Answer(200, big);
        HrmsClient c = simple();
        HrmsClient.Response answered = c.send(STATUS, Map.of(), null, "B-1");
        assertThat(answered.status()).isEqualTo(200);
        assertThat(answered.body()).hasSize(big.length());
    }

    @Test
    void SRS_INT_6_aResponseAboveOneMiBFailsTheCall() {
        String huge = "{\"pad\":\"" + "a".repeat(1_100_000) + "\"}";
        apiAnswer = r -> new Answer(200, huge);
        HrmsClient c = simple();
        assertThatThrownBy(() -> c.send(STATUS, Map.of(), null, "B-2"))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e ->
                                assertThat(e.getErrorCode())
                                        .isEqualTo(IntegrationErrorCode.CALL_FAILED));
    }

    // ------------------------------------------------------------------ pools

    @Test
    void R_INT_theSharedPoolIsNamedHrmsClientAndAnApiOwnPoolAfterItsKey() throws Exception {
        Call own =
                new Call(null, null, null, null, null, null, new Pool(2, 3, 100, 5, 60, 10, null));
        HrmsClient c = simple(Map.of(STATUS, own, PUSH, plain()));
        c.send(STATUS, Map.of(), null, "P-1");
        c.send(PUSH, Map.of(), null, "P-2");
        Map<String, ConnectionProvider> pools = field(c, "pools");
        assertThat(pools).containsOnlyKeys("shared", "api-" + STATUS.code());
        assertThat(pools.get("shared").name()).isEqualTo("hrms-client");
        assertThat(pools.get("api-" + STATUS.code()).name()).isEqualTo("hrms-api-" + STATUS.code());
    }

    @Test
    void R_INT_lifoLeasesTheLastReturnedConnectionAndFifoTheFirstReturned() throws Exception {
        assertThat(eventually("LIFO", true))
                .as("LIFO reuses the last returned connection")
                .isTrue();
        assertThat(eventually("FIFO", false))
                .as("FIFO reuses the first returned connection")
                .isTrue();
    }

    /**
     * The reuse order shows only when the two overlapping calls really overlap, which a loaded
     * machine can disturb; a wrong leasing choice fails EVERY attempt, a scheduling hiccup only
     * one. Up to three attempts, the first that shows the expected order wins.
     */
    private boolean eventually(String leasing, boolean expectSlowerCallsConnection)
            throws Exception {
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                if (thirdCallReusesTheSlowerCallsConnection(leasing)
                        == expectSlowerCallsConnection) {
                    return true;
                }
            } catch (java.util.concurrent.TimeoutException
                    | AssertionError
                    | IllegalStateException ex) {
                // a disturbed attempt: try again
            }
        }
        return false;
    }

    /**
     * Two calls overlap (so two connections): the quick one is returned to the pool first, the slow
     * one last. A third call then reuses the last returned one under LIFO and the first returned
     * under FIFO.
     */
    private boolean thirdCallReusesTheSlowerCallsConnection(String leasing) throws Exception {
        seen.clear();
        apiCalls.set(0);
        // The slow call is answered only once the quick one has come back, so the quick connection
        // is always returned first and the slow one last (a fixed 900 ms delay raced the quick
        // call's set-up on a loaded machine, and then the quick one came back last).
        java.util.concurrent.CountDownLatch quickBack = new java.util.concurrent.CountDownLatch(1);
        delay =
                r -> {
                    if (r.number() == 1) {
                        try {
                            quickBack.await(30, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    return 0L;
                };
        Call call =
                new Call(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        new Pool(2, 5, 2000, 30, 60, 10, leasing));
        HrmsClient c = simple(Map.of(STATUS, call));
        ExecutorService exec = Executors.newFixedThreadPool(2);
        try {
            Future<HrmsClient.Response> slow =
                    exec.submit(() -> c.send(STATUS, Map.of(), null, "L-1"));
            long until = System.currentTimeMillis() + 5000;
            while (apiCalls.get() < 1 && System.currentTimeMillis() < until) {
                Thread.sleep(10);
            }
            Future<HrmsClient.Response> quick =
                    exec.submit(() -> c.send(STATUS, Map.of(), null, "L-2"));
            quick.get(30, TimeUnit.SECONDS);
            quickBack.countDown();
            slow.get(30, TimeUnit.SECONDS);
        } finally {
            quickBack.countDown();
            exec.shutdownNow();
        }
        int slowPort = portOfRequest(1);
        int quickPort = portOfRequest(2);
        assertThat(slowPort).isNotEqualTo(quickPort);
        delay = r -> 0L;
        c.send(STATUS, Map.of(), null, "L-3");
        int third = portOfRequest(3);
        assertThat(third).isIn(slowPort, quickPort);
        return third == slowPort;
    }

    private int portOfRequest(int number) {
        return seen.stream()
                .filter(r -> r.number() == number)
                .findFirst()
                .orElseThrow()
                .remotePort();
    }

    // ------------------------------------------------------------------ retry

    @Test
    void SRS_INT_6_theBackOffWaitsTheConfiguredSecondsBetweenAttempts() {
        apiAnswer = r -> r.number() == 1 ? new Answer(503, "{}") : new Answer(200, "{\"ok\":1}");
        HrmsClient c =
                build(
                        settings(1, 1),
                        "STATIC",
                        new HrmsCredentialsProperties(null, null, null, null),
                        Map.of(STATUS, plain()));
        long started = System.nanoTime();
        HrmsClient.Response answered = c.send(STATUS, Map.of(), null, "R-1");
        long took = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertThat(answered.status()).isEqualTo(200);
        assertThat(answered.attempts()).isEqualTo(2);
        assertThat(took).isBetween(950L, 8000L);
    }

    @Test
    void SRS_INT_6_aRefusedLoginIsNotRetriedByTheRetryPolicy() {
        tokenAnswer = r -> new Answer(401, "{\"error_code\":\"BAD_LOGIN\"}");
        HrmsClient c = withLogin(2);
        assertThatThrownBy(() -> c.send(STATUS, Map.of("request_id", "1"), null, "T-1"))
                .isInstanceOf(FciException.class);
        assertThat(tokenCalls.get()).isEqualTo(1);
        assertThat(apiCalls.get()).isZero();
    }

    @Test
    void SRS_INT_4_aTokenAnswerWithoutATokenAndARefusedLoginAreToldApart() {
        tokenAnswer = r -> new Answer(200, "{\"status\":\"success\"}");
        HrmsClient c = withLogin(0);
        assertThatThrownBy(() -> c.send(STATUS, Map.of("request_id", "1"), null, "T-2"))
                .isInstanceOf(FciException.class);
        verify(refusals)
                .record(
                        eq("HRMS_API_AUTH"),
                        eq("Token request refused: HTTP 200, no token in the answer"));

        org.mockito.Mockito.clearInvocations(refusals);
        tokenAnswer = r -> new Answer(401, "{\"error_code\":\"BAD_LOGIN\"}");
        assertThatThrownBy(() -> c.send(STATUS, Map.of("request_id", "1"), null, "T-3"))
                .isInstanceOf(FciException.class);
        verify(refusals)
                .record(
                        eq("HRMS_API_AUTH"),
                        eq("Token request refused: HTTP 401, the login was refused"));
    }

    @Test
    void SRS_INT_6_theBulkheadAccessorFindsTheBulkheadOfTheNamedApiOnly() {
        HrmsClient c = simple(Map.of(STATUS, plain(), PUSH, plain()));
        c.send(STATUS, Map.of(), null, "H-1");
        c.send(PUSH, Map.of(), null, "H-2");
        assertThat(c.bulkhead(STATUS.code()).orElseThrow().getName())
                .isEqualTo("hrms-" + STATUS.code());
        assertThat(c.bulkhead(PUSH.code()).orElseThrow().getName())
                .isEqualTo("hrms-" + PUSH.code());
        assertThat(c.bulkhead("no_such_api")).isEmpty();
    }

    @Test
    void SRS_INT_6_theConstructorWithoutTelemetrySettingsBuildsAWorkingClient() {
        Map<String, ApiUrl> urls = new HashMap<>();
        Map<String, Limit> limits = new HashMap<>();
        urls.put(STATUS.code(), new ApiUrl(STATUS.collectionPath(), "OUTBOUND", null));
        limits.put(STATUS.code(), new Limit(4, 2000));
        HrmsClient c =
                new HrmsClient(
                        new HrmsApiProperties(url(), urls, limits, null, null),
                        new HrmsClientProperties(5, 10, 2, 30, 60, 1, KEY, null, "STATIC", null),
                        new HrmsCredentialsProperties(null, null, null, null),
                        code -> Optional.of(settings(0, 0)),
                        intake,
                        store,
                        alerts,
                        refusals);
        clients.add(c);
        assertThat(c.send(STATUS, Map.of(), null, "K-8").status()).isEqualTo(200);
    }
}
