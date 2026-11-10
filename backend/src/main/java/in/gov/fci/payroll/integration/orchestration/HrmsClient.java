package in.gov.fci.payroll.integration.orchestration;

import in.gov.fci.payroll.integration.api.HrmsApi;
import in.gov.fci.payroll.integration.api.HrmsReply;
import in.gov.fci.payroll.integration.api.InterfaceIntake;
import in.gov.fci.payroll.integration.api.InterfaceSettings;
import in.gov.fci.payroll.integration.api.InterfaceSettings.Settings;
import in.gov.fci.payroll.integration.business.HrmsBearer;
import in.gov.fci.payroll.integration.business.HrmsContractCatalog;
import in.gov.fci.payroll.integration.business.HrmsContracts;
import in.gov.fci.payroll.integration.business.IntegrationErrorCode;
import in.gov.fci.payroll.integration.business.IntegrationRules;
import in.gov.fci.payroll.integration.config.HrmsApiProperties;
import in.gov.fci.payroll.integration.config.HrmsClientProperties;
import in.gov.fci.payroll.integration.config.HrmsCredentialsProperties;
import in.gov.fci.payroll.integration.config.HrmsLogProperties;
import in.gov.fci.payroll.platform.logging.MaskingPolicy;
import in.gov.fci.payroll.platform.logging.MaskingProperties;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import tools.jackson.databind.json.JsonMapper;

/**
 * The outbound HRMS client (owner 2026-11-05; SRS-INT-6, SRS-INT-16, SRS-INT-30, SRS-INT-35; R-INT,
 * R-PII): a Spring {@link WebClient} on a pooled Reactor Netty connector, for the exchanges where
 * Payroll is the caller - the payment status pushed to HRMS (SRS-INT-35), Test connection on X-03,
 * and any Postman API whose URL setting is OUTBOUND (one method {@link #send} for all of them, the
 * URL read from the API's own setting; unset = not configured and refused).
 *
 * <p>Protection, outermost first: <b>Bulkhead</b> (the API's own limit on concurrent calls and
 * wait, {@code fci.integration.hrms.bulkhead.<code>.*}; a full bulkhead refuses at once and is
 * recorded for X-03) -&gt; <b>Retry</b> (the interface's retry count, back-off and strategy from
 * {@code interface_config}; only 5xx, I/O and timeouts are retried, never a 4xx; the
 * Idempotency-Key is the same on every attempt) -&gt; the call, under the interface's time limit as
 * connect, read and write timeout. There is no circuit breaker in this client (owner decision,
 * SRS-INT-6 lists one: PENDING API-23-15). Connections come from one named pool with bounded size,
 * waiting callers, idle time and lifetime, evicted in the background, with metrics. TLS is verified
 * with the JVM trust store. The api-key and bearer token are secrets from settings; nothing of a
 * request or response body is logged.
 */
@Service
public class HrmsClient {

    private static final Logger log = LoggerFactory.getLogger(HrmsClient.class);

    /** The status line and body of the HRMS answer (a 4xx is an answer, not an error). */
    public record Response(int status, String body, int attempts) {}

