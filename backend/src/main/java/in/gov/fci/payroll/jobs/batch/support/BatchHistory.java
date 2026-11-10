package in.gov.fci.payroll.jobs.batch.support;

import java.util.List;
import java.util.Set;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Read-only view of Spring Batch's job repository (the shared BATCH_* tables): the executions,
 * steps and counts that C-13 shows and that launch and crash recovery check. It replaces Spring
 * Batch's {@code JobExplorer} (deprecated for removal in Spring Batch 6) and exposes reads only, so
 * the online instance can show progress without any bean able to start or change a job.
 */
public interface BatchHistory {

    /** The execution with this id, or null. */
    JobExecution getJobExecution(long executionId);

    /** Every execution (attempt) of a job instance. */
    List<JobExecution> getJobExecutions(JobInstance instance);

    /** The executions of a job that are running now (on any node: the repository is shared). */
    Set<JobExecution> findRunningJobExecutions(String jobName);

    /**
     * The reads of a repository (on the batch instance, its own {@link JobRepository} bean).
     *
     * <p>Spring Batch 6's repository throws for an unknown execution id where JobExplorer returned
     * null, and the exception passes its transactional proxy: inside a caller's transaction it
     * would mark that transaction rollback-only even when caught (a STOP of an execution that has
     * ended would silently lose its other writes). The lookup therefore runs outside the caller's
     * transaction, and "no such execution" is null, as callers (stop, crash recovery, C-13) expect.
     */
    static BatchHistory of(JobRepository repository, PlatformTransactionManager transactions) {
        TransactionTemplate outside = new TransactionTemplate(transactions);
        outside.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        outside.setReadOnly(true);
        return new BatchHistory() {
            @Override
            public JobExecution getJobExecution(long executionId) {
                return outside.execute(
                        status -> {
                            try {
                                return repository.getJobExecution(executionId);
                            } catch (EmptyResultDataAccessException unknown) {
                                return null;
                            }
                        });
            }

            @Override
            public List<JobExecution> getJobExecutions(JobInstance instance) {
                return repository.getJobExecutions(instance);
            }

            @Override
            public Set<JobExecution> findRunningJobExecutions(String jobName) {
                return repository.findRunningJobExecutions(jobName);
            }
        };
    }
}
