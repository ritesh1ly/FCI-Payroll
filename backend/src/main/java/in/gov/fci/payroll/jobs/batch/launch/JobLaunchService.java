package in.gov.fci.payroll.jobs.batch.launch;

import in.gov.fci.payroll.jobs.batch.lock.JobLockService;
import in.gov.fci.payroll.jobs.batch.support.BatchHistory;
import in.gov.fci.payroll.jobs.batch.support.BatchInstance;
import in.gov.fci.payroll.jobs.batch.support.BatchKeys;
import in.gov.fci.payroll.jobs.business.JobParameterCodec;
import in.gov.fci.payroll.jobs.business.model.BatchJobRequest;
import in.gov.fci.payroll.jobs.business.model.JobStatus;
import in.gov.fci.payroll.jobs.orchestration.JobJournal;
import in.gov.fci.payroll.jobs.persistence.JobRepositories;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Launches a PICKED request on this node.
 *
 * <ol>
 *   <li>Already-running check: {@link BatchHistory} must show no running execution of the job with
 *       the same parameter hash (on any node: the job repository is shared).
 *   <li>Job lock ({@link JobLockService}): acquired before the launch, released by the job listener
 *       when the attempt ends. If either check fails, the request goes back to the state it came
 *       from and the poller retries later (audited as LOCK_REFUSED).
 *   <li>Launch on the job executor with the request id as the only identifying parameter. The same
 *       request therefore always maps to the same JobInstance: launching it again after FAILED or
 *       STOPPED is a Spring Batch restart from the last committed chunk (Resume / Restart), and a
 *       COMPLETED request can never run twice.
 * </ol>
 */
@Service
@Profile("batch")
public class JobLaunchService {

    private static final Logger log = LoggerFactory.getLogger(JobLaunchService.class);

    private final JobRepositories.JobRequests requests;
    private final JobRepositories.Halts halts;
    private final JobLockService locks;
    private final BatchHistory explorer;
    private final JobOperator operator;
    private final ApplicationContext context;
    private final ThreadPoolTaskExecutor executor;
    private final JobJournal journal;
    private final BatchInstance instance;
    private final TransactionTemplate tx;
    private final Clock clock;

    /**
     * Requests this node is running right now (request id; shutdown and crash recovery read it).
     */
    private final Set<Long> running = ConcurrentHashMap.newKeySet();

    public JobLaunchService(
            JobRepositories.JobRequests requests,
            JobRepositories.Halts halts,
            JobLockService locks,
            BatchHistory explorer,
            JobOperator operator,
            ApplicationContext context,
            @Qualifier("jobExecutor") ThreadPoolTaskExecutor executor,
            JobJournal journal,
            BatchInstance instance,
            TransactionTemplate tx,
            Clock clock) {
        this.requests = requests;
        this.halts = halts;
        this.locks = locks;
        this.explorer = explorer;
        this.operator = operator;
        this.context = context;
        this.executor = executor;
        this.journal = journal;
        this.instance = instance;
        this.tx = tx;
        this.clock = clock;
    }

    /** Free job slots on this node. */
    public int freeSlots(int max) {
        return Math.max(0, max - running.size());
    }

    public Set<Long> running() {
        return Set.copyOf(running);
    }

    /** Called by the job listener when an attempt has finished. */
    public void finished(Long requestId) {
        running.remove(requestId);
    }

