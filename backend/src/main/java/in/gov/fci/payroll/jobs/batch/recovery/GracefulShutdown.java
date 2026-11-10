package in.gov.fci.payroll.jobs.batch.recovery;

import in.gov.fci.payroll.jobs.batch.launch.JobLaunchService;
import in.gov.fci.payroll.jobs.batch.support.BatchInstance;
import in.gov.fci.payroll.jobs.batch.support.JobStopper;
import in.gov.fci.payroll.jobs.business.model.BatchJobRequest;
import in.gov.fci.payroll.jobs.business.model.JobStatus;
import in.gov.fci.payroll.jobs.config.BatchProperties;
import in.gov.fci.payroll.jobs.orchestration.JobJournal;
import in.gov.fci.payroll.jobs.persistence.JobRepositories;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Clean stop of the batch node (SIGTERM, Tomcat shutdown). Before the executors and the data source
 * go away it stops accepting work, asks every job this node runs to stop at the next chunk boundary
 * with the intent PAUSE (so the request ends PAUSED and can be resumed from its last commit), and
 * waits up to {@code fci.batch.shutdown-timeout} for them. A job still running after that is left
 * to crash recovery on the next start.
 */
@Component
@Profile("batch")
public class GracefulShutdown implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(GracefulShutdown.class);

    private final JobLaunchService launcher;
    private final JobRepositories.JobRequests requests;
    private final JobStopper stopper;
    private final JobJournal journal;
    private final BatchInstance instance;
    private final BatchProperties properties;
    private final TransactionTemplate tx;
    private final AtomicBoolean accepting = new AtomicBoolean(false);

    public GracefulShutdown(
            JobLaunchService launcher,
            JobRepositories.JobRequests requests,
            JobStopper stopper,
            JobJournal journal,
            BatchInstance instance,
            BatchProperties properties,
            TransactionTemplate tx) {
        this.launcher = launcher;
        this.requests = requests;
        this.stopper = stopper;
        this.journal = journal;
        this.instance = instance;
        this.properties = properties;
        this.tx = tx;
    }

    /** The poller asks before taking new work. */
    public boolean isAccepting() {
        return accepting.get();
    }

    @Override
    public void start() {
        accepting.set(true);
    }

    @Override
    public void stop() {
        accepting.set(false);
        Set<Long> mine = launcher.running();
        log.info("event=BATCH_SHUTDOWN instance={} runningJobs={}", instance.id(), mine.size());
        for (Long requestId : mine) {
            tx.executeWithoutResult(s -> requests.findById(requestId).ifPresent(this::pause));
        }
        Instant deadline = Instant.now().plus(properties.shutdownTimeout());
        while (!launcher.running().isEmpty() && Instant.now().isBefore(deadline)) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (!launcher.running().isEmpty()) {
            log.warn(
                    "event=BATCH_SHUTDOWN_TIMEOUT instance={} stillRunning={}",
                    instance.id(),
                    launcher.running());
        }
    }

    private void pause(BatchJobRequest request) {
        if (request.getStatus() != JobStatus.RUNNING || request.getJobExecutionId() == null) {
            return;
        }
        try {
            request.requestStop("PAUSE");
            requests.save(request);
            if (!stopper.stop(request.getJobExecutionId())) {
                throw new IllegalStateException("not running");
            }
            journal.record(
                    request.getId(),
                    JobJournal.Event.CONTROL_APPLIED,
                    instance.id(),
                    JobStatus.RUNNING,
                    null,
                    request.getJobExecutionId(),
                    "PAUSE on node shutdown");
        } catch (Exception notRunning) {
            log.info(
                    "event=BATCH_SHUTDOWN_STOP_SKIPPED jobRequestId={} reason={}",
                    request.getId(),
                    notRunning.getClass().getSimpleName());
        }
    }

    @Override
    public boolean isRunning() {
        return accepting.get();
    }

    /**
     * Stopped first (highest phase): before the job and partition executors (phase {@link
     * #EXECUTOR_PHASE}), the scheduler and the data source.
     */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    /** Lifecycle phase of the batch executors: below this hook, so they wait for paused jobs. */
    public static final int EXECUTOR_PHASE = Integer.MAX_VALUE - 1000;
}
