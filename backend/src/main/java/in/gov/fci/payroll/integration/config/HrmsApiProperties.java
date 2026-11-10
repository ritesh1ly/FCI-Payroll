package in.gov.fci.payroll.integration.config;

import in.gov.fci.payroll.integration.api.HrmsApi;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * The published URL of every HRMS API, one setting each (SRS-INT-1, SRS-INT-16; R-RULE):
 *
 * <pre>
 * fci.integration.hrms.base-url: https://payroll.example.invalid/hrms/
 * fci.integration.hrms.api.request_ota.path: request-ota
 * fci.integration.hrms.api.request_ota.direction: INBOUND
 * fci.integration.hrms.bulkhead.get_ota_status.max-concurrent-calls: 4
 * fci.integration.hrms.bulkhead.get_ota_status.max-wait-millis: 500
 * fci.integration.hrms.claim-type.request_pay_travel_allowance: TRAVEL
 * </pre>
 *
 * A path that starts with http:// or https:// is used as it stands; any other path is joined to the
 * base address. The base configuration holds none: an API with no URL is not configured and callers
 * refuse it.
 *
 * <p>Direction (owner 2026-11-05): {@code INBOUND} (the default when the setting is absent) means
 * Payroll publishes the API at the path and HRMS calls it, as the Postman collection says; {@code
 * OUTBOUND} means Payroll calls that URL on HRMS through {@code HrmsClient}. One setting per API
 * serves either direction, the deployment says which.
 *
 * <p>Per-call policy (owner 2026-11-06), every value optional and flag-based, so an operator
 * changes behaviour without a code change. {@code fci.integration.hrms.defaults.*} applies to every
 * API and {@code fci.integration.hrms.api.<code>.call.*} overrides it for one API; what neither
 * states falls back to the interface's time limit (timeouts) and to the shared pool of {@code
 * client.*}:
 *
 * <pre>
 * ...call.connect-timeout-ms: 2000        TCP connect (default: the interface time limit)
 * ...call.read-timeout-ms: 8000           wait for data on the connection (default: the interface time limit)
 * ...call.timeout-ms: 10000               the whole call, all in (default: the interface time limit)
 * ...call.mask-data: Y                    mask personal data in the exchange log (default Y)
 * ...call.log-request: Y                  log the request body, masked (default Y)
 * ...call.log-response: Y                 log the response body, masked (default Y)
 * ...call.pool.max-connections: 20        this API's own connection pool (its own route); absent = shared pool
 * ...call.pool.pending-acquire-max-count: 40
 * ...call.pool.pending-acquire-timeout-ms: 2000
 * ...call.pool.max-idle-seconds: 30
 * ...call.pool.max-life-seconds: 300
 * ...call.pool.evict-interval-seconds: 30
 * ...call.pool.leasing: LIFO              FIFO or LIFO
 * </pre>
 *
 * <p>Bulkhead (R-INT, SRS-INT-6): the concurrency limit of the outbound calls of one API; unset =
 * not configured and the call is refused. Claim type: the M-15 claim type an HRMS claim API maps
 * to; unset = the record is kept as received, nothing is applied (PENDING API-23-9).
 */
