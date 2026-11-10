package in.gov.fci.payroll.payroll.batch.computation;

import in.gov.fci.payroll.configuration.api.PayConfiguration;
import in.gov.fci.payroll.jobs.batch.support.BatchKeys;
import in.gov.fci.payroll.jobs.batch.support.RequestParametersValidator;
import in.gov.fci.payroll.payroll.batch.cycle.CycleRuns;
import in.gov.fci.payroll.payroll.orchestration.ComputationResumeCheck;
import java.time.YearMonth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.listener.JobExecutionListener;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.StepExecution;

/**
 * Opens the v4.0 computation run of a job execution (before) and closes it (after). A restart of
 * the same execution keeps its run: the run id is in the job's execution context. Registered after
 * the jobs module's lifecycle listener, so it closes the run before the request is marked finished.
 *
 * <p>SRS-15-26 / BR-CYC-013: before a relaunch continues the run, the parameters and the version
 * set it started with are compared with those of now ({@link ComputationResumeCheck}); after a
 * change the relaunch fails at once, before any member is read, naming the reason. The online
 * instance refused the command already; this covers a version activated in between and the
 * automatic restart after a crash.
 */
public class ComputationRunListener implements JobExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(ComputationRunListener.class);

    /** Set when a relaunch was refused: the run stays as the failure left it (counts, status). */
    static final String RESUME_REFUSED = "computation.resumeRefused";

    /** Set when this execution continues the run after a failure (its eligible count adds up). */
    static final String RESUMED = "computation.resumed";

    private final CycleRuns cycles;
    private final PayConfiguration configuration;
    private final in.gov.fci.payroll.payroll.api.StaffDeductionRunner deductions;
    private final ComputationResumeCheck resume;
    private final String jobName;
    private final CycleRuns.RunType runType;
    private final JobRepository repository;

    /** With the deduction sources, whose versions enter the run's hash (plan 16 phase 16e-2). */
    public ComputationRunListener(
            CycleRuns cycles,
            PayConfiguration configuration,
            ComputationResumeCheck resume,
            in.gov.fci.payroll.payroll.api.StaffDeductionRunner deductions,
            String jobName,
            CycleRuns.RunType runType,
            JobRepository repository) {
        this.cycles = cycles;
        this.configuration = configuration;
        this.resume = resume;
        this.deductions = deductions;
        this.jobName = jobName;
        this.runType = runType;
        this.repository = repository;
    }

    @Override
    public void beforeJob(JobExecution execution) {
        if (execution.getStatus() == BatchStatus.STOPPING) {
            return;
        }
        long requestId = RequestParametersValidator.requestId(execution.getJobParameters());
        // A restart carries the context of the execution before it: an earlier refusal is not
        // this relaunch's.
        execution.getExecutionContext().remove(RESUME_REFUSED);
        execution.getExecutionContext().remove(RESUMED);
        if (execution.getExecutionContext().containsKey(ComputationKeys.RUN_ID)) {
            requireResumable(execution, requestId);
            execution.getExecutionContext().putString(RESUMED, "Y");
            // SRS-15-28: the restarted run holds the instance's lease again.
            cycles.retakeLease(ComputationKeys.get(execution.getExecutionContext()));
            return;
        }
        long locationId =
                Long.parseLong(
                        RequestParametersValidator.required(
                                execution.getJobParameters(), "locationId"));
        YearMonth period =
                YearMonth.parse(
                        RequestParametersValidator.required(
                                execution.getJobParameters(), "payPeriod"));
        String paramHash = execution.getJobParameters().getString(BatchKeys.PARAM_HASH);
        PayConfiguration.Snapshot snapshot = configuration.staffConfiguration(period.atDay(1));
        String versions = deductions.open(period).versionSetHash(snapshot);
        CycleRuns.OpenRun run =
                cycles.open(
                        requestId,
                        locationId,
                        period,
                        runType,
                        paramHash == null ? "-" : paramHash,
                        versions);
        ComputationKeys.put(execution.getExecutionContext(), run);
        // SRS-15-26 / SRS-15-28 (batch campaign 2026-10-30, R3): saved at once, not only at the end
        // of the step. A node lost mid-step otherwise leaves a job context without the run, and the
        // restart opens a new run that its own dead run's lease refuses (CYCLE_LEASE_HELD) instead
        // of continuing after the committed chunks.
        repository.updateExecutionContext(execution);
        log.info(
                "event=COMPUTATION_RUN_OPENED runId={} cycleInstanceId={} runType={} period={}",
                run.runId(),
                run.cycleId(),
                runType,
                period);
    }

    /** Fails the relaunch (FAILED, the reason as its exit message) when the run may not go on. */
    private void requireResumable(JobExecution execution, long requestId) {
        String paramHash = execution.getJobParameters().getString(BatchKeys.PARAM_HASH);
        resume.refusal(requestId, jobName, paramHash)
                .ifPresent(
                        refusal -> {
                            log.warn(
                                    "event=COMPUTATION_RESUME_REFUSED requestId={} code={}",
                                    requestId,
                                    refusal.code());
                            execution
                                    .getExecutionContext()
                                    .putString(RESUME_REFUSED, refusal.code());
                            throw new IllegalStateException(
                                    refusal.code() + ": " + refusal.defaultDetail());
                        });
    }

    @Override
    public void afterJob(JobExecution execution) {
        if (!execution.getExecutionContext().containsKey(ComputationKeys.RUN_ID)
                || execution.getExecutionContext().containsKey(RESUME_REFUSED)) {
            return;
        }
        CycleRuns.OpenRun run = ComputationKeys.get(execution.getExecutionContext());
        long eligible = 0;
        for (StepExecution step : execution.getStepExecutions()) {
            if (step.getStepName().contains(":partition")) {
                // G0 (SRS-15-15): the payable population. A member filtered out (salary stopped)
                // is an explicit exclusion, not eligible; a member skipped for bad data stays
                // eligible, so the run is not reconciled until it is computed. Only the items of
                // committed chunks count: a chunk rolled back by a failure is read again by the
                // resumed execution (BMAD TR-070: read - filter counted it twice).
                eligible +=
                        step.getWriteCount()
                                + step.getReadSkipCount()
                                + step.getProcessSkipCount()
                                + step.getWriteSkipCount();
            }
        }
        boolean completed = execution.getStatus() == BatchStatus.COMPLETED;
        cycles.close(
                run, completed, eligible, execution.getExecutionContext().containsKey(RESUMED));
        log.info(
                "event=COMPUTATION_RUN_CLOSED runId={} completed={} eligible={}",
                run.runId(),
                completed,
                eligible);
    }
}