    /**
     * Launches {@code request} (already PICKED by this node). {@code previous} is where it returns
     * if the launch is refused: PENDING for a first run, PAUSED / FAILED / STOPPED for a resume or
     * restart. Returns false if refused.
     */
    public boolean launch(BatchJobRequest request, JobStatus previous) {
        if (halts.existsByReleasedAtIsNull()) {
            // Plan 05j: a halt engaged between the claim and the launch wins.
            refuse(request.getId(), previous, "Batch processing is halted");
            return false;
        }
        Job job = context.getBean(request.getJobType().jobBean(), Job.class);
        String lockKey = JobLockService.key(request.getJobType().name(), request.getParamHash());
        if (runningElsewhere(job.getName(), request.getParamHash())
                || !locks.acquire(lockKey, request.getId())) {
            refuse(request.getId(), previous, "The same job with the same parameters is running");
            return false;
        }
        running.add(request.getId());
        JobParameters parameters = parameters(request);
        try {
            executor.execute(() -> run(request.getId(), job, parameters));
        } catch (RuntimeException rejected) {
            running.remove(request.getId());
            locks.release(request.getId());
            refuse(request.getId(), previous, "No free job thread on " + instance.id());
            return false;
        }
        return true;
    }

    private void run(Long requestId, Job job, JobParameters parameters) {
        try {
            // Synchronous on this job thread (the operator's own executor is synchronous).
            JobExecution execution = operator.start(job, parameters);
            log.info(
                    "event=JOB_RETURNED jobRequestId={} jobExecutionId={} status={}",
                    requestId,
                    execution.getId(),
                    execution.getStatus());
        } catch (Exception refused) {
            // Spring Batch refused before the job listener ran (e.g. invalid parameters, instance
            // already complete, repository unavailable): fail the request here.
            log.error(
                    "event=JOB_LAUNCH_FAILED jobRequestId={} exception={}",
                    requestId,
                    refused.getClass().getName());
            tx.executeWithoutResult(
                    s ->
                            requests.findById(requestId)
                                    .ifPresent(
                                            r -> {
                                                JobStatus from = r.getStatus();
                                                r.finished(
                                                        JobStatus.FAILED,
                                                        r.getReadCount(),
                                                        r.getWriteCount(),
                                                        r.getSkipCount(),
                                                        r.getResultSummary(),
                                                        refused.getClass().getSimpleName()
                                                                + ": "
                                                                + refused.getMessage(),
                                                        clock.instant());
                                                requests.save(r);
                                                journal.record(
                                                        requestId,
                                                        JobJournal.Event.FAILED,
                                                        instance.id(),
                                                        from,
                                                        JobStatus.FAILED,
                                                        r.getJobExecutionId(),
                                                        "Launch refused: "
                                                                + refused.getClass()
                                                                        .getSimpleName());
                                            }));
            locks.release(requestId);
            running.remove(requestId);
        }
    }

    private boolean runningElsewhere(String jobName, String paramHash) {
        return explorer.findRunningJobExecutions(jobName).stream()
                .anyMatch(
                        e ->
                                paramHash.equals(
                                        e.getJobParameters().getString(BatchKeys.PARAM_HASH)));
    }

    private void refuse(Long requestId, JobStatus previous, String why) {
        tx.executeWithoutResult(
                s ->
                        requests.findById(requestId)
                                .ifPresent(
                                        r -> {
                                            r.unpick(previous);
                                            requests.save(r);
                                            journal.record(
                                                    requestId,
                                                    JobJournal.Event.LOCK_REFUSED,
                                                    instance.id(),
                                                    JobStatus.PICKED,
                                                    previous,
                                                    r.getJobExecutionId(),
                                                    why);
                                        }));
        log.warn("event=JOB_LAUNCH_REFUSED jobRequestId={} reason=\"{}\"", requestId, why);
    }

    /** The Spring Batch parameters of a request (also used by the job tests). */
    public static JobParameters parameters(BatchJobRequest request) {
        JobParametersBuilder builder =
                new JobParametersBuilder()
                        .addLong(BatchKeys.REQUEST_ID, request.getId())
                        .addString(BatchKeys.PARAM_HASH, request.getParamHash(), false);
        for (Map.Entry<String, String> entry :
                JobParameterCodec.fromJson(request.getParams()).entrySet()) {
            builder.addString(entry.getKey(), entry.getValue(), false);
        }
        return builder.toJobParameters();
    }
}
