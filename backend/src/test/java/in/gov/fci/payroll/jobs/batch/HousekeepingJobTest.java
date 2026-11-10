package in.gov.fci.payroll.jobs.batch;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.platform.idempotency.IdempotencyRepository;
import in.gov.fci.payroll.platform.maintenance.Housekeeping;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Batch housekeeping (HLD batch node; OQ-10: audit_event is never purged here): expired idempotency
 * records and browser sessions are purged at the same instant, then every module's task runs with
 * that instant.
 */
class HousekeepingJobTest {

    private static final Instant NOW = Instant.parse("2026-10-28T06:00:00Z");

    @Test
    void OQ_10_expiredTechnicalRowsArePurgedAndEveryModuleTaskRunsAtTheSameInstant() {
        IdempotencyRepository idempotency = mock(IdempotencyRepository.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        Housekeeping otps = mock(Housekeeping.class);
        when(otps.name()).thenReturn("expiredOtps");
        Housekeeping sla = mock(Housekeeping.class);
        when(sla.name()).thenReturn("slaBreaches");
        new HousekeepingJob(idempotency, List.of(otps, sla), jdbc, Clock.fixed(NOW, ZoneOffset.UTC))
                .purgeExpired();
        verify(idempotency).purgeExpired(NOW);
        verify(jdbc).update(anyString(), eq(NOW.toEpochMilli()));
        verify(otps).run(NOW);
        verify(sla).run(NOW);
    }
}
