package in.gov.fci.payroll.jobs.business.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * C-13 job request life cycle (batch_job_request; ADR job queue): maker-checker gate, claim by a
 * batch node, attempts, stop, finish, crash recovery, with the illegal transitions refused.
 */
class BatchJobRequestTest {

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
    void C_13_aSubmittedRequestIsPendingWithItsParameters() {
        BatchJobRequest r = submitted();
        assertThat(r.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(r.getJobType()).isEqualTo(JobType.PAYROLL_DRY_RUN);
        assertThat(r.getParams()).contains("2026-10");
        assertThat(r.getParamHash()).isEqualTo("hash");
        assertThat(r.getPriority()).isEqualTo(5);
        assertThat(r.getSource()).isEqualTo(BatchJobRequest.Source.UI);
        assertThat(r.getRequestedBy()).isEqualTo(7L);
        assertThat(r.getRequestedByLogin()).isEqualTo("maker.delhi");
        assertThat(r.getLocationId()).isEqualTo(42L);
        assertThat(r.getPayPeriod()).isEqualTo("2026-10");
        assertThat(r.getRequestedAt()).isEqualTo(T0);
        assertThat(r.getAttemptNo()).isZero();
        assertThat(r.duration(T0)).as("not started").isNull();
    }

    @Test
    void C_13_aRequestAwaitsApprovalOnceThenIsApprovedOrRejected() {
        BatchJobRequest r = submitted();
        r.awaitApproval(99L);
        assertThat(r.getStatus()).isEqualTo(JobStatus.AWAITING_APPROVAL);
        assertThat(r.getApprovalId()).isEqualTo(99L);
        assertThatThrownBy(() -> r.awaitApproval(100L)).isInstanceOf(IllegalStateException.class);
        r.approved();
        assertThat(r.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThatThrownBy(r::approved).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> r.rejected("x", T0)).isInstanceOf(IllegalStateException.class);
        // Already linked to an approval: cannot await another even when pending again.
        assertThatThrownBy(() -> r.awaitApproval(101L)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void C_13_aRejectedRequestEndsWithTheCheckersReasonCutTo1000() {
        BatchJobRequest r = submitted();
        r.awaitApproval(1L);
        r.rejected("x".repeat(1200), T0.plusSeconds(60));
        assertThat(r.getStatus()).isEqualTo(JobStatus.REJECTED);
        assertThat(r.getErrorMessage()).hasSize(1000);
        assertThat(r.getFinishedAt()).isEqualTo(T0.plusSeconds(60));
        BatchJobRequest exact = submitted();
        exact.awaitApproval(2L);
        exact.rejected("y".repeat(1000), T0);
        assertThat(exact.getErrorMessage()).hasSize(1000);
        BatchJobRequest none = submitted();
        none.awaitApproval(3L);
        none.rejected(null, T0);
        assertThat(none.getErrorMessage()).isNull();
    }

    @ParameterizedTest
    @EnumSource(
            value = JobStatus.class,
            names = {"PENDING", "PAUSED", "FAILED", "STOPPED"})
    void C_13_aWaitingPausedFailedOrStoppedRequestCanBePicked(JobStatus from) {
        BatchJobRequest r = submitted();
        r.finished(from, 0, 0, 0, null, "old error", T0);
        r.requestStop("PAUSE");
        r.pick("batch-1", T0.plusSeconds(5));
        assertThat(r.getStatus()).isEqualTo(JobStatus.PICKED);
        assertThat(r.getClaimedBy()).isEqualTo("batch-1");
        assertThat(r.getPickedAt()).isEqualTo(T0.plusSeconds(5));
        assertThat(r.getFinishedAt()).isNull();
        assertThat(r.getErrorMessage()).isNull();
        assertThat(r.getStopIntent()).isNull();
    }

    @ParameterizedTest
    @EnumSource(
            value = JobStatus.class,
            names = {"AWAITING_APPROVAL", "REJECTED", "PICKED", "RUNNING", "COMPLETED"})
    void C_13_anyOtherRequestCannotBePicked(JobStatus from) {
        BatchJobRequest r = submitted();
        r.finished(from, 0, 0, 0, null, null, T0);
        assertThatThrownBy(() -> r.pick("batch-1", T0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(from.name());
    }

    @Test
    void C_13_aLostClaimIsReleasedToItsPreviousStatus() {
        BatchJobRequest r = submitted();
        r.pick("batch-1", T0);
        r.unpick(JobStatus.PENDING);
        assertThat(r.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(r.getClaimedBy()).isNull();
    }

    @Test
    void C_13_eachStartCountsAnAttemptAndKeepsTheFirstStartTime() {
        BatchJobRequest r = submitted();
        r.pick("batch-1", T0);
        r.started(11L, T0.plusSeconds(1));
        assertThat(r.getStatus()).isEqualTo(JobStatus.RUNNING);
        assertThat(r.getJobExecutionId()).isEqualTo(11L);
        assertThat(r.getAttemptNo()).isEqualTo(1);
        assertThat(r.duration(T0.plusSeconds(31))).isEqualTo(Duration.ofSeconds(30));
        r.started(12L, T0.plusSeconds(100));
        assertThat(r.getAttemptNo()).isEqualTo(2);
        assertThat(r.getJobExecutionId()).isEqualTo(12L);
        assertThat(r.getStartedAt()).isEqualTo(T0.plusSeconds(1));
    }

    @Test
    void C_13_aFinishedRequestRecordsCountsAndCutsSummaryAndError() {
        BatchJobRequest r = submitted();
        r.started(1L, T0);
        r.requestStop("STOP");
        assertThat(r.getStopIntent()).isEqualTo("STOP");
        r.finished(
                JobStatus.COMPLETED,
                10,
                9,
                1,
                "s".repeat(600),
                "e".repeat(1500),
                T0.plusSeconds(120));
        assertThat(r.getStatus()).isEqualTo(JobStatus.COMPLETED);
        assertThat(r.getReadCount()).isEqualTo(10);
        assertThat(r.getWriteCount()).isEqualTo(9);
        assertThat(r.getSkipCount()).isEqualTo(1);
        assertThat(r.getResultSummary()).hasSize(500);
        assertThat(r.getErrorMessage()).hasSize(1000);
        assertThat(r.getStopIntent()).isNull();
        // The duration runs to the finish, not to "now".
        assertThat(r.duration(T0.plusSeconds(9999))).isEqualTo(Duration.ofSeconds(120));
    }

    @Test
    void C_13_cancelAndCrashRecoveryEndTheRequest() {
        BatchJobRequest cancelled = submitted();
        cancelled.cancel(T0);
        assertThat(cancelled.getStatus()).isEqualTo(JobStatus.STOPPED);
        assertThat(cancelled.getFinishedAt()).isEqualTo(T0);

        BatchJobRequest crashed = submitted();
        crashed.started(1L, T0);
        crashed.requestStop("PAUSE");
        crashed.recoverAsFailed("node died", T0.plusSeconds(3));
        assertThat(crashed.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(crashed.getErrorMessage()).isEqualTo("node died");
        assertThat(crashed.getFinishedAt()).isEqualTo(T0.plusSeconds(3));
        assertThat(crashed.getStopIntent()).isNull();
    }

    @Test
    void C_13_openAndActiveStatusesDriveTheDuplicateGuardAndOwnership() {
        assertThat(EnumSet.allOf(JobStatus.class).stream().filter(JobStatus::isOpen))
                .containsExactlyInAnyOrder(
                        JobStatus.AWAITING_APPROVAL,
                        JobStatus.PENDING,
                        JobStatus.PICKED,
                        JobStatus.RUNNING,
                        JobStatus.PAUSED);
        assertThat(JobStatus.ACTIVE).containsExactlyInAnyOrder(JobStatus.PICKED, JobStatus.RUNNING);
    }

    @Test
    void C_13_onlyUserJobsAreSubmittableAndEachNamesItsBean() {
        assertThat(JobType.PAYROLL_DRY_RUN.isSubmittable()).isTrue();
        assertThat(JobType.DA_ARREAR.isSubmittable()).isTrue();
        assertThat(JobType.INTERFACE_PULL.isSubmittable()).isFalse();
        assertThat(JobType.MONTH_END_CLOSE.isSubmittable()).isFalse();
        assertThat(JobType.PAYROLL_COMPUTATION.jobBean()).isEqualTo("payrollComputationJob");
        assertThat(JobType.of("DA_ARREAR")).contains(JobType.DA_ARREAR);
        assertThat(JobType.of("da_arrear")).isEmpty();
        assertThat(JobType.of(null)).isEmpty();
    }
}
