package in.gov.fci.payroll.jobs.batch.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.jobs.batch.support.JobAbandoner;
import in.gov.fci.payroll.jobs.business.model.BatchJobRequest;
import in.gov.fci.payroll.jobs.business.model.JobStatus;
import in.gov.fci.payroll.jobs.business.model.JobType;
import in.gov.fci.payroll.jobs.config.BatchProperties;
import in.gov.fci.payroll.jobs.persistence.JobRepositories;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * SRS-15-27 / BR-CYC-014: a failed or stopped job left beyond the restart window is abandoned by
 * the system; one whose state moved meanwhile, or that another node takes first, is skipped.
 */
class AbandonSweepTest {

    private static final Instant NOW = Instant.parse("2026-10-24T06:00:00Z");

    private final JobRepositories.JobRequests requests = mock(JobRepositories.JobRequests.class);
    private final JobAbandoner abandoner = mock(JobAbandoner.class);

    /**
     * The configured period of SRS-15-27, not a business value (CLR-132, BMAD TR-079): any value
     * the setting holds drives the sweep and its message; 5 h is a test value, never the 72 h
     * default.
     */
    private static final Duration WINDOW = Duration.ofHours(5);

    private final BatchProperties properties = mock(BatchProperties.class);
    private final AbandonSweep sweep =
            new AbandonSweep(
                    requests,
                    abandoner,
                    properties,
                    new TransactionTemplate(mock(PlatformTransactionManager.class)),
                    Clock.fixed(NOW, ZoneOffset.UTC));

    private static BatchJobRequest ended(long id, JobStatus status) {
        BatchJobRequest r =
                BatchJobRequest.submit(
                        JobType.PAYROLL_DRY_RUN,
                        "{}",
                        "hash-" + id,
                        5,
                        BatchJobRequest.Source.UI,
                        2L,
                        "maker@x",
                        10L,
                        "2026-09",
                        NOW.minus(Duration.ofHours(100)));
        ReflectionTestUtils.setField(r, "id", id);
        ReflectionTestUtils.setField(r, "status", status);
        ReflectionTestUtils.setField(r, "finishedAt", NOW.minus(Duration.ofHours(90)));
        return r;
    }

    @Test
    void BR_CYC_014_jobsPastTheRestartWindowAreAbandonedTheRestSkipped() {
        when(properties.restartWindow()).thenReturn(WINDOW);
        BatchJobRequest expired = ended(1, JobStatus.FAILED);
        BatchJobRequest moved = ended(2, JobStatus.STOPPED);
        BatchJobRequest taken = ended(3, JobStatus.FAILED);
        when(requests.findTop50ByStatusInAndFinishedAtBeforeOrderByFinishedAt(
                        eq(EnumSet.of(JobStatus.FAILED, JobStatus.STOPPED)), eq(NOW.minus(WINDOW))))
                .thenReturn(List.of(expired, moved, taken));
        when(requests.findById(1L)).thenReturn(Optional.of(expired));
        // Restarted by an operator between the query and the re-read: no longer STOPPED.
        BatchJobRequest restarted = ended(2, JobStatus.PICKED);
        when(requests.findById(2L)).thenReturn(Optional.of(restarted));
        when(requests.findById(3L)).thenThrow(new OptimisticLockingFailureException("taken"));
        when(abandoner.abandon(
                        expired, "system.housekeeping", "Restart window of 5 h passed (SRS-15-27)"))
                .thenReturn(true);

        assertThat(sweep.sweep()).isEqualTo(1);

        verify(abandoner)
                .abandon(
                        expired, "system.housekeeping", "Restart window of 5 h passed (SRS-15-27)");
        verify(abandoner, never()).abandon(eq(restarted), any(), any());
        verify(abandoner, never()).abandon(eq(moved), any(), any());
    }

    @Test
    void BR_CYC_014_nothingExpiredNothingAbandoned() {
        when(properties.restartWindow()).thenReturn(WINDOW);
        when(requests.findTop50ByStatusInAndFinishedAtBeforeOrderByFinishedAt(any(), any()))
                .thenReturn(List.of());
        assertThat(sweep.sweep()).isZero();
        verify(abandoner, never()).abandon(any(), any(), any());
    }

    @Test
    void BR_CYC_014_aJobWhoseExecutionTurnsOutRunningIsNotCounted() {
        when(properties.restartWindow()).thenReturn(WINDOW);
        BatchJobRequest expired = ended(1, JobStatus.FAILED);
        when(requests.findTop50ByStatusInAndFinishedAtBeforeOrderByFinishedAt(any(), any()))
                .thenReturn(List.of(expired));
        when(requests.findById(1L)).thenReturn(Optional.of(expired));
        when(abandoner.abandon(eq(expired), any(), any())).thenReturn(false);
        assertThat(sweep.sweep()).isZero();
    }
}
