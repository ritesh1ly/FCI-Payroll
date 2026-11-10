package in.gov.fci.payroll.jobs.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.jobs.business.model.BatchJobRequest;
import in.gov.fci.payroll.jobs.business.model.JobStatus;
import in.gov.fci.payroll.jobs.business.model.JobType;
import in.gov.fci.payroll.jobs.persistence.JobRepositories;
import in.gov.fci.payroll.platform.approval.ApprovalDecided;
import in.gov.fci.payroll.platform.approval.ApprovalStatus;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * C-13 maker-checker (SRS-15-26, R-MC): an approval puts the request in the queue; a rejection or a
 * return ends it with the Checker's reason, or the outcome alone when none was given; a request no
 * longer awaiting approval, or another entity's decision, changes nothing.
 */
class JobApprovalEffectTest {

    private static final Instant NOW = Instant.parse("2026-10-22T06:00:00Z");

    private final JobRepositories.JobRequests requests = mock(JobRepositories.JobRequests.class);
    private final JobJournal journal = mock(JobJournal.class);
    private final JobApprovalEffect effect = new JobApprovalEffect(requests, journal);

    private BatchJobRequest awaiting(long id) {
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
                        NOW.minusSeconds(600));
        ReflectionTestUtils.setField(r, "id", id);
        r.awaitApproval(60L + id);
        when(requests.findById(id)).thenReturn(Optional.of(r));
        return r;
    }

    private static ApprovalDecided decided(
            String entity, long id, ApprovalStatus outcome, String reason) {
        return new ApprovalDecided(
                70L,
                "C-13",
                entity,
                String.valueOf(id),
                outcome,
                reason,
                "{}",
                "checker@x",
                NOW,
                null);
    }

    @Test
    void SRS_15_26_anApprovalPutsTheRequestInTheQueue() {
        BatchJobRequest r = awaiting(1L);
        effect.on(decided(JobApprovalEffect.ENTITY, 1L, ApprovalStatus.APPROVED, null));
        assertThat(r.getStatus()).isEqualTo(JobStatus.PENDING);
        verify(journal)
                .record(
                        1L,
                        JobJournal.Event.APPROVED,
                        "checker@x",
                        JobStatus.AWAITING_APPROVAL,
                        JobStatus.PENDING,
                        null,
                        "Approval 70");
        verify(requests).save(r);
    }

    @Test
    void SRS_15_26_aRejectionOrAReturnEndsTheRequestWithTheReasonOrTheOutcomeAlone() {
        BatchJobRequest rejected = awaiting(2L);
        effect.on(decided(JobApprovalEffect.ENTITY, 2L, ApprovalStatus.REJECTED, "Wrong period"));
        assertThat(rejected.getStatus()).isEqualTo(JobStatus.REJECTED);
        assertThat(rejected.getErrorMessage()).isEqualTo("REJECTED: Wrong period");

        BatchJobRequest returned = awaiting(3L);
        effect.on(decided(JobApprovalEffect.ENTITY, 3L, ApprovalStatus.RETURNED, null));
        assertThat(returned.getStatus()).isEqualTo(JobStatus.REJECTED);
        assertThat(returned.getErrorMessage()).isEqualTo("RETURNED");
        assertThat(returned.getFinishedAt()).isEqualTo(NOW);
        verify(journal)
                .record(
                        3L,
                        JobJournal.Event.REJECTED,
                        "checker@x",
                        JobStatus.AWAITING_APPROVAL,
                        JobStatus.REJECTED,
                        null,
                        "RETURNED");
    }

    @Test
    void SRS_15_26_aRequestNoLongerAwaitingApprovalOrAnotherEntityChangesNothing() {
        BatchJobRequest r = awaiting(4L);
        r.approved();
        effect.on(decided(JobApprovalEffect.ENTITY, 4L, ApprovalStatus.REJECTED, "late"));
        assertThat(r.getStatus()).isEqualTo(JobStatus.PENDING);
        when(requests.findById(5L)).thenReturn(Optional.empty());
        effect.on(decided(JobApprovalEffect.ENTITY, 5L, ApprovalStatus.APPROVED, null));
        effect.on(decided("batch_job_control", 4L, ApprovalStatus.APPROVED, null));
        verify(requests, never()).save(any());
        verify(journal, never()).record(anyLong(), any(), any(), any(), any(), any(), any());
        verifyNoInteractions(journal);
    }
}
