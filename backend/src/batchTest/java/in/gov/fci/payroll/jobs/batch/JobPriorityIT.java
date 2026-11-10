package in.gov.fci.payroll.jobs.batch;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.jobs.batch.queue.JobClaimer;
import in.gov.fci.payroll.jobs.business.model.BatchJobRequest;
import in.gov.fci.payroll.jobs.business.model.JobStatus;
import in.gov.fci.payroll.jobs.business.model.JobType;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * SRS-NF-11 / SRS-NF-14 (deep review DR-17) on the real queue: the Actual Run is claimed before
 * older bulk work, bulk work is taken only within the allowance the poller gives (the node's last
 * slot stays for priority work), the bulk rows passed over stay PENDING, and the node counts the
 * bulk jobs it holds.
 */
class JobPriorityIT extends BatchTestSupport {

    @Autowired private JobClaimer claimer;
    @Autowired private TransactionTemplate tx;

    private long queued(JobType type, String hash) {
        BatchJobRequest request =
                BatchJobRequest.submit(
                        type,
                        "{}",
                        hash,
                        type.priority(),
                        BatchJobRequest.Source.UI,
                        null,
                        "test",
                        busiestUnit().locationId(),
                        "2026-07",
                        clock.instant());
        return requests.save(request).getId();
    }

    private String status(long id) {
        return jdbc.queryForObject(
                "SELECT job_status FROM batch_job_request WHERE job_request_id = ?",
                String.class,
                id);
    }

    @AfterEach
    void closeTheQueue() {
        jdbc.update(
                "UPDATE batch_job_request SET job_status = 'COMPLETED' WHERE job_status IN"
                        + " ('PENDING', 'PICKED')");
    }

    @Test
    void SRS_NF_11_theActualRunGoesFirstAndBulkWorkOnlyWithinItsAllowance() {
        closeTheQueue();
        long simulation = queued(JobType.SIMULATION_RUN, "h-prio-sim");
        long form16 = queued(JobType.TAX_FORM16_PART_B, "h-prio-f16");
        long actual = queued(JobType.PAYROLL_COMPUTATION, "h-prio-act");

        List<BatchJobRequest> taken = tx.execute(s -> claimer.claimPending(3, 1, 10));

        assertThat(taken)
                .extracting(BatchJobRequest::getId)
                .as("priority first, then one bulk job")
                .containsExactly(actual, form16);
        assertThat(status(simulation)).as("passed over, still queued").isEqualTo("PENDING");
        assertThat(claimer.runningLowPriority()).as("the bulk job this node holds").isEqualTo(1);

        List<BatchJobRequest> none = tx.execute(s -> claimer.claimPending(2, 0, 10));
        assertThat(none).as("no allowance left: the last slot waits").isEmpty();
        assertThat(status(simulation)).isEqualTo("PENDING");
        assertThat(status(actual)).isEqualTo(JobStatus.PICKED.name());
    }
}
