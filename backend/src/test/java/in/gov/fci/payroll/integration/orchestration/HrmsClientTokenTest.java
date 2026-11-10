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
import in.gov.fci.payroll.integration.config.HrmsApiProperties;
import in.gov.fci.payroll.integration.config.HrmsApiProperties.ApiUrl;
import in.gov.fci.payroll.integration.config.HrmsApiProperties.Limit;
import in.gov.fci.payroll.integration.config.HrmsClientProperties;
import in.gov.fci.payroll.integration.config.HrmsCredentialsProperties;
import in.gov.fci.payroll.platform.error.FciException;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * SRS-INT-4, SRS-INT-6, R-INT, R-PII: how Payroll authenticates to HRMS as the contract states -
 * the api-key and the bearer token only where the operation's security says so; the token taken
 * from {@code POST token} with the configured login, kept until shortly before it expires, renewed
 * once on a 401, fetched by one caller at a time; every refusal of the login surfaces as a failed
 * call and no business call is sent without a token. Against a JDK HTTP server.
 */
class HrmsClientTokenTest {

    private static final String KEY = "test-only-api-key-0002";
    private static final HrmsApi STATUS = HrmsApi.GET_OTA_STATUS;
    private static final Map<String, String> QUERY = Map.of("request_id", "1", "emp_ota_id", "2");

    /** What the server answers. */
    private record Answer(int status, String body) {}

    /** One request as the server saw it. */
    private record Seen(
            String method, String path, String apiKey, String authorization, String body) {}

    private HttpServer server;
    private final List<Seen> seen = new CopyOnWriteArrayList<>();
    private final AtomicInteger tokenCalls = new AtomicInteger();
    private final AtomicInteger apiCalls = new AtomicInteger();
    private volatile Supplier<Answer> tokenAnswer;
    private volatile Function<String, Answer> apiAnswer =
            auth -> new Answer(200, "{\"status\":\"success\"}");
    private volatile long tokenDelayMillis;
    private final List<HrmsClient> clients = new ArrayList<>();
    private MutableClock clock;
    private InterfaceIntake intake;
    private HrmsRefusals refusals;
    private IntegrationStore store;

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

