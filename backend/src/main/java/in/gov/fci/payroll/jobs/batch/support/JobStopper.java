package in.gov.fci.payroll.jobs.batch.support;

import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.launch.JobExecutionNotRunningException;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Signals a running job execution to stop after its current chunk (STOP / PAUSE, node shutdown).
 *
 * <p>Spring Batch 6's {@link JobOperator} is transactional: a refusal ("not running") passing its
 * proxy inside the caller's transaction marks that transaction rollback-only, even when caught, and
 * the caller's other writes (the command's rejection, the cleared intent) would be lost. The stop
 * therefore runs outside the caller's transaction (Spring Batch commits the STOPPING status on its
 * own), and "unknown or no longer running" - including an execution that ends between the check and
 * the stop - is a plain {@code false}.
 */
@Component
@Profile("batch")
public class JobStopper {

    private final JobOperator operator;
    private final BatchHistory history;
    private final TransactionTemplate outside;

    public JobStopper(
            JobOperator operator, BatchHistory history, PlatformTransactionManager transactions) {
        this.operator = operator;
        this.history = history;
        this.outside = new TransactionTemplate(transactions);
        this.outside.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
    }

    /** True if the stop was signalled; false if there is no such execution or it is not running. */
    public boolean stop(Long executionId) {
        if (executionId == null) {
            return false;
        }
        return Boolean.TRUE.equals(
                outside.execute(
                        status -> {
                            JobExecution execution = history.getJobExecution(executionId);
                            if (execution == null || !execution.isRunning()) {
                                return false;
                            }
                            try {
                                return operator.stop(execution);
                            } catch (JobExecutionNotRunningException endedMeanwhile) {
                                return false;
                            }
                        }));
    }
}
