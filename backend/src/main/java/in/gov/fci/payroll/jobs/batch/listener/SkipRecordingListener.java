package in.gov.fci.payroll.jobs.batch.listener;

import in.gov.fci.payroll.jobs.batch.support.BatchKeys;
import in.gov.fci.payroll.jobs.batch.support.ItemKey;
import in.gov.fci.payroll.jobs.orchestration.JobJournal;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.batch.core.listener.SkipListener;
import org.springframework.batch.core.scope.context.StepContext;
import org.springframework.batch.core.scope.context.StepSynchronizationManager;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Writes every skipped record to the failure log ({@code batch_skipped_item}: request, execution,
 * step, phase, item key, error) so nothing is skipped silently. The step context is taken from the
 * current thread, which makes one shared listener safe for concurrent partitions.
 */
@Component
@Profile("batch")
public class SkipRecordingListener implements SkipListener<Object, Object> {

    private final JobJournal journal;
    private final MeterRegistry meters;

    public SkipRecordingListener(JobJournal journal, MeterRegistry meters) {
        this.journal = journal;
        this.meters = meters;
    }

    @Override
    public void onSkipInRead(Throwable t) {
        record("READ", null, t);
    }

    @Override
    public void onSkipInProcess(Object item, Throwable t) {
        record("PROCESS", ItemKey.of(item), t);
    }

    @Override
    public void onSkipInWrite(Object item, Throwable t) {
        record("WRITE", ItemKey.of(item), t);
    }

    private void record(String phase, String key, Throwable error) {
        StepContext context = StepSynchronizationManager.getContext();
        if (context == null) {
            return;
        }
        StepExecution step = context.getStepExecution();
        Long requestId = step.getJobParameters().getLong(BatchKeys.REQUEST_ID);
        journal.skipped(requestId, step.getJobExecutionId(), step.getStepName(), phase, key, error);
        meters.counter(
                        "fci.batch.skips",
                        "step",
                        step.getStepName().replaceAll(":partition\\d+$", ""),
                        "phase",
                        phase)
                .increment();
    }
}
