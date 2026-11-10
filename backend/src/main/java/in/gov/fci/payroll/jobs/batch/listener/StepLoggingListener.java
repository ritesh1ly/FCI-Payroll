package in.gov.fci.payroll.jobs.batch.listener;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.listener.StepExecutionListener;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * One structured line at the start and end of every step (and every partition), with its counts. A
 * step that skipped records ends with the exit code COMPLETED_WITH_SKIPS, visible on C-13.
 */
@Component
@Profile("batch")
public class StepLoggingListener implements StepExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(StepLoggingListener.class);

    public static final String COMPLETED_WITH_SKIPS = "COMPLETED_WITH_SKIPS";

    @Override
    public void beforeStep(StepExecution step) {
        log.info(
                "event=STEP_STARTED step={} jobExecutionId={}",
                step.getStepName(),
                step.getJobExecutionId());
    }

    @Override
    public ExitStatus afterStep(StepExecution step) {
        log.info(
                "event=STEP_FINISHED step={} status={} read={} written={} filtered={} skipped={}"
                        + " commits={} rollbacks={}",
                step.getStepName(),
                step.getStatus(),
                step.getReadCount(),
                step.getWriteCount(),
                step.getFilterCount(),
                step.getSkipCount(),
                step.getCommitCount(),
                step.getRollbackCount());
        if (step.getExitStatus().getExitCode().equals(ExitStatus.COMPLETED.getExitCode())
                && step.getSkipCount() > 0) {
            return new ExitStatus(
                    COMPLETED_WITH_SKIPS,
                    step.getSkipCount() + " record(s) skipped, see the failure log");
        }
        return step.getExitStatus();
    }
}
