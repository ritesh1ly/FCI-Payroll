package in.gov.fci.payroll.jobs.batch.recovery;

import in.gov.fci.payroll.jobs.batch.launch.JobLaunchService;
import in.gov.fci.payroll.jobs.batch.lock.JobLockService;
import in.gov.fci.payroll.jobs.batch.support.BatchHistory;
import in.gov.fci.payroll.jobs.batch.support.BatchInstance;
import in.gov.fci.payroll.jobs.batch.support.JobEnds;
import in.gov.fci.payroll.jobs.business.model.BatchJobControl;
import in.gov.fci.payroll.jobs.business.model.BatchJobRequest;
import in.gov.fci.payroll.jobs.business.model.ControlCommand;
import in.gov.fci.payroll.jobs.business.model.JobStatus;
import in.gov.fci.payroll.jobs.config.BatchProperties;
import in.gov.fci.payroll.jobs.orchestration.JobJournal;
import in.gov.fci.payroll.jobs.orchestration.JobRequestService;
import in.gov.fci.payroll.jobs.persistence.JobRepositories;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Crash recovery. A request that is PICKED or RUNNING but whose job lock is missing or has not had
 * a heartbeat for {@code fci.batch.lock.stale-after} belongs to a node that died (kill -9, power
 * loss, OOM). Its Spring Batch execution is still STARTED / STOPPING in the job repository and
 * would block a restart forever. Recovery:
 *
 * <ol>
 *   <li>marks the execution and its unfinished steps FAILED (end time now, exit status FAILED with
 *       "abnormal shutdown"), so Spring Batch allows a restart from the last committed chunk;
 *   <li>marks the request FAILED, deletes the stale lock, audits RECOVERED;
 *   <li>with {@code fci.batch.recovery.auto-restart=true} queues a RESTART command (actor
 *       system.recovery); otherwise the request waits for a manual Restart (C-13).
 * </ol>
 *
 * It runs once when this node is ready and then on every poll, so a surviving node also cleans up
 * after a dead one. Requests this node is running itself are never touched.
 */
