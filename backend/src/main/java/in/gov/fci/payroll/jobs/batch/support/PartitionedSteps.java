package in.gov.fci.payroll.jobs.batch.support;

import java.util.Collection;
import org.springframework.batch.core.listener.StepExecutionListener;
import org.springframework.batch.core.partition.Partitioner;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.core.step.builder.PartitionStepBuilder;
import org.springframework.batch.core.step.builder.StepBuilder;

/**
 * The one rule for what a job has read, written and skipped, live while it runs and final when it
 * ends (C-13, SRS-NF-2). Spring Batch runs a partitioned step as a manager and workers named {@code
 * <worker>:<partition>}, and adds the workers' counts into the manager only when the whole step
 * ends: until then the manager shows nothing. A job's counts are therefore those of the steps that
 * process items themselves, the workers and the plain steps, never a manager's. They are right at
 * every moment and never counted twice.
 *
 * <p>The manager is told apart by a mark it saves in the shared job repository as it starts, so an
 * online instance reading the repository sees it while the step runs. Build every partitioned step
 * with {@link #manager}, which installs the mark.
 */
public final class PartitionedSteps {

    /** What a job's steps have read, written and skipped. */
    public record Counts(long read, long written, long skipped) {}

    private PartitionedSteps() {}

    /**
     * Spring Batch's builder of a partitioned step, its manager already marked: add the partition
     * handler and the listeners, then build.
     */
    public static PartitionStepBuilder manager(
            String name, JobRepository repository, String workerName, Partitioner partitioner) {
        return new StepBuilder(name, repository)
                .partitioner(workerName, partitioner)
                .listener(marker(repository));
    }

    /** A worker of a partitioned step, by the name Spring Batch gives it. */
    public static boolean isWorker(String stepName) {
        return stepName.contains(":");
    }

    /** The manager of a partitioned step built by {@link #manager}. */
    public static boolean isManager(StepExecution step) {
        return step.getExecutionContext().containsKey(BatchKeys.PARTITION_MANAGER);
    }

    /** The counts of a job's steps: every step that processes items itself, no manager. */
    public static Counts counts(Collection<StepExecution> steps) {
        long read = 0;
        long written = 0;
        long skipped = 0;
        for (StepExecution step : steps) {
            if (!isManager(step)) {
                read += step.getReadCount();
                written += step.getWriteCount();
                skipped += step.getSkipCount();
            }
        }
        return new Counts(read, written, skipped);
    }

    /**
     * Marks the manager as it starts and saves the mark at once, before the step splits. The value
     * is the step's own name; an unchanged value on a restart leaves the context clean, so Spring
     * Batch's restart of the partitions is unaffected.
     */
    static StepExecutionListener marker(JobRepository repository) {
        return new StepExecutionListener() {
            @Override
            public void beforeStep(StepExecution step) {
                step.getExecutionContext()
                        .putString(BatchKeys.PARTITION_MANAGER, step.getStepName());
                repository.updateExecutionContext(step);
            }
        };
    }
}