    /** A failure worth another attempt. */
    private static final class Transient extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Transient(String message, Throwable cause) {
            super(message, cause);
        }
    }

    static final String PAYMENT_STATUS_INTERFACE = "HRMS_PAYMENT_STATUS";
    static final String AUTH_INTERFACE = "HRMS_API_AUTH";
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /**
     * Which authentication headers a call carries, as the contract (OpenAPI security) of that API
     * states.
     */
    record Auth(boolean apiKey, boolean bearer) {}

    static final String TEST_BULKHEAD = "connection_test";
    private static final int MAX_RESPONSE_BYTES = 1_048_576;
    private static final long MIN_WAIT_MILLIS = 10;

    private final HrmsApiProperties apis;
    private final HrmsClientProperties client;
    private final InterfaceSettings settings;
    private final InterfaceIntake intake;
    private final IntegrationStore store;
    private final FailureAlerts alerts;
    private final HrmsRefusals refusals;
    private final Map<String, ConnectionProvider> pools = new ConcurrentHashMap<>();
    private final Map<String, HrmsApiProperties.Resolved> policies = new ConcurrentHashMap<>();
    private final HrmsCredentialsProperties credentials;
    private final HrmsContractCatalog contract;
    private final HrmsTelemetry telemetry;
    private final HrmsBearer bearer = new HrmsBearer();
    private final Object tokenLock = new Object();
    private final Clock clock;
    private final Map<String, WebClient> webClients = new ConcurrentHashMap<>();
    private final Map<String, Bulkhead> bulkheads = new ConcurrentHashMap<>();
    private volatile boolean closed;

    /** For code and tests that have no login and use the contract file of the class path. */
    public HrmsClient(
            HrmsApiProperties apis,
            HrmsClientProperties client,
            InterfaceSettings settings,
            InterfaceIntake intake,
            IntegrationStore store,
            FailureAlerts alerts,
            HrmsRefusals refusals) {
        this(
                apis,
                client,
                new HrmsCredentialsProperties(null, null, null, null),
                HrmsContractCatalog.load(),
                settings,
                intake,
                store,
                alerts,
                refusals,
                Clock.systemUTC());
    }

    /** The constructor Spring uses: the settings, the masking policy and the metric registry. */
    @Autowired
    public HrmsClient(
            HrmsApiProperties apis,
            HrmsClientProperties client,
            HrmsCredentialsProperties credentials,
            InterfaceSettings settings,
            InterfaceIntake intake,
            IntegrationStore store,
            FailureAlerts alerts,
            HrmsRefusals refusals,
            HrmsLogProperties logging,
            MaskingPolicy masking,
            MeterRegistry metrics,
            Environment environment) {
        this(
                apis,
                client,
                credentials,
                HrmsContractCatalog.load(),
                settings,
                intake,
                store,
                alerts,
                refusals,
                Clock.systemUTC(),
                new HrmsTelemetry(logging, masking, metrics, unmaskedAllowed(environment)));
        validate(apis);
    }

    /**
     * Without telemetry settings: a default log policy (no masking rule, so no body) and a private
     * registry.
     */
    public HrmsClient(
            HrmsApiProperties apis,
            HrmsClientProperties client,
            HrmsCredentialsProperties credentials,
            InterfaceSettings settings,
            InterfaceIntake intake,
            IntegrationStore store,
            FailureAlerts alerts,
            HrmsRefusals refusals) {
        this(
                apis,
                client,
                credentials,
                HrmsContractCatalog.load(),
                settings,
                intake,
                store,
                alerts,
                refusals,
                Clock.systemUTC());
    }

    HrmsClient(
            HrmsApiProperties apis,
            HrmsClientProperties client,
            HrmsCredentialsProperties credentials,
            HrmsContractCatalog contract,
            InterfaceSettings settings,
            InterfaceIntake intake,
            IntegrationStore store,
            FailureAlerts alerts,
            HrmsRefusals refusals,
            Clock clock) {
        this(
                apis,
                client,
                credentials,
                contract,
                settings,
                intake,
                store,
                alerts,
                refusals,
                clock,
                new HrmsTelemetry(
                        new HrmsLogProperties(false, null),
                        new MaskingPolicy(new MaskingProperties(null, null)),
                        new SimpleMeterRegistry(),
                        true));
    }

    HrmsClient(
            HrmsApiProperties apis,
            HrmsClientProperties client,
            HrmsCredentialsProperties credentials,
            HrmsContractCatalog contract,
            InterfaceSettings settings,
            InterfaceIntake intake,
            IntegrationStore store,
            FailureAlerts alerts,
            HrmsRefusals refusals,
            Clock clock,
            HrmsTelemetry telemetry) {
        this.telemetry = telemetry;
        this.apis = apis;
        this.client = client;
        this.credentials = credentials;
        this.contract = contract;
        this.clock = clock;
        this.settings = settings;
        this.intake = intake;
        this.store = store;
        this.alerts = alerts;
        this.refusals = refusals;
    }

    @PreDestroy
    void close() {
        closed = true;
        pools.values().forEach(ConnectionProvider::dispose);
        pools.clear();
        webClients.clear();
    }

    /** The registry interface an API's outbound calls are recorded under. */
    static String interfaceOf(HrmsApi api) {
        if (api == HrmsApi.TOKEN) {
            return AUTH_INTERFACE;
        }
        return HrmsContracts.request(api)
                .map(HrmsContracts.Request::interfaceCode)
                .orElse(PAYMENT_STATUS_INTERFACE);
    }

    /**
     * Calls an HRMS API Payroll is configured to call (URL setting with direction OUTBOUND).
     *
     * @param query URL parameters, or null
     * @param jsonBody the request body, or null for a GET
     * @param idempotencyKey sent as {@code Idempotency-Key} on every attempt; null makes a new one
     */
    public Response send(
            HrmsApi api, Map<String, String> query, String jsonBody, String idempotencyKey) {
        String url =
                apis.outboundUrl(api)
                        .orElseThrow(() -> notConfigured(api.code(), "its outbound URL setting"));
        return exchange(
                interfaceOf(api),
                api.code(),
                HttpMethod.valueOf(api.method()),
                url,
                query,
                jsonBody,
                idempotencyKey,
                authOf(api.code()));
    }

    /**
     * Pushes the status and amount of an HRMS-originated payment to HRMS (SRS-INT-35) through the
     * OUTBOUND API {@code api}; the key is fixed by the payment and the status, so a retry (or a
     * later repeat) is the same message.
     */
    public Response pushPaymentStatus(
            HrmsApi api, String requestId, String status, BigDecimal amount) {
        String body =
                "{\"request_id\":\""
                        + jsonText(requestId)
                        + "\",\"status\":\""
                        + jsonText(status)
                        + "\",\"amount\":"
                        + (amount == null ? "null" : amount.toPlainString())
                        + "}";
        return send(api, null, body, "PAYSTATUS-" + api.code() + "-" + requestId + "-" + status);
    }

    /**
     * Test connection (SRS-INT-33): one GET of the HRMS base address with the api-key, through the
     * payment-status interface and its own bulkhead ({@code connection_test}).
     */
    public Response probe() {
        String base = apis.baseUrl();
        if (base == null || base.isBlank()) {
            throw notConfigured("connection_test", "fci.integration.hrms.base-url");
        }
        return exchange(
                PAYMENT_STATUS_INTERFACE,
                TEST_BULKHEAD,
                HttpMethod.GET,
                base.strip(),
                null,
                null,
                null,
                authOf("welcome"));
    }

    private Response exchange(
            String interfaceCode,
            String bulkheadKey,
            HttpMethod method,
            String url,
            Map<String, String> query,
            String jsonBody,
            String idempotencyKey,
            Auth auth) {
        if (closed) {
            throw new in.gov.fci.payroll.platform.error.FciException(
                    IntegrationErrorCode.CALL_FAILED,
                    new Object[] {interfaceCode, 0, "the client is shut down"},
                    List.of(),
                    null);
        }
        Settings s =
                settings.inForce(interfaceCode)
                        .orElseThrow(
                                () ->
                                        new in.gov.fci.payroll.platform.error.FciException(
                                                IntegrationErrorCode.NOT_CONFIGURED,
                                                new Object[] {interfaceCode},
                                                List.of(),
                                                null));
        HrmsApiProperties.Limit limit =
                apis.limit(bulkheadKey)
                        .orElseThrow(() -> notConfigured(bulkheadKey, "its bulkhead limit"));
        String key = idempotencyKey == null ? UUID.randomUUID().toString() : idempotencyKey;
        Bulkhead bulkhead = bulkheadOf(bulkheadKey, limit);
        HrmsApiProperties.Resolved policy = policyOf(bulkheadKey, s);
        Retry retry = retryOf(bulkheadKey, s);
        retry.getEventPublisher().onRetry(event -> telemetry.retry(bulkheadKey));
        AtomicInteger attempts = new AtomicInteger();
        Callable<Response> call =
                () -> {
                    int number = attempts.incrementAndGet();
                    return once(
                            s,
                            method,
                            url,
                            query,
                            jsonBody,
                            key,
                            auth,
                            policy,
                            new HrmsTelemetry.Ctx(interfaceCode, bulkheadKey, number));
                };
        try {
            Response answered =
                    Bulkhead.decorateCallable(bulkhead, Retry.decorateCallable(retry, call)).call();
            int made = attempts.get();
            Response result = new Response(answered.status(), answered.body(), made);
            window(
                    interfaceCode,
                    key,
                    result.status() < 400,
                    "HTTP " + result.status() + ", " + made + " attempt(s)");
            if (result.status() >= 400) {
                alerts.raise(
                        store.interfaceOf(interfaceCode),
                        null,
                        null,
                        null,
                        "HRMS answered HTTP " + result.status() + " to " + bulkheadKey);
            }
            return result;
        } catch (BulkheadFullException full) {
            telemetry.bulkheadRefused(bulkheadKey);
            refusals.record(
                    interfaceCode,
                    "Call to "
                            + bulkheadKey
                            + " refused: the limit of concurrent calls is reached");
            throw new in.gov.fci.payroll.platform.error.FciException(
                    IntegrationErrorCode.BULKHEAD_FULL,
                    new Object[] {bulkheadKey},
                    List.of(),
                    full);
        } catch (in.gov.fci.payroll.platform.error.FciException refused) {
            // A refusal made before anything was sent (an API, login or limit that is not
            // configured, a token request that failed and was recorded on its own interface) is
            // not a failure of this HRMS call: it keeps its own code and is not retried.
            throw refused;
        } catch (Exception failed) {
            String why = InterfaceCallService.summary(failed);
            int made = attempts.get();
            String reason =
                    "Call to " + bulkheadKey + " failed after " + made + " attempt(s): " + why;
            window(interfaceCode, key, false, reason);
            alerts.raise(store.interfaceOf(interfaceCode), null, null, null, reason);
            throw new in.gov.fci.payroll.platform.error.FciException(
                    IntegrationErrorCode.CALL_FAILED,
                    new Object[] {interfaceCode, made, why},
                    List.of(),
                    failed);
        }
    }

    /**
     * One attempt. The headers follow the contract of the API (OpenAPI security): the api-key where
     * the contract says so, a bearer token where it says so. When HRMS refuses a token Payroll
     * fetched itself (expired, revoked, or refused early), the call is NOT failed: that token is
     * dropped, a new one fetched and the call repeated, at most {@code token-renewals} times (not
     * counted as attempts of the retry policy). Only the token that failed is dropped, so callers
     * that meet the same expired token share one new fetch.
     */
    private Response once(
            Settings s,
            HttpMethod method,
            String url,
            Map<String, String> query,
            String jsonBody,
            String key,
            Auth auth,
            HrmsApiProperties.Resolved policy,
            HrmsTelemetry.Ctx ctx) {
        String token = auth.bearer() ? bearerToken() : null;
        Response answered = attempt(method, url, query, jsonBody, key, auth, token, policy, ctx);
        int renewals = 0;
        while (token != null
                && client.bearer().isEmpty()
                && renewals < credentials.renewals()
                && tokenRefused(answered)) {
            bearer.invalidate(token);
            telemetry.token("renewed");
            token = bearerToken();
            answered = attempt(method, url, query, jsonBody, key, auth, token, policy, ctx);
            renewals++;
        }
        return answered;
    }

    /**
     * Whether HRMS refused the BEARER TOKEN: 401, or 403 with a token error code. A refused api-key
     * (an {@code API_KEY...} code) is not a token problem: a new token cannot help.
     */
    static boolean tokenRefused(Response answered) {
        if (answered.status() != 401 && answered.status() != 403) {
            return false;
        }
        String code = HrmsReply.parse(answered.status(), answered.body(), 1).errorCode();
        if (code != null && code.contains("API_KEY")) {
            return false;
        }
        return answered.status() == 401 || (code != null && code.startsWith("TOKEN"));
    }

    /** One call with the given token; a 5xx, an I/O failure or a timeout is transient. */
    private Response attempt(
            HttpMethod method,
            String url,
            Map<String, String> query,
            String jsonBody,
            String key,
            Auth auth,
            String token,
            HrmsApiProperties.Resolved policy,
            HrmsTelemetry.Ctx ctx) {
        UriComponentsBuilder target = UriComponentsBuilder.fromUriString(url);
        if (query != null) {
            query.forEach(target::queryParam);
        }
        URI uri = target.build().encode().toUri();
        long started = System.nanoTime();
        Response answered = null;
        String failure = null;
        try {
            WebClient.RequestBodySpec spec =
                    webClient(policy)
                            .method(method)
                            .uri(uri)
                            .accept(MediaType.APPLICATION_JSON)
                            .headers(
                                    h -> {
                                        h.set("Idempotency-Key", key);
                                        if (auth.apiKey()) {
                                            client.key().ifPresent(k -> h.set("api-key", k));
                                        }
                                        if (token != null) {
                                            h.setBearerAuth(token);
                                        }
                                    });
            WebClient.RequestHeadersSpec<?> request =
                    jsonBody == null
                            ? spec
                            : spec.contentType(MediaType.APPLICATION_JSON).bodyValue(jsonBody);
            answered =
                    request.exchangeToMono(
                                    r ->
                                            r.bodyToMono(String.class)
                                                    .defaultIfEmpty("")
                                                    .map(
                                                            b ->
                                                                    new Response(
                                                                            r.statusCode().value(),
                                                                            b,
                                                                            1)))
                            .block(Duration.ofMillis(policy.timeoutMs() + 500L));
            if (answered == null) {
                throw new Transient("no answer", null);
            }
            if (answered.status() >= 500) {
                failure = "HTTP " + answered.status();
                throw new Transient(failure, null);
            }
            return answered;
        } catch (Transient t) {
            if (failure == null) {
                failure = t.getMessage();
            }
            throw t;
        } catch (WebClientRequestException io) {
            failure = io.getClass().getSimpleName();
            throw new Transient(failure, io);
        } catch (IllegalStateException timeout) {
            // Mono.block(Duration) timing out.
            failure = "TimeoutException";
            throw new Transient(failure, timeout);
        } finally {
            telemetry.exchange(
                    ctx,
                    method,
                    uri,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                    jsonBody,
                    answered,
                    failure,
                    policy);
        }
    }

    /** A client for the call policy: one per pool, connect and read time, reused for every call. */
    private WebClient webClient(HrmsApiProperties.Resolved policy) {
        HrmsApiProperties.PoolSpec spec = policy.pool();
        String id = spec.key() + "|" + policy.connectTimeoutMs() + "|" + policy.readTimeoutMs();
        WebClient existing = webClients.get(id);
        if (existing != null) {
            return existing;
        }
        return webClients.computeIfAbsent(
                id,
                k -> {
                    ConnectionProvider provider =
                            pools.computeIfAbsent(spec.key(), n -> buildPool(spec));
                    long readMs = policy.readTimeoutMs();
                    HttpClient http =
                            HttpClient.create(provider)
                                    .option(
                                            ChannelOption.CONNECT_TIMEOUT_MILLIS,
                                            policy.connectTimeoutMs())
                                    .responseTimeout(Duration.ofMillis(readMs))
                                    .doOnConnected(
                                            c ->
                                                    c.addHandlerLast(
                                                                    new ReadTimeoutHandler(
                                                                            readMs,
                                                                            TimeUnit.MILLISECONDS))
                                                            .addHandlerLast(
                                                                    new WriteTimeoutHandler(
                                                                            readMs,
                                                                            TimeUnit
                                                                                    .MILLISECONDS)));
                    return WebClient.builder()
                            .clientConnector(new ReactorClientHttpConnector(http))
                            .codecs(c -> c.defaultCodecs().maxInMemorySize(MAX_RESPONSE_BYTES))
                            .build();
                });
    }

    /**
     * One connection pool (one route): bounded connections, a bounded queue of waiting callers,
     * idle and lifetime limits, background eviction of stale connections, FIFO or LIFO leasing,
     * metrics. The shared pool keeps the name {@code hrms-client}; an API with its own pool is
     * {@code hrms-api-<code>}.
     */
    private static ConnectionProvider buildPool(HrmsApiProperties.PoolSpec spec) {
        ConnectionProvider.Builder builder =
                ConnectionProvider.builder(
                                spec.key().equals("shared") ? "hrms-client" : "hrms-" + spec.key())
                        .maxConnections(spec.maxConnections())
                        .pendingAcquireMaxCount(spec.pendingAcquireMaxCount())
                        .pendingAcquireTimeout(Duration.ofMillis(spec.pendingAcquireTimeoutMs()))
                        .maxIdleTime(Duration.ofSeconds(spec.maxIdleSeconds()))
                        .maxLifeTime(Duration.ofSeconds(spec.maxLifeSeconds()))
                        .evictInBackground(Duration.ofSeconds(spec.evictIntervalSeconds()))
                        .metrics(true);
        return (spec.lifo() ? builder.lifo() : builder.fifo()).build();
    }

    /**
     * The call policy of an API, resolved once and kept (the settings do not change while running).
     */
    private HrmsApiProperties.Resolved policyOf(String code, Settings s) {
        return policies.computeIfAbsent(
                code + ":" + s.timeoutSeconds(),
                k -> apis.resolve(code, s.timeoutSeconds(), client));
    }

    /** Whether {@code mask-data: N} is honoured: only on dev, local and demo, never with prod. */
    static boolean unmaskedAllowed(Environment environment) {
        return environment.acceptsProfiles(Profiles.of("dev", "local", "demo"))
                && !environment.acceptsProfiles(Profiles.of("prod"));
    }

    /**
     * Stops the start on a setting that cannot work (a misspelt API code, a number below 1, an
     * unknown lease order) and warns about one that is suspicious (a connect or read time longer
     * than the whole call, a very large pool). A typo that silently does nothing is the commonest
     * operations fault.
     */
    static void validate(HrmsApiProperties apis) {
        List<String> errors = apis.errors();
        if (!errors.isEmpty()) {
            throw new IllegalStateException(
                    "invalid fci.integration.hrms settings: " + String.join("; ", errors));
        }
        apis.warnings().forEach(w -> log.warn("fci.integration.hrms: {}", w));
    }

    private Bulkhead bulkheadOf(String key, HrmsApiProperties.Limit limit) {
        return bulkheads.computeIfAbsent(
                key + ":" + limit.maxConcurrentCalls() + ":" + limit.maxWaitMillis(),
                k ->
                        Bulkhead.of(
                                "hrms-" + key,
                                BulkheadConfig.custom()
                                        .maxConcurrentCalls(limit.maxConcurrentCalls())
                                        .maxWaitDuration(Duration.ofMillis(limit.maxWaitMillis()))
                                        .build()));
    }

    /** SRS-INT-6: retry count, back-off and strategy from the interface's settings. */
    private static Retry retryOf(String name, Settings s) {
        IntervalFunction wait =
                attempt ->
                        Math.max(
                                MIN_WAIT_MILLIS,
                                1000L
                                        * IntegrationRules.backoffSeconds(
                                                s.backoffStrategy(), s.backoffSeconds(), attempt));
        return Retry.of(
                "hrms-" + name,
                RetryConfig.custom()
                        .maxAttempts(s.retryCount() + 1)
                        .intervalFunction(wait)
                        .retryOnException(e -> e instanceof Transient)
                        .build());
    }

    /** One window per call, so X-03 shows the outcome (received 1 = accepted or rejected). */
    private void window(String interfaceCode, String key, boolean ok, String detail) {
        long run = intake.open(interfaceCode, key, null, null);
        intake.completeCounts(run, 1, ok ? 1 : 0, ok ? 0 : 1, IntegrationRules.cut(detail, 500));
    }

    /**
     * For Test connection: whether Payroll can get the bearer token HRMS wants. A failure of the
     * login raises like any call; a missing login is reported, not raised.
     */
    String tokenCheck() {
        if (client.bearer().isPresent()) {
            return "static bearer token configured";
        }
        if (credentials.login().isEmpty()) {
            return "no login configured";
        }
        bearerToken();
        return "token obtained";
    }

    /** The authentication headers a call carries, as the contract of the API states. */
    private Auth authOf(String code) {
        return contract.of(code)
                .map(e -> new Auth(e.needsApiKey(), e.needsBearer()))
                .orElse(new Auth(true, true));
    }

    /**
     * The bearer token for an API that needs one: the static token of the settings when there is
     * one, else the token fetched with the configured login, kept until shortly before it expires
     * (one fetch at a time).
     */
    private String bearerToken() {
        Optional<String> fixed = client.bearer();
        if (fixed.isPresent()) {
            return fixed.get();
        }
        synchronized (tokenLock) {
            Optional<String> held = bearer.current(clock.instant());
            if (held.isPresent()) {
                return held.get();
            }
            HrmsCredentialsProperties.Login login =
                    credentials
                            .login()
                            .orElseThrow(
                                    () ->
                                            notConfigured(
                                                    HrmsApi.TOKEN.code(),
                                                    "fci.integration.hrms.client.credentials"
                                                            + " (user name and password)"));
            String url =
                    apis.outboundUrl(HrmsApi.TOKEN)
                            .orElseThrow(
                                    () ->
                                            notConfigured(
                                                    HrmsApi.TOKEN.code(),
                                                    "its outbound URL setting"));
            String body =
                    MAPPER.createObjectNode()
                            .put("username", login.username())
                            .put("password", login.password())
                            .toString();
            Response answered =
                    exchange(
                            AUTH_INTERFACE,
                            HrmsApi.TOKEN.code(),
                            HttpMethod.POST,
                            url,
                            null,
                            body,
                            null,
                            // the token call can never carry the token it asks for
                            new Auth(authOf(HrmsApi.TOKEN.code()).apiKey(), false));
            Optional<HrmsBearer.Issued> issued =
                    answered.status() == 200 ? HrmsBearer.read(answered.body()) : Optional.empty();
            if (issued.isEmpty()) {
                String why =
                        "HTTP "
                                + answered.status()
                                + (answered.status() == 200
                                        ? ", no token in the answer"
                                        : ", the login was refused");
                refusals.record(AUTH_INTERFACE, "Token request refused: " + why);
                telemetry.token("refused");
                throw new in.gov.fci.payroll.platform.error.FciException(
                        IntegrationErrorCode.CALL_FAILED,
                        new Object[] {AUTH_INTERFACE, answered.attempts(), "token: " + why},
                        List.of(),
                        null);
            }
            bearer.keep(issued.get(), Instant.now(clock), credentials.skew());
            telemetry.token("fetched");
            return issued.get().token();
        }
    }

    private static in.gov.fci.payroll.platform.error.FciException notConfigured(
            String api, String what) {
        return new in.gov.fci.payroll.platform.error.FciException(
                IntegrationErrorCode.HRMS_API_NOT_CONFIGURED,
                new Object[] {api, what},
                List.of(),
                null);
    }

    private static String jsonText(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** For tests: whether a bulkhead exists for the API (built on first use). */
    Optional<Bulkhead> bulkhead(String key) {
        return bulkheads.entrySet().stream()
                .filter(e -> e.getKey().startsWith(key + ":"))
                .map(Map.Entry::getValue)
                .findFirst();
    }
}
