package in.gov.fci.payroll.jobs.batch.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import in.gov.fci.payroll.jobs.batch.support.PartitionedSteps.Counts;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.item.ExecutionContext;

/**
 * The count rule of a job (SRS-NF-2, C-13): the steps that process items count, a partitioned
 * step's manager never does, so the counts are right while the workers run and are not doubled once
 * the manager has summed them. The manager's mark is saved as it starts and, on a restart, leaves
 * the restored context clean.
 */
class PartitionedStepsTest {

    private final JobExecution job =
            new JobExecution(7L, new JobInstance(3L, "payrollDryRunJob"), new JobParameters());

    private StepExecution step(long id, String name, long read, long written, long skipped) {
        StepExecution step = new StepExecution(id, name, job);
        step.setReadCount(read);
        step.setWriteCount(written);
        step.setWriteSkipCount(skipped);
        return step;
    }

    private StepExecution marked(StepExecution step) {
        PartitionedSteps.marker(mock(JobRepository.class)).beforeStep(step);
        return step;
    }

    @Test
    void SRS_NF_2_theWorkersAndPlainStepsCountAndAManagerNever() {
        StepExecution running = marked(step(1L, "payrollComputationStep", 0, 0, 0));
        StepExecution ended = marked(step(2L, "payrollComputationStep", 30, 28, 2));
        List<StepExecution> workers =
                List.of(
                        step(3L, "payrollComputationWorker:partition0", 20, 19, 1),
                        step(4L, "payrollComputationWorker:partition1", 10, 9, 1));
        StepExecution plain = step(5L, "monthEndReadinessStep", 4, 4, 0);

        assertThat(PartitionedSteps.counts(List.of(running, workers.get(0), workers.get(1), plain)))
                .as("while the workers run")
                .isEqualTo(new Counts(34, 32, 2));
        assertThat(PartitionedSteps.counts(List.of(ended, workers.get(0), workers.get(1), plain)))
                .as("after the manager summed them")
                .isEqualTo(new Counts(34, 32, 2));
        assertThat(PartitionedSteps.counts(List.of())).isEqualTo(new Counts(0, 0, 0));
    }

    @Test
    void SRS_NF_2_aWorkerIsKnownByTheNameSpringBatchGivesItAndAManagerByItsMark() {
        assertThat(PartitionedSteps.isWorker("payrollComputationWorker:partition0")).isTrue();
        assertThat(PartitionedSteps.isWorker("payrollComputationStep")).isFalse();
        StepExecution manager = step(1L, "payrollComputationStep", 0, 0, 0);
        assertThat(PartitionedSteps.isManager(manager)).as("before it starts").isFalse();
        assertThat(PartitionedSteps.isManager(marked(manager))).isTrue();
        assertThat(PartitionedSteps.isManager(step(2L, "daArrearStep", 1, 1, 0))).isFalse();
    }

    @Test
    void SRS_NF_2_theMarkIsSavedAsTheManagerStartsAndARestartLeavesTheContextClean() {
        JobRepository repository = mock(JobRepository.class);
        StepExecution first = step(1L, "payrollComputationStep", 0, 0, 0);
        PartitionedSteps.marker(repository).beforeStep(first);
        verify(repository).updateExecutionContext(first);
        assertThat(first.getExecutionContext().getString(BatchKeys.PARTITION_MANAGER))
                .isEqualTo("payrollComputationStep");

        // A restart restores the context of the failed attempt, its mark included: marking it
        // again changes nothing, so Spring Batch still sees a clean context and reuses the
        // partitions of that attempt.
        ExecutionContext restored =
                new ExecutionContext(Map.of(BatchKeys.PARTITION_MANAGER, "payrollComputationStep"));
        StepExecution restart = step(2L, "payrollComputationStep", 0, 0, 0);
        restart.setExecutionContext(restored);
        PartitionedSteps.marker(repository).beforeStep(restart);
        assertThat(restored.isDirty()).isFalse();
        verify(repository).updateExecutionContext(restart);
    }
}
