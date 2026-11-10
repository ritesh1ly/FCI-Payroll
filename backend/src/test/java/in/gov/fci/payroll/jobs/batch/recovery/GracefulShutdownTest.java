package in.gov.fci.payroll.jobs.batch.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.jobs.batch.launch.JobLaunchService;
import in.gov.fci.payroll.jobs.batch.support.BatchInstance;
import in.gov.fci.payroll.jobs.batch.support.JobStopper;
import in.gov.fci.payroll.jobs.business.model.BatchJobRequest;
import in.gov.fci.payroll.jobs.business.model.JobStatus;
import in.gov.fci.payroll.jobs.business.model.JobType;
import in.gov.fci.payroll.jobs.config.BatchProperties;
import in.gov.fci.payroll.jobs.orchestration.JobJournal;
import in.gov.fci.payroll.jobs.persistence.JobRepositories;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A clean stop of a batch node (SRS-15-25, SRS-EH-6: a run keeps its committed work and resumes the
 * rest). The node stops taking work, asks each job it runs to stop at the next chunk with the
 * intent PAUSE, so the request ends PAUSED and can be resumed, and waits for them up to the
 * shutdown timeout; a job it cannot stop, or one still running at the deadline, is left to crash
 * recovery on the next start.
 */
class GracefulShutdownTest {

    private static final Instant NOW = Instant.parse("2026-10-23T06:00:00Z");

    private final JobLaunchService launcher = mock(JobLaunchService.class);
    private final JobRepositories.JobRequests requests = mock(JobRepositories.JobRequests.class);
    private final JobStopper stopper = mock(JobStopper.class);
    private final JobJournal journal = mock(JobJournal.class);
    private final BatchInstance instance = mock(BatchInstance.class);
    private final BatchProperties properties = mock(BatchProperties.class);

    private final GracefulShutdown shutdown =
            new GracefulShutdown(
                    launcher,
                    requests,
                    stopper,
                    journal,
                    instance,
                    properties,
                    new TransactionTemplate(mock(PlatformTransactionManager.class)));

    private BatchJobRequest request(long id, boolean running, long execution) {
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
                        NOW);
        ReflectionTestUtils.setField(r, "id", id);
        if (running) {
            r.pick("node-1", NOW);
            r.started(execution, NOW);
        }
        when(requests.findById(id)).thenReturn(Optional.of(r));
        return r;
    }

    @Test
    void SRS_15_25_aNodeStartsAcceptingWorkAndStopsFirstAmongTheBatchComponents() {
        assertThat(shutdown.isAccepting()).isFalse();
        assertThat(shutdown.isRunning()).isFalse();
        shutdown.start();
        assertThat(shutdown.isAccepting()).isTrue();
        assertThat(shutdown.isRunning()).isTrue();
        assertThat(shutdown.getPhase())
                .as("before the executors, which wait for the paused jobs")
                .isGreaterThan(GracefulShutdown.EXECUTOR_PHASE);
    }

    @Test
    void SRS_15_25_eachRunningJobIsPausedAtTheNextChunkAndTheNodeTakesNoNewWork() {
        shutdown.start();
        when(instance.id()).thenReturn("node-1");
        when(properties.shutdownTimeout()).thenReturn(Duration.ofSeconds(5));
        BatchJobRequest running = request(1L, true, 71L);
        BatchJobRequest queued = request(2L, false, 0L);
        BatchJobRequest finished = request(3L, true, 73L);
        when(stopper.stop(71L)).thenReturn(true);
        // The node's jobs, then none once they have paused.
        when(launcher.running()).thenReturn(Set.of(1L, 2L, 3L)).thenReturn(Set.of());
        // Request 3's execution ended meanwhile: it cannot be stopped.
        when(stopper.stop(73L)).thenReturn(false);

        shutdown.stop();

        assertThat(shutdown.isAccepting()).isFalse();
        assertThat(running.getStopIntent()).isEqualTo("PAUSE");
        verify(journal)
                .record(
                        eq(1L),
                        eq(JobJournal.Event.CONTROL_APPLIED),
                        eq("node-1"),
                        eq(JobStatus.RUNNING),
                        any(),
                        eq(71L),
                        eq("PAUSE on node shutdown"));
        assertThat(queued.getStopIntent()).as("not running: nothing to pause").isNull();
        verify(journal, never()).record(eq(3L), any(), any(), any(), any(), anyLong(), any());
        verify(requests, never()).save(queued);
        verify(stopper, never()).stop(0L);
    }

    @Test
    void SRS_15_25_aJobStillRunningAtTheDeadlineIsLeftToCrashRecovery() {
        shutdown.start();
        when(properties.shutdownTimeout()).thenReturn(Duration.ofMillis(200));
        when(launcher.running()).thenReturn(Set.of(9L));

        long started = System.nanoTime();
        shutdown.stop();

        assertThat(Duration.ofNanos(System.nanoTime() - started))
                .as("waits about the timeout, then gives up")
                .isGreaterThanOrEqualTo(Duration.ofMillis(200));
        verify(stopper, never()).stop(any());
    }

    @Test
    void SRS_15_25_anInterruptedWaitEndsAtOnceAndKeepsTheInterrupt() {
        shutdown.start();
        when(properties.shutdownTimeout()).thenReturn(Duration.ofSeconds(30));
        when(launcher.running()).thenReturn(Set.of(9L));

        Thread.currentThread().interrupt();
        long started = System.nanoTime();
        shutdown.stop();

        assertThat(Thread.interrupted()).as("the interrupt is kept for the caller").isTrue();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
    }
}
