package in.gov.fci.payroll.integration.orchestration;

import static in.gov.fci.payroll.ScriptedJdbc.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.ScriptedJdbc;
import in.gov.fci.payroll.platform.cache.CacheProperties;
import in.gov.fci.payroll.platform.cache.CacheRegions;
import java.sql.Date;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * The settings in force of an interface (SRS-INT-16, BR-INT-008; plan 23 D23-3): a row missing its
 * retry count, back-off, time limit or strategy, or a required breaker without its threshold, is
 * not configured; a breaker not required needs none; no row is no settings.
 */
class InterfaceSettingsServiceTest {

    private final ScriptedJdbc db = new ScriptedJdbc();
    private final AtomicLong version = new AtomicLong(1);
    private final CacheRegions regions = mock(CacheRegions.class);
    private final InterfaceSettingsService service;

    InterfaceSettingsServiceTest() {
        when(regions.properties())
                .thenReturn(new CacheProperties(Duration.ofSeconds(10), Duration.ofMinutes(15)));
        when(regions.version(InterfaceSettingsService.REGION)).thenAnswer(c -> version.get());
        service =
                new InterfaceSettingsService(
                        db.jdbc,
                        Clock.fixed(Instant.parse("2026-10-29T06:00:00Z"), ZoneOffset.UTC),
                        regions);
    }

    private static Map<String, Object> config(
            Integer retry,
            Integer backoff,
            Integer timeout,
            String strategy,
            String breaker,
            Integer threshold) {
        return row(
                "retry_count", retry,
                "backoff_seconds", backoff,
                "backoff_strategy", strategy,
                "timeout_seconds", timeout,
                "circuit_breaker_required", breaker,
                "circuit_breaker_threshold", threshold,
                "ack_timeout_seconds", null,
                "counterparty_sla", "T+1",
                "effective_from", Date.valueOf(LocalDate.of(2026, 4, 1)),
                "is_confirmed", "Y");
    }

    private boolean configured(Map<String, Object> row) {
        db.rows("FROM interface_config c JOIN", row);
        // Each case is a changed row: the region moves, as a migration that changes it does.
        version.incrementAndGet();
        return service.inForce("I-HRMS").isPresent();
    }

    @Test
    void BR_INT_008_aRowMissingAnyRequiredSettingIsNotConfigured() {
        assertThat(configured(config(null, 30, 60, "EXPONENTIAL", "N", null))).isFalse();
        assertThat(configured(config(3, null, 60, "EXPONENTIAL", "N", null))).isFalse();
        assertThat(configured(config(3, 30, null, "EXPONENTIAL", "N", null))).isFalse();
        assertThat(configured(config(3, 30, 60, null, "N", null))).isFalse();
        assertThat(configured(config(3, 30, 60, "EXPONENTIAL", "Y", null))).isFalse();
    }

    @Test
    void BR_INT_008_aBreakerNotRequiredNeedsNoThresholdAndARequiredOneHasIt() {
        assertThat(configured(config(3, 30, 60, "FIXED", "N", null))).isTrue();
        assertThat(configured(config(3, 30, 60, "FIXED", "Y", 5))).isTrue();
        db.rows("FROM interface_config c JOIN");
        version.incrementAndGet();
        assertThat(service.inForce("I-HRMS")).isEmpty();
    }

    @Test
    void HLD_3_12_theSettingsAreReadOncePerInterfaceAndDayUntilTheRegionMoves() {
        db.rows("FROM interface_config c JOIN", config(3, 30, 60, "FIXED", "N", null));
        assertThat(service.inForce("I-HRMS")).isPresent();
        assertThat(service.inForce("I-HRMS")).isPresent();
        // "Not configured" is held too: the call is refused without asking again.
        db.rows("FROM interface_config c JOIN");
        assertThat(service.inForce("I-EPFO")).isEmpty();
        assertThat(service.inForce("I-EPFO")).isEmpty();
        assertThat(db.queries("FROM interface_config c JOIN")).hasSize(2);
        version.incrementAndGet();
        assertThat(service.inForce("I-HRMS")).isEmpty();
        assertThat(db.queries("FROM interface_config c JOIN")).hasSize(3);
    }
}
