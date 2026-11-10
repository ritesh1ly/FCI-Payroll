package in.gov.fci.payroll.integration.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import in.gov.fci.payroll.integration.api.HrmsApi;
import in.gov.fci.payroll.integration.api.InterfaceIntake;
import in.gov.fci.payroll.integration.api.InterfaceSettings.Settings;
import in.gov.fci.payroll.integration.config.HrmsApiProperties;
import in.gov.fci.payroll.integration.config.HrmsApiProperties.ApiUrl;
import in.gov.fci.payroll.integration.config.HrmsApiProperties.Limit;
import in.gov.fci.payroll.integration.config.HrmsClientProperties;
import in.gov.fci.payroll.platform.error.FciException;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
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
import java.util.function.IntFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The outbound WebClient (owner 2026-11-05; SRS-INT-6, SRS-INT-16, SRS-INT-30, SRS-INT-35; R-INT,
 * R-PII) against a JDK HTTP server: the URL and bulkhead settings, retry only on 5xx / I/O /
 * timeout with one Idempotency-Key, no retry on a 4xx, bulkhead isolation between APIs, the
 * connection pool (acquire timeout, keep-alive reuse, idle eviction), and no secret in what is
 * reported.
 */
class HrmsClientTest {

    private static final String KEY = "test-only-api-key-0001";
    private static final String BEARER = "test-only-bearer-0001";
    private static final HrmsApi STATUS_API = HrmsApi.GET_OTA_STATUS;
    private static final HrmsApi PUSH_API = HrmsApi.GET_MEDICAL_CLAIM_STATUS;

    /** One request as the server saw it. */
    private record Seen(
            String method,
            String uri,
            String idempotencyKey,
            String apiKey,
            String authorization,
            String body,
            int remotePort) {}

    private HttpServer server;
    private final List<Seen> seen = new CopyOnWriteArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();
    private volatile IntFunction<Integer> statusOf = n -> 200;
    private volatile long delayMillis = 0;

    /**
     * Holds the request with this idempotency key at the server until the test releases it, so a
     * test about the pool decides the order of events itself instead of racing a fixed delay.
     */
    private volatile String heldKey;

    private final java.util.concurrent.CountDownLatch release =
            new java.util.concurrent.CountDownLatch(1);

    /** A scripted answer that replaces the plain one. */
    private volatile Special special;

    /** One scripted server behaviour. */
    @FunctionalInterface
    private interface Special {
        void answer(com.sun.net.httpserver.HttpExchange exchange) throws IOException;
    }

    private final List<HrmsClient> clients = new ArrayList<>();

