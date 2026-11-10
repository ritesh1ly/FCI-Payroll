package in.gov.fci.payroll.jobs.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.jobs.business.model.BatchJobControl;
import in.gov.fci.payroll.jobs.business.model.BatchJobRequest;
import in.gov.fci.payroll.jobs.business.model.ControlCommand;
import in.gov.fci.payroll.jobs.business.model.JobStatus;
import in.gov.fci.payroll.jobs.business.model.JobType;
import in.gov.fci.payroll.jobs.persistence.JobRepositories;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Plan 05j step j2 (SRS-G-1, SRS-15-27): the checker's approval of an abandon queues the ABANDON
 * command for the batch node; a rejection changes nothing but the job's journal.
 */
class JobAbandonEffectTest {

    private static final Instant NOW = Instant.parse("2026-10-24T06:00:00Z");

    private final JobRepositories.JobRequests requests = mock(JobRepositories.JobRequests.class);
    private final JobRepositories.Controls controls = mock(JobRepositories.Controls.class);
    private final JobJournal journal = mock(JobJournal.class);
    private final JobAbandonEffect effect = new JobAbandonEffect(requests, controls, journal);

    private BatchJobRequest failed() {
        BatchJobRequest r =
                BatchJobRequest.submit(
                        JobType.PAYROLL_DRY_RUN,
                        "{}",
                        "hash",
                        5,
                        BatchJobRequest.Source.UI,
                        2L,
                        "maker@x",
                        10L,
                        "2026-09",
                        NOW.minusSeconds(7_200));
        ReflectionTestUtils.setField(r, "id", 7L);
        r.pick("node-1", NOW.minusSeconds(3_600));
        r.started(77L, NOW.minusSeconds(3_600));
        ReflectionTestUtils.setField(r, "status", JobStatus.FAILED);
        when(requests.findById(7L)).thenReturn(Optional.of(r));
        return r;
    }

    private static ApprovalDecided decision(ApprovalStatus outcome, String reason) {
        return new ApprovalDecided(
                55L,
                "C-13",
                JobRequestService.ABANDON_ENTITY,
                "7",
                outcome,
                reason,
                "{}",
                "checker@x",
                NOW);
    }

    @Test
    void SRS_G_1_anApprovedAbandonQueuesTheCommandInTheCheckersName() {
        BatchJobRequest failed = failed();

        effect.on(decision(ApprovalStatus.APPROVED, null));

        ArgumentCaptor<BatchJobControl> saved = ArgumentCaptor.forClass(BatchJobControl.class);
        verify(controls).save(saved.capture());
        BatchJobControl command = saved.getValue();
        assertThat(command.getJobRequestId()).isEqualTo(7L);
        assertThat(command.getCommand()).isEqualTo(ControlCommand.ABANDON);
        assertThat(command.getStatus()).isEqualTo(BatchJobControl.Status.PENDING);
        assertThat(command.getRequestedByLogin()).isEqualTo("checker@x");
        assertThat(command.getRequestedAt()).isEqualTo(NOW);
        assertThat(command.getReason()).isEqualTo("Approval 55");
        verify(journal)
                .record(
                        7L,
                        JobJournal.Event.CONTROL_REQUESTED,
                        "checker@x",
                        JobStatus.FAILED,
                        null,
                        77L,
                        "ABANDON approved: Approval 55");
        // The batch node, not the decision, ends the job.
        assertThat(failed.getStatus()).isEqualTo(JobStatus.FAILED);
    }

    @Test
    void SRS_G_1_aRejectedAbandonLeavesTheJobAsItWasAndSaysSo() {
        BatchJobRequest failed = failed();

        effect.on(decision(ApprovalStatus.REJECTED, "Retry after the fix"));

        verify(controls, never()).save(any());
        verify(journal)
                .record(
                        7L,
                        JobJournal.Event.ABANDON_REFUSED,
                        "checker@x",
                        JobStatus.FAILED,
                        null,
                        77L,
                        "REJECTED: Approval 55: Retry after the fix");
        assertThat(failed.getStatus()).isEqualTo(JobStatus.FAILED);
    }

    @Test
    void SRS_G_1_otherDecisionsAndUnknownRequestsAreIgnored() {
        effect.on(
                new ApprovalDecided(
                        1L,
                        "C-13",
                        "batch_job_request",
                        "7",
                        ApprovalStatus.APPROVED,
                        null,
                        "{}",
                        "checker@x",
                        NOW));
        verifyNoInteractions(requests, controls, journal);

        when(requests.findById(7L)).thenReturn(Optional.empty());
        effect.on(decision(ApprovalStatus.APPROVED, null));
        verify(controls, never()).save(any());
        verify(journal, never()).record(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void SRS_G_1_theCommandsReasonFitsItsColumn() {
        failed();
        effect.on(decision(ApprovalStatus.APPROVED, "x".repeat(600)));
        ArgumentCaptor<BatchJobControl> saved = ArgumentCaptor.forClass(BatchJobControl.class);
        verify(controls).save(saved.capture());
        assertThat(saved.getValue().getReason()).hasSize(500).startsWith("Approval 55: xxx");
        verify(journal)
                .record(
                        eq(7L),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        eq(
                                ("ABANDON approved: Approval 55: " + "x".repeat(600))
                                        .substring(0, 500)));
    }
}
