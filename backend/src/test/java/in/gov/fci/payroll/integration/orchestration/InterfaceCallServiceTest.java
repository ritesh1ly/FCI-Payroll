package in.gov.fci.payroll.integration.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.integration.api.InterfaceSettings;
import in.gov.fci.payroll.integration.api.InterfaceSettings.Settings;
import in.gov.fci.payroll.platform.error.FciException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * A call through an interface interrupted while it runs or while it waits its back-off, an open
 * breaker without a probe time, and the one-line failure summary (plan 23 phase 23a; SRS-INT-6,
 * SRS-INT-31; D23-8).
 */
class InterfaceCallServiceTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final IntegrationStore store = mock(IntegrationStore.class);
    private final InterfaceSettings settings = mock(InterfaceSettings.class);
    private final AtomicInteger waits = new AtomicInteger();
    private boolean interruptWait;

    private InterfaceCallService service() {
        return new InterfaceCallService(
                settings,
                store,
                mock(FailureAlerts.class),
                Clock.fixed(Instant.parse("2026-10-28T06:00:00Z"), ZoneOffset.UTC),
                mock(PlatformTransactionManager.class),
                Runnable::run,
                s -> {
                    waits.incrementAndGet();
                    if (interruptWait) {
                        throw new InterruptedException("stop");
                    }
                });
    }

    @BeforeEach
    void wire() {
        when(store.jdbc()).thenReturn(jdbc);
        when(store.interfaceOf("TAX_RETURN"))
                .thenReturn(
                        new IntegrationStore.Registered(
                                9L, "TAX_RETURN", "TAX_AUTHORITY", "BIDIRECTIONAL"));
        when(settings.inForce("TAX_RETURN"))
                .thenReturn(
                        Optional.of(
                                new Settings(
                                        "TAX_RETURN",
                                        2,
                                        1,
                                        "FIXED",
                                        1,
                                        true,
                                        3,
                                        null,
                                        null,
                                        LocalDate.of(2020, 4, 1),
                                        false)));
        breaker("CLOSED", null);
    }

    @AfterEach
    void clearInterrupt() {
        // A test that interrupts must not leak the flag into the next one.
        Thread.interrupted();
    }

    private void breaker(String state, Timestamp next) {
        Map<String, Object> row = new HashMap<>();
        row.put("BREAKER_STATE", state);
        row.put("CONSECUTIVE_FAILURES", 0);
        row.put("NEXT_PROBE_AT", next);
        when(jdbc.queryForList(contains("FROM interface_breaker_state"), eq(9L)))
                .thenReturn(List.of(row));
    }

    @Test
    void SRS_INT_6_anInterruptedCallStopsWithoutRetrying() {
        AtomicInteger attempts = new AtomicInteger();
        assertThatThrownBy(
                        () ->
                                service()
                                        .call(
                                                "TAX_RETURN",
                                                () -> {
                                                    attempts.incrementAndGet();
                                                    throw new InterruptedException("shutting down");
                                                }))
                .isInstanceOf(FciException.class)
                .hasMessage("INT-502-CALL-FAILED");
        assertThat(attempts.get()).isEqualTo(1);
        assertThat(waits.get()).isZero();
    }

    @Test
    void SRS_INT_6_anInterruptedBackOffStopsTheRetries() {
        interruptWait = true;
        AtomicInteger attempts = new AtomicInteger();
        assertThatThrownBy(
                        () ->
                                service()
                                        .call(
                                                "TAX_RETURN",
                                                () -> {
                                                    attempts.incrementAndGet();
                                                    throw new IllegalStateException();
                                                }))
                .isInstanceOf(FciException.class);
        assertThat(attempts.get()).as("no retry after the interrupted wait").isEqualTo(1);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    @Test
    void SRS_INT_6_anOpenBreakerWithoutAProbeTimeLetsOneProbeThrough() throws Exception {
        breaker("OPEN", null);
        assertThat(service().call("TAX_RETURN", () -> "ack")).isEqualTo("ack");
        // The call is the probe (HALF_OPEN), and its answer closes the breaker.
        verify(jdbc).update(contains("breaker_state = 'HALF_OPEN'"), any(Timestamp.class), eq(9L));
        verify(jdbc).update(contains("breaker_state = 'CLOSED'"), any(Timestamp.class), eq(9L));
    }

    @Test
    void SRS_INT_6_anOpenBreakerBeforeItsProbeTimeRefusesWithoutCalling() {
        breaker("OPEN", Timestamp.from(Instant.parse("2026-10-28T06:05:00Z")));
        AtomicInteger attempts = new AtomicInteger();
        assertThatThrownBy(
                        () ->
                                service()
                                        .call(
                                                "TAX_RETURN",
                                                () -> {
                                                    attempts.incrementAndGet();
                                                    return "never";
                                                }))
                .isInstanceOf(FciException.class)
                .hasMessage("INT-503-CIRCUIT-OPEN");
        assertThat(attempts.get()).isZero();
        verify(jdbc, never()).update(contains("breaker_state"), any(Timestamp.class), eq(9L));
    }

    @Test
    void SRS_INT_31_theFailureReadsAsOneLine() {
        assertThat(InterfaceCallService.summary(null)).isEqualTo("no answer");
        assertThat(InterfaceCallService.summary(new IllegalStateException()))
                .isEqualTo("IllegalStateException");
        assertThat(InterfaceCallService.summary(new IllegalStateException(" ")))
                .isEqualTo("IllegalStateException");
        assertThat(InterfaceCallService.summary(new IllegalStateException("down")))
                .isEqualTo("IllegalStateException: down");
        assertThat(InterfaceCallService.summary(new IllegalStateException("x".repeat(400))))
                .hasSize(300);
    }
}
