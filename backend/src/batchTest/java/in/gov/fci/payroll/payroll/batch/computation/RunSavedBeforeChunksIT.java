package in.gov.fci.payroll.payroll.batch.computation;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.configuration.api.PayConfiguration;
import in.gov.fci.payroll.jobs.batch.BatchTestSupport;
import in.gov.fci.payroll.jobs.batch.launch.JobLaunchService;
import in.gov.fci.payroll.jobs.business.model.BatchJobRequest;
import in.gov.fci.payroll.jobs.business.model.JobType;
import in.gov.fci.payroll.payroll.api.StaffDeductionRunner;
import in.gov.fci.payroll.payroll.batch.cycle.CycleRuns;
import in.gov.fci.payroll.payroll.orchestration.ComputationResumeCheck;
import in.gov.fci.payroll.payroll.orchestration.CycleLeases;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * SRS-15-26 / SRS-15-28 (batch campaign 2026-10-30, scenario R3, a batch node killed mid-run): the
 * run a computation opens is in the job's saved context before the first chunk, so a restart after
 * the batch node is lost mid-step continues that run under its own lease instead of opening a new
 * run that the dead run's lease refuses (CYCLE_LEASE_HELD).
 */
class RunSavedBeforeChunksIT extends BatchTestSupport {

    @Autowired private PayConfiguration configuration;
    @Autowired private ComputationResumeCheck resume;
    @Autowired private StaffDeductionRunner deductions;
    @Autowired private CycleLeases leases;

    @Test
    void SRS_15_26_theOpenedRunIsSavedWithTheJobBeforeAnyChunk() {
        Unit unit = busiestUnit();
        BatchJobRequest request =
                pickedRequest(
                        JobType.PAYROLL_DRY_RUN,
                        Map.of("locationId", unit.locationId().toString(), "payPeriod", "2026-05"),
                        unit.locationId());
        JobExecution execution =
                executions()
                        .create(
                                ComputationJobConfig.DRY_RUN_JOB,
                                JobLaunchService.parameters(request));
        ComputationRunListener listener =
                new ComputationRunListener(
                        cycles,
                        configuration,
                        resume,
                        deductions,
                        "PAYROLL_DRY_RUN",
                        CycleRuns.RunType.SIMULATION,
                        jobRepository);

        listener.beforeJob(execution);
        CycleRuns.OpenRun run = ComputationKeys.get(execution.getExecutionContext());
        try {
            // What a restart after a crash reads: the context as saved, not the one in memory.
            JobExecution saved = jobRepository.getJobExecution(execution.getId());
            assertThat(saved).isNotSameAs(execution);
            assertThat(saved.getExecutionContext().getLong(ComputationKeys.RUN_ID))
                    .isEqualTo(run.runId());
        } finally {
            leases.releaseRun(run.cycleId(), run.runId());
        }
    }

    @Test
    void SRS_15_26_aPartitionRestartedWithoutItsSavedUnitClaimsTheSameUnitAgain() {
        Unit unit = busiestUnit();
        BatchJobRequest request =
                pickedRequest(
                        JobType.PAYROLL_DRY_RUN,
                        Map.of("locationId", unit.locationId().toString(), "payPeriod", "2026-04"),
                        unit.locationId());
        CycleRuns.OpenRun run =
                cycles.open(
                        request.getId(),
                        unit.locationId(),
                        java.time.YearMonth.of(2026, 4),
                        CycleRuns.RunType.SIMULATION,
                        "h-unit",
                        "v-unit");
        try {
            long first = cycles.claimUnit(run.runId(), 2, run.orgUnitId(), 10, "node-a", null);
            // The node was lost before the partition's first commit: no unit id was saved.
            long again = cycles.claimUnit(run.runId(), 2, run.orgUnitId(), 10, "node-b", null);
            assertThat(again).as("the same unit, not a second one").isEqualTo(first);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT attempt_count FROM computation_work_unit WHERE"
                                            + " work_unit_id = ?",
                                    Integer.class,
                                    first))
                    .isEqualTo(2);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM computation_work_unit WHERE run_id = ?",
                                    Integer.class,
                                    run.runId()))
                    .isEqualTo(1);
        } finally {
            leases.releaseRun(run.cycleId(), run.runId());
        }
    }
}
