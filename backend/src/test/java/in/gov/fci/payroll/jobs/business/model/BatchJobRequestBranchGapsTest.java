package in.gov.fci.payroll.jobs.business.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * SRS-15-27: an abandoned request ends for good; it keeps the time it finished when it had finished
 * (a failed run abandoned later), and finishes now when it never had.
 */
class BatchJobRequestBranchGapsTest {

    private static final Instant T0 = Instant.parse("2026-10-21T05:00:00Z");

    private static BatchJobRequest submitted() {
        return BatchJobRequest.submit(
                JobType.PAYROLL_DRY_RUN,
                "{\"payPeriod\":\"2026-10\"}",
                "hash",
                5,
                BatchJobRequest.Source.UI,
                7L,
                "maker.delhi",
                42L,
                "2026-10",
                T0);
    }

    @Test
    void SRS_15_27_anAbandonedRequestKeepsTheTimeItFinishedOrFinishesNow() {
        BatchJobRequest never = submitted();
        never.abandon(T0.plusSeconds(60));
        assertThat(never.getStatus()).isEqualTo(JobStatus.ABANDONED);
        assertThat(never.getFinishedAt()).isEqualTo(T0.plusSeconds(60));
        assertThat(never.getStopIntent()).isNull();

        BatchJobRequest failed = submitted();
        failed.recoverAsFailed("node lost", T0.plusSeconds(30));
        failed.abandon(T0.plusSeconds(3600));
        assertThat(failed.getStatus()).isEqualTo(JobStatus.ABANDONED);
        assertThat(failed.getFinishedAt()).isEqualTo(T0.plusSeconds(30));
    }
}
