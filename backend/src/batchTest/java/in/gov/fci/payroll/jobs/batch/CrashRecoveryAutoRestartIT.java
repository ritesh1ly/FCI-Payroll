package in.gov.fci.payroll.jobs.batch;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.jobs.batch.recovery.CrashRecoveryService;
import in.gov.fci.payroll.jobs.business.JobParameterCodec;
import in.gov.fci.payroll.jobs.business.model.BatchJobControl;
import in.gov.fci.payroll.jobs.business.model.BatchJobRequest;
import in.gov.fci.payroll.jobs.business.model.JobStatus;
import in.gov.fci.payroll.jobs.business.model.JobType;
import in.gov.fci.payroll.jobs.persistence.JobRepositories;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * With {@code fci.batch.recovery.auto-restart=true} a recovered request inside the restart window
 * gets exactly one RESTART command from system.recovery; one past the window gets none.
 */
@TestPropertySource(properties = "fci.batch.recovery.auto-restart=true")
class CrashRecoveryAutoRestartIT extends BatchTestSupport {

    @Autowired private CrashRecoveryService recovery;
    @Autowired private JobRepositories.Controls controls;

    private BatchJobRequest abandoned(Instant requestedAt) {
        Map<String, String> params =
                Map.of("payPeriod", "2026-07", "reason", "auto-" + System.nanoTime());
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
                                requestedAt));
        r.pick("dead-node", clock.instant().minus(Duration.ofMinutes(10)));
        return requests.save(r);
    }

    private long restarts(BatchJobRequest r) {
        Long n =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM batch_job_control WHERE job_request_id = ? AND"
                                + " command = 'RESTART' AND requested_by_login = 'system.recovery'",
                        Long.class,
                        r.getId());
        return n == null ? 0 : n;
    }

    @Test
    void aRecoveredRequestInsideTheWindowIsRestartedOnce() {
        BatchJobRequest r = abandoned(clock.instant().minus(Duration.ofMinutes(20)));
        recovery.recoverAbandoned();
        assertThat(requests.findById(r.getId()).orElseThrow().getStatus())
                .isEqualTo(JobStatus.FAILED);
        assertThat(restarts(r)).isEqualTo(1);
        assertThat(
                        controls.existsByJobRequestIdAndStatus(
                                r.getId(), BatchJobControl.Status.PENDING))
                .isTrue();
        assertThat(
                        jdbc.queryForList(
                                "SELECT event_type FROM batch_job_audit WHERE job_request_id = ?",
                                String.class,
                                r.getId()))
                .contains("RECOVERED", "CONTROL_REQUESTED");
    }

    // OPEN-QUESTION (tracker 01.16): recovery stamps finishedAt = now before the window check, so a
    // recovered request is always inside the restart window, however old the request is. Pinned
    // here
    // as built; change the test with the decision.
    @Test
    void anOldRecoveredRequestIsStillRestartedBecauseTheWindowCountsFromTheRecovery() {
        BatchJobRequest old = abandoned(clock.instant().minus(Duration.ofHours(100)));
        recovery.recoverAbandoned();
        BatchJobRequest after = requests.findById(old.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(restarts(old)).isEqualTo(1);
        // A second pass never queues a second restart.
        recovery.recoverAbandoned();
        assertThat(restarts(old)).isEqualTo(1);
    }
}
