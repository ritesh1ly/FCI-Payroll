package in.gov.fci.payroll.jobs.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.gov.fci.payroll.jobs.batch.control.ControlCommandProcessor;
import in.gov.fci.payroll.jobs.batch.launch.JobLaunchService;
import in.gov.fci.payroll.jobs.batch.support.OutageTrigger;
import in.gov.fci.payroll.jobs.business.model.BatchJobControl;
import in.gov.fci.payroll.jobs.business.model.BatchJobRequest;
import in.gov.fci.payroll.jobs.business.model.ControlCommand;
import in.gov.fci.payroll.jobs.business.model.JobStatus;
import in.gov.fci.payroll.jobs.business.model.JobType;
import in.gov.fci.payroll.jobs.persistence.JobRepositories;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.launch.JobRestartException;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.test.context.TestPropertySource;

/**
 * Restart after failure: the database fails on the fifth payroll_result insert (an outage that is
 * neither retryable nor skippable), the job FAILS with the earlier chunks committed; the restart
 * (same request = same JobInstance) continues after the last committed chunk, writes every
 * remaining result exactly once and completes the request as attempt 2.
 */
@TestPropertySource(
        properties = {
            "fci.batch.partition.grid-size=1",
            "fci.batch.chunk.size=2",
            "fci.batch.chunk.page-size=2"
        })
class PayrollDryRunRestartTest extends BatchTestSupport {

    @Autowired
    @Qualifier("payrollDryRunJob")
    private Job job;

    @Autowired private ControlCommandProcessor processor;
    @Autowired private JobRepositories.Controls controls;

    @AfterEach
    void disarm() {
        OutageTrigger.arm(-1);
        jdbc.execute("DROP TRIGGER IF EXISTS trg_test_outage");
    }

    private List<String> leaseEvents(long cycleId) {
        return jdbc.queryForList(
                "SELECT event_type FROM cycle_lease_log WHERE cycle_instance_id = ? ORDER BY"
                        + " lease_log_id",
                String.class,
                cycleId);
    }