    private InterfaceIntake intake;
    private IntegrationStore store;
    private FailureAlerts alerts;
    private HrmsRefusals refusals;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext(
                "/",
                exchange -> {
                    int n = calls.incrementAndGet();
                    String body =
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8);
                    seen.add(
                            new Seen(
                                    exchange.getRequestMethod(),
                                    exchange.getRequestURI().toString(),
                                    exchange.getRequestHeaders().getFirst("Idempotency-Key"),
                                    exchange.getRequestHeaders().getFirst("api-key"),
                                    exchange.getRequestHeaders().getFirst("Authorization"),
                                    body,
                                    exchange.getRemoteAddress().getPort()));
                    if (special != null) {
                        special.answer(exchange);
                        exchange.close();
                        return;
                    }
                    String key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
                    if (key != null && key.equals(heldKey)) {
                        try {
                            release.await(30, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    if (delayMillis > 0) {
                        try {
                            Thread.sleep(delayMillis);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    byte[] answer = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(statusOf.apply(n), answer.length);
                    exchange.getResponseBody().write(answer);
                    exchange.close();
                });
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
    }

    @AfterEach
    void stop() {
        server.stop(0);
        clients.forEach(HrmsClient::close);
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

    private HrmsClient client(
            Settings settings,
            Map<String, Limit> limits,
            HrmsClientProperties pool,
            Map<String, ApiUrl> apis) {
        HrmsClient c =
                new HrmsClient(
                        new HrmsApiProperties(url(), apis, limits, null),
                        pool,
                        code -> Optional.ofNullable(settings),
                        intake,
                        store,
                        alerts,
                        refusals);
        clients.add(c);
        return c;
    }

    private static HrmsClientProperties pool(int connections, int pendingSeconds, int idleSeconds) {
        return new HrmsClientProperties(
                connections, 10, pendingSeconds, idleSeconds, 60, 1, KEY, null, BEARER, null);
    }

    private static Map<String, ApiUrl> outbound(HrmsApi... apis) {
        Map<String, ApiUrl> m = new java.util.HashMap<>();
        for (HrmsApi api : apis) {
            m.put(api.code(), new ApiUrl(api.collectionPath(), "OUTBOUND"));
        }
        return m;
    }

    private static Map<String, Limit> limit(int concurrent, int waitMillis, String... codes) {
        Map<String, Limit> m = new java.util.HashMap<>();
        for (String code : codes) {
            m.put(code, new Limit(concurrent, waitMillis));
        }
        return m;
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

    /**
     * The first call of a JVM loads Netty and resolves the host: warm up before timing anything.
     */
    private void warm(HrmsClient c, HrmsApi... apis) {
        long before = delayMillis;
        delayMillis = 0;
        for (HrmsApi api : apis) {
            warmOne(c, api);
        }
        delayMillis = before;
        seen.clear();
        calls.set(0);
    }

    private HrmsClient standard(int retries) {
        return client(
                settings(retries, 3),
                limit(4, 200, STATUS_API.code(), PUSH_API.code(), "connection_test"),
                pool(10, 2, 30),
                outbound(STATUS_API, PUSH_API));
    }

    // SRS-INT-16: the URL is one setting per API; unset or published-by-Payroll = not configured.
    @Test
    void SRS_INT_16_anApiWithoutAnOutboundUrlIsRefusedWithoutACall() {
        HrmsClient c =
                client(
                        settings(0, 3),
                        limit(4, 200, STATUS_API.code()),
                        pool(5, 2, 30),
                        Map.of(STATUS_API.code(), new ApiUrl("get-ota-status")));
        assertThatThrownBy(() -> c.send(STATUS_API, Map.of(), null, null))
                .isInstanceOf(FciException.class)
                .hasMessage("INT-503-HRMS-API-NOT-CONFIGURED");
        assertThatThrownBy(() -> c.send(HrmsApi.REQUEST_OTA, Map.of(), "{}", null))
                .hasMessage("INT-503-HRMS-API-NOT-CONFIGURED");
        assertThat(calls).hasValue(0);
    }

    @Test
    void SRS_INT_6_anApiWithoutABulkheadLimitIsRefusedWithoutACall() {
        HrmsClient c = client(settings(0, 3), Map.of(), pool(5, 2, 30), outbound(STATUS_API));
        assertThatThrownBy(() -> c.send(STATUS_API, Map.of(), null, null))
                .hasMessage("INT-503-HRMS-API-NOT-CONFIGURED");
        assertThat(calls).hasValue(0);
        HrmsClient invalid =
                client(
                        settings(0, 3),
                        Map.of(STATUS_API.code(), new Limit(0, 10)),
                        pool(5, 2, 30),
                        outbound(STATUS_API));
        assertThatThrownBy(() -> invalid.send(STATUS_API, Map.of(), null, null))
                .hasMessage("INT-503-HRMS-API-NOT-CONFIGURED");
    }

    @Test
    void SRS_INT_16_anInterfaceWithoutSettingsInForceIsRefusedWithoutACall() {
        HrmsClient c =
                new HrmsClient(
                        new HrmsApiProperties(
                                url(),
                                outbound(STATUS_API),
                                limit(2, 100, STATUS_API.code()),
                                null),
                        pool(5, 2, 30),
                        code -> Optional.empty(),
                        intake,
                        store,
                        alerts,
                        refusals);
        clients.add(c);
        assertThatThrownBy(() -> c.send(STATUS_API, Map.of(), null, null))
                .hasMessage("INT-503-NOT-CONFIGURED");
        assertThat(calls).hasValue(0);
    }

    @Test
    void SRS_INT_30_aCallCarriesTheKeyTheTokenAndAnIdempotencyKey() {
        HrmsClient c = standard(0);
        HrmsClient.Response r =
                c.send(STATUS_API, Map.of("request_id", "4516", "emp_ota_id", "9"), null, "K-1");
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body()).contains("ok");
        assertThat(r.attempts()).isEqualTo(1);
        Seen s = seen.getFirst();
        assertThat(s.method()).isEqualTo("GET");
        assertThat(s.uri())
                .startsWith(STATUS_API.collectionPath())
                .contains("request_id=4516")
                .contains("emp_ota_id=9");
        assertThat(s.apiKey()).isEqualTo(KEY);
        assertThat(s.authorization()).isEqualTo("Bearer " + BEARER);
        assertThat(s.idempotencyKey()).isEqualTo("K-1");
        verify(intake).completeCounts(eq(7L), eq(1L), eq(1L), eq(0L), contains("HTTP 200"));
    }

    @Test
    void SRS_INT_30_aCallWithoutAKeyGetsAFreshOne() {
        HrmsClient c = standard(0);
        c.send(STATUS_API, Map.of(), null, null);
        c.send(STATUS_API, Map.of(), null, null);
        assertThat(seen.get(0).idempotencyKey())
                .isNotBlank()
                .isNotEqualTo(seen.get(1).idempotencyKey());
    }

    // SRS-INT-6: a 5xx is retried with the same Idempotency-Key until it succeeds.
    @Test
    void SRS_INT_6_SRS_INT_30_aServerErrorIsRetriedWithTheSameKeyThenSucceeds() {
        statusOf = n -> n < 3 ? 503 : 200;
        HrmsClient c = standard(3);
        HrmsClient.Response r = c.send(STATUS_API, Map.of(), null, "K-2");
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.attempts()).isEqualTo(3);
        assertThat(seen)
                .hasSize(3)
                .allSatisfy(s -> assertThat(s.idempotencyKey()).isEqualTo("K-2"));
    }

    @Test
    void SRS_INT_6_retriesExhaustedFailTheCallAndQueueTheFailure() {
        statusOf = n -> 500;
        HrmsClient c = standard(2);
        assertThatThrownBy(() -> c.send(STATUS_API, Map.of(), null, "K-3"))
                .isInstanceOf(FciException.class)
                .hasMessage("INT-502-CALL-FAILED")
                .satisfies(
                        e -> assertThat(e.toString()).doesNotContain(KEY).doesNotContain(BEARER));
        assertThat(seen).hasSize(3);
        verify(alerts)
                .raise(
                        any(),
                        org.mockito.ArgumentMatchers.isNull(),
                        org.mockito.ArgumentMatchers.isNull(),
                        org.mockito.ArgumentMatchers.isNull(),
                        contains("3 attempt(s)"));
        verify(intake).completeCounts(eq(7L), eq(1L), eq(0L), eq(1L), contains("3 attempt(s)"));
    }

    @Test
    void SRS_INT_6_aClientErrorIsNeverRetriedAndIsReturnedAndQueued() {
        statusOf = n -> 401;
        HrmsClient c = standard(3);
        HrmsClient.Response r = c.send(STATUS_API, Map.of(), null, "K-4");
        assertThat(r.status()).isEqualTo(401);
        assertThat(r.attempts()).isEqualTo(1);
        assertThat(seen).hasSize(1);
        verify(alerts)
                .raise(
                        any(),
                        org.mockito.ArgumentMatchers.isNull(),
                        org.mockito.ArgumentMatchers.isNull(),
                        org.mockito.ArgumentMatchers.isNull(),
                        contains("HTTP 401"));
        verify(intake).completeCounts(eq(7L), eq(1L), eq(0L), eq(1L), contains("HTTP 401"));
    }

    @Test
    void SRS_INT_16_aSlowAnswerTimesOutAtTheInterfacesLimitAndIsRetried() {
        delayMillis = 2500;
        HrmsClient c =
                client(
                        settings(1, 1),
                        limit(4, 200, STATUS_API.code()),
                        pool(10, 2, 30),
                        outbound(STATUS_API));
        assertThatThrownBy(() -> c.send(STATUS_API, Map.of(), null, "K-5"))
                .hasMessage("INT-502-CALL-FAILED");
        // Two attempts were made (the original and one retry), each cut at the time limit.
        assertThat(calls.get()).isBetween(1, 2);
        verify(intake).completeCounts(eq(7L), eq(1L), eq(0L), eq(1L), contains("2 attempt(s)"));
    }

    @Test
    void SRS_INT_6_anUnreachableHostIsRetriedThenFails() {
        HrmsClient c =
                new HrmsClient(
                        new HrmsApiProperties(
                                "http://127.0.0.1:1",
                                outbound(STATUS_API),
                                limit(2, 100, STATUS_API.code()),
                                null),
                        pool(5, 2, 30),
                        code -> Optional.of(settings(1, 1)),
                        intake,
                        store,
                        alerts,
                        refusals);
        clients.add(c);
        assertThatThrownBy(() -> c.send(STATUS_API, Map.of(), null, null))
                .hasMessage("INT-502-CALL-FAILED");
        verify(intake).completeCounts(eq(7L), eq(1L), eq(0L), eq(1L), contains("2 attempt(s)"));
    }

    // R-INT: one slow API cannot use up another API's calls.
    @Test
    void R_INT_aFullBulkheadRefusesWhileAnotherApisBulkheadStillServes() throws Exception {
        delayMillis = 800;
        HrmsClient c =
                client(
                        settings(0, 5),
                        Map.of(
                                STATUS_API.code(),
                                new Limit(1, 0),
                                PUSH_API.code(),
                                new Limit(1, 0)),
                        pool(10, 2, 30),
                        outbound(STATUS_API, PUSH_API));
        warm(c, STATUS_API, PUSH_API);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            CountDownLatch inside = new CountDownLatch(1);
            Future<HrmsClient.Response> slow =
                    pool.submit(
                            () -> {
                                inside.countDown();
                                return c.send(STATUS_API, Map.of(), null, "S-1");
                            });
            inside.await();
            Thread.sleep(250);
            assertThatThrownBy(() -> c.send(STATUS_API, Map.of(), null, "S-2"))
                    .isInstanceOf(FciException.class)
                    .hasMessage("INT-503-BULKHEAD-FULL");
            verify(refusals).record(anyString(), contains("limit of concurrent calls"));
            // The other API has its own bulkhead and is served at once.
            assertThat(c.send(PUSH_API, Map.of(), null, "P-1").status()).isEqualTo(200);
            assertThat(slow.get(10, TimeUnit.SECONDS).status()).isEqualTo(200);
        } finally {
            pool.shutdownNow();
        }
        assertThat(seen.stream().map(Seen::idempotencyKey)).doesNotContain("S-2");
    }

    @Test
    void R_INT_aWaitingCallIsServedWhenTheBulkheadFreesWithinItsMaxWait() throws Exception {
        delayMillis = 400;
        HrmsClient c =
                client(
                        settings(0, 5),
                        Map.of(STATUS_API.code(), new Limit(1, 3000)),
                        pool(10, 2, 30),
                        outbound(STATUS_API));
        warm(c, STATUS_API);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<HrmsClient.Response> first =
                    pool.submit(() -> c.send(STATUS_API, Map.of(), null, "W-1"));
            Thread.sleep(100);
            Future<HrmsClient.Response> second =
                    pool.submit(() -> c.send(STATUS_API, Map.of(), null, "W-2"));
            assertThat(first.get(10, TimeUnit.SECONDS).status()).isEqualTo(200);
            assertThat(second.get(10, TimeUnit.SECONDS).status()).isEqualTo(200);
        } finally {
            pool.shutdownNow();
        }
    }

    // The pool bounds connections: a caller that cannot get one within the pending timeout fails.
    @Test
    void R_INT_anExhaustedPoolFailsTheWaitingCallAtThePendingAcquireTimeout() throws Exception {
        HrmsClient c =
                client(
                        settings(0, 6),
                        limit(4, 0, STATUS_API.code(), PUSH_API.code()),
                        pool(1, 1, 30),
                        outbound(STATUS_API, PUSH_API));
        warm(c, STATUS_API);
        // The holder keeps the pool's only connection until the waiting call has been refused:
        // the server answers H-1 only when the test releases it (a fixed delay raced the 1 s
        // pending-acquire timeout on a loaded machine and let H-2 through).
        heldKey = "H-1";
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<HrmsClient.Response> holder =
                    pool.submit(() -> c.send(STATUS_API, Map.of(), null, "H-1"));
            // Deterministic: wait until the holder's request is in flight at the server (the pool's
            // only
            // connection is then taken), not a fixed time that a loaded machine can overrun.
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (seen.stream().noneMatch(r -> "H-1".equals(r.idempotencyKey()))
                    && System.nanoTime() < until) {
                Thread.sleep(10);
            }
            assertThat(seen).anyMatch(r -> "H-1".equals(r.idempotencyKey()));
            assertThatThrownBy(() -> c.send(PUSH_API, Map.of(), null, "H-2"))
                    .isInstanceOf(FciException.class)
                    .hasMessage("INT-502-CALL-FAILED");
            release.countDown();
            assertThat(holder.get(10, TimeUnit.SECONDS).status()).isEqualTo(200);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThat(seen.stream().map(Seen::idempotencyKey)).doesNotContain("H-2");
    }

    @Test
    void R_INT_aKeepAliveConnectionIsReusedAndAnIdleOneIsEvicted() throws Exception {
        HrmsClient c =
                client(
                        settings(0, 3),
                        limit(4, 200, STATUS_API.code()),
                        pool(5, 2, 1),
                        outbound(STATUS_API));
        c.send(STATUS_API, Map.of(), null, "R-1");
        c.send(STATUS_API, Map.of(), null, "R-2");
        assertThat(seen.get(1).remotePort())
                .as("same connection reused")
                .isEqualTo(seen.get(0).remotePort());
        Thread.sleep(3500);
        c.send(STATUS_API, Map.of(), null, "R-3");
        assertThat(seen.get(2).remotePort())
                .as("idle connection evicted")
                .isNotEqualTo(seen.get(0).remotePort());
    }

    // SRS-INT-35: the payment status push names the payment and the status, with a key fixed by
    // both.
    @Test
    void SRS_INT_35_aStatusPushIsOneMessageWhoseKeyIsFixedByPaymentAndStatus() {
        statusOf = n -> n == 1 ? 502 : 200;
        HrmsClient c =
                client(
                        settings(2, 3),
                        limit(4, 200, HrmsApi.REQUEST_OTA.code()),
                        pool(5, 2, 30),
                        Map.of(HrmsApi.REQUEST_OTA.code(), new ApiUrl("/status", "OUTBOUND")));
        HrmsClient.Response r =
                c.pushPaymentStatus(HrmsApi.REQUEST_OTA, "4516", "Paid", new BigDecimal("1000.50"));
        assertThat(r.status()).isEqualTo(200);
        assertThat(seen).hasSize(2);
        assertThat(seen.get(0).idempotencyKey()).isEqualTo("PAYSTATUS-request_ota-4516-Paid");
        assertThat(seen.get(1).idempotencyKey()).isEqualTo(seen.get(0).idempotencyKey());
        assertThat(seen.get(0).method()).isEqualTo("POST");
        assertThat(seen.get(0).body())
                .isEqualTo("{\"request_id\":\"4516\",\"status\":\"Paid\",\"amount\":1000.50}");
        c.pushPaymentStatus(HrmsApi.REQUEST_OTA, "a\"b", "x\\y", null);
        assertThat(seen.get(2).body())
                .isEqualTo("{\"request_id\":\"a\\\"b\",\"status\":\"x\\\\y\",\"amount\":null}");
    }

    @Test
    void SRS_INT_33_testConnectionCallsTheBaseAddressThroughItsOwnBulkhead() {
        HrmsClient c = standard(0);
        assertThat(c.probe().status()).isEqualTo(200);
        assertThat(seen.getFirst().apiKey()).isEqualTo(KEY);
        assertThat(c.bulkhead("connection_test")).isPresent();
        HrmsClient noBase =
                new HrmsClient(
                        new HrmsApiProperties(" ", Map.of(), limit(1, 1, "connection_test"), null),
                        pool(5, 2, 30),
                        code -> Optional.of(settings(0, 1)),
                        intake,
                        store,
                        alerts,
                        refusals);
        clients.add(noBase);
        assertThatThrownBy(noBase::probe).hasMessage("INT-503-HRMS-API-NOT-CONFIGURED");
        HrmsClient noLimit = client(settings(0, 3), Map.of(), pool(5, 2, 30), Map.of());
        assertThatThrownBy(noLimit::probe).hasMessage("INT-503-HRMS-API-NOT-CONFIGURED");
    }

    @Test
    void SRS_INT_33_theAdapterReportsOneLineWithoutACredential() {
        HrmsConnectionAdapter adapter = new HrmsConnectionAdapter(standard(0));
        assertThat(adapter.interfaceCode()).isEqualTo("HRMS_PAYMENT_STATUS");
        assertThat(adapter.testConnection())
                .isEqualTo(
                        "HRMS answered HTTP 200 after 1 attempt(s); static bearer token configured")
                .doesNotContain(KEY)
                .doesNotContain(BEARER);
        assertThatThrownBy(() -> adapter.reprocess(1L, "X", "{}"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void SRS_INT_16_theInterfaceOfAnApiIsItsStagingInterfaceOrThePaymentStatusOne() {
        assertThat(HrmsClient.interfaceOf(HrmsApi.REQUEST_OTA)).isEqualTo("HRMS_EMP_OTA");
        assertThat(HrmsClient.interfaceOf(HrmsApi.GET_OTA_STATUS)).isEqualTo("HRMS_PAYMENT_STATUS");
    }

    @Test
    void R_PII_nothingIsSentWhenNoSecretIsSetAndAnApiThatNeedsABearerIsNotCalled() {
        HrmsClient c =
                client(
                        settings(0, 3),
                        limit(4, 200, STATUS_API.code(), "connection_test"),
                        new HrmsClientProperties(5, 5, 2, 30, 60, 5, null, null, null, null),
                        outbound(STATUS_API));
        // The contract says this API carries a bearer token; none can be obtained, so no call is
        // made.
        assertThatThrownBy(() -> c.send(STATUS_API, Map.of(), null, "N-1"))
                .isInstanceOf(FciException.class)
                .hasMessage("INT-503-HRMS-API-NOT-CONFIGURED");
        assertThat(seen).isEmpty();
        // Welcome states only the api-key; with no key set it still goes, with no header at all.
        c.probe();
        assertThat(seen.getFirst().apiKey()).isNull();
        assertThat(seen.getFirst().authorization()).isNull();
        assertThat(new HrmsClientProperties(5, 5, 2, 30, 60, 5, KEY, null, BEARER, null).toString())
                .doesNotContain(KEY)
                .doesNotContain(BEARER);
        verify(refusals, never()).record(anyString(), anyString());
        verify(intake, never()).rejected(anyLong(), anyString());
    }

    private static void body(com.sun.net.httpserver.HttpExchange exchange, int status, byte[] bytes)
            throws IOException {
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
    }

    private HrmsClient aimedAt(String base, int retries, int timeoutSeconds) {
        HrmsClient c =
                new HrmsClient(
                        new HrmsApiProperties(
                                base, outbound(STATUS_API), limit(4, 100, STATUS_API.code()), null),
                        pool(5, 2, 30),
                        code -> Optional.of(settings(retries, timeoutSeconds)),
                        intake,
                        store,
                        alerts,
                        refusals);
        clients.add(c);
        return c;
    }

    @Test
    void SRS_INT_6_aHostThatDoesNotResolveFailsAfterTheRetries() {
        HrmsClient c = aimedAt("http://no-such-host.invalid", 1, 2);
        assertThatThrownBy(() -> c.send(STATUS_API, Map.of(), null, "D-1"))
                .hasMessage("INT-502-CALL-FAILED");
        verify(intake).completeCounts(eq(7L), eq(1L), eq(0L), eq(1L), contains("2 attempt(s)"));
    }

    @Test
    void SRS_INT_6_aTlsHandshakeAgainstAPlainServerFailsAndIsNeverDowngraded() {
        HrmsClient c = aimedAt("https://127.0.0.1:" + server.getAddress().getPort(), 0, 2);
        assertThatThrownBy(() -> c.send(STATUS_API, Map.of(), null, "T-1"))
                .hasMessage("INT-502-CALL-FAILED");
        assertThat(seen).as("nothing reached the server in clear text").isEmpty();
    }

    @Test
    void SRS_INT_16_aConnectThatNeverCompletesIsCutAtTheTimeLimit() {
        // 10.255.255.1 is not routable: the connect either hangs or is refused at once; both fail.
        HrmsClient c = aimedAt("http://10.255.255.1:81", 0, 1);
        long started = System.nanoTime();
        assertThatThrownBy(() -> c.send(STATUS_API, Map.of(), null, "C-1"))
                .hasMessage("INT-502-CALL-FAILED");
        assertThat(java.util.concurrent.TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started))
                .isLessThan(5);
    }

    @Test
    void SRS_INT_6_aResponseOverTheSizeLimitFailsTheCallInsteadOfFillingMemory() {
        special = x -> body(x, 200, new byte[2 * 1024 * 1024]);
        HrmsClient c = standard(0);
        assertThatThrownBy(() -> c.send(STATUS_API, Map.of(), null, "B-1"))
                .hasMessage("INT-502-CALL-FAILED");
    }

    @Test
    void SRS_INT_6_aServerThatTricklesItsAnswerIsCutAtTheOverallLimit() {
        special =
                x -> {
                    x.sendResponseHeaders(200, 0);
                    try {
                        for (int i = 0; i < 20; i++) {
                            x.getResponseBody().write(120);
                            x.getResponseBody().flush();
                            Thread.sleep(300);
                        }
                    } catch (IOException | InterruptedException e) {
                        // the client gave up
                    }
                };
        HrmsClient c =
                client(
                        settings(0, 1),
                        limit(4, 100, STATUS_API.code()),
                        pool(5, 2, 30),
                        outbound(STATUS_API));
        long started = System.nanoTime();
        assertThatThrownBy(() -> c.send(STATUS_API, Map.of(), null, "L-1"))
                .hasMessage("INT-502-CALL-FAILED");
        assertThat(java.util.concurrent.TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started))
                .isLessThan(5);
    }

    @Test
    void SRS_INT_6_aRedirectIsNotFollowed() {
        special =
                x -> {
                    x.getResponseHeaders().add("Location", "http://127.0.0.1:1/elsewhere");
                    body(x, 302, new byte[0]);
                };
        HrmsClient c = standard(2);
        HrmsClient.Response r = c.send(STATUS_API, Map.of(), null, "R-9");
        assertThat(r.status()).isEqualTo(302);
        assertThat(seen).hasSize(1);
    }

    @Test
    void SRS_INT_6_anEmptyMalformedOrErrorBodyIsReturnedAsReceivedNotInterpreted() {
        special = x -> body(x, 200, new byte[0]);
        HrmsClient c = standard(0);
        assertThat(c.send(STATUS_API, Map.of(), null, "E-1").body()).isEmpty();
        special = x -> body(x, 200, "{not json".getBytes(StandardCharsets.UTF_8));
        assertThat(c.send(STATUS_API, Map.of(), null, "E-2").body()).isEqualTo("{not json");
        special = x -> body(x, 200, "{\"error\":\"failed\"}".getBytes(StandardCharsets.UTF_8));
        HrmsClient.Response r = c.send(STATUS_API, Map.of(), null, "E-3");
        assertThat(r.status())
                .as("the caller reads the body; the client does not guess")
                .isEqualTo(200);
        assertThat(r.body()).contains("failed");
    }

    @Test
    void SRS_INT_6_aCallAfterShutdownFailsCleanly() {
        HrmsClient c = standard(0);
        c.send(STATUS_API, Map.of(), null, "S-0");
        c.close();
        assertThatThrownBy(() -> c.send(STATUS_API, Map.of(), null, "S-1"))
                .hasMessage("INT-502-CALL-FAILED");
    }

    @Test
    void R_PII_noLogLineCarriesTheKeyTheTokenTheBodyOrTheQuery() {
        ch.qos.logback.classic.Logger root =
                (ch.qos.logback.classic.Logger)
                        org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ch.qos.logback.classic.Level before = root.getLevel();
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logs =
                new ch.qos.logback.core.read.ListAppender<>();
        logs.start();
        root.addAppender(logs);
        root.setLevel(ch.qos.logback.classic.Level.INFO);
        try {
            HrmsClient c = standard(1);
            c.send(STATUS_API, Map.of("emp_num", "SECRET-EMP"), null, "G-1");
            statusOf = n -> 500;
            assertThatThrownBy(
                            () -> c.send(STATUS_API, Map.of("emp_num", "SECRET-EMP"), null, "G-2"))
                    .isInstanceOf(FciException.class);
        } finally {
            root.detachAppender(logs);
            root.setLevel(before);
        }
        assertThat(logs.list).isNotEmpty();
        for (var event : logs.list) {
            String line = event.getFormattedMessage() + " " + event.getThrowableProxy();
            assertThat(line)
                    .doesNotContain(KEY)
                    .doesNotContain(BEARER)
                    .doesNotContain("SECRET-EMP")
                    .doesNotContain("SECRET-REQ")
                    .doesNotContain("123456.78");
        }
    }

    @Test
    void SRS_INT_33_testConnectionWithNoBaseAddressAtAllIsNotConfiguredAndMakesNoCall() {
        HrmsClient noBase =
                new HrmsClient(
                        new HrmsApiProperties(null, Map.of(), limit(1, 1, "connection_test"), null),
                        pool(5, 2, 30),
                        code -> Optional.of(settings(0, 1)),
                        intake,
                        store,
                        alerts,
                        refusals);
        clients.add(noBase);
        assertThatThrownBy(noBase::probe).hasMessage("INT-503-HRMS-API-NOT-CONFIGURED");
        assertThat(seen).isEmpty();
    }

    @Test
    void SRS_INT_35_aPushWithNoRequestIdOrStatusSendsEmptyTextAndAKeyThatStillNamesBoth() {
        HrmsClient c =
                client(
                        settings(0, 3),
                        limit(4, 200, HrmsApi.REQUEST_OTA.code()),
                        pool(5, 2, 30),
                        Map.of(HrmsApi.REQUEST_OTA.code(), new ApiUrl("/status", "OUTBOUND")));
        HrmsClient.Response r =
                c.pushPaymentStatus(HrmsApi.REQUEST_OTA, null, null, BigDecimal.ZERO);
        assertThat(r.status()).isEqualTo(200);
        assertThat(seen.getFirst().body())
                .isEqualTo("{\"request_id\":\"\",\"status\":\"\",\"amount\":0}");
        assertThat(seen.getFirst().idempotencyKey()).isEqualTo("PAYSTATUS-request_ota-null-null");
    }
}
