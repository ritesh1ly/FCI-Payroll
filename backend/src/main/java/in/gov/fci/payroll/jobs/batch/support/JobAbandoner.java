package in.gov.fci.payroll.jobs.batch.support;

import in.gov.fci.payroll.jobs.api.AbandonListener;
import in.gov.fci.payroll.jobs.business.model.BatchJobRequest;
import in.gov.fci.payroll.jobs.business.model.JobStatus;
import in.gov.fci.payroll.jobs.orchestration.JobJournal;
import in.gov.fci.payroll.jobs.persistence.JobRepositories;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.launch.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Ends a job for good (plan 05j steps j2 / j3; SRS-15-27): the request ABANDONED (final), its
 * Spring Batch execution ABANDONED (so Spring Batch itself refuses a restart of it), and every
 * {@link AbandonListener} told (the payroll module marks the computation run ABANDONED, its results
 * staying QUARANTINED). Nothing is deleted.
 *
 * <p>As in {@link JobStopper}, the Spring Batch call runs outside the caller's transaction: Spring
 * Batch 6's operator is transactional, and a refusal inside the caller's transaction would mark it
 * rollback-only. An execution that is running after all is not abandoned: the caller is told
 * (false) and changes nothing.
 */
@Component
@Profile("batch")
public class JobAbandoner {

    private static final Logger log = LoggerFactory.getLogger(JobAbandoner.class);

    private final JobOperator operator;
    private final BatchHistory history;
    private final JobRepositories.JobRequests requests;
    private final JobJournal journal;
    private final List<AbandonListener> listeners;
    private final TransactionTemplate outside;
    private final Clock clock;

    public JobAbandoner(
            JobOperator operator,
            BatchHistory history,
            JobRepositories.JobRequests requests,
            JobJournal journal,
            List<AbandonListener> listeners,
            PlatformTransactionManager transactions,
            Clock clock) {
        this.operator = operator;
        this.history = history;
        this.requests = requests;
        this.journal = journal;
        this.listeners = List.copyOf(listeners);
        this.outside = new TransactionTemplate(transactions);
        this.outside.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        this.clock = clock;
    }

    /**
     * Abandons {@code request} (FAILED, STOPPED or PAUSED) in the caller's transaction. False, with
     * nothing changed, if its execution turns out to be running.
     *
     * @param actor who abandons it (the approving checker, or {@code system.housekeeping})
     * @param detail the journal line (the approval, or the restart window passed)
     */
    public boolean abandon(BatchJobRequest request, String actor, String detail) {
        if (!abandonExecution(request.getJobExecutionId())) {
            return false;
        }
        JobStatus from = request.getStatus();
        request.abandon(clock.instant());
        requests.save(request);
        listeners.forEach(l -> l.abandoned(request.getId(), request.getJobType().name()));
        journal.record(
                request.getId(),
                JobJournal.Event.ABANDONED,
                actor,
                from,
                JobStatus.ABANDONED,
                request.getJobExecutionId(),
                detail);
        log.warn("event=JOB_ABANDONED jobRequestId={} from={} by={}", request.getId(), from, actor);
        return true;
    }

    /** True when the execution is abandoned now, already was, or never existed (nothing ran). */
    private boolean abandonExecution(Long executionId) {
        if (executionId == null) {
            return true;
        }
        return Boolean.TRUE.equals(
                outside.execute(
                        status -> {
                            JobExecution execution = history.getJobExecution(executionId);
                            if (execution == null
                                    || execution.getStatus() == BatchStatus.ABANDONED) {
                                return true;
                            }
                            if (execution.isRunning()) {
                                return false;
                            }
                            try {
                                operator.abandon(execution);
                                return true;
                            } catch (JobExecutionAlreadyRunningException running) {
                                return false;
                            }
                        }));
    }
}
