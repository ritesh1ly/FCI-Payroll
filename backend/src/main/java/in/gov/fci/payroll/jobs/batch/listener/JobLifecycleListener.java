package in.gov.fci.payroll.jobs.batch.listener;

import in.gov.fci.payroll.jobs.batch.launch.JobLaunchService;
import in.gov.fci.payroll.jobs.batch.lock.JobLockService;
import in.gov.fci.payroll.jobs.batch.support.BatchInstance;
import in.gov.fci.payroll.jobs.batch.support.BatchKeys;
import in.gov.fci.payroll.jobs.batch.support.JobEnds;
import in.gov.fci.payroll.jobs.batch.support.PartitionedSteps;
import in.gov.fci.payroll.jobs.business.model.BatchJobRequest;
import in.gov.fci.payroll.jobs.business.model.JobStatus;
import in.gov.fci.payroll.jobs.orchestration.JobJournal;
import in.gov.fci.payroll.jobs.persistence.JobRepositories;
import in.gov.fci.payroll.platform.logging.CriticalLog;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.listener.JobExecutionListener;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The bridge between Spring Batch and the request lifecycle, attached to every job.
 *
 * <p>beforeJob: MDC (request, execution, job), request RUNNING with the execution id and attempt
 * number, audit STARTED, timer start. A request that is not PICKED by this node (should not happen)
 * stops the execution at once.
 *
 * <p>afterJob: totals from the step executions ({@link PartitionedSteps#counts}: the workers of a
 * partitioned step, never its manager, as C-13 shows them while the job runs), the outcome
 * (COMPLETED; STOPPED becomes PAUSED or STOPPED by the recorded intent; anything else FAILED with
 * the first failure), audit, metrics, lock release, cleanup.
 */
@Component
@Profile("batch")
public class JobLifecycleListener implements JobExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(JobLifecycleListener.class);

    private final JobRepositories.JobRequests requests;
    private final JobJournal journal;
    private final JobLockService locks;
    private final JobLaunchService launcher;
    private final BatchInstance instance;
    private final MeterRegistry meters;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final ApplicationEventPublisher events;
    private final Map<Long, Timer.Sample> timers = new ConcurrentHashMap<>();

    public JobLifecycleListener(
            JobRepositories.JobRequests requests,
            JobJournal journal,
            JobLockService locks,
            JobLaunchService launcher,
            BatchInstance instance,
            MeterRegistry meters,
            TransactionTemplate tx,
            Clock clock,
            ApplicationEventPublisher events) {
        this.requests = requests;
        this.journal = journal;
        this.locks = locks;
        this.launcher = launcher;
        this.instance = instance;
        this.meters = meters;
        this.tx = tx;
        this.clock = clock;
        this.events = events;
    }

    @Override
    public void beforeJob(JobExecution execution) {
        Long requestId = execution.getJobParameters().getLong(BatchKeys.REQUEST_ID);
        MDC.put(BatchKeys.MDC_REQUEST, String.valueOf(requestId));
        MDC.put(BatchKeys.MDC_EXECUTION, String.valueOf(execution.getId()));
        MDC.put(BatchKeys.MDC_JOB, execution.getJobInstance().getJobName());
        timers.put(execution.getId(), Timer.start(meters));
        tx.executeWithoutResult(
                s ->
                        requests.findById(requestId)
                                .ifPresent(
                                        request -> {
                                            if (request.getStatus() != JobStatus.PICKED) {
                                                log.warn(
                                                        "event=JOB_NOT_PICKED jobRequestId={}"
                                                                + " status={}",
                                                        requestId,
                                                        request.getStatus());
                                                execution.setStatus(BatchStatus.STOPPING);
                                                return;
                                            }
                                            request.started(execution.getId(), clock.instant());
                                            requests.save(request);
                                            journal.record(
                                                    requestId,
                                                    JobJournal.Event.STARTED,
                                                    instance.id(),
                                                    JobStatus.PICKED,
                                                    JobStatus.RUNNING,
                                                    execution.getId(),
                                                    "Attempt "
                                                            + request.getAttemptNo()
                                                            + " on "
                                                            + instance.id());
                                        }));
        log.info(
                "event=JOB_STARTED jobRequestId={} jobExecutionId={} job={}",
                requestId,
                execution.getId(),
                execution.getJobInstance().getJobName());
    }

    @Override
    public void afterJob(JobExecution execution) {
        Long requestId = execution.getJobParameters().getLong(BatchKeys.REQUEST_ID);
        try {
            PartitionedSteps.Counts counts = PartitionedSteps.counts(execution.getStepExecutions());
            String summary = null;
            for (StepExecution step : execution.getStepExecutions()) {
                if (!PartitionedSteps.isWorker(step.getStepName())
                        && step.getExecutionContext().containsKey(BatchKeys.SUMMARY)) {
                    summary = step.getExecutionContext().getString(BatchKeys.SUMMARY);
                }
            }
            long r = counts.read();
            long w = counts.written();
            long k = counts.skipped();
            String s = summary;
            String error = rootCause(execution);
            JobStatus[] outcome = new JobStatus[1];
            tx.executeWithoutResult(
                    t ->
                            requests.findById(requestId)
                                    .ifPresent(
                                            request -> {
                                                JobStatus from = request.getStatus();
                                                outcome[0] =
                                                        outcome(execution.getStatus(), request);
                                                request.finished(
                                                        outcome[0],
                                                        r,
                                                        w,
                                                        k,
                                                        s,
                                                        outcome[0] == JobStatus.COMPLETED
                                                                ? null
                                                                : blankToNull(error),
                                                        clock.instant());
                                                requests.save(request);
                                                // SRS-NF-2 / NF-5 (DR-18): the requester is told.
                                                JobEnds.of(request).ifPresent(events::publishEvent);
                                                journal.record(
                                                        requestId,
                                                        event(outcome[0]),
                                                        instance.id(),
                                                        from,
                                                        outcome[0],
                                                        execution.getId(),
                                                        "read "
                                                                + r
                                                                + ", written "
                                                                + w
                                                                + ", skipped "
                                                                + k
                                                                + (error == null
                                                                                || outcome[0]
                                                                                        == JobStatus
                                                                                                .COMPLETED
                                                                        ? ""
                                                                        : "; " + error));
                                            }));
            String job = execution.getJobInstance().getJobName();
            String status = outcome[0] == null ? "UNKNOWN" : outcome[0].name();
            Timer.Sample sample = timers.remove(execution.getId());
            if (sample != null) {
                sample.stop(
                        Timer.builder("fci.batch.job.duration")
                                .tag("job", job)
                                .tag("status", status)
                                .register(meters));
            }
            meters.counter("fci.batch.jobs", "job", job, "status", status).increment();
            if (outcome[0] == JobStatus.FAILED) {
                // OD-130: the job name and ids only; the failure text stays on the job record.
                CriticalLog.log(
                        CriticalLog.Event.BATCH_FAILED,
                        CriticalLog.fields(
                                "job", job, "request", requestId, "execution", execution.getId()));
            }
            meters.counter("fci.batch.items", "job", job, "phase", "read").increment(r);
            meters.counter("fci.batch.items", "job", job, "phase", "write").increment(w);
            meters.counter("fci.batch.items", "job", job, "phase", "skip").increment(k);
            log.info(
                    "event=JOB_FINISHED jobRequestId={} jobExecutionId={} batchStatus={} outcome={}"
                            + " read={} written={} skipped={}",
                    requestId,
                    execution.getId(),
                    execution.getStatus(),
                    status,
                    r,
                    w,
                    k);
        } finally {
            locks.release(requestId);
            launcher.finished(requestId);
            MDC.remove(BatchKeys.MDC_REQUEST);
            MDC.remove(BatchKeys.MDC_EXECUTION);
            MDC.remove(BatchKeys.MDC_JOB);
        }
    }

    /**
     * The failure users need to see: a worker step's own exception first (a partition manager only
     * reports "Partition handler returned an unsuccessful step"), then the job's, then its exit
     * description.
     */
    static String rootCause(JobExecution execution) {
        java.util.Optional<Throwable> worker =
                execution.getStepExecutions().stream()
                        .filter(s -> !s.getFailureExceptions().isEmpty())
                        .sorted(
                                java.util.Comparator.comparing(
                                        (StepExecution s) ->
                                                !PartitionedSteps.isWorker(s.getStepName())))
                        .map(s -> s.getFailureExceptions().get(0))
                        .findFirst();
        return worker.or(() -> execution.getAllFailureExceptions().stream().findFirst())
                .map(JobLifecycleListener::deepest)
                .map(t -> t.getClass().getSimpleName() + ": " + t.getMessage())
                .orElse(
                        execution.getStatus() == BatchStatus.COMPLETED
                                ? null
                                : execution.getExitStatus().getExitDescription());
    }

    /**
     * Fault-tolerant steps wrap the real error (ExhaustedRetryException ...): report the original.
     */
    private static Throwable deepest(Throwable error) {
        Throwable current = error;
        for (int depth = 0;
                current.getCause() != null && current.getCause() != current && depth < 10;
                depth++) {
            current = current.getCause();
        }
        return current;
    }

    static JobStatus outcome(BatchStatus status, BatchJobRequest request) {
        return switch (status) {
            case COMPLETED -> JobStatus.COMPLETED;
            case STOPPED, STOPPING ->
                    "PAUSE".equals(request.getStopIntent()) ? JobStatus.PAUSED : JobStatus.STOPPED;
            default -> JobStatus.FAILED;
        };
    }

    private static JobJournal.Event event(JobStatus status) {
        return switch (status) {
            case COMPLETED -> JobJournal.Event.COMPLETED;
            case PAUSED -> JobJournal.Event.PAUSED;
            case STOPPED -> JobJournal.Event.STOPPED;
            default -> JobJournal.Event.FAILED;
        };
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
