package in.gov.fci.payroll.platform.logging;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

/**
 * The common telemetry of a call to another system (SRS-INT-6, R-INT, R-PII): one structured log
 * event per attempt and Micrometer metrics, the same for every integration (HRMS, bank, EBS, ...).
 * A client builds one instance for its system and reports each attempt; nothing about the peer is
 * hard-coded here.
 *
 * <p>Event fields (the message reads on its own): {@code peer.system}, {@code peer.interface},
 * {@code peer.operation}, {@code peer.attempt}, {@code peer.outcome} (OK, CLIENT_ERROR,
 * SERVER_ERROR, TIMEOUT, IO_ERROR), {@code http.request.method}, {@code url.path}, {@code
 * url.query} (masked), {@code http.response.status_code}, {@code peer.duration_ms}, {@code
 * http.request.body.bytes}, {@code http.response.body.bytes}, {@code peer.failure} and, when bodies
 * are on, {@code peer.request.body} and {@code peer.response.body}. The correlation id and the user
 * come from the MDC of the request.
 *
 * <p>What a call logs is decided per call by {@link Options} (request body, response body,
 * masking), so an operator can switch any API's logging without a code change. Masked bodies and
 * the query go through the configured {@link MaskingPolicy} and are cut; with no policy they are
 * withheld; an operation named in {@code withheld} (a login) never shows a body. The masking work
 * is done only when an event is written, so a disabled logger costs a level check and a timer
 * update. Metrics: timer {@code <prefix>.calls} (tags operation, method, status, outcome) and
 * counters {@code <prefix>.<name>} through {@link #count}.
 */
public final class ExchangeLog {

    /** Shown instead of a body that must never be logged. */
    public static final String WITHHELD = "[withheld]";

    /** Shown instead of a body when no masking policy is configured. */
    public static final String NO_POLICY = "[withheld: no masking rules configured]";

    /** Which call an attempt belongs to. */
    public record Call(String peerInterface, String operation, int attempt) {}

    /** What one call logs: the request body, the response body, and whether they are masked. */
    public record Options(boolean logRequest, boolean logResponse, boolean mask) {}

    private final Logger log;
    private final String system;
    private final String prefix;
    private final boolean bodies;
    private final int maxBody;
    private final MaskingPolicy policy;
    private final MeterRegistry registry;
    private final Set<String> withheld;

    /**
     * @param loggerName the logger of the events, e.g. {@code FCI_HRMS_EXCHANGE}
     * @param system the peer, e.g. {@code hrms}
     * @param metricPrefix the metric name prefix, e.g. {@code fci.hrms.client}
     * @param bodies the master switch for bodies; false = no body is ever logged
     * @param maxBody the most characters of one body or query that are logged
     * @param withheld operations whose bodies are never logged
     */
    public ExchangeLog(
            String loggerName,
            String system,
            String metricPrefix,
            boolean bodies,
            int maxBody,
            MaskingPolicy policy,
            MeterRegistry registry,
            Set<String> withheld) {
        this.log = LoggerFactory.getLogger(loggerName);
        this.system = system;
        this.prefix = metricPrefix;
        this.bodies = bodies;
        this.maxBody = maxBody;
        this.policy = policy;
        this.registry = registry;
        this.withheld = Set.copyOf(withheld);
    }

    /** The class of an attempt from its status and failure. */
    public static String outcome(Integer status, String failure) {
        if (failure == null) {
            return status != null && status >= 400 ? "CLIENT_ERROR" : "OK";
        }
        if (status != null) {
            return "SERVER_ERROR";
        }
        return failure.contains("Timeout") ? "TIMEOUT" : "IO_ERROR";
    }

