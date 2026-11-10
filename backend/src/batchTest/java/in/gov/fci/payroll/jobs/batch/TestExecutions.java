package in.gov.fci.payroll.jobs.batch;

import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.infrastructure.item.ExecutionContext;

/**
 * Job executions in the shared repository, as another node's launch would leave them (Spring Batch
 * 6: the instance first, then its execution). A class of its own: {@code @SpringBatchTest} calls
 * every method of a test class that returns a {@link JobExecution} to set up the job scope.
 */
public final class TestExecutions {

    private final JobRepository repository;

    TestExecutions(JobRepository repository) {
        this.repository = repository;
    }

    public JobExecution create(String jobName, JobParameters parameters) {
        JobInstance instance = repository.getJobInstance(jobName, parameters);
        if (instance == null) {
            instance = repository.createJobInstance(jobName, parameters);
        }
        return repository.createJobExecution(instance, parameters, new ExecutionContext());
    }
}
