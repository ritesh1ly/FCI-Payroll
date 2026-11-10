package in.gov.fci.payroll.jobs.batch.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.jobs.batch.launch.JobLaunchService;
import in.gov.fci.payroll.jobs.batch.lock.JobLockService;
import in.gov.fci.payroll.jobs.batch.support.BatchHistory;
import in.gov.fci.payroll.jobs.batch.support.BatchInstance;
import in.gov.fci.payroll.jobs.business.model.BatchJobControl;
import in.gov.fci.payroll.jobs.business.model.BatchJobRequest;
import in.gov.fci.payroll.jobs.business.model.ControlCommand;
import in.gov.fci.payroll.jobs.business.model.JobStatus;
import in.gov.fci.payroll.jobs.business.model.JobType;
import in.gov.fci.payroll.jobs.config.BatchProperties;
import in.gov.fci.payroll.jobs.orchestration.JobJournal;
import in.gov.fci.payroll.jobs.orchestration.JobRequestService;
import in.gov.fci.payroll.jobs.persistence.JobRepositories;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Crash recovery (SRS-15-25: committed work is kept and the rest restarted). A request is abandoned
 * only when no node runs it here, it was picked longer ago than the stale limit and its lock has no
 * heartbeat. Its execution and unfinished steps are failed so Spring Batch allows a restart from
 * the last commit; the request is FAILED and audited RECOVERED; with automatic restart on, a
 * RESTART is queued once, within the restart window.
 */
class CrashRecoveryServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-23T06:00:00Z");

    private final JobRepositories.JobRequests requests = mock(JobRepositories.JobRequests.class);
    private final JobRepositories.Controls controls = mock(JobRepositories.Controls.class);
    private final JobLockService locks = mock(JobLockService.class);
    private final JobLaunchService launcher = mock(JobLaunchService.class);
    private final BatchHistory history = mock(BatchHistory.class);
    private final JobRepository repository = mock(JobRepository.class);
    private final JobRequestService service = mock(JobRequestService.class);
    private final JobJournal journal = mock(JobJournal.class);
    private final BatchInstance instance = mock(BatchInstance.class);
    private final BatchProperties properties = mock(BatchProperties.class);
    private final BatchProperties.Recovery recovery = mock(BatchProperties.Recovery.class);

    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

    private final CrashRecoveryService crashRecovery =
            new CrashRecoveryService(
                    requests,
                    controls,
                    locks,
                    launcher,
                    history,
                    repository,
                    service,
                    journal,
                    instance,
                    properties,
                    new TransactionTemplate(mock(PlatformTransactionManager.class)),
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    events);

    private final List<BatchJobRequest> active = new ArrayList<>();

    @BeforeEach
    void wire() {
        BatchProperties.Lock lock = mock(BatchProperties.Lock.class);
        when(lock.staleAfter()).thenReturn(Duration.ofMinutes(3));
        when(properties.lock()).thenReturn(lock);
        when(properties.recovery()).thenReturn(recovery);
        when(instance.id()).thenReturn("node-2");
        when(requests.findByStatusIn(JobStatus.ACTIVE)).thenReturn(active);
        when(launcher.running()).thenReturn(Set.of());
    }

    /** A request picked {@code minutesAgo} by {@code node}, running execution {@code execution}. */
    private BatchJobRequest request(long id, int minutesAgo, String node, Long execution) {
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
                        NOW.minusSeconds(3600));
        ReflectionTestUtils.setField(r, "id", id);
        r.pick(node == null ? "node-1" : node, NOW.minusSeconds(60L * minutesAgo));
        if (node == null) {
            ReflectionTestUtils.setField(r, "claimedBy", null);
        }
        if (execution != null) {
            r.started(execution, NOW.minusSeconds(60L * minutesAgo));
        }
        when(requests.findById(id)).thenReturn(Optional.of(r));
        active.add(r);
        return r;
    }

    @Test
    void SRS_15_25_onlyARequestNoNodeRunsPickedLongAgoAndWithoutHeartbeatIsAbandoned() {
        BatchJobRequest mine = request(1L, 30, "node-2", 71L);
        BatchJobRequest fresh = request(2L, 1, "node-1", 72L);
        BatchJobRequest alive = request(3L, 30, "node-1", 73L);
        BatchJobRequest dead = request(4L, 30, "node-1", null);
        when(launcher.running()).thenReturn(Set.of(1L));
        when(locks.isStale(3L)).thenReturn(false);
        when(locks.isStale(4L)).thenReturn(true);
        when(locks.isStale(2L)).thenReturn(true);
        // A row left without its pick time counts as picked long ago.
        BatchJobRequest legacy = request(12L, 30, "node-1", null);
        ReflectionTestUtils.setField(legacy, "pickedAt", null);
        when(locks.isStale(12L)).thenReturn(true);

        assertThat(crashRecovery.recoverAbandoned()).isEqualTo(2);
        assertThat(legacy.getStatus()).isEqualTo(JobStatus.FAILED);

        assertThat(mine.getStatus()).isEqualTo(JobStatus.RUNNING);
        assertThat(fresh.getStatus()).as("picked a minute ago").isEqualTo(JobStatus.RUNNING);
        assertThat(alive.getStatus()).as("its lock still beats").isEqualTo(JobStatus.RUNNING);
        assertThat(dead.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(dead.getErrorMessage())
                .isEqualTo("Abnormal shutdown of node-1 detected by node-2");
        verify(locks).release(4L);
        verify(locks, never()).release(1L);
        verify(journal)
                .record(
                        eq(4L),
                        eq(JobJournal.Event.RECOVERED),
                        eq(CrashRecoveryService.ACTOR),
                        eq(JobStatus.PICKED),
                        eq(JobStatus.FAILED),
                        any(),
                        eq("Abnormal shutdown of node-1 detected by node-2"));
        verify(history, never()).getJobExecution(anyLong());
    }

    @Test
    void SRS_15_25_theDeadNodesExecutionAndItsUnfinishedStepsAreFailedSoItCanRestart() {
        BatchJobRequest dead = request(5L, 30, null, 75L);
        when(locks.isStale(5L)).thenReturn(true);
        JobExecution execution =
                new JobExecution(75L, new JobInstance(9L, "payrollDryRun"), new JobParameters());
        execution.setStatus(BatchStatus.STARTED);
        StepExecution unfinished = new StepExecution(1L, "worker:partition0", execution);
        unfinished.setStatus(BatchStatus.STARTED);
        StepExecution done = new StepExecution(2L, "worker:partition1", execution);
        done.setStatus(BatchStatus.COMPLETED);
        execution.addStepExecutions(List.of(unfinished, done));
        when(history.getJobExecution(75L)).thenReturn(execution);

        crashRecovery.recoverAbandoned();

        String message = "Abnormal shutdown of a batch node detected by node-2";
        assertThat(dead.getErrorMessage()).isEqualTo(message);
        assertThat(unfinished.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(unfinished.getExitStatus().getExitDescription()).contains(message);
        assertThat(unfinished.getEndTime()).isNotNull();
        assertThat(done.getStatus()).as("committed work stays").isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        verify(repository).update(unfinished);
        verify(repository, never()).update(done);
        verify(repository).update(execution);
    }

    @Test
    void SRS_15_25_anExecutionThatAlreadyEndedOrIsGoneIsLeftAsItIs() {
        request(6L, 30, "node-1", 76L);
        request(7L, 30, "node-1", 77L);
        when(locks.isStale(any())).thenReturn(true);
        JobExecution ended =
                new JobExecution(76L, new JobInstance(9L, "payrollDryRun"), new JobParameters());
        ended.setStatus(BatchStatus.FAILED);
        when(history.getJobExecution(76L)).thenReturn(ended);
        when(history.getJobExecution(77L)).thenReturn(null);

        assertThat(crashRecovery.recoverAbandoned()).isEqualTo(2);

        verify(repository, never()).update(any(JobExecution.class));
        verify(repository, never()).update(any(StepExecution.class));
    }

    @Test
    void SRS_15_25_withAutomaticRestartARestartIsQueuedOnceWithinTheWindow() {
        when(recovery.autoRestart()).thenReturn(true);
        BatchJobRequest restartable = request(8L, 30, "node-1", 78L);
        BatchJobRequest alreadyQueued = request(9L, 30, "node-1", 79L);
        BatchJobRequest tooOld = request(10L, 30, "node-1", 80L);
        when(locks.isStale(any())).thenReturn(true);
        when(service.withinRestartWindow(restartable)).thenReturn(true);
        when(service.withinRestartWindow(alreadyQueued)).thenReturn(true);
        when(service.withinRestartWindow(tooOld)).thenReturn(false);
        when(controls.existsByJobRequestIdAndStatus(9L, BatchJobControl.Status.PENDING))
                .thenReturn(true);

        crashRecovery.recoverAbandoned();

        ArgumentCaptor<BatchJobControl> queued = ArgumentCaptor.forClass(BatchJobControl.class);
        verify(controls).save(queued.capture());
        assertThat(queued.getValue().getJobRequestId()).isEqualTo(8L);
        assertThat(queued.getValue().getCommand()).isEqualTo(ControlCommand.RESTART);
        assertThat(queued.getValue().getRequestedByLogin()).isEqualTo(CrashRecoveryService.ACTOR);
        verify(journal)
                .record(
                        eq(8L),
                        eq(JobJournal.Event.CONTROL_REQUESTED),
                        eq(CrashRecoveryService.ACTOR),
                        eq(JobStatus.FAILED),
                        any(),
                        eq(78L),
                        eq("RESTART (auto)"));
    }

    @Test
    void SRS_15_25_withoutAutomaticRestartARecoveredRequestWaitsForAManualRestart() {
        when(recovery.autoRestart()).thenReturn(false);
        request(11L, 30, "node-1", 81L);
        when(locks.isStale(11L)).thenReturn(true);

        crashRecovery.onStartup();

        verify(controls, never()).save(any());
        verify(service, never()).withinRestartWindow(any());
        verify(journal, times(1))
                .record(eq(11L), eq(JobJournal.Event.RECOVERED), any(), any(), any(), any(), any());
    }

    @Test
    void SRS_15_25_aJobThatEndedAfterTheSweepListedItKeepsItsRealOutcome() {
        BatchJobRequest ended = request(21L, 30, "node-2", 81L);
        when(locks.isStale(21L)).thenReturn(true);
        // Listed while RUNNING; COMPLETED by the time recovery reads it (campaign R5).
        BatchJobRequest completed =
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
                        NOW.minusSeconds(3600));
        ReflectionTestUtils.setField(completed, "id", 21L);
        ReflectionTestUtils.setField(completed, "status", JobStatus.COMPLETED);
        when(requests.findById(21L)).thenReturn(Optional.of(completed));

        assertThat(crashRecovery.recoverAbandoned()).isZero();
        assertThat(completed.getStatus()).isEqualTo(JobStatus.COMPLETED);
        assertThat(ended.getStatus()).isEqualTo(JobStatus.RUNNING);
        verify(requests, never()).save(any());
        verify(locks, never()).release(21L);
    }

    @Test
    void SRS_15_25_aJobThisNodeStartedAfterTheSweepListedItIsNotRecovered() {
        BatchJobRequest listed = request(22L, 30, "node-2", 82L);
        when(locks.isStale(22L)).thenReturn(true);
        // Not running when listed; running here when recovery reads it.
        Set<Long> none = Set.of();
        when(launcher.running()).thenReturn(none).thenReturn(Set.of(22L));

        assertThat(crashRecovery.recoverAbandoned()).isZero();
        assertThat(listed.getStatus()).isEqualTo(JobStatus.RUNNING);
        verify(requests, never()).save(any());
    }

    @Test
    void SRS_NF_2_theRequesterOfARecoveredJobIsToldItFailed() {
        request(31L, 30, "node-1", 91L);
        when(locks.isStale(31L)).thenReturn(true);
        assertThat(crashRecovery.recoverAbandoned()).isEqualTo(1);
        ArgumentCaptor<in.gov.fci.payroll.jobs.api.JobEnded> told =
                ArgumentCaptor.forClass(in.gov.fci.payroll.jobs.api.JobEnded.class);
        verify(events).publishEvent(told.capture());
        assertThat(told.getValue().status()).isEqualTo("FAILED");
        assertThat(told.getValue().requestedBy()).isEqualTo(2L);
        assertThat(told.getValue().error()).startsWith("Abnormal shutdown of node-1");
    }
}
