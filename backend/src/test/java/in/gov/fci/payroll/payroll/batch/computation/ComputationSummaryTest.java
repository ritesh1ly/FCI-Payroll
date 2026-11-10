package in.gov.fci.payroll.payroll.batch.computation;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.jobs.batch.support.BatchKeys;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.step.StepExecution;

/**
 * SRS-NF-2 (C-13 shows the request's outcome): the computation step's one-line summary names the
 * run when the job opened one and counts what it read, paid, filtered and quarantined.
 */
class ComputationSummaryTest {

    private static StepExecution step() {
        JobExecution job =
                new JobExecution(
                        40L, new JobInstance(3L, "payrollComputation"), new JobParameters());
        return new StepExecution(1L, ComputationJobConfig.MANAGER, job);
    }

    @Test
    void SRS_NF_2_theSummaryNamesTheRunWhenThereIsOne() {
        StepExecution withRun = step();
        withRun.getJobExecution().getExecutionContext().putLong(ComputationKeys.RUN_ID, 11L);
        withRun.getJobExecution()
                .getExecutionContext()
                .putString(ComputationKeys.RUN_TYPE, "ACTUAL");
        ComputationJobConfig.summary().afterStep(withRun);
        assertThat(withRun.getExecutionContext().getString(BatchKeys.SUMMARY))
                .startsWith("ACTUAL run 11: members read 0");

        StepExecution none = step();
        ComputationJobConfig.summary().afterStep(none);
        assertThat(none.getExecutionContext().getString(BatchKeys.SUMMARY))
                .startsWith("members read 0, results 0");
    }
}
