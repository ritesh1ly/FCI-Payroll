package in.gov.fci.payroll.jobs.batch;

import in.gov.fci.payroll.platform.idempotency.IdempotencyRepository;
import in.gov.fci.payroll.platform.maintenance.Housekeeping;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.StringJoiner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Periodic housekeeping of the batch instance: purges expired idempotency records and expired
 * browser sessions (Spring Session's own cleanup is disabled on the online instance so that it runs
 * no scheduled task), then runs every module's {@link Housekeeping} task (OTP expiry, inbox SLA
 * flags …). {@code audit_event} is append-only and never purged here (retention >> OPEN << OQ-10).
 */
@Component
@Profile("batch")
public class HousekeepingJob {

    private static final Logger log = LoggerFactory.getLogger(HousekeepingJob.class);

    private final IdempotencyRepository idempotency;
    private final List<Housekeeping> tasks;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public HousekeepingJob(
            IdempotencyRepository idempotency,
            List<Housekeeping> tasks,
            JdbcTemplate jdbc,
            Clock clock) {
        this.idempotency = idempotency;
        this.tasks = tasks;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Scheduled(
            fixedDelayString = "${fci.api.maintenance.interval:PT10M}",
            initialDelayString = "PT1M")
    @Transactional
    public void purgeExpired() {
        Instant now = clock.instant();
        int purged = idempotency.purgeExpired(now);
        int sessions =
                jdbc.update("DELETE FROM SPRING_SESSION WHERE EXPIRY_TIME < ?", now.toEpochMilli());
        StringJoiner done = new StringJoiner(" ");
        for (Housekeeping task : tasks) {
            done.add(task.name() + "=" + task.run(now));
        }
        log.info("event=MAINTENANCE purgedRows={} expiredSessions={} {}", purged, sessions, done);
    }
}
