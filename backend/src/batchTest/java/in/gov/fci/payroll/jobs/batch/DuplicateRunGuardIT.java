package in.gov.fci.payroll.jobs.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.gov.fci.payroll.iam.persistence.AccessRepositories;
import in.gov.fci.payroll.jobs.batch.launch.JobLaunchService;
import in.gov.fci.payroll.jobs.batch.lock.JobLockService;
import in.gov.fci.payroll.jobs.batch.queue.JobClaimer;
import in.gov.fci.payroll.jobs.batch.recovery.CrashRecoveryService;
import in.gov.fci.payroll.jobs.batch.support.BatchHistory;
import in.gov.fci.payroll.jobs.business.model.BatchJobRequest;
import in.gov.fci.payroll.jobs.business.model.JobStatus;
import in.gov.fci.payroll.jobs.business.model.JobType;
import in.gov.fci.payroll.jobs.orchestration.JobJournal;
import in.gov.fci.payroll.jobs.orchestration.JobRequestService;
import in.gov.fci.payroll.platform.error.ApiErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.security.AccessContext;
import in.gov.fci.payroll.platform.security.AccessMode;
import in.gov.fci.payroll.platform.security.ActiveGroup;
import in.gov.fci.payroll.platform.security.ScopeType;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The guards that keep one job from running twice: API duplicate check, job lock (with takeover of
 * a dead node's lock), the BatchHistory running check at launch, SKIP LOCKED claiming between two
 * nodes, and crash recovery of an execution left STARTED by a dead node.
 */
class DuplicateRunGuardIT extends BatchTestSupport {

    @Autowired private JobRequestService service;
    @Autowired private JobLockService locks;
    @Autowired private JobLaunchService launcher;
    @Autowired private JobClaimer claimer;
    @Autowired private CrashRecoveryService recovery;
    @Autowired private JobJournal journal;
    @Autowired private BatchHistory explorer;
    @Autowired private AccessRepositories.Users users;
    @Autowired private TransactionTemplate tx;

    private AccessContext maker(Long locationId) {
        Long userId = users.findByLoginId("maker.delhi@fci-demo.test").orElseThrow().getId();
        ActiveGroup group =
                new ActiveGroup(
                        1L,
                        "AG-T",
                        "Test",
                        1L,
                        "PAYROLL_MAKER",
                        "Maker",
                        ScopeType.DIVISION,
                        false,
                        Set.of(locationId),
                        Set.of(),
                        AccessMode.FULL);
        return new AccessContext(
                userId,
                "maker.delhi@fci-demo.test",
                "Maker",
                "s1",
                "PAYROLL",
                locationId,
                null,
                List.of(group),
                Map.of(1L, Set.of("C-13:VIEW", "C-13:CREATE", "C-13:EDIT")),
                Set.of(),
                Set.of(),
                Set.of(locationId));
    }

    @Test
    void theApiRefusesASecondOpenRequestForTheSameJobAndParameters() {
        Long location = org.byCode("DH001").orElseThrow().getId();
        AccessContext context = maker(location);
        Map<String, String> params =
                Map.of("locationId", location.toString(), "payPeriod", "2026-07");

        JobRequestService.JobView first =
                service.submit(context, "PAYROLL_DRY_RUN", params, null, "E2E");

        assertThat(first.status()).isEqualTo("AWAITING_APPROVAL");
        assertThatThrownBy(() -> service.submit(context, "PAYROLL_DRY_RUN", params, 9, "again"))
                .isInstanceOfSatisfying(
                        FciException.class,
                        e ->
                                assertThat(e.getErrorCode())
                                        .isEqualTo(ApiErrorCode.JOB_ALREADY_QUEUED));
        assertThat(
                        service.submit(
                                        context,
                                        "PAYROLL_DRY_RUN",
                                        Map.of(
                                                "locationId",
                                                location.toString(),
                                                "payPeriod",
                                                "2026-06"),
                                        null,
                                        "E2E")
                                .status())
                .as("other parameters are another job")
                .isEqualTo("AWAITING_APPROVAL");
    }

    @Test
    void theLockAdmitsOneHolderAndIsTakenOverOnlyWhenStale() {
        Unit unit = busiestUnit();
        BatchJobRequest a =
                pickedRequest(
                        JobType.PAYROLL_DRY_RUN,
                        Map.of("locationId", unit.locationId().toString(), "payPeriod", "2026-05"),
                        unit.locationId());
        BatchJobRequest b =
                pickedRequest(
                        JobType.PAYROLL_DRY_RUN,
                        Map.of("locationId", unit.locationId().toString(), "payPeriod", "2026-04"),
                        unit.locationId());
        String key = JobLockService.key("PAYROLL_DRY_RUN", a.getParamHash());

        assertThat(locks.acquire(key, a.getId())).isTrue();
        assertThat(locks.acquire(key, b.getId())).as("held").isFalse();

        jdbc.update(
                "UPDATE batch_job_lock SET heartbeat_at = ? WHERE lock_key = ?",
                Timestamp.from(clock.instant().minus(Duration.ofHours(1))),
                key);
        assertThat(locks.acquire(key, b.getId()))
                .as("stale lock of a dead node is taken over")
                .isTrue();
        locks.release(b.getId());
        locks.release(a.getId());
    }

    @Test
    void launchIsRefusedWhileTheSameJobRunsAndTheRequestGoesBackToTheQueue() throws Exception {
        Unit unit = busiestUnit();
        Map<String, String> params =
                Map.of("locationId", unit.locationId().toString(), "payPeriod", "2026-03");
        BatchJobRequest request = pickedRequest(JobType.PAYROLL_DRY_RUN, params, unit.locationId());
        // Another node runs the same job with the same parameters: a STARTED execution in the
        // shared repository.
        JobExecution elsewhere =
                executions()
                        .create(
                                "payrollDryRun",
                                JobLaunchService.parameters(
                                        pickedRequest(
                                                JobType.PAYROLL_DRY_RUN,
                                                params,
                                                unit.locationId())));
        elsewhere.setStatus(BatchStatus.STARTED);
        jobRepository.update(elsewhere);

        boolean launched = launcher.launch(request, JobStatus.PENDING);

        assertThat(launched).isFalse();
        assertThat(requests.findById(request.getId()).orElseThrow().getStatus())
                .isEqualTo(JobStatus.PENDING);
        assertThat(journal.audit(request.getId()))
                .extracting(JobJournal.AuditEntry::event)
                .contains("LOCK_REFUSED");
    }

    @Test
    void twoNodesNeverClaimTheSameRequest() throws Exception {
        Unit unit = busiestUnit();
        jdbc.update(
                "UPDATE batch_job_request SET job_status = 'COMPLETED' WHERE job_status ="
                        + " 'PENDING'");
        BatchJobRequest request =
                BatchJobRequest.submit(
                        JobType.PAYROLL_DRY_RUN,
                        "{}",
                        "h-claim",
                        5,
                        BatchJobRequest.Source.UI,
                        null,
                        "test",
                        unit.locationId(),
                        "2026-07",
                        clock.instant());
        Long id = requests.save(request).getId();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        // Node A claims and holds its transaction open.
        CompletableFuture<List<BatchJobRequest>> nodeA =
                CompletableFuture.supplyAsync(
                        () ->
                                tx.execute(
                                        s -> {
                                            List<BatchJobRequest> mine = claimer.claimPending(10);
                                            locked.countDown();
                                            try {
                                                release.await(10, TimeUnit.SECONDS);
                                            } catch (InterruptedException e) {
                                                Thread.currentThread().interrupt();
                                            }
                                            return mine;
                                        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        // Node B polls meanwhile: the locked row is skipped, not waited for.
        List<BatchJobRequest> nodeB = tx.execute(s -> claimer.claimPending(10));
        release.countDown();

        assertThat(nodeA.get(10, TimeUnit.SECONDS))
                .extracting(BatchJobRequest::getId)
                .containsExactly(id);
        assertThat(nodeB).extracting(BatchJobRequest::getId).doesNotContain(id);
        assertThat(requests.findById(id).orElseThrow().getStatus()).isEqualTo(JobStatus.PICKED);
    }

    @Test
    void crashRecoveryFailsTheAbandonedExecutionSoItCanBeRestarted() throws Exception {
        Unit unit = busiestUnit();
        BatchJobRequest request =
                pickedRequest(
                        JobType.PAYROLL_DRY_RUN,
                        Map.of("locationId", unit.locationId().toString(), "payPeriod", "2026-02"),
                        unit.locationId());
        JobExecution abandoned =
                executions().create("payrollDryRun", JobLaunchService.parameters(request));
        abandoned.setStatus(BatchStatus.STARTED);
        jobRepository.update(abandoned);
        request.started(abandoned.getId(), clock.instant());
        requests.save(request);
        // The node died long ago: no lock, picked an hour ago.
        jdbc.update(
                "UPDATE batch_job_request SET picked_at = ? WHERE job_request_id = ?",
                Timestamp.from(clock.instant().minus(Duration.ofHours(1))),
                request.getId());

        int recovered = recovery.recoverAbandoned();

        assertThat(recovered).isGreaterThanOrEqualTo(1);
        BatchJobRequest after = requests.findById(request.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(after.getErrorMessage()).contains("Abnormal shutdown");
        assertThat(explorer.getJobExecution(abandoned.getId()).getStatus())
                .isEqualTo(BatchStatus.FAILED);
        assertThat(journal.audit(request.getId()))
                .extracting(JobJournal.AuditEntry::event)
                .contains("RECOVERED");
        assertThat(service.withinRestartWindow(after)).as("restartable").isTrue();
    }
}