    @Test
    void restartContinuesFromTheLastCommittedChunk() throws Exception {
        jobLauncher.setJob(job);
        Unit unit = busiestUnit();
        assertThat(unit.activeEmployees()).as("needs more than two chunks").isGreaterThan(5);
        jdbc.execute(
                "CREATE TRIGGER trg_test_outage BEFORE INSERT ON payroll_result FOR EACH ROW CALL"
                        + " \""
                        + OutageTrigger.class.getName()
                        + "\"");
        OutageTrigger.arm(5);
        BatchJobRequest request =
                pickedRequest(
                        JobType.PAYROLL_DRY_RUN,
                        Map.of("locationId", unit.locationId().toString(), "payPeriod", "2026-07"),
                        unit.locationId());

        JobExecution first = jobLauncher.startJob(JobLaunchService.parameters(request));

        assertThat(first.getStatus()).isEqualTo(BatchStatus.FAILED);
        long committed =
                first.getStepExecutions().stream()
                        .filter(s -> s.getStepName().contains(":partition"))
                        .mapToLong(StepExecution::getWriteCount)
                        .sum();
        assertThat(committed).as("two chunks committed before the outage").isPositive();
        assertThat(results(request.getId()))
                .as("only committed chunks left rows")
                .isEqualTo(committed);
        BatchJobRequest failed = requests.findById(request.getId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(failed.getErrorMessage()).contains("simulated outage");
        // SRS-15-28 (plan 05c): the failed run gave the cycle lease back at its close.
        long cycleId =
                jdbc.queryForObject(
                        "SELECT cycle_instance_id FROM computation_run WHERE parameters_json LIKE"
                                + " ?",
                        Long.class,
                        "{\"jobRequestId\":" + request.getId() + ",%");
        assertThat(leaseEvents(cycleId)).containsExactly("ACQUIRED", "RELEASED");

        // RESTART as the control processor applies it: PICKED again, relaunched with the same
        // parameters.
        OutageTrigger.arm(-1);
        failed.pick("test-node", clock.instant());
        requests.save(failed);
        JobExecution second = jobLauncher.startJob(JobLaunchService.parameters(failed));

        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        // The restart took the lease again for its own duration.
        assertThat(leaseEvents(cycleId))
                .containsExactly("ACQUIRED", "RELEASED", "ACQUIRED", "RELEASED");
        assertThat(second.getJobInstance().getInstanceId())
                .isEqualTo(first.getJobInstance().getInstanceId());
        long reread =
                second.getStepExecutions().stream()
                        .filter(s -> s.getStepName().contains(":partition"))
                        .mapToLong(StepExecution::getReadCount)
                        .sum();
        long firstRead =
                first.getStepExecutions().stream()
                        .filter(s -> s.getStepName().contains(":partition"))
                        .mapToLong(
                                s ->
                                        s.getWriteCount()
                                                + s.getFilterCount()
                                                + s.getWriteSkipCount()
                                                + s.getProcessSkipCount())
                        .sum();
        assertThat(reread)
                .as("only what was not committed is read again")
                .isEqualTo(unit.activeEmployees() - firstRead);
        long written =
                second.getStepExecutions().stream()
                        .filter(s -> s.getStepName().contains(":partition"))
                        .mapToLong(StepExecution::getWriteCount)
                        .sum();
        Long distinct =
                jdbc.queryForObject(
                        "SELECT COUNT(DISTINCT r.employee_id) FROM payroll_result r JOIN"
                            + " computation_run c ON c.run_id = r.run_id WHERE c.parameters_json"
                            + " LIKE ?",
                        Long.class,
                        "{\"jobRequestId\":" + request.getId() + ",%");
        assertThat(results(request.getId()))
                .as("every computed member exactly once")
                .isEqualTo(committed + written)
                .isEqualTo(distinct);
        BatchJobRequest done = requests.findById(request.getId()).orElseThrow();
        assertThat(done.getStatus()).isEqualTo(JobStatus.COMPLETED);
        assertThat(done.getAttemptNo()).isEqualTo(2);
    }

    /**
     * SRS-15-27 / SRS-15-7 / BR-CYC-014 (plan 05j; BMAD TR-064): an approved ABANDON ends the
     * failed run for good. The request is ABANDONED with its failure on record, the Spring Batch
     * execution is ABANDONED (so Spring Batch itself refuses to continue it), the computation run
     * is ABANDONED and kept, and its (dry-run, SIMULATION) result rows are discarded.
     */
    @Test
    void SRS_15_27_anAbandonedRunEndsForGoodItsResultsDiscardedAndIsNeverContinued()
            throws Exception {
        jobLauncher.setJob(job);
        Unit unit = busiestUnit();
        jdbc.execute(
                "CREATE TRIGGER trg_test_outage BEFORE INSERT ON payroll_result FOR EACH ROW CALL"
                        + " \""
                        + OutageTrigger.class.getName()
                        + "\"");
        OutageTrigger.arm(5);
        BatchJobRequest request =
                pickedRequest(
                        JobType.PAYROLL_DRY_RUN,
                        Map.of("locationId", unit.locationId().toString(), "payPeriod", "2026-09"),
                        unit.locationId());
        JobExecution first = jobLauncher.startJob(JobLaunchService.parameters(request));
        assertThat(first.getStatus()).isEqualTo(BatchStatus.FAILED);
        OutageTrigger.arm(-1);
        long rows = results(request.getId());
        assertThat(rows).isPositive();
        String run = "{\"jobRequestId\":" + request.getId() + ",%";

        // As the checker's approval records it (JobAbandonEffect); the node applies it.
        controls.save(
                BatchJobControl.request(
                        request.getId(),
                        ControlCommand.ABANDON,
                        null,
                        "ddo.delhi@fci-demo.test",
                        "Approval 1: wrong period",
                        clock.instant()));
        processor.processPending();

        BatchJobRequest abandoned = requests.findById(request.getId()).orElseThrow();
        assertThat(abandoned.getStatus()).isEqualTo(JobStatus.ABANDONED);
        assertThat(abandoned.getErrorMessage()).contains("simulated outage");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM BATCH_JOB_EXECUTION WHERE job_execution_id = ?",
                                String.class,
                                first.getId()))
                .isEqualTo("ABANDONED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT run_status FROM computation_run WHERE parameters_json LIKE"
                                        + " ?",
                                String.class,
                                run))
                .isEqualTo("ABANDONED");
        assertThat(results(request.getId()))
                .as("SRS-15-27: the abandoned run's results are discarded")
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM computation_run WHERE parameters_json LIKE ?",
                                Integer.class,
                                run))
                .as("the run record is kept")
                .isOne();
        assertThat(
                        jdbc.queryForList(
                                "SELECT event_type FROM batch_job_audit WHERE job_request_id = ?",
                                String.class,
                                request.getId()))
                .contains("ABANDONED");

        // Spring Batch itself refuses to continue the instance, and no execution is added.
        Integer executions =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM BATCH_JOB_EXECUTION WHERE job_instance_id = ?",
                        Integer.class,
                        first.getJobInstance().getInstanceId());
        assertThatThrownBy(() -> jobLauncher.startJob(JobLaunchService.parameters(abandoned)))
                .isInstanceOfAny(
                        JobInstanceAlreadyCompleteException.class, JobRestartException.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM BATCH_JOB_EXECUTION WHERE job_instance_id"
                                        + " = ?",
                                Integer.class,
                                first.getJobInstance().getInstanceId()))
                .isEqualTo(executions);
    }

    /**
     * SRS-15-26 / BR-CYC-013: the version set the run started with is no longer the one in force
     * for its period (as after an activation), so the relaunch fails before any member is read,
     * naming the reason, and the run keeps what its failure left: no row written, counts unchanged.
     */
    @Test
    void SRS_15_26_aRelaunchAfterARuleChangeFailsAtOnceAndLeavesTheRunAsItWas() throws Exception {
        jobLauncher.setJob(job);
        Unit unit = busiestUnit();
        jdbc.execute(
                "CREATE TRIGGER trg_test_outage BEFORE INSERT ON payroll_result FOR EACH ROW CALL"
                        + " \""
                        + OutageTrigger.class.getName()
                        + "\"");
        OutageTrigger.arm(5);
        BatchJobRequest request =
                pickedRequest(
                        JobType.PAYROLL_DRY_RUN,
                        Map.of("locationId", unit.locationId().toString(), "payPeriod", "2026-08"),
                        unit.locationId());
        JobExecution first = jobLauncher.startJob(JobLaunchService.parameters(request));
        assertThat(first.getStatus()).isEqualTo(BatchStatus.FAILED);
        OutageTrigger.arm(-1);
        String run = "{\"jobRequestId\":" + request.getId() + ",%";
        jdbc.update(
                "UPDATE computation_run SET rule_version_set_hash = ? WHERE parameters_json LIKE ?",
                "0".repeat(64),
                run);
        Map<String, Object> before =
                jdbc.queryForMap(
                        "SELECT run_status, result_count, eligible_count, unit_committed FROM"
                                + " computation_run WHERE parameters_json LIKE ?",
                        run);
        long rows = results(request.getId());

        BatchJobRequest failed = requests.findById(request.getId()).orElseThrow();
        failed.pick("test-node", clock.instant());
        requests.save(failed);
        JobExecution second = jobLauncher.startJob(JobLaunchService.parameters(failed));

        assertThat(second.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(second.getStepExecutions().stream().mapToLong(StepExecution::getReadCount).sum())
                .as("no member is read")
                .isZero();
        assertThat(results(request.getId())).isEqualTo(rows);
        assertThat(
                        jdbc.queryForMap(
                                "SELECT run_status, result_count, eligible_count, unit_committed"
                                        + " FROM computation_run WHERE parameters_json LIKE ?",
                                run))
                .isEqualTo(before);
        BatchJobRequest refused = requests.findById(request.getId()).orElseThrow();
        assertThat(refused.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(refused.getErrorMessage()).contains("JOB-409-RESUME-RULES");
    }
}
