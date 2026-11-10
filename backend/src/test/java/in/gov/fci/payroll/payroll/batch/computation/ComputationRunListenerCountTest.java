package in.gov.fci.payroll.payroll.batch.computation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.configuration.api.PayConfiguration;
import in.gov.fci.payroll.jobs.batch.support.BatchKeys;
import in.gov.fci.payroll.payroll.api.StaffDeductionRunner;
import in.gov.fci.payroll.payroll.batch.cycle.CycleRuns;
import in.gov.fci.payroll.payroll.orchestration.ComputationResumeCheck;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.StepExecution;

/**
 * SRS-15-15 (G0) / BR-CYC-012 (BMAD TR-070): the computation run is closed with the members of
 * committed chunks only - written plus skipped on read, process or write, summed over the
 * partitions; a member filtered out (salary stopped) is no part of it, and a step that is no
 * partition is not counted. A relaunch that continues the run marks itself resumed and retakes the
 * cycle's lease; a refused relaunch leaves the run as the failure left it. Counts are fixtures.
 */
class ComputationRunListenerCountTest {

    private final CycleRuns cycles = mock(CycleRuns.class);
    private final ComputationResumeCheck resume = mock(ComputationResumeCheck.class);
    private final ComputationRunListener listener =
            new ComputationRunListener(
                    cycles,
                    mock(PayConfiguration.class),
                    resume,
                    mock(StaffDeductionRunner.class),
                    "payrollComputation",
                    CycleRuns.RunType.ACTUAL,
                    mock(JobRepository.class));

    private static final CycleRuns.OpenRun RUN =
            new CycleRuns.OpenRun(
                    31L, 9L, 40L, "202610", LocalDate.of(2026, 10, 1), CycleRuns.RunType.ACTUAL);

    private static JobExecution execution() {
        JobExecution job =
                new JobExecution(
                        50L,
                        new JobInstance(5L, "payrollComputation"),
                        new JobParametersBuilder()
                                .addLong(BatchKeys.REQUEST_ID, 77L)
                                .toJobParameters());
        ComputationKeys.put(job.getExecutionContext(), RUN);
        return job;
    }

    private static StepExecution step(
            JobExecution job,
            long id,
            String name,
            long write,
            long readSkip,
            long processSkip,
            long writeSkip,
            long filter) {
        StepExecution s = new StepExecution(id, name, job);
        s.setWriteCount(write);
        s.setReadSkipCount(readSkip);
        s.setProcessSkipCount(processSkip);
        s.setWriteSkipCount(writeSkip);
        s.setFilterCount(filter);
        s.setReadCount(write + readSkip + processSkip + writeSkip + filter + 40);
        return s;
    }

    @Test
    void SRS_15_15_theEligibleCountIsTheCommittedMembersOfEveryPartition() {
        JobExecution job = execution();
        job.addStepExecutions(
                List.of(
                        step(job, 1L, "compute:partition0", 3, 1, 2, 4, 5),
                        step(job, 2L, "compute:partition1", 10, 0, 0, 0, 0),
                        step(job, 3L, "computeManager", 100, 100, 100, 100, 100)));
        job.setStatus(BatchStatus.COMPLETED);
        listener.afterJob(job);
        verify(cycles).close(RUN, true, 20L, false);
    }

    @Test
    void BR_CYC_012_aResumedRunClosesAsResumedAndAFailedOneAsNotCompleted() {
        JobExecution job = execution();
        when(resume.refusal(anyLong(), anyString(), any())).thenReturn(Optional.empty());
        listener.beforeJob(job);
        assertThat(job.getExecutionContext().getString(ComputationRunListener.RESUMED))
                .isEqualTo("Y");
        verify(cycles).retakeLease(RUN);
        job.addStepExecutions(List.of(step(job, 1L, "compute:partition0", 2, 0, 0, 1, 0)));
        job.setStatus(BatchStatus.FAILED);
        listener.afterJob(job);
        verify(cycles).close(RUN, false, 3L, true);
    }

    @Test
    void BR_CYC_012_aRunNotOpenedOrARefusedRelaunchIsNotClosed() {
        JobExecution none =
                new JobExecution(
                        51L,
                        new JobInstance(6L, "payrollComputation"),
                        new JobParametersBuilder()
                                .addLong(BatchKeys.REQUEST_ID, 78L)
                                .toJobParameters());
        listener.afterJob(none);
        JobExecution refused = execution();
        refused.getExecutionContext().putString(ComputationRunListener.RESUME_REFUSED, "X");
        listener.afterJob(refused);
        verify(cycles, never()).close(any(), anyBoolean(), anyLong(), anyBoolean());
    }
}
