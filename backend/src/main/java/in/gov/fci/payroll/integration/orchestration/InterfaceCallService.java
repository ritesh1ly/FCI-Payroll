package in.gov.fci.payroll.integration.orchestration;

import in.gov.fci.payroll.integration.api.InterfaceBreakers;
import in.gov.fci.payroll.integration.api.InterfaceCalls;
import in.gov.fci.payroll.integration.api.InterfaceSettings;
import in.gov.fci.payroll.integration.api.InterfaceSettings.Settings;
import in.gov.fci.payroll.integration.business.IntegrationErrorCode;
import in.gov.fci.payroll.integration.business.IntegrationRules;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.logging.ContextPropagation;
import in.gov.fci.payroll.platform.logging.CriticalLog;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A resilient call through an interface (plan 23 phase 23a; SRS-INT-6, SRS-INT-16, SRS-INT-31; HLD
 * integration layer; D23-3, D23-8). The values are the interface's settings in force, never coded
 * constants: without them the call is refused. The breaker state is held in the database ({@code
 * interface_breaker_state}; the DDL: a per-instance breaker on a stateless tier is not a breaker):
 * an open breaker refuses calls until its probe time, then lets one probe through. A call that
 * keeps failing beyond the retry limit counts one consecutive failure, opens the breaker at its
 * threshold (probing again after the next back-off interval), goes to the exception queue and
 * raises the Integration Failure at once.
 */
@Service
public class InterfaceCallService implements InterfaceCalls, InterfaceBreakers {

    /** Waits between attempts (a seam for tests; the batch tier may wait). */
    @FunctionalInterface
    interface Pause {
        void seconds(long seconds) throws InterruptedException;
    }

    private static final String CLOSED = "CLOSED";
    private static final String OPEN = "OPEN";
    private static final String HALF_OPEN = "HALF_OPEN";

    private final InterfaceSettings settings;
    private final IntegrationStore store;
    private final FailureAlerts alerts;
    private final Clock clock;
    private final TransactionTemplate own;
    private final Executor executor;
    private final Pause pause;

    @Autowired
    public InterfaceCallService(
            InterfaceSettings settings,
            IntegrationStore store,
            FailureAlerts alerts,
            Clock clock,
            PlatformTransactionManager transactions) {
        this(
                settings,
                store,
                alerts,
                clock,
                transactions,
                ContextPropagation.contextExecutor("fci-intf-", 4),
                s -> Thread.sleep(TimeUnit.SECONDS.toMillis(s)));
    }

    InterfaceCallService(
            InterfaceSettings settings,
            IntegrationStore store,
            FailureAlerts alerts,
            Clock clock,
            PlatformTransactionManager transactions,
            Executor executor,
            Pause pause) {
        this.settings = settings;
        this.store = store;
        this.alerts = alerts;
        this.clock = clock;
        this.own = new TransactionTemplate(transactions);
        this.own.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.executor = executor;
        this.pause = pause;
    }

