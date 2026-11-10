package in.gov.fci.payroll.jobs.batch.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import in.gov.fci.payroll.jobs.batch.support.BatchKeys;
import in.gov.fci.payroll.jobs.batch.support.ItemKey;
import in.gov.fci.payroll.jobs.orchestration.JobJournal;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.scope.context.StepSynchronizationManager;
import org.springframework.batch.core.step.StepExecution;

/**
 * SRS-EH-7: every skipped record is written to the failure log with its step and phase and counted
 * per step (a partition counted under its step); a skip reported outside any step (no step context
 * on the thread) has nowhere to be recorded and is left alone.
 */
@ResourceLock("thread-context")
class SkipRecordingListenerTest {

    private final JobJournal journal = mock(JobJournal.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final SkipRecordingListener listener = new SkipRecordingListener(journal, meters);

    @Test
    void SRS_EH_7_aSkipOutsideAnyStepIsNotRecorded() {
        listener.onSkipInRead(new IllegalStateException("no step"));
        verifyNoInteractions(journal);
        assertThat(meters.getMeters()).isEmpty();
    }

    @Test
    void SRS_EH_7_aSkipInAPartitionIsRecordedUnderItsStep() {
        JobExecution job =
                new JobExecution(
                        40L,
                        new JobInstance(3L, "payrollComputation"),
                        new JobParametersBuilder()
                                .addLong(BatchKeys.REQUEST_ID, 7L)
                                .toJobParameters());
        StepExecution step = new StepExecution(5L, "compute:partition2", job);
        IllegalStateException error = new IllegalStateException("bad row");
        StepSynchronizationManager.register(step);
        try {
            listener.onSkipInRead(error);
        } finally {
            StepSynchronizationManager.close();
        }
        verify(journal).skipped(7L, 40L, "compute:partition2", "READ", null, error);
        assertThat(meters.counter("fci.batch.skips", "step", "compute", "phase", "READ").count())
                .isEqualTo(1.0);
    }

    private StepExecution step(String name) {
        JobExecution job =
                new JobExecution(
                        41L,
                        new JobInstance(4L, "payrollComputation"),
                        new JobParametersBuilder()
                                .addLong(BatchKeys.REQUEST_ID, 8L)
                                .toJobParameters());
        return new StepExecution(6L, name, job);
    }

    private record Row(String itemKey) implements ItemKey {}

    @Test
    void SRS_EH_7_aSkipInTheWriterIsRecordedWithTheItemsKeyAndCountedUnderWrite() {
        IllegalStateException error = new IllegalStateException("constraint");
        StepSynchronizationManager.register(step("compute"));
        try {
            listener.onSkipInWrite(new Row("EMP-1001"), error);
        } finally {
            StepSynchronizationManager.close();
        }
        verify(journal).skipped(8L, 41L, "compute", "WRITE", "EMP-1001", error);
        assertThat(meters.counter("fci.batch.skips", "step", "compute", "phase", "WRITE").count())
                .isEqualTo(1.0);
    }

    @Test
    void SRS_EH_7_aSkipInTheProcessorNamesAnItemWithoutAKeyByItsTypeNeverItsContent() {
        IllegalStateException error = new IllegalStateException("bad");
        StepSynchronizationManager.register(step("compute"));
        try {
            listener.onSkipInProcess("salary 45000 of Rajesh", error);
            listener.onSkipInProcess(null, error);
        } finally {
            StepSynchronizationManager.close();
        }
        verify(journal).skipped(8L, 41L, "compute", "PROCESS", "String", error);
        verify(journal).skipped(8L, 41L, "compute", "PROCESS", null, error);
    }

    @Test
    void SRS_EH_7_everyRepeatedSkipIsCountedAndOnlyTheTrailingPartitionSuffixIsStripped() {
        StepSynchronizationManager.register(step("compute:partition12"));
        try {
            listener.onSkipInRead(new IllegalStateException("one"));
            listener.onSkipInRead(new IllegalStateException("two"));
        } finally {
            StepSynchronizationManager.close();
        }
        assertThat(meters.counter("fci.batch.skips", "step", "compute", "phase", "READ").count())
                .isEqualTo(2.0);
    }

    @Test
    void SRS_EH_7_aWriteOrProcessSkipOutsideAnyStepIsNotRecordedEither() {
        listener.onSkipInWrite(new Row("EMP-1001"), new IllegalStateException("x"));
        listener.onSkipInProcess(new Row("EMP-1001"), new IllegalStateException("x"));
        verifyNoInteractions(journal);
        assertThat(meters.getMeters()).isEmpty();
    }
}
