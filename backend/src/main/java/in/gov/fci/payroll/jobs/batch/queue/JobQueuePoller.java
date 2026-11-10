package in.gov.fci.payroll.jobs.batch.queue;

import in.gov.fci.payroll.jobs.batch.control.BatchHaltEnforcer;
import in.gov.fci.payroll.jobs.batch.control.ControlCommandProcessor;
import in.gov.fci.payroll.jobs.batch.launch.JobLaunchService;
import in.gov.fci.payroll.jobs.batch.recovery.AbandonSweep;
import in.gov.fci.payroll.jobs.batch.recovery.CrashRecoveryService;
import in.gov.fci.payroll.jobs.batch.recovery.GracefulShutdown;
import in.gov.fci.payroll.jobs.business.model.BatchJobRequest;
import in.gov.fci.payroll.jobs.business.model.JobStatus;
import in.gov.fci.payroll.jobs.config.BatchProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The batch node's heartbeat of work, every {@code fci.batch.poller.interval}:
 *
 * <ol>
 *   <li>crash recovery of requests left by a dead node;
 *   <li>failed or stopped jobs past the restart window abandoned (SRS-15-27, {@link AbandonSweep});
 *   <li>pending control commands (Stop / Pause / Resume / Restart / Abandon);
 *   <li>while batch processing is halted (plan 05j), this node's running jobs told to stop and
 *       nothing new launched ({@link BatchHaltEnforcer});
 *   <li>new PENDING requests, up to the free job slots, claimed with SKIP LOCKED and launched.
 * </ol>
 *
 * Nothing is taken while the node shuts down.
 */
@Component
@Profile("batch")
public class JobQueuePoller {

    private static final Logger log = LoggerFactory.getLogger(JobQueuePoller.class);

    private final JobClaimer claimer;
    private final JobLaunchService launcher;
    private final ControlCommandProcessor controls;
    private final CrashRecoveryService recovery;
    private final AbandonSweep sweep;
    private final BatchHaltEnforcer halt;
    private final GracefulShutdown shutdown;
    private final BatchProperties properties;

    public JobQueuePoller(
            JobClaimer claimer,
            JobLaunchService launcher,
            ControlCommandProcessor controls,
            CrashRecoveryService recovery,
            AbandonSweep sweep,
            BatchHaltEnforcer halt,
            GracefulShutdown shutdown,
            BatchProperties properties) {
        this.claimer = claimer;
        this.launcher = launcher;
        this.controls = controls;
        this.recovery = recovery;
        this.sweep = sweep;
        this.halt = halt;
        this.shutdown = shutdown;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${fci.batch.poller.interval:5s}", initialDelayString = "10s")
    public void poll() {
        if (!shutdown.isAccepting()) {
            return;
        }
        try {
            recovery.recoverAbandoned();
            sweep.sweep();
            controls.processPending();
            if (halt.enforce()) {
                return;
            }
            int slots = properties.poller().maxConcurrentJobs();
            int free = launcher.freeSlots(slots);
            if (free > 0) {
                // SRS-NF-11 (DR-17): bulk work never takes the last slot of a node with more than
                // one.
                int lowAllowed =
                        slots == 1 ? free : Math.max(0, slots - 1 - claimer.runningLowPriority());
                for (BatchJobRequest request :
                        claimer.claimPending(
                                Math.min(free, properties.poller().claimBatchSize()),
                                lowAllowed,
                                properties.poller().claimBatchSize())) {
                    launcher.launch(request, JobStatus.PENDING);
                }
            }
        } catch (RuntimeException failure) {
            // One failed poll (database briefly unavailable) must not stop the scheduler.
            log.error(
                    "event=JOB_POLL_FAILED exception={} message=\"{}\"",
                    failure.getClass().getName(),
                    failure.getMessage());
        }
    }
}