    /** Records one attempt: a reply, or a failure with or without a reply. */
    public void exchange(
            Call call,
            String method,
            URI uri,
            long millis,
            String requestBody,
            Integer status,
            String responseBody,
            String failure,
            Options options) {
        String outcome = outcome(status, failure);
        Timer.builder(prefix + ".calls")
                .tag("operation", call.operation())
                .tag("method", method)
                .tag("status", status == null ? "none" : String.valueOf(status))
                .tag("outcome", outcome)
                .register(registry)
                .record(millis, TimeUnit.MILLISECONDS);
        Level level =
                outcome.equals("OK") || outcome.equals("CLIENT_ERROR") ? Level.INFO : Level.WARN;
        if (!log.isEnabledForLevel(level)) {
            return;
        }
        String path = LogSanitizer.encodeControlCharacters(cut(uri.getPath(), 200));
        var event =
                log.atLevel(level)
                        .addKeyValue("peer.system", system)
                        .addKeyValue("peer.interface", call.peerInterface())
                        .addKeyValue("peer.operation", call.operation())
                        .addKeyValue("peer.attempt", call.attempt())
                        .addKeyValue("peer.outcome", outcome)
                        .addKeyValue("http.request.method", method)
                        .addKeyValue("url.path", path)
                        .addKeyValue("peer.duration_ms", millis)
                        .addKeyValue("http.request.body.bytes", bytes(requestBody));
        if (uri.getRawQuery() != null) {
            event = event.addKeyValue("url.query", query(uri.getRawQuery(), options.mask()));
        }
        if (status != null) {
            event =
                    event.addKeyValue("http.response.status_code", status)
                            .addKeyValue("http.response.body.bytes", bytes(responseBody));
        }
        if (failure != null) {
            event =
                    event.addKeyValue(
                            "peer.failure",
                            LogSanitizer.encodeControlCharacters(cut(failure, 200)));
        }
        if (bodies) {
            boolean hide = withheld.contains(call.operation());
            if (options.logRequest()) {
                event =
                        event.addKeyValue(
                                "peer.request.body", body(requestBody, hide, options.mask()));
            }
            if (options.logResponse()) {
                event =
                        event.addKeyValue(
                                "peer.response.body", body(responseBody, hide, options.mask()));
            }
        }
        event.log(
                "{} {} {} -> {} in {} ms (attempt {}, {})",
                system.toUpperCase(Locale.ROOT),
                method,
                path,
                status == null ? "no answer" : status,
                millis,
                call.attempt(),
                outcome);
    }

    /**
     * A body for the log: masked by the policy and cut; null stays null; never shown for a withheld
     * operation. With masking off for the call the body is shown as it is (still cut).
     */
    public String body(String raw, boolean hide, boolean mask) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        if (hide) {
            return WITHHELD;
        }
        if (!mask) {
            return LogSanitizer.encodeControlCharacters(cut(raw, maxBody));
        }
        if (!policy.configured()) {
            return NO_POLICY;
        }
        return LogSanitizer.encodeControlCharacters(cut(policy.body(raw), maxBody));
    }

    /**
     * A query string for the log, each value masked by its parameter name unless masking is off.
     */
    public String query(String rawQuery, boolean mask) {
        if (!mask) {
            return LogSanitizer.encodeControlCharacters(cut(rawQuery, 300));
        }
        if (!policy.configured()) {
            return NO_POLICY;
        }
        return LogSanitizer.encodeControlCharacters(cut(policy.query(rawQuery), 300));
    }

    private static String cut(String text, int max) {
        return text.length() > max ? text.substring(0, max) + "..." : text;
    }

    private static int bytes(String text) {
        return text == null ? 0 : text.getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * Counts an event of the peer: {@code <prefix>.<name>} tagged {@code tag=value}; also logged.
     */
    public void count(String name, String tag, String value) {
        registry.counter(prefix + "." + name, tag, value).increment();
        log.atInfo()
                .addKeyValue("peer.system", system)
                .addKeyValue("peer." + name, value)
                .log("{} {} {}", system.toUpperCase(Locale.ROOT), name, value);
    }
}
