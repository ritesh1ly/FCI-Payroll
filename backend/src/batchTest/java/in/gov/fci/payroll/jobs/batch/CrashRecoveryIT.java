package in.gov.fci.payroll.jobs.batch;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.jobs.batch.lock.JobLockService;
import in.gov.fci.payroll.jobs.batch.recovery.CrashRecoveryService;
import in.gov.fci.payroll.jobs.batch.support.BatchHistory;
import in.gov.fci.payroll.jobs.business.JobParameterCodec;
import in.gov.fci.payroll.jobs.business.model.BatchJobRequest;
import in.gov.fci.payroll.jobs.business.model.JobStatus;
import in.gov.fci.payroll.jobs.business.model.JobType;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Crash recovery of the batch tier (BATCH_DESIGN: kill -9, power loss, OOM): a PICKED or RUNNING
 * request whose node stopped heart-beating is failed with its Spring Batch execution (so it can be
 * restarted from the last committed chunk) and journalled RECOVERED; requests still alive (recent
 * pick, fresh heartbeat) are never touched. Without auto-restart nothing is relaunched.
 */
class CrashRecoveryIT extends BatchTestSupport {

    @Autowired private CrashRecoveryService recovery;
    @Autowired private JobLockService locks;
    @Autowired private JobRepository repository;
    @Autowired private BatchHistory explorer;
    @Autowired private in.gov.fci.payroll.jobs.persistence.JobRepositories.Controls controls;

    private BatchJobRequest running(String node, Instant pickedAt, boolean withExecution)
            throws Exception {
        Map<String, String> params =
                Map.of("payPeriod", "2026-07", "reason", "crash-it-" + System.nanoTime());
        BatchJobRequest r =
                requests.save(
                        BatchJobRequest.submit(
                                JobType.PAYROLL_DRY_RUN,
                                JobParameterCodec.toJson(params),
                                JobParameterCodec.hash("PAYROLL_DRY_RUN", params),
                                5,
                                BatchJobRequest.Source.UI,
                                null,
                                "test.maker",
                                null,
                                "2026-07",
                                pickedAt));
        r.pick(node, pickedAt);
        if (withExecution) {
            JobExecution e =
                    executions()
                            .create(
                                    "crashRecoveryIt",
                                    new JobParametersBuilder()
                                            .addLong("run", System.nanoTime())
                                            .toJobParameters());
            e.setStatus(BatchStatus.STARTED);
            e.setStartTime(java.time.LocalDateTime.now());
            repository.update(e);
            r.started(e.getId(), pickedAt);
        }
        return requests.save(r);
    }

    private String journalOf(BatchJobRequest r) {
        return String.join(
                ",",
                jdbc.queryForList(
                        "SELECT event_type FROM batch_job_audit WHERE job_request_id = ? ORDER BY"
                                + " audit_id",
                        String.class,
                        r.getId()));
    }

    @Test
    void anAbandonedRunIsFailedWithItsExecutionSoItCanBeRestarted() throws Exception {
        BatchJobRequest dead =
                running("dead-node", clock.instant().minus(Duration.ofMinutes(10)), true);
        recovery.recoverAbandoned();
        BatchJobRequest after = requests.findById(dead.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(after.getErrorMessage())
                .startsWith("Abnormal shutdown of dead-node detected by ");
        assertThat(after.getFinishedAt()).isNotNull();
        JobExecution execution = explorer.getJobExecution(dead.getJobExecutionId());
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(execution.getEndTime()).isNotNull();
        assertThat(journalOf(dead)).contains("RECOVERED");
        // Auto-restart is off by default: the request waits for a manual Restart (C-13).
        assertThat(
                        controls.existsByJobRequestIdAndStatus(
                                dead.getId(),
                                in.gov.fci.payroll.jobs.business.model.BatchJobControl.Status
                                        .PENDING))
                .isFalse();
    }

    @Test
    void aPickedRequestWithoutAnExecutionIsRecoveredToo() throws Exception {
        BatchJobRequest dead = running(null, clock.instant().minus(Duration.ofHours(1)), false);
        assertThat(recovery.recoverAbandoned()).isGreaterThanOrEqualTo(1);
        BatchJobRequest after = requests.findById(dead.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(after.getErrorMessage()).startsWith("Abnormal shutdown of a batch node");
    }

    @Test
    void aRecentPickOrAFreshHeartbeatIsLeftAlone() throws Exception {
        BatchJobRequest recent = running("live-node", clock.instant(), false);
        BatchJobRequest beating =
                running("live-node", clock.instant().minus(Duration.ofMinutes(10)), false);
        assertThat(locks.acquire("crash-it|" + beating.getId(), beating.getId())).isTrue();
        try {
            recovery.recoverAbandoned();
            assertThat(requests.findById(recent.getId()).orElseThrow().getStatus())
                    .isEqualTo(JobStatus.PICKED);
            assertThat(requests.findById(beating.getId()).orElseThrow().getStatus())
                    .isEqualTo(JobStatus.PICKED);
            assertThat(journalOf(beating)).doesNotContain("RECOVERED");
        } finally {
            locks.release(beating.getId());
            // Leave nothing PICKED behind for other tests.
            for (BatchJobRequest r : new BatchJobRequest[] {recent, beating}) {
                BatchJobRequest x = requests.findById(r.getId()).orElseThrow();
                x.recoverAsFailed("test clean-up", clock.instant());
                requests.save(x);
            }
        }
    }
}