@ConfigurationProperties("fci.integration.hrms")
public record HrmsApiProperties(
        String baseUrl,
        Map<String, ApiUrl> api,
        Map<String, Limit> bulkhead,
        Map<String, String> claimType,
        Call defaults) {

    /** One API's connection pool (its own route): sizes, queue, lifetimes and lease order. */
    public record Pool(
            Integer maxConnections,
            Integer pendingAcquireMaxCount,
            Integer pendingAcquireTimeoutMs,
            Integer maxIdleSeconds,
            Integer maxLifeSeconds,
            Integer evictIntervalSeconds,
            String leasing) {}

    /**
     * The call policy of one API or of all: timeouts, masking, logging, pool. Every value is
     * optional.
     */
    public record Call(
            Integer connectTimeoutMs,
            Integer readTimeoutMs,
            Integer timeoutMs,
            Boolean maskData,
            Boolean logRequest,
            Boolean logResponse,
            Pool pool) {}

    /** One API's URL setting and the direction it is used in, with its optional call policy. */
    public record ApiUrl(String path, String direction, Call call) {
        /** The canonical constructor, the one Spring binds. */
        @ConstructorBinding
        public ApiUrl {}

        /** A path and direction without a call policy. */
        public ApiUrl(String path, String direction) {
            this(path, direction, null);
        }

        /** A path with the default direction (INBOUND). */
        public ApiUrl(String path) {
            this(path, null, null);
        }

        /** Whether Payroll publishes this API (the default) rather than calls it. */
        public boolean inbound() {
            return direction == null
                    || direction.isBlank()
                    || "INBOUND".equalsIgnoreCase(direction.trim());
        }
    }

    /** The concurrency limit of one API's outbound calls. */
    public record Limit(Integer maxConcurrentCalls, Integer maxWaitMillis) {}

    /** The canonical constructor, the one Spring binds. */
    @ConstructorBinding
    public HrmsApiProperties {}

    /** Without a default call policy. */
    public HrmsApiProperties(
            String baseUrl,
            Map<String, ApiUrl> api,
            Map<String, Limit> bulkhead,
            Map<String, String> claimType) {
        this(baseUrl, api, bulkhead, claimType, null);
    }

    /** The base address and URLs only. */
    public HrmsApiProperties(String baseUrl, Map<String, ApiUrl> api) {
        this(baseUrl, api, null, null, null);
    }

    /**
     * What one call uses, after the API's own policy, the defaults and the fallbacks are applied.
     */
    public record Resolved(
            int connectTimeoutMs,
            int readTimeoutMs,
            int timeoutMs,
            boolean maskData,
            boolean logRequest,
            boolean logResponse,
            PoolSpec pool) {}

    /**
     * The pool a call is made through: {@code key} names it (shared, the defaults', or the API's
     * own).
     */
    public record PoolSpec(
            String key,
            int maxConnections,
            int pendingAcquireMaxCount,
            long pendingAcquireTimeoutMs,
            long maxIdleSeconds,
            long maxLifeSeconds,
            long evictIntervalSeconds,
            boolean lifo) {}

    private static boolean flag(Boolean own, Boolean general) {
        return own != null ? own : general == null || general;
    }

    private static int pick(Integer own, Integer general, int fallback) {
        if (own != null && own > 0) {
            return own;
        }
        return general != null && general > 0 ? general : fallback;
    }

    /**
     * Resolves the policy of one API: its own {@code call} values, else {@code defaults}, else the
     * interface's time limit for the three timeouts, masking and both log flags on, and the shared
     * pool of {@code client}. A number that is not positive counts as unset.
     */
    public Resolved resolve(String code, int interfaceTimeoutSeconds, HrmsClientProperties client) {
        ApiUrl entry = api == null ? null : api.get(code);
        Call own = entry == null ? null : entry.call();
        Call common = defaults;
        int fallbackMs = Math.max(1, interfaceTimeoutSeconds) * 1000;
        Pool ownPool = own == null ? null : own.pool();
        Pool commonPool = common == null ? null : common.pool();
        String key = ownPool != null ? "api-" + code : commonPool != null ? "defaults" : "shared";
        String leasing =
                ownPool != null && ownPool.leasing() != null
                        ? ownPool.leasing()
                        : commonPool == null ? null : commonPool.leasing();
        return new Resolved(
                pick(
                        own == null ? null : own.connectTimeoutMs(),
                        common == null ? null : common.connectTimeoutMs(),
                        fallbackMs),
                pick(
                        own == null ? null : own.readTimeoutMs(),
                        common == null ? null : common.readTimeoutMs(),
                        fallbackMs),
                pick(
                        own == null ? null : own.timeoutMs(),
                        common == null ? null : common.timeoutMs(),
                        fallbackMs),
                flag(
                        own == null ? null : own.maskData(),
                        common == null ? null : common.maskData()),
                flag(
                        own == null ? null : own.logRequest(),
                        common == null ? null : common.logRequest()),
                flag(
                        own == null ? null : own.logResponse(),
                        common == null ? null : common.logResponse()),
                new PoolSpec(
                        key,
                        pick(
                                ownPool == null ? null : ownPool.maxConnections(),
                                commonPool == null ? null : commonPool.maxConnections(),
                                client.connections()),
                        pick(
                                ownPool == null ? null : ownPool.pendingAcquireMaxCount(),
                                commonPool == null ? null : commonPool.pendingAcquireMaxCount(),
                                client.pending()),
                        pick(
                                ownPool == null ? null : ownPool.pendingAcquireTimeoutMs(),
                                commonPool == null ? null : commonPool.pendingAcquireTimeoutMs(),
                                (int) client.pendingTimeout().toMillis()),
                        pick(
                                ownPool == null ? null : ownPool.maxIdleSeconds(),
                                commonPool == null ? null : commonPool.maxIdleSeconds(),
                                (int) client.maxIdle().toSeconds()),
                        pick(
                                ownPool == null ? null : ownPool.maxLifeSeconds(),
                                commonPool == null ? null : commonPool.maxLifeSeconds(),
                                (int) client.maxLife().toSeconds()),
                        pick(
                                ownPool == null ? null : ownPool.evictIntervalSeconds(),
                                commonPool == null ? null : commonPool.evictIntervalSeconds(),
                                (int) client.evictInterval().toSeconds()),
                        "LIFO".equalsIgnoreCase(leasing)));
    }

    private ApiUrl entry(HrmsApi hrmsApi) {
        return api == null ? null : api.get(hrmsApi.code());
    }

    /**
     * Pool sizes above these draw a warning (file descriptors and server connections are finite).
     */
    private static final int POOL_WARN = 500;

    private static final int POOLS_WARN_TOTAL = 1000;

    private static void callProblems(
            String where, Call call, List<String> errors, List<String> warnings) {
        if (call == null) {
            return;
        }
        negative(where + ".connect-timeout-ms", call.connectTimeoutMs(), errors);
        negative(where + ".read-timeout-ms", call.readTimeoutMs(), errors);
        negative(where + ".timeout-ms", call.timeoutMs(), errors);
        if (call.timeoutMs() != null && call.timeoutMs() > 0) {
            if (call.connectTimeoutMs() != null && call.connectTimeoutMs() > call.timeoutMs()) {
                warnings.add(
                        where
                                + ": connect-timeout-ms is longer than timeout-ms and can never be"
                                + " reached");
            }
            if (call.readTimeoutMs() != null && call.readTimeoutMs() > call.timeoutMs()) {
                warnings.add(
                        where
                                + ": read-timeout-ms is longer than timeout-ms and can never be"
                                + " reached");
            }
        }
        Pool pool = call.pool();
        if (pool == null) {
            return;
        }
        negative(where + ".pool.max-connections", pool.maxConnections(), errors);
        negative(where + ".pool.pending-acquire-max-count", pool.pendingAcquireMaxCount(), errors);
        negative(
                where + ".pool.pending-acquire-timeout-ms", pool.pendingAcquireTimeoutMs(), errors);
        negative(where + ".pool.max-idle-seconds", pool.maxIdleSeconds(), errors);
        negative(where + ".pool.max-life-seconds", pool.maxLifeSeconds(), errors);
        negative(where + ".pool.evict-interval-seconds", pool.evictIntervalSeconds(), errors);
        if (pool.leasing() != null
                && !"FIFO".equalsIgnoreCase(pool.leasing())
                && !"LIFO".equalsIgnoreCase(pool.leasing())) {
            errors.add(where + ".pool.leasing must be FIFO or LIFO, not '" + pool.leasing() + "'");
        }
        if (pool.maxConnections() != null && pool.maxConnections() > POOL_WARN) {
            warnings.add(
                    where + ".pool.max-connections " + pool.maxConnections() + " is very large");
        }
        if (pool.maxIdleSeconds() != null
                && pool.maxLifeSeconds() != null
                && pool.maxIdleSeconds() > pool.maxLifeSeconds()) {
            warnings.add(where + ".pool: max-idle-seconds is longer than max-life-seconds");
        }
    }

    private static void negative(String name, Integer value, List<String> errors) {
        if (value != null && value < 1) {
            errors.add(name + " must be 1 or more, not " + value);
        }
    }

    /**
     * Settings that cannot work: an API code under {@code api} or {@code bulkhead} that no API has
     * (a typo would silently do nothing), a number below 1, a lease order that is not FIFO or LIFO.
     */
    public List<String> errors() {
        List<String> errors = new ArrayList<>();
        Set<String> known = new HashSet<>();
        for (HrmsApi a : HrmsApi.values()) {
            known.add(a.code());
        }
        if (api != null) {
            api.forEach(
                    (code, entry) -> {
                        if (!known.contains(code)) {
                            errors.add("api." + code + " is not an HRMS API code");
                        }
                        if (entry != null) {
                            callProblems(
                                    "api." + code + ".call",
                                    entry.call(),
                                    errors,
                                    new ArrayList<>());
                        }
                    });
        }
        if (bulkhead != null) {
            bulkhead.keySet().stream()
                    .filter(code -> !known.contains(code) && !"connection_test".equals(code))
                    .forEach(code -> errors.add("bulkhead." + code + " is not an HRMS API code"));
        }
        callProblems("defaults", defaults, errors, new ArrayList<>());
        return errors;
    }

    /** Settings that work but look wrong: a time longer than the whole call, a very large pool. */
    public List<String> warnings() {
        List<String> warnings = new ArrayList<>();
        int total = 0;
        if (api != null) {
            for (var e : api.entrySet()) {
                if (e.getValue() != null) {
                    callProblems(
                            "api." + e.getKey() + ".call",
                            e.getValue().call(),
                            new ArrayList<>(),
                            warnings);
                    Call call = e.getValue().call();
                    if (call != null
                            && call.pool() != null
                            && call.pool().maxConnections() != null) {
                        total += call.pool().maxConnections();
                    }
                }
            }
        }
        callProblems("defaults", defaults, new ArrayList<>(), warnings);
        if (total > POOLS_WARN_TOTAL) {
            warnings.add("the per-API pools together allow " + total + " connections");
        }
        return warnings;
    }

    /** The URL the API is published under, or empty when it is not configured. */
    public Optional<String> url(HrmsApi hrmsApi) {
        ApiUrl entry = entry(hrmsApi);
        String path = entry == null || entry.path() == null ? "" : entry.path().trim();
        if (path.isEmpty()) {
            return Optional.empty();
        }
        if (path.startsWith("http://") || path.startsWith("https://")) {
            return Optional.of(path);
        }
        String base = baseUrl == null ? "" : baseUrl.trim();
        if (base.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(base.replaceAll("/+$", "") + "/" + path.replaceAll("^/+", ""));
    }

    /** Whether the API is configured for Payroll to publish (a path is set, direction INBOUND). */
    public boolean published(HrmsApi hrmsApi) {
        return routePath(hrmsApi).isPresent();
    }

    /**
     * The servlet path Payroll publishes the API at: the setting itself when it is a path (a
     * leading slash is added), the path part when it is a full URL; empty when not configured or
     * set for the outbound direction.
     */
    public Optional<String> routePath(HrmsApi hrmsApi) {
        ApiUrl entry = entry(hrmsApi);
        if (entry == null || entry.path() == null || !entry.inbound()) {
            return Optional.empty();
        }
        String path = entry.path().trim();
        if (path.isEmpty()) {
            return Optional.empty();
        }
        if (path.startsWith("http://") || path.startsWith("https://")) {
            try {
                path = URI.create(path).getPath();
            } catch (IllegalArgumentException ex) {
                return Optional.empty();
            }
            if (path == null || path.isBlank()) {
                return Optional.empty();
            }
        }
        return Optional.of("/" + path.replaceAll("^/+", ""));
    }

    /** The URL Payroll calls on HRMS for an OUTBOUND API, or empty when not configured. */
    public Optional<String> outboundUrl(HrmsApi hrmsApi) {
        ApiUrl entry = entry(hrmsApi);
        return entry == null || entry.inbound() ? Optional.empty() : url(hrmsApi);
    }

    /** The bulkhead limit of the API when both values are set and valid, else empty. */
    public Optional<Limit> limit(HrmsApi hrmsApi) {
        return limit(hrmsApi.code());
    }

    /** The bulkhead limit under a setting name ({@code connection_test} for Test connection). */
    public Optional<Limit> limit(String code) {
        Limit l = bulkhead == null ? null : bulkhead.get(code);
        return l == null
                        || l.maxConcurrentCalls() == null
                        || l.maxConcurrentCalls() < 1
                        || l.maxWaitMillis() == null
                        || l.maxWaitMillis() < 0
                ? Optional.empty()
                : Optional.of(l);
    }
}