    private Supplier<Answer> issuing(String lifetimeJson) {
        return () -> {
            int n = tokenCalls.get();
            return new Answer(
                    200,
                    "{\"status\":\"success\",\"data\":{\"token\":\"T"
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
        intake = mock(InterfaceIntake.class);
        when(intake.open(anyString(), anyString(), any(), any())).thenReturn(7L);
        store = mock(IntegrationStore.class);
        when(store.interfaceOf(anyString()))
                .thenAnswer(
                        i ->
                                new IntegrationStore.Registered(
                                        1L, i.getArgument(0), "HRMS", "OUTBOUND"));
        refusals = mock(HrmsRefusals.class);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String path = exchange.getRequestURI().getPath();
        Answer answer;
        if (path.equals("/token")) {
            tokenCalls.incrementAndGet();
            seen.add(
                    new Seen(
                            exchange.getRequestMethod(),
                            path,
                            exchange.getRequestHeaders().getFirst("api-key"),
                            exchange.getRequestHeaders().getFirst("Authorization"),
                            body));
            if (tokenDelayMillis > 0) {
                try {
                    Thread.sleep(tokenDelayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            answer = tokenAnswer.get();
        } else {
            apiCalls.incrementAndGet();
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            seen.add(
                    new Seen(
                            exchange.getRequestMethod(),
                            path,
                            exchange.getRequestHeaders().getFirst("api-key"),
                            auth,
                            body));
            answer = apiAnswer.apply(auth);
        }
        byte[] bytes = answer.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(answer.status(), bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        clients.forEach(HrmsClient::close);
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static Settings settings() {
        // A generous call budget (30 s): no test here is about the timeout, and a cold client's
        // first
        // exchange on a loaded machine once took over 5 s (INT-502-CALL-FAILED, TimeoutException).
        return new Settings("X", 0, 0, "FIXED", 30, false, null, null, null, LocalDate.now(), true);
    }

    private HrmsClient client(
            HrmsClientProperties pool,
            HrmsCredentialsProperties login,
            HrmsContractCatalog contract,
            HrmsApi... apis) {
        Map<String, ApiUrl> urls = new HashMap<>();
        Map<String, Limit> limits = new HashMap<>();
        urls.put(HrmsApi.TOKEN.code(), new ApiUrl(HrmsApi.TOKEN.collectionPath(), "OUTBOUND"));
        limits.put(HrmsApi.TOKEN.code(), new Limit(4, 2000));
        limits.put("connection_test", new Limit(2, 2000));
        for (HrmsApi api : apis) {
            urls.put(api.code(), new ApiUrl(api.collectionPath(), "OUTBOUND"));
            limits.put(api.code(), new Limit(8, 2000));
        }
        HrmsClient c =
                new HrmsClient(
                        new HrmsApiProperties(url(), urls, limits, null),
                        pool,
                        login,
                        contract,
                        code -> Optional.of(settings()),
                        intake,
                        store,
                        mock(FailureAlerts.class),
                        refusals,
                        clock);
        clients.add(c);
        return c;
    }

    private static HrmsClientProperties pool(String staticBearer) {
        return new HrmsClientProperties(5, 5, 2, 30, 60, 1, KEY, null, staticBearer, null);
    }

    private HrmsClient standard() {
        return client(
                pool(null),
                new HrmsCredentialsProperties("payroll", "pw-secret", null, 60),
                HrmsContractCatalog.load(),
                STATUS,
                HrmsApi.REQUEST_OTA);
    }

    private List<Seen> apiSeen() {
        return seen.stream().filter(s -> !s.path().equals("/token")).toList();
    }

    @Test
    void SRS_INT_4_theTokenIsFetchedWithTheLoginAndTheApiKeyAndReusedForLaterCalls() {
        HrmsClient c = standard();
        assertThat(c.send(STATUS, QUERY, null, "K1").status()).isEqualTo(200);
        assertThat(c.send(STATUS, QUERY, null, "K2").status()).isEqualTo(200);
        assertThat(tokenCalls.get()).isEqualTo(1);
        Seen login = seen.getFirst();
        assertThat(login.method()).isEqualTo("POST");
        assertThat(login.apiKey()).isEqualTo(KEY);
        assertThat(login.authorization()).as("the token call sends no bearer").isNull();
        assertThat(JsonMapper.builder().build().readTree(login.body()).get("username").asString())
                .isEqualTo("payroll");
        assertThat(login.body()).contains("pw-secret");
        assertThat(apiSeen())
                .extracting(Seen::authorization)
                .containsExactly("Bearer T1", "Bearer T1");
        assertThat(apiSeen()).extracting(Seen::apiKey).containsOnly(KEY);
        verify(intake).open(eq("HRMS_API_AUTH"), anyString(), any(), any());
    }

    @Test
    void SRS_INT_4_welcomeSendsTheApiKeyAndNoBearerAndFetchesNoToken() {
        HrmsClient c = standard();
        assertThat(c.probe().status()).isEqualTo(200);
        assertThat(tokenCalls.get()).isZero();
        assertThat(apiSeen())
                .singleElement()
                .satisfies(
                        s -> {
                            assertThat(s.apiKey()).isEqualTo(KEY);
                            assertThat(s.authorization()).isNull();
                            assertThat(s.path()).isEqualTo("/");
                        });
    }

    @Test
    void SRS_INT_4_aStaticBearerTokenWinsAndNoTokenCallIsMade() {
        HrmsClient c =
                client(
                        pool("STATIC-1"),
                        new HrmsCredentialsProperties("payroll", "pw", null, 60),
                        HrmsContractCatalog.load(),
                        STATUS);
        c.send(STATUS, QUERY, null, null);
        assertThat(tokenCalls.get()).isZero();
        assertThat(apiSeen().getFirst().authorization()).isEqualTo("Bearer STATIC-1");
        assertThat(c.tokenCheck()).isEqualTo("static bearer token configured");
    }

    @Test
    void SRS_INT_4_a401OnAFetchedTokenRenewsItOnceAndRepeatsTheCall() {
        apiAnswer =
                auth ->
                        "Bearer T1".equals(auth)
                                ? new Answer(401, "{\"error_code\":\"TOKEN_EXPIRED\"}")
                                : new Answer(200, "{\"status\":\"success\"}");
        HrmsClient c = standard();
        assertThat(c.send(STATUS, QUERY, null, null).status()).isEqualTo(200);
        assertThat(tokenCalls.get()).isEqualTo(2);
        assertThat(apiSeen())
                .extracting(Seen::authorization)
                .containsExactly("Bearer T1", "Bearer T2");
    }

    @Test
    void SRS_INT_4_aSecond401IsReturnedAsTheAnswerAndNeverLoops() {
        apiAnswer = auth -> new Answer(401, "{\"error_code\":\"TOKEN_REVOKED\"}");
        HrmsClient c = standard();
        assertThat(c.send(STATUS, QUERY, null, null).status()).isEqualTo(401);
        // The login plus the default two renewals, then the answer is returned: bounded, never a
        // loop.
        assertThat(tokenCalls.get()).isEqualTo(3);
        assertThat(apiCalls.get()).isEqualTo(3);
    }

    @Test
    void SRS_INT_4_a401OnAStaticBearerIsReturnedWithoutARenewal() {
        apiAnswer = auth -> new Answer(401, "{\"error_code\":\"INVALID_TOKEN\"}");
        HrmsClient c =
                client(
                        pool("STATIC-2"),
                        new HrmsCredentialsProperties("payroll", "pw", null, 60),
                        HrmsContractCatalog.load(),
                        STATUS);
        assertThat(c.send(STATUS, QUERY, null, null).status()).isEqualTo(401);
        assertThat(tokenCalls.get()).isZero();
        assertThat(apiCalls.get()).isEqualTo(1);
    }

    @Test
    void SRS_INT_4_aRefusedLoginFailsTheCallAndNoBusinessCallIsSent() {
        tokenAnswer =
                () ->
                        new Answer(
                                401,
                                "{\"status\":\"error\",\"error_code\":\"INVALID_CREDENTIALS\"}");
        HrmsClient c = standard();
        assertThatThrownBy(() -> c.send(STATUS, QUERY, null, null))
                .isInstanceOf(FciException.class)
                .hasMessage("INT-502-CALL-FAILED");
        assertThat(apiCalls.get()).isZero();
        verify(refusals).record(eq("HRMS_API_AUTH"), contains("Token request refused"));
    }

    @Test
    void SRS_INT_4_everyKindOfBadTokenAnswerFailsTheCall() {
        List<Answer> bad =
                List.of(
                        new Answer(200, "{\"data\":{}}"),
                        new Answer(200, ""),
                        new Answer(200, "<html>gateway</html>"),
                        new Answer(423, "{\"error_code\":\"ACCOUNT_LOCKED\"}"),
                        new Answer(403, "{\"error_code\":\"USER_INACTIVE\"}"),
                        new Answer(429, "{\"error_code\":\"TOO_MANY_LOGIN_ATTEMPTS\"}"),
                        new Answer(500, "{\"error_code\":\"INTERNAL_SERVER_ERROR\"}"),
                        new Answer(503, ""));
        for (Answer a : bad) {
            tokenAnswer = () -> a;
            HrmsClient c = standard();
            assertThatThrownBy(() -> c.send(STATUS, QUERY, null, null))
                    .as(a.status() + " " + a.body())
                    .isInstanceOf(FciException.class)
                    .hasMessage("INT-502-CALL-FAILED");
        }
        assertThat(apiCalls.get()).isZero();
    }

    @Test
    void SRS_INT_16_withoutALoginOrAStaticTokenAnApiThatNeedsOneIsNotCalled() {
        HrmsClient c =
                client(
                        pool(null),
                        new HrmsCredentialsProperties(null, null, null, null),
                        HrmsContractCatalog.load(),
                        STATUS);
        assertThatThrownBy(() -> c.send(STATUS, QUERY, null, null))
                .isInstanceOf(FciException.class)
                .hasMessage("INT-503-HRMS-API-NOT-CONFIGURED");
        assertThat(seen).isEmpty();
        assertThat(c.tokenCheck()).isEqualTo("no login configured");
    }

    @Test
    void SRS_INT_16_aLoginWithoutAnOutboundTokenUrlIsNotConfigured() {
        HrmsClient c =
                new HrmsClient(
                        new HrmsApiProperties(
                                url(),
                                Map.of(
                                        STATUS.code(),
                                        new ApiUrl(STATUS.collectionPath(), "OUTBOUND")),
                                Map.of(STATUS.code(), new Limit(8, 2000)),
                                null),
                        pool(null),
                        new HrmsCredentialsProperties("payroll", "pw", null, 60),
                        HrmsContractCatalog.load(),
                        code -> Optional.of(settings()),
                        intake,
                        store,
                        mock(FailureAlerts.class),
                        refusals,
                        clock);
        clients.add(c);
        assertThatThrownBy(() -> c.send(STATUS, QUERY, null, null))
                .isInstanceOf(FciException.class)
                .hasMessage("INT-503-HRMS-API-NOT-CONFIGURED");
        assertThat(seen).isEmpty();
    }

    @Test
    void SRS_INT_4_aTokenIsRenewedOnlyWhenTheSkewBeforeItsExpiryIsReached() {
        tokenAnswer = issuing(",\"expires_in\":120");
        HrmsClient c = standard();
        c.send(STATUS, QUERY, null, null);
        clock.advance(59);
        c.send(STATUS, QUERY, null, null);
        assertThat(tokenCalls.get()).isEqualTo(1);
        clock.advance(2);
        c.send(STATUS, QUERY, null, null);
        assertThat(tokenCalls.get()).isEqualTo(2);
        assertThat(apiSeen())
                .extracting(Seen::authorization)
                .containsExactly("Bearer T1", "Bearer T1", "Bearer T2");
    }

    @Test
    void SRS_INT_4_aTokenWithoutAStatedLifetimeIsUsedForOneCallOnly() {
        tokenAnswer = issuing("");
        HrmsClient c = standard();
        c.send(STATUS, QUERY, null, null);
        c.send(STATUS, QUERY, null, null);
        assertThat(tokenCalls.get()).isEqualTo(2);
    }

    @Test
    void SRS_INT_4_concurrentCallsShareOneTokenFetch() throws Exception {
        tokenDelayMillis = 300;
        HrmsClient c = standard();
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                results.add(pool.submit(() -> c.send(STATUS, QUERY, null, null).status()));
            }
            for (Future<Integer> f : results) {
                assertThat(f.get(15, TimeUnit.SECONDS)).isEqualTo(200);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(tokenCalls.get()).isEqualTo(1);
    }

    @Test
    void SRS_INT_33_testConnectionReportsWhetherATokenCanBeObtained() {
        HrmsClient c = standard();
        assertThat(c.tokenCheck()).isEqualTo("token obtained");
        assertThat(c.tokenCheck()).isEqualTo("token obtained");
        assertThat(tokenCalls.get()).isEqualTo(1);
        tokenAnswer = () -> new Answer(401, "{}");
        HrmsClient other = standard();
        assertThatThrownBy(other::tokenCheck).isInstanceOf(FciException.class);
    }

    @Test
    void SRS_INT_4_anOperationThatStatesNoSecurityGetsNoApiKeyAndNoBearer() {
        HrmsContractCatalog open = openApi("[]");
        HrmsClient c =
                client(
                        pool(null),
                        new HrmsCredentialsProperties("payroll", "pw", null, 60),
                        open,
                        STATUS);
        assertThat(c.send(STATUS, QUERY, null, null).status()).isEqualTo(200);
        assertThat(tokenCalls.get()).isZero();
        assertThat(apiSeen().getFirst().apiKey()).isNull();
        assertThat(apiSeen().getFirst().authorization()).isNull();
    }

    @Test
    void SRS_INT_4_anOperationThatStatesOnlyAnApiKeyGetsNoBearerAndNoTokenFetch() {
        HrmsContractCatalog keyOnly = openApi("[{\"ApiKeyAuth\":[]}]");
        HrmsClient c =
                client(
                        pool(null),
                        new HrmsCredentialsProperties("payroll", "pw", null, 60),
                        keyOnly,
                        STATUS);
        c.send(STATUS, QUERY, null, null);
        assertThat(tokenCalls.get()).isZero();
        assertThat(apiSeen().getFirst().apiKey()).isEqualTo(KEY);
        assertThat(apiSeen().getFirst().authorization()).isNull();
    }

    @Test
    void SRS_INT_4_anOperationThatStatesOnlyABearerGetsNoApiKey() {
        HrmsContractCatalog bearerOnly = openApi("[{\"BearerAuth\":[]}]");
        HrmsClient c =
                client(
                        pool(null),
                        new HrmsCredentialsProperties("payroll", "pw", null, 60),
                        bearerOnly,
                        STATUS);
        c.send(STATUS, QUERY, null, null);
        assertThat(apiSeen().getFirst().apiKey()).isNull();
        assertThat(apiSeen().getFirst().authorization()).isEqualTo("Bearer T1");
    }

    @Test
    void SRS_INT_4_anApiMissingFromTheContractIsSentWithBothHeadersToBeSafe() {
        HrmsContractCatalog empty =
                HrmsContractCatalog.parse(JsonMapper.builder().build().readTree("{\"paths\":{}}"));
        HrmsClient c =
                client(
                        pool(null),
                        new HrmsCredentialsProperties("payroll", "pw", null, 60),
                        empty,
                        STATUS);
        c.send(STATUS, QUERY, null, null);
        assertThat(apiSeen().getFirst().apiKey()).isEqualTo(KEY);
        assertThat(apiSeen().getFirst().authorization()).isEqualTo("Bearer T1");
    }

    private static HrmsContractCatalog openApi(String security) {
        String doc =
                "{\"paths\":{\"/get-ota-status\":{\"get\":{\"operationId\":\"get_ota_status\",\"security\":"
                        + security
                        + ",\"parameters\":[{\"name\":\"request_id\",\"in\":\"query\"},"
                        + "{\"name\":\"emp_ota_id\",\"in\":\"query\"}]}},"
                        + "\"/token\":{\"post\":{\"operationId\":\"token\",\"security\":[{\"ApiKeyAuth\":[]}]}}}}";
        return HrmsContractCatalog.parse(JsonMapper.builder().build().readTree(doc));
    }

    @Test
    void SRS_INT_6_aClosedClientRefusesEvenBeforeAskingForAToken() {
        HrmsClient c = standard();
        c.close();
        assertThatThrownBy(() -> c.send(STATUS, QUERY, null, null))
                .isInstanceOf(FciException.class);
        assertThat(seen).isEmpty();
        verify(refusals, never()).record(anyString(), anyString());
    }
}
