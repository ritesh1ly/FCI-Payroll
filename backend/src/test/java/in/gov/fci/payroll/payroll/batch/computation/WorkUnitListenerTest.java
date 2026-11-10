package in.gov.fci.payroll.payroll.batch.computation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.jobs.batch.support.BatchKeys;
import in.gov.fci.payroll.payroll.batch.cycle.CycleRuns;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.step.StepExecution;

/**
 * The work units of a computation run: the manager step is unit 1 and a partition its number plus
 * one, a restarted partition claims its own unit again with its planned count, a step outside a run
 * (a launch without a run record) claims nothing, and a unit is committed only when its step
 * neither failed nor recorded a failure.
 */
class WorkUnitListenerTest {

    private static final CycleRuns.OpenRun RUN =
            new CycleRuns.OpenRun(
                    11L, 5L, 300L, "202610", LocalDate.of(2026, 10, 1), CycleRuns.RunType.ACTUAL);

    private final CycleRuns cycles = mock(CycleRuns.class);
    private final WorkUnitListener listener = new WorkUnitListener(cycles);

    private static StepExecution step(String name, boolean inRun) {
        JobExecution job =
                new JobExecution(
                        40L, new JobInstance(3L, "payrollComputation"), new JobParameters());
        if (inRun) {
            ComputationKeys.put(job.getExecutionContext(), RUN);
        }
        return new StepExecution(1L, name, job);
    }

    @Test
    void aStepOutsideAComputationRunClaimsAndFinishesNothing() {
        StepExecution step = step("payrollComputationStep", false);
        listener.beforeStep(step);
        assertThat(listener.afterStep(step)).isEqualTo(step.getExitStatus());
        verifyNoInteractions(cycles);
        assertThat(step.getExecutionContext().containsKey(ComputationKeys.WORK_UNIT_ID)).isFalse();
    }

    @Test
    void theManagerStepIsUnitOneAndAPartitionItsNumberPlusOne() {
        when(cycles.claimUnit(eq(11L), eq(1), eq(300L), eq(0), anyString(), isNull()))
                .thenReturn(77L);
        StepExecution manager = step("payrollComputationStep", true);
        listener.beforeStep(manager);
        assertThat(manager.getExecutionContext().getLong(ComputationKeys.WORK_UNIT_ID))
                .isEqualTo(77L);

        // A restarted partition claims its own unit again, with the count planned for it.
        when(cycles.claimUnit(eq(11L), eq(4), eq(300L), eq(250), anyString(), eq(70L)))
                .thenReturn(70L);
        StepExecution partition = step("payrollComputationWorker:partition3", true);
        partition.getExecutionContext().putLong(BatchKeys.PLANNED, 250L);
        partition.getExecutionContext().putLong(ComputationKeys.WORK_UNIT_ID, 70L);
        listener.beforeStep(partition);
        assertThat(partition.getExecutionContext().getLong(ComputationKeys.WORK_UNIT_ID))
                .isEqualTo(70L);
    }

    @Test
    void afterTheStepTheUnitIsCommittedOrFailedWithItsFirstFailure() {
        StepExecution committed = step("payrollComputationStep", true);
        committed.getExecutionContext().putLong(ComputationKeys.WORK_UNIT_ID, 77L);
        committed.setStatus(BatchStatus.COMPLETED);
        committed.setWriteCount(250);
        committed.setExitStatus(ExitStatus.COMPLETED);
        assertThat(listener.afterStep(committed)).isEqualTo(ExitStatus.COMPLETED);
        verify(cycles).finishUnit(77L, true, 250, null);

        StepExecution failed = step("payrollComputationWorker:partition0", true);
        failed.getExecutionContext().putLong(ComputationKeys.WORK_UNIT_ID, 78L);
        failed.setStatus(BatchStatus.FAILED);
        failed.addFailureException(new IllegalStateException("retry exhausted"));
        failed.addFailureException(new IllegalStateException("later"));
        listener.afterStep(failed);
        verify(cycles).finishUnit(78L, false, 0, "retry exhausted");

        // A failure recorded on a step that still completed (after its last commit) is no
        // committed unit either: the restart re-runs it rather than trusting it.
        StepExecution tainted = step("payrollComputationStep", true);
        tainted.getExecutionContext().putLong(ComputationKeys.WORK_UNIT_ID, 79L);
        tainted.setStatus(BatchStatus.COMPLETED);
        tainted.addFailureException(new IllegalStateException("after commit"));
        listener.afterStep(tainted);
        verify(cycles).finishUnit(79L, false, 0, "after commit");
    }
}
