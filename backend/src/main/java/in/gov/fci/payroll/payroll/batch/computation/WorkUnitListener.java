package in.gov.fci.payroll.payroll.batch.computation;

import in.gov.fci.payroll.jobs.batch.support.BatchKeys;
import in.gov.fci.payroll.payroll.batch.cycle.CycleRuns;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.listener.StepExecutionListener;
import org.springframework.batch.core.step.StepExecution;

/**
 * One {@code computation_work_unit} per worker partition: CLAIMED when the partition starts (again,
 * with one more attempt, on a restart), COMMITTED or FAILED when it ends, with the results it
 * wrote. Deviation from v4.0 (documented): Spring Batch commits a partition chunk by chunk, so
 * "COMMITTED" is recorded at the end of the partition, not in the transaction of every result row;
 * a restart re-runs only the chunks not yet committed, so no member gets two results.
 */
public class WorkUnitListener implements StepExecutionListener {

    private final CycleRuns cycles;

    public WorkUnitListener(CycleRuns cycles) {
        this.cycles = cycles;
    }

    @Override
    public void beforeStep(StepExecution step) {
        if (!step.getJobExecution().getExecutionContext().containsKey(ComputationKeys.RUN_ID)) {
            return;
        }
        CycleRuns.OpenRun run = ComputationKeys.get(step.getJobExecution().getExecutionContext());
        Long existing =
                step.getExecutionContext().containsKey(ComputationKeys.WORK_UNIT_ID)
                        ? step.getExecutionContext().getLong(ComputationKeys.WORK_UNIT_ID)
                        : null;
        String name = step.getStepName();
        int unitNo =
                name.contains(":partition")
                        ? Integer.parseInt(name.substring(name.lastIndexOf("partition") + 9)) + 1
                        : 1;
        int planned =
                step.getExecutionContext().containsKey(BatchKeys.PLANNED)
                        ? (int) step.getExecutionContext().getLong(BatchKeys.PLANNED)
                        : 0;
        long id =
                cycles.claimUnit(
                        run.runId(),
                        unitNo,
                        run.orgUnitId(),
                        planned,
                        Thread.currentThread().getName(),
                        existing);
        step.getExecutionContext().putLong(ComputationKeys.WORK_UNIT_ID, id);
    }

    @Override
    public ExitStatus afterStep(StepExecution step) {
        if (step.getExecutionContext().containsKey(ComputationKeys.WORK_UNIT_ID)) {
            boolean ok =
                    step.getStatus() != org.springframework.batch.core.BatchStatus.FAILED
                            && step.getFailureExceptions().isEmpty();
            String failure =
                    step.getFailureExceptions().isEmpty()
                            ? null
                            : String.valueOf(step.getFailureExceptions().get(0).getMessage());
            cycles.finishUnit(
                    step.getExecutionContext().getLong(ComputationKeys.WORK_UNIT_ID),
                    ok,
                    step.getWriteCount(),
                    failure);
        }
        return step.getExitStatus();
    }
}