    @Override
    public <T> T call(String interfaceCode, Callable<T> call) {
        IntegrationStore.Registered intf = store.interfaceOf(interfaceCode);
        Settings s =
                settings.inForce(interfaceCode)
                        .orElseThrow(
                                () ->
                                        new FciException(
                                                IntegrationErrorCode.NOT_CONFIGURED,
                                                new Object[] {interfaceCode},
                                                List.of(),
                                                null));
        boolean probe = admit(intf);
        int attempts = probe ? 1 : s.retryCount() + 1;
        Exception last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                T result = attempt(call, s.timeoutSeconds());
                succeeded(intf);
                return result;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                last = e;
                break;
            } catch (Exception e) {
                last = e;
                if (attempt < attempts && !wait(s, attempt)) {
                    break;
                }
            }
        }
        String reason = "Call failed after " + attempts + " attempt(s): " + summary(last);
        failed(intf, s, reason);
        throw new FciException(
                IntegrationErrorCode.CALL_FAILED,
                new Object[] {interfaceCode, attempts, summary(last)},
                List.of(),
                last);
    }

    /** OD-148: the breaker alone, for a caller that retries per record (InterfaceBreakers). */
    @Override
    public boolean admit(String interfaceCode) {
        if (settings.inForce(interfaceCode).isEmpty()) {
            return false;
        }
        try {
            admit(store.interfaceOf(interfaceCode));
            return true;
        } catch (FciException open) {
            if (open.getErrorCode() == IntegrationErrorCode.CIRCUIT_OPEN) {
                return false;
            }
            throw open;
        }
    }

    @Override
    public void succeeded(String interfaceCode) {
        succeeded(store.interfaceOf(interfaceCode));
    }

    @Override
    public void failed(String interfaceCode, String reason) {
        settings.inForce(interfaceCode)
                .ifPresent(
                        s ->
                                failed(
                                        store.interfaceOf(interfaceCode),
                                        s,
                                        IntegrationRules.cut(reason, 300)));
    }

    /** One attempt under the interface's time limit (Resilience4j TimeLimiter, R-INT). */
    private <T> T attempt(Callable<T> call, int timeoutSeconds) throws Exception {
        TimeLimiter limiter =
                TimeLimiter.of(
                        TimeLimiterConfig.custom()
                                .timeoutDuration(Duration.ofSeconds(timeoutSeconds))
                                .cancelRunningFuture(true)
                                .build());
        return limiter.executeFutureSupplier(
                () ->
                        CompletableFuture.supplyAsync(
                                () -> {
                                    try {
                                        return call.call();
                                    } catch (Exception e) {
                                        throw new CompletionException(e);
                                    }
                                },
                                executor));
    }

    /** Waits the back-off before the next attempt; false when interrupted. */
    private boolean wait(Settings s, int attempt) {
        try {
            pause.seconds(
                    IntegrationRules.backoffSeconds(
                            s.backoffStrategy(), s.backoffSeconds(), attempt));
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Refuses while the breaker is open and its probe time has not come; at the probe time the
     * breaker turns HALF_OPEN and this call is the probe (answers true).
     */
    private boolean admit(IntegrationStore.Registered intf) {
        Boolean probe =
                own.execute(
                        tx -> {
                            Map<String, Object> b = breaker(intf.id());
                            if (!OPEN.equals(b.get("BREAKER_STATE"))) {
                                return HALF_OPEN.equals(b.get("BREAKER_STATE"));
                            }
                            Timestamp next = (Timestamp) b.get("NEXT_PROBE_AT");
                            Instant now = clock.instant();
                            if (next != null && now.isBefore(next.toInstant())) {
                                throw new FciException(
                                        IntegrationErrorCode.CIRCUIT_OPEN,
                                        new Object[] {intf.code(), next.toInstant().toString()},
                                        List.of(),
                                        null);
                            }
                            store.jdbc()
                                    .update(
                                            "UPDATE interface_breaker_state SET breaker_state ="
                                                    + " 'HALF_OPEN', updated_at = ? WHERE"
                                                    + " interface_id = ?",
                                            Timestamp.from(now),
                                            intf.id());
                            return true;
                        });
        return Boolean.TRUE.equals(probe);
    }

    private void succeeded(IntegrationStore.Registered intf) {
        own.executeWithoutResult(
                tx -> {
                    breaker(intf.id());
                    store.jdbc()
                            .update(
                                    "UPDATE interface_breaker_state SET breaker_state = 'CLOSED',"
                                            + " consecutive_failures = 0, opened_at = NULL,"
                                            + " next_probe_at = NULL, updated_at = ? WHERE"
                                            + " interface_id = ?",
                                    Timestamp.from(clock.instant()),
                                    intf.id());
                });
    }

    private void failed(IntegrationStore.Registered intf, Settings s, String reason) {
        own.executeWithoutResult(
                tx -> {
                    Map<String, Object> b = breaker(intf.id());
                    int failures = ((Number) b.get("CONSECUTIVE_FAILURES")).intValue() + 1;
                    boolean wasProbe = HALF_OPEN.equals(b.get("BREAKER_STATE"));
                    Instant now = clock.instant();
                    boolean opens =
                            wasProbe
                                    || IntegrationRules.opens(
                                            s.circuitBreakerRequired(),
                                            s.circuitBreakerThreshold(),
                                            failures);
                    Instant next =
                            now.plusSeconds(
                                    IntegrationRules.backoffSeconds(
                                            s.backoffStrategy(),
                                            s.backoffSeconds(),
                                            s.retryCount() + 1));
                    store.jdbc()
                            .update(
                                    "UPDATE interface_breaker_state SET breaker_state = ?,"
                                            + " consecutive_failures = ?, opened_at = ?,"
                                            + " next_probe_at = ?, updated_at = ? WHERE"
                                            + " interface_id = ?",
                                    opens ? OPEN : CLOSED,
                                    failures,
                                    opens ? Timestamp.from(now) : null,
                                    opens ? Timestamp.from(next) : null,
                                    Timestamp.from(now),
                                    intf.id());
                    alerts.raise(intf, null, null, null, reason);
                    if (opens) {
                        // OD-130: X-03 shows it; the critical log lets an alert page someone.
                        CriticalLog.log(
                                CriticalLog.Event.INTEGRATION_BREAKER_OPEN,
                                CriticalLog.fields("interface", intf.id(), "failures", failures));
                    }
                });
    }

    /** The interface's breaker row, created CLOSED on first use. */
    private Map<String, Object> breaker(long interfaceId) {
        List<Map<String, Object>> rows =
                store.jdbc()
                        .queryForList(
                                "SELECT breaker_state, consecutive_failures, next_probe_at FROM"
                                        + " interface_breaker_state WHERE interface_id = ?",
                                interfaceId);
        if (!rows.isEmpty()) {
            return rows.getFirst();
        }
        store.jdbc()
                .update(
                        "INSERT INTO interface_breaker_state (interface_id, breaker_state,"
                                + " consecutive_failures, updated_at) VALUES (?, 'CLOSED', 0, ?)",
                        interfaceId,
                        Timestamp.from(clock.instant()));
        return Map.of("BREAKER_STATE", CLOSED, "CONSECUTIVE_FAILURES", 0);
    }

    /** The failure as one line: its type and message, cut (identifiers only, SRS-SEC-4). */
    static String summary(Throwable error) {
        if (error == null) {
            return "no answer";
        }
        String message = error.getMessage();
        return IntegrationRules.cut(
                error.getClass().getSimpleName()
                        + (message == null || message.isBlank() ? "" : ": " + message),
                300);
    }
}
