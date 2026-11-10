package in.gov.fci.payroll.jobs.batch;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.integration.orchestration.InterfaceSettingsService;
import in.gov.fci.payroll.jobs.batch.launch.JobLaunchService;
import in.gov.fci.payroll.jobs.business.model.BatchJobRequest;
import in.gov.fci.payroll.jobs.business.model.JobStatus;
import in.gov.fci.payroll.jobs.business.model.JobType;
import in.gov.fci.payroll.platform.cache.CacheRegions;
import in.gov.fci.payroll.platform.error.FciException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * The two scheduled jobs run like any request (SRS-NF-2): the nightly HRMS pull records that
 * nothing was pulled while no interface exists (OQ-8), and the month-end readiness check counts the
 * ACTIVE employees and the approvals still pending, read-only. Both end COMPLETED with their
 * summary on the request (C-13).
 */
class ScheduledJobsRunTest extends BatchTestSupport {

    @Autowired
    @Qualifier("interfacePullJob")
    private Job interfacePull;

    @Autowired
    @Qualifier("monthEndCloseJob")
    private Job monthEndClose;

    @Autowired private CacheRegions regions;

    private BatchJobRequest run(Job job, JobType type) throws Exception {
        BatchJobRequest request =
                pickedRequest(
                        type,
                        Map.of("payPeriod", "2026-10", "marker", "run-" + System.nanoTime()),
                        null);
        jobLauncher.setJob(job);
        assertThat(jobLauncher.startJob(JobLaunchService.parameters(request)).getStatus())
                .isEqualTo(BatchStatus.COMPLETED);
        return requests.findById(request.getId()).orElseThrow();
    }

    @Test
    void SRS_NF_2_theNightlyPullRecordsThatNothingWasPulledWithoutAnInterface() throws Exception {
        BatchJobRequest done = run(interfacePull, JobType.INTERFACE_PULL);
        assertThat(done.getStatus()).isEqualTo(JobStatus.COMPLETED);
        assertThat(done.getReadCount()).as("one pull").isEqualTo(1);
        assertThat(done.getResultSummary())
                .isEqualTo(
                        "Received 0, accepted 0, rejected 0. No HRMS interface configured"
                                + " (fci.batch.interfaces.hrms-source, OQ-8); nothing pulled");
    }

    // The nightly pull writes its exchange envelope (SRS-INT-32).
    @Test
    void SRS_INT_32_theNightlyPullWritesItsExchangeWindow() throws Exception {
        Integer before =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM interface_run r JOIN interface_registry i ON"
                                + " i.interface_id = r.interface_id WHERE i.interface_code ="
                                + " 'HRMS_BULK_RECONCILE' AND r.run_status = 'OK'",
                        Integer.class);
        run(interfacePull, JobType.INTERFACE_PULL);
        Integer after =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM interface_run r JOIN interface_registry i ON"
                                + " i.interface_id = r.interface_id WHERE i.interface_code ="
                                + " 'HRMS_BULK_RECONCILE' AND r.run_status = 'OK'",
                        Integer.class);
        assertThat(after).isEqualTo(before + 1);
    }

    // BMAD TR-087 (SRS-INT-16 l.5221; R-INT): the refusal is the not-configured one of
    // HRMS_BULK_RECONCILE, not any failure, and no OK window is written for it.
    @Test
    void SRS_INT_16_theNightlyPullIsRefusedWhileItsInterfaceIsNotConfigured() throws Exception {
        String okWindows =
                "SELECT COUNT(*) FROM interface_run r JOIN interface_registry i ON i.interface_id ="
                        + " r.interface_id WHERE i.interface_code = 'HRMS_BULK_RECONCILE' AND"
                        + " r.run_status = 'OK'";
        Integer before = jdbc.queryForObject(okWindows, Integer.class);
        String config =
                "UPDATE interface_config SET effective_to = ? WHERE interface_id = (SELECT"
                        + " interface_id FROM interface_registry WHERE interface_code ="
                        + " 'HRMS_BULK_RECONCILE')";
        jdbc.update(config, java.sql.Date.valueOf(java.time.LocalDate.of(2020, 4, 1)));
        // Changed with SQL: the cached settings reload (as a migration marks the region).
        regions.changed(InterfaceSettingsService.REGION, "test");
        try {
            BatchJobRequest request =
                    pickedRequest(
                            JobType.INTERFACE_PULL,
                            Map.of("payPeriod", "2026-10", "marker", "run-" + System.nanoTime()),
                            null);
            jobLauncher.setJob(interfacePull);
            JobExecution refused = jobLauncher.startJob(JobLaunchService.parameters(request));
            assertThat(refused.getStatus()).isEqualTo(BatchStatus.FAILED);
            assertThat(refused.getAllFailureExceptions())
                    .anySatisfy(
                            ex ->
                                    assertThat(causes(ex))
                                            .as("the not-configured refusal of the interface")
                                            .anySatisfy(
                                                    c ->
                                                            assertThat(c)
                                                                    .isInstanceOf(
                                                                            FciException.class)
                                                                    .hasMessage(
                                                                            "INT-503-NOT-CONFIGURED")));
            assertThat(jdbc.queryForObject(okWindows, Integer.class))
                    .as("no OK window for a refused pull")
                    .isEqualTo(before);
        } finally {
            jdbc.update(config, (Object) null);
            regions.changed(InterfaceSettingsService.REGION, "test");
        }
    }

    private static java.util.List<Throwable> causes(Throwable top) {
        java.util.List<Throwable> chain = new java.util.ArrayList<>();
        for (Throwable t = top; t != null && !chain.contains(t); t = t.getCause()) {
            chain.add(t);
        }
        return chain;
    }

    // BMAD TR-068: the month-end readiness summary is in no source (not SRS-NF-2, whose progress
    // and end notice are JobEndNoticeIT); named after what it checks.
    @Test
    void monthEndJob_theReadinessSummaryCountsActiveEmployeesAndPendingApprovals()
            throws Exception {
        Integer employees =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM employee WHERE record_status = 'ACTIVE'",
                        Integer.class);
        Integer pending =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM approval_queue WHERE status = 'PENDING_APPROVAL'",
                        Integer.class);

        BatchJobRequest done = run(monthEndClose, JobType.MONTH_END_CLOSE);

        assertThat(done.getStatus()).isEqualTo(JobStatus.COMPLETED);
        assertThat(done.getResultSummary())
                .isEqualTo(
                        "Readiness: "
                                + employees
                                + " active employees, "
                                + pending
                                + " approvals pending");
    }
}
