package in.gov.fci.payroll.jobs.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.gov.fci.payroll.jobs.batch.launch.JobLaunchService;
import in.gov.fci.payroll.jobs.batch.listener.StepLoggingListener;
import in.gov.fci.payroll.jobs.batch.support.BatchHistory;
import in.gov.fci.payroll.jobs.batch.support.PartitionedSteps;
import in.gov.fci.payroll.jobs.business.model.BatchJobRequest;
import in.gov.fci.payroll.jobs.business.model.JobStatus;
import in.gov.fci.payroll.jobs.business.model.JobType;
import in.gov.fci.payroll.jobs.orchestration.JobJournal;
import in.gov.fci.payroll.jobs.orchestration.JobRequestService;
import in.gov.fci.payroll.payroll.batch.computation.ComputationJobConfig;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * @SpringBatchTest of PAYROLL_DRY_RUN: partitioned run, request lifecycle, skip to the failure log.
 */
// Partitions of 3 and chunks of 5: the BatchTestSupport defaults (shared context).
class PayrollDryRunJobTest extends BatchTestSupport {

    @Autowired
    @Qualifier("payrollDryRunJob")
    private Job job;

    @Autowired private JobJournal journal;
    @Autowired private JobRequestService jobRequests;
    @Autowired private BatchHistory history;

    @BeforeEach
    void useJob() {
        jobLauncher.setJob(job);
    }