@Service
@Profile("batch")
public class CrashRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(CrashRecoveryService.class);
    static final String ACTOR = "system.recovery";

    private final JobRepositories.JobRequests requests;
    private final JobRepositories.Controls controls;
    private final JobLockService locks;
    private final JobLaunchService launcher;
    private final BatchHistory explorer;
    private final JobRepository repository;
    private final JobRequestService service;
    private final JobJournal journal;
    private final BatchInstance instance;
    private final BatchProperties properties;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    public CrashRecoveryService(
            JobRepositories.JobRequests requests,
            JobRepositories.Controls controls,
            JobLockService locks,
            JobLaunchService launcher,
            BatchHistory explorer,
            JobRepository repository,
            JobRequestService service,
            JobJournal journal,
            BatchInstance instance,
            BatchProperties properties,
            TransactionTemplate tx,
            Clock clock,
            ApplicationEventPublisher events) {
        this.requests = requests;
        this.controls = controls;
        this.locks = locks;
        this.launcher = launcher;
        this.explorer = explorer;
        this.repository = repository;
        this.service = service;
        this.journal = journal;
        this.instance = instance;
        this.properties = properties;
        this.tx = tx;
        this.clock = clock;
        this.events = events;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        int recovered = recoverAbandoned();
        log.info(
                "event=BATCH_RECOVERY_STARTUP instance={} recovered={} autoRestart={}",
                instance.id(),
                recovered,
                properties.recovery().autoRestart());
    }

    /** Recovers every abandoned request; returns how many. */
    public int recoverAbandoned() {
        int recovered = 0;
        Instant staleBefore = clock.instant().minus(properties.lock().staleAfter());
        for (BatchJobRequest request : requests.findByStatusIn(JobStatus.ACTIVE)) {
            if (launcher.running().contains(request.getId())) {
                continue;
            }
            boolean pickedLongAgo =
                    request.getPickedAt() == null || request.getPickedAt().isBefore(staleBefore);
            if (!pickedLongAgo || !locks.isStale(request.getId())) {
                continue;
            }
            if (recover(request.getId())) {
                recovered++;
            }
        }
        return recovered;
    }

    /** Fails an abandoned request; false when it ended or started running here meanwhile. */
    private boolean recover(Long requestId) {
        java.util.concurrent.atomic.AtomicBoolean done =
                new java.util.concurrent.atomic.AtomicBoolean();
        tx.executeWithoutResult(
                s ->
                        requests.findById(requestId)
                                // Batch campaign 2026-10-30 (R5): the sweep lists the active jobs
                                // first; one that ended meanwhile (COMPLETED, FAILED, STOPPED) is
                                // never recovered over its real outcome. A finish racing this
                                // transaction is caught by the request's @Version.
                                .filter(r -> JobStatus.ACTIVE.contains(r.getStatus()))
                                .filter(r -> !launcher.running().contains(requestId))
                                .ifPresent(
                                        request -> {
                                            done.set(true);
                                            JobStatus from = request.getStatus();
                                            String message =
                                                    "Abnormal shutdown of "
                                                            + (request.getClaimedBy() == null
                                                                    ? "a batch node"
                                                                    : request.getClaimedBy())
                                                            + " detected by "
                                                            + instance.id();
                                            if (request.getJobExecutionId() != null) {
                                                failExecution(request.getJobExecutionId(), message);
                                            }
                                            request.recoverAsFailed(message, clock.instant());
                                            requests.save(request);
                                            // SRS-NF-2 / NF-5 (DR-18): the requester is told.
                                            JobEnds.of(request).ifPresent(events::publishEvent);
                                            journal.record(
                                                    requestId,
                                                    JobJournal.Event.RECOVERED,
                                                    ACTOR,
                                                    from,
                                                    JobStatus.FAILED,
                                                    request.getJobExecutionId(),
                                                    message);
                                            if (properties.recovery().autoRestart()
                                                    && service.withinRestartWindow(request)
                                                    && !controls.existsByJobRequestIdAndStatus(
                                                            requestId,
                                                            BatchJobControl.Status.PENDING)) {
                                                controls.save(
                                                        BatchJobControl.request(
                                                                requestId,
                                                                ControlCommand.RESTART,
                                                                null,
                                                                ACTOR,
                                                                "Automatic restart after crash"
                                                                        + " recovery",
                                                                clock.instant()));
                                                journal.record(
                                                        requestId,
                                                        JobJournal.Event.CONTROL_REQUESTED,
                                                        ACTOR,
                                                        JobStatus.FAILED,
                                                        null,
                                                        request.getJobExecutionId(),
                                                        "RESTART (auto)");
                                            }
                                            log.warn(
                                                    "event=JOB_RECOVERED jobRequestId={} from={}"
                                                            + " jobExecutionId={}",
                                                    requestId,
                                                    from,
                                                    request.getJobExecutionId());
                                        }));
        if (done.get()) {
            locks.release(requestId);
        }
        return done.get();
    }

    private void failExecution(Long executionId, String message) {
        JobExecution execution = explorer.getJobExecution(executionId);
        if (execution == null || !execution.getStatus().isRunning()) {
            return;
        }
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneId.systemDefault());
        for (StepExecution step : execution.getStepExecutions()) {
            if (step.getStatus().isRunning()) {
                step.setStatus(BatchStatus.FAILED);
                step.setExitStatus(ExitStatus.FAILED.addExitDescription(message));
                step.setEndTime(now);
                repository.update(step);
            }
        }
        execution.setStatus(BatchStatus.FAILED);
        execution.setExitStatus(ExitStatus.FAILED.addExitDescription(message));
        execution.setEndTime(now);
        repository.update(execution);
    }
}
