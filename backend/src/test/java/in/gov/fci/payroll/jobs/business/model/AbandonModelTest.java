package in.gov.fci.payroll.jobs.business.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Plan 05j (SRS-15-27): the model of the kill switch. ABANDONED is final and never open; ABANDON
 * applies to a failed, stopped or paused job, is approved first and starts no attempt; abandoning
 * keeps the failure on record; a halt is engaged, asked to be released, refused or released.
 */
class AbandonModelTest {

    private static final Instant NOW = Instant.parse("2026-10-24T06:00:00Z");

    @Test
    void SRS_15_27_abandonedIsFinalAndNeverOpen() {
        assertThat(JobStatus.ABANDONED.isOpen()).isFalse();
        assertThat(JobStatus.OPEN).doesNotContain(JobStatus.ABANDONED);
        assertThat(JobStatus.ACTIVE).doesNotContain(JobStatus.ABANDONED);
        for (ControlCommand command : ControlCommand.values()) {
            assertThat(command.isValidFor(JobStatus.ABANDONED)).as(command.name()).isFalse();
        }
    }

    @Test
    void SRS_15_27_abandonAppliesToAFailedStoppedOrPausedJobIsApprovedFirstAndStartsNothing() {
        assertThat(ControlCommand.ABANDON.validFrom())
                .containsExactlyInAnyOrder(JobStatus.FAILED, JobStatus.STOPPED, JobStatus.PAUSED);
        assertThat(ControlCommand.ABANDON.needsApproval()).isTrue();
        assertThat(ControlCommand.ABANDON.startsAttempt()).isFalse();
        for (ControlCommand other : ControlCommand.values()) {
            if (other != ControlCommand.ABANDON) {
                assertThat(other.needsApproval()).as(other.name()).isFalse();
            }
        }
    }

    @Test
    void SRS_15_27_abandoningKeepsTheFailureOnRecordAndEndsAJobThatNeverEnded() {
        BatchJobRequest failed =
                BatchJobRequest.submit(
                        JobType.PAYROLL_DRY_RUN,
                        "{}",
                        "h",
                        5,
                        BatchJobRequest.Source.UI,
                        2L,
                        "maker@x",
                        10L,
                        "2026-09",
                        NOW.minusSeconds(7_200));
        failed.pick("node-1", NOW.minusSeconds(3_600));
        failed.started(77L, NOW.minusSeconds(3_600));
        failed.finished(JobStatus.FAILED, 1, 1, 0, "s", "boom", NOW.minusSeconds(1_800));
        failed.requestStop("STOP");
        failed.abandon(NOW);
        assertThat(failed.getStatus()).isEqualTo(JobStatus.ABANDONED);
        assertThat(failed.getErrorMessage()).isEqualTo("boom");
        assertThat(failed.getFinishedAt()).isEqualTo(NOW.minusSeconds(1_800));
        assertThat(failed.getStopIntent()).isNull();

        BatchJobRequest paused =
                BatchJobRequest.submit(
                        JobType.PAYROLL_DRY_RUN,
                        "{}",
                        "h",
                        5,
                        BatchJobRequest.Source.UI,
                        2L,
                        "maker@x",
                        10L,
                        "2026-09",
                        NOW.minusSeconds(7_200));
        paused.pick("node-1", NOW.minusSeconds(3_600));
        paused.started(78L, NOW.minusSeconds(3_600));
        paused.finished(JobStatus.PAUSED, 1, 1, 0, "s", null, NOW.minusSeconds(60));
        paused.abandon(NOW);
        assertThat(paused.getFinishedAt()).isEqualTo(NOW.minusSeconds(60));
    }

    @Test
    void SRS_15_25_aHaltIsEngagedAskedToBeReleasedRefusedOrReleased() {
        BatchHalt halt = BatchHalt.engage("admin@x", "Wrong rates", NOW);
        assertThat(halt.isEngaged()).isTrue();
        assertThat(halt.getHaltedBy()).isEqualTo("admin@x");
        assertThat(halt.getHaltReason()).isEqualTo("Wrong rates");
        assertThat(halt.getHaltedAt()).isEqualTo(NOW);

        halt.releaseRequested(66L, "admin@x", "Fixed");
        assertThat(halt.getReleaseApprovalId()).isEqualTo(66L);
        assertThat(halt.getReleaseRequestedBy()).isEqualTo("admin@x");
        assertThat(halt.getReleaseReason()).isEqualTo("Fixed");
        assertThat(halt.isEngaged()).isTrue();

        halt.releaseRefused();
        assertThat(halt.getReleaseApprovalId()).isNull();
        assertThat(halt.getReleaseRequestedBy()).isNull();
        assertThat(halt.getReleaseReason()).isNull();
        assertThat(halt.isEngaged()).isTrue();

        halt.releaseRequested(67L, "admin@x", "Fixed and checked");
        halt.released("checker@x", NOW.plusSeconds(60));
        assertThat(halt.isEngaged()).isFalse();
        assertThat(halt.getReleasedBy()).isEqualTo("checker@x");
        assertThat(halt.getReleasedAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(halt.getId()).isNull();
    }
}
