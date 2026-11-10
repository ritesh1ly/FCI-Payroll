package in.gov.fci.payroll.jobs.batch.recovery;

import in.gov.fci.payroll.jobs.batch.support.JobAbandoner;
import in.gov.fci.payroll.jobs.business.model.BatchJobRequest;
import in.gov.fci.payroll.jobs.business.model.JobStatus;
import in.gov.fci.payroll.jobs.config.BatchProperties;
import in.gov.fci.payroll.jobs.persistence.JobRepositories;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * SRS-15-27 / BR-CYC-014 (plan 05j step j3): a run left failed beyond the configured period is
 * abandoned. A FAILED or STOPPED job that ended more than the restart window ago (the period after
 * which it can no longer be resumed, {@code fci.batch.restart-window}) is abandoned by the system,
 * with the same effect as an approved Abandon. One request per transaction; a request another node
 * abandons first is skipped (its row version moved).
 */
@Component
@Profile("batch")
public class AbandonSweep {

    private static final Logger log = LoggerFactory.getLogger(AbandonSweep.class);

    static final String ACTOR = "system.housekeeping";

    private final JobRepositories.JobRequests requests;
    private final JobAbandoner abandoner;
    private final BatchProperties properties;
    private final TransactionTemplate tx;
    private final Clock clock;

    public AbandonSweep(
            JobRepositories.JobRequests requests,
            JobAbandoner abandoner,
            BatchProperties properties,
            TransactionTemplate tx,
            Clock clock) {
        this.requests = requests;
        this.abandoner = abandoner;
        this.properties = properties;
        this.tx = tx;
        this.clock = clock;
    }

    /** Abandons up to 50 expired jobs; returns how many. */
    public int sweep() {
        Instant cutoff = clock.instant().minus(properties.restartWindow());
        int done = 0;
        for (BatchJobRequest expired :
                requests.findTop50ByStatusInAndFinishedAtBeforeOrderByFinishedAt(
                        EnumSet.of(JobStatus.FAILED, JobStatus.STOPPED), cutoff)) {
            try {
                Boolean abandoned =
                        tx.execute(
                                s ->
                                        requests.findById(expired.getId())
                                                .filter(r -> r.getStatus() == expired.getStatus())
                                                .map(
                                                        r ->
                                                                abandoner.abandon(
                                                                        r,
                                                                        ACTOR,
                                                                        "Restart window of "
                                                                                + properties
                                                                                        .restartWindow()
                                                                                        .toHours()
                                                                                + " h passed"
                                                                                + " (SRS-15-27)"))
                                                .orElse(false));
                if (Boolean.TRUE.equals(abandoned)) {
                    done++;
                }
            } catch (OptimisticLockingFailureException another) {
                log.info("event=JOB_ABANDON_SKIPPED jobRequestId={}", expired.getId());
            }
        }
        return done;
    }
}