    @Test
    void partitionedRunWritesOneResultPerActiveEmployeeAndCompletesTheRequest() throws Exception {
        Unit unit = busiestUnit();
        assertThat(unit.activeEmployees()).as("demo seed has employees").isGreaterThan(3);
        BatchJobRequest request =
                pickedRequest(
                        JobType.PAYROLL_DRY_RUN,
                        Map.of("locationId", unit.locationId().toString(), "payPeriod", "2026-07"),
                        unit.locationId());

        JobExecution execution = jobLauncher.startJob(JobLaunchService.parameters(request));

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        List<StepExecution> workers =
                execution.getStepExecutions().stream()
                        .filter(s -> s.getStepName().contains(":partition"))
                        .toList();
        assertThat(workers).hasSizeBetween(1, 3);
        long read = workers.stream().mapToLong(StepExecution::getReadCount).sum();
        long written = workers.stream().mapToLong(StepExecution::getWriteCount).sum();
        long filtered = workers.stream().mapToLong(StepExecution::getFilterCount).sum();
        long skipped = workers.stream().mapToLong(StepExecution::getSkipCount).sum();
        assertThat(read)
                .as("every eligible member of the cycle unit is read")
                .isEqualTo(unit.activeEmployees());
        assertThat(read)
                .as("read = paid + not paid (stop / hold) + quarantined")
                .isEqualTo(written + filtered + skipped);
        assertThat(written).as("demo rates are seeded, so members are computed").isPositive();
        assertThat(results(request.getId()))
                .as("one payroll_result per computed member")
                .isEqualTo(written);

        BatchJobRequest done = requests.findById(request.getId()).orElseThrow();
        assertThat(done.getStatus()).isEqualTo(JobStatus.COMPLETED);
        assertThat(done.getAttemptNo()).isEqualTo(1);
        assertThat(done.getReadCount()).isEqualTo(unit.activeEmployees());
        assertThat(done.getWriteCount()).isEqualTo(written);
        assertThat(done.getSkipCount()).isEqualTo(skipped);
        // C-13 counts a running job by the same rule (SRS-NF-2): the workers, never the manager,
        // which marked itself in the shared repository as it started.
        JobExecution stored = history.getJobExecution(execution.getId());
        assertThat(stored.getStepExecutions())
                .filteredOn(PartitionedSteps::isManager)
                .extracting(StepExecution::getStepName)
                .containsExactly(ComputationJobConfig.MANAGER);
        assertThat(PartitionedSteps.counts(stored.getStepExecutions()))
                .isEqualTo(new PartitionedSteps.Counts(read, written, skipped));
        assertThat(done.getResultSummary()).contains("members read " + unit.activeEmployees());
        assertThat(journal.audit(request.getId()))
                .extracting(JobJournal.AuditEntry::event)
                .containsSubsequence("STARTED", "COMPLETED");

        // C-13 shows the attempt with every step and its counts, as Spring Batch recorded them.
        in.gov.fci.payroll.platform.security.AccessContext viewer =
                new in.gov.fci.payroll.platform.security.AccessContext(
                        999L,
                        "c13.viewer@fci-demo.test",
                        "Viewer",
                        "s",
                        "P",
                        unit.locationId(),
                        null,
                        List.of(),
                        java.util.Map.of(),
                        java.util.Set.of("C-13:VIEW"),
                        java.util.Set.of(),
                        java.util.Set.of(unit.locationId()));
        JobRequestService.JobDetail detail = jobRequests.detail(viewer, request.getId());
        assertThat(detail.attempts()).hasSize(1);
        JobRequestService.AttemptView attempt = detail.attempts().get(0);
        assertThat(attempt.status()).isEqualTo("COMPLETED");
        assertThat(attempt.jobExecutionId()).isEqualTo(execution.getId());
        assertThat(attempt.steps()).hasSize(execution.getStepExecutions().size());
        assertThat(attempt.steps().stream().filter(JobRequestService.StepView::partition))
                .hasSize(workers.size());
        assertThat(
                        attempt.steps().stream()
                                .filter(JobRequestService.StepView::partition)
                                .mapToLong(JobRequestService.StepView::readCount)
                                .sum())
                .isEqualTo(read);
        // An office outside the viewer's scope does not show the request at all.
        in.gov.fci.payroll.platform.security.AccessContext elsewhere =
                new in.gov.fci.payroll.platform.security.AccessContext(
                        998L,
                        "other@fci-demo.test",
                        "Other",
                        "s",
                        "P",
                        -1L,
                        null,
                        List.of(),
                        java.util.Map.of(),
                        java.util.Set.of("C-13:VIEW"),
                        java.util.Set.of(),
                        java.util.Set.of(-1L));
        assertThatThrownBy(() -> jobRequests.detail(elsewhere, request.getId()))
                .isInstanceOf(in.gov.fci.payroll.platform.error.FciException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM batch_job_lock", Integer.class))
                .as("lock released")
                .isZero();
    }

    @Test
    void aBadRecordIsSkippedLoggedAndTheRunContinues() throws Exception {
        Unit unit = busiestUnit();
        Long victim =
                named.queryForObject(
                        "SELECT MIN(employee_id) FROM employee WHERE employee_type = 'STAFF'"
                                + " AND record_status = 'ACTIVE'"
                                + " AND employment_status NOT IN ('SEPARATED', 'DISMISSED')"
                                + " AND current_basic IS NOT NULL AND location_id IN (:ids)",
                        Map.of("ids", org.subtree(unit.locationId())),
                        Long.class);
        Object original =
                jdbc.queryForObject(
                        "SELECT current_basic FROM employee WHERE employee_id = ?",
                        Object.class,
                        victim);
        jdbc.update("UPDATE employee SET current_basic = -1 WHERE employee_id = ?", victim);
        try {
            BatchJobRequest request =
                    pickedRequest(
                            JobType.PAYROLL_DRY_RUN,
                            Map.of(
                                    "locationId",
                                    unit.locationId().toString(),
                                    "payPeriod",
                                    "2026-06"),
                            unit.locationId());

            JobExecution execution = jobLauncher.startJob(JobLaunchService.parameters(request));

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            Long victimResults =
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM payroll_result r JOIN computation_run c ON"
                                    + " c.run_id = r.run_id WHERE r.employee_id = ? AND"
                                    + " c.parameters_json LIKE ?",
                            Long.class,
                            victim,
                            "{\"jobRequestId\":" + request.getId() + ",%");
            assertThat(victimResults).as("a negative basic is never paid").isZero();
            BatchJobRequest done = requests.findById(request.getId()).orElseThrow();
            assertThat(done.getSkipCount()).isPositive();
            assertThat(journal.skippedItems(request.getId(), 0, 100))
                    .anySatisfy(
                            item -> {
                                assertThat(item.phase()).isEqualTo("PROCESS");
                                assertThat(item.itemKey()).isEqualTo("employee:" + victim);
                                // A member the rules cannot compute: an invalid record
                                // (MEMBER_DATA) of the kind
                                // the X-05 worklist also flags (US-GAP-1).
                                assertThat(item.errorClass()).endsWith("MemberBlockedException");
                            });
            assertThat(execution.getStepExecutions())
                    .anyMatch(
                            s ->
                                    StepLoggingListener.COMPLETED_WITH_SKIPS.equals(
                                            s.getExitStatus().getExitCode()));
        } finally {
            jdbc.update(
                    "UPDATE employee SET current_basic = ? WHERE employee_id = ?",
                    original,
                    victim);
        }
    }
}
