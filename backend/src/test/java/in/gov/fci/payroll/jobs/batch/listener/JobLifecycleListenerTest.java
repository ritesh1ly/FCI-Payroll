package in.gov.fci.payroll.jobs.batch.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.jobs.api.JobEnded;
import in.gov.fci.payroll.jobs.batch.launch.JobLaunchService;
import in.gov.fci.payroll.jobs.batch.lock.JobLockService;
import in.gov.fci.payroll.jobs.batch.support.BatchInstance;
import in.gov.fci.payroll.jobs.batch.support.BatchKeys;
import in.gov.fci.payroll.jobs.business.model.BatchJobRequest;
import in.gov.fci.payroll.jobs.business.model.JobStatus;
import in.gov.fci.payroll.jobs.business.model.JobType;
import in.gov.fci.payroll.jobs.orchestration.JobJournal;
import in.gov.fci.payroll.jobs.persistence.JobRepositories;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The request's side of a job execution (SRS-15-25; C-13): an attempt starts only for a request
 * this node picked; it ends COMPLETED, PAUSED or STOPPED by the recorded intent, or FAILED with the
 * deepest cause of the step that failed, and in every case the job lock and the job slot are
 * released and the run is counted.
 */
class JobLifecycleListenerTest {

    private static final Instant NOW = Instant.parse("2026-10-23T06:00:00Z");

    private final JobRepositories.JobRequests requests = mock(JobRepositories.JobRequests.class);
    private final JobJournal journal = mock(JobJournal.class);
    private final JobLockService locks = mock(JobLockService.class);
    private final JobLaunchService launcher = mock(JobLaunchService.class);
    private final BatchInstance instance = mock(BatchInstance.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final JobLifecycleListener listener =
            new JobLifecycleListener(
                    requests,
                    journal,
                    locks,
                    launcher,
                    instance,
                    meters,
                    new TransactionTemplate(mock(PlatformTransactionManager.class)),
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    events);

    private BatchJobRequest request;

    @BeforeEach
    void picked() {
        when(instance.id()).thenReturn("node-1");
        request =
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
        ReflectionTestUtils.setField(request, "id", 7L);
        request.pick("node-1", NOW);
        when(requests.findById(7L)).thenReturn(Optional.of(request));
    }

    private static JobExecution execution(BatchStatus status) {
        JobExecution execution =
                new JobExecution(
                        40L,
                        new JobInstance(3L, "payrollDryRun"),
                        new JobParametersBuilder()
                                .addLong(BatchKeys.REQUEST_ID, 7L)
                                .toJobParameters());
        execution.setStatus(status);
        return execution;
    }

    private double jobs(String status) {
        return meters.counter("fci.batch.jobs", "job", "payrollDryRun", "status", status).count();
    }

    @Test
    void SRS_15_25_anAttemptStartsOnlyForARequestThisNodePicked() {
        JobExecution started = execution(BatchStatus.STARTED);
        listener.beforeJob(started);
        assertThat(request.getStatus()).isEqualTo(JobStatus.RUNNING);
        assertThat(request.getJobExecutionId()).isEqualTo(40L);
        verify(journal)
                .record(
                        eq(7L),
                        eq(JobJournal.Event.STARTED),
                        eq("node-1"),
                        eq(JobStatus.PICKED),
                        eq(JobStatus.RUNNING),
                        eq(40L),
                        eq("Attempt 1 on node-1"));

        // Cancelled between the pick and the launch: the execution stops before any step.
        request.cancel(NOW);
        JobExecution late = execution(BatchStatus.STARTED);
        listener.beforeJob(late);
        assertThat(late.getStatus()).isEqualTo(BatchStatus.STOPPING);
        assertThat(request.getStatus()).isEqualTo(JobStatus.STOPPED);
    }

    @Test
    void SRS_15_25_aStoppedRunEndsPausedOrStoppedByTheIntentRecordedWithTheCommand() {
        listener.beforeJob(execution(BatchStatus.STARTED));
        request.requestStop("PAUSE");
        listener.afterJob(execution(BatchStatus.STOPPED));
        assertThat(request.getStatus()).as("resumable").isEqualTo(JobStatus.PAUSED);
        assertThat(jobs("PAUSED")).isEqualTo(1);

        request.pick("node-1", NOW);
        listener.beforeJob(execution(BatchStatus.STARTED));
        request.requestStop("STOP");
        listener.afterJob(execution(BatchStatus.STOPPING));
        assertThat(request.getStatus()).isEqualTo(JobStatus.STOPPED);
        assertThat(request.getErrorMessage()).as("a stop is no error").isNull();
        verify(journal)
                .record(
                        eq(7L),
                        eq(JobJournal.Event.STOPPED),
                        eq("node-1"),
                        eq(JobStatus.RUNNING),
                        eq(JobStatus.STOPPED),
                        eq(40L),
                        any());
        verify(locks, org.mockito.Mockito.times(2)).release(7L);
        verify(launcher, org.mockito.Mockito.times(2)).finished(7L);
    }

    @Test
    void SRS_15_25_aFailedRunNamesTheDeepestCauseOfTheWorkerThatFailed() {
        listener.beforeJob(execution(BatchStatus.STARTED));
        JobExecution failed = execution(BatchStatus.FAILED);
        StepExecution manager = new StepExecution(1L, "payrollComputationStep", failed);
        manager.addFailureException(
                new IllegalStateException("Partition handler returned an unsuccessful step"));
        StepExecution worker = new StepExecution(2L, "payrollComputationWorker:partition0", failed);
        worker.addFailureException(
                new RuntimeException("retry exhausted", new SQLException("simulated outage")));
        failed.addStepExecutions(List.of(manager, worker));

        listener.afterJob(failed);

        assertThat(request.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(request.getErrorMessage()).isEqualTo("SQLException: simulated outage");
        verify(journal)
                .record(
                        eq(7L),
                        eq(JobJournal.Event.FAILED),
                        eq("node-1"),
                        eq(JobStatus.RUNNING),
                        eq(JobStatus.FAILED),
                        eq(40L),
                        eq("read 0, written 0, skipped 0; SQLException: simulated outage"));
        assertThat(jobs("FAILED")).isEqualTo(1);
        assertThat(meters.find("fci.batch.job.duration").timer()).isNotNull();
    }

    @Test
    void SRS_15_25_aRunWithoutItsRequestIsCountedUnknownAndStillReleasesItsLock() {
        when(requests.findById(7L)).thenReturn(Optional.empty());
        listener.afterJob(execution(BatchStatus.COMPLETED));
        assertThat(jobs("UNKNOWN")).isEqualTo(1);
        assertThat(meters.find("fci.batch.job.duration").timer())
                .as("never started here: no timer")
                .isNull();
        verify(locks).release(7L);
        verify(launcher).finished(7L);
        verify(journal, never()).record(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void SRS_15_25_theFailureShownIsTheJobsOwnElseItsExitDescriptionNeverForACompletedRun() {
        JobExecution jobFailure = execution(BatchStatus.FAILED);
        jobFailure.addFailureException(new IllegalStateException("CYCLE_LOCKED: locked"));
        assertThat(JobLifecycleListener.rootCause(jobFailure))
                .isEqualTo("IllegalStateException: CYCLE_LOCKED: locked");

        JobExecution interrupted = execution(BatchStatus.FAILED);
        interrupted.setExitStatus(ExitStatus.FAILED.addExitDescription("Interrupted"));
        assertThat(JobLifecycleListener.rootCause(interrupted)).isEqualTo("Interrupted");

        assertThat(JobLifecycleListener.rootCause(execution(BatchStatus.COMPLETED))).isNull();
    }

    @Test
    void SRS_15_25_aCauseChainIsFollowedAtMostTenDeepAndNeverInACircle() {
        Throwable chain = new IllegalStateException("level 12");
        for (int level = 11; level >= 0; level--) {
            chain = new RuntimeException("level " + level, chain);
        }
        JobExecution deep = execution(BatchStatus.FAILED);
        deep.addFailureException(chain);
        assertThat(JobLifecycleListener.rootCause(deep)).isEqualTo("RuntimeException: level 10");

        JobExecution circular = execution(BatchStatus.FAILED);
        circular.addFailureException(
                new RuntimeException("its own cause") {
                    @Override
                    public synchronized Throwable getCause() {
                        return this;
                    }
                });
        assertThat(JobLifecycleListener.rootCause(circular)).endsWith(": its own cause");
    }

    @Test
    void SRS_15_25_aBlankFailureIsRecordedAsNoMessageAndAnAbandonedRunAsFailed() {
        listener.beforeJob(execution(BatchStatus.STARTED));
        JobExecution blank = execution(BatchStatus.FAILED);
        blank.setExitStatus(new ExitStatus("FAILED", "  "));
        listener.afterJob(blank);
        assertThat(request.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(request.getErrorMessage()).isNull();

        assertThat(JobLifecycleListener.outcome(BatchStatus.ABANDONED, request))
                .isEqualTo(JobStatus.FAILED);
        assertThat(JobLifecycleListener.outcome(BatchStatus.COMPLETED, request))
                .isEqualTo(JobStatus.COMPLETED);
    }

    @Test
    void SRS_NF_2_theRequesterIsToldWhenTheJobEndsNeverWhenItPauses() {
        listener.beforeJob(execution(BatchStatus.STARTED));
        request.requestStop("PAUSE");
        listener.afterJob(execution(BatchStatus.STOPPED));
        assertThat(request.getStatus()).isEqualTo(JobStatus.PAUSED);
        verify(events, never()).publishEvent(any(JobEnded.class));

        request.pick("node-1", NOW);
        listener.beforeJob(execution(BatchStatus.STARTED));
        listener.afterJob(execution(BatchStatus.COMPLETED));
        ArgumentCaptor<JobEnded> told = ArgumentCaptor.forClass(JobEnded.class);
        verify(events).publishEvent(told.capture());
        assertThat(told.getValue().jobRequestId()).isEqualTo(7L);
        assertThat(told.getValue().status()).isEqualTo("COMPLETED");
        assertThat(told.getValue().requestedBy()).isEqualTo(2L);
        assertThat(told.getValue().payPeriod()).isEqualTo("2026-09");
    }

    @Test
    void SRS_NF_2_aScheduledJobWithoutARequesterTellsNobody() {
        ReflectionTestUtils.setField(request, "requestedBy", null);
        listener.beforeJob(execution(BatchStatus.STARTED));
        listener.afterJob(execution(BatchStatus.FAILED));
        assertThat(request.getStatus()).isEqualTo(JobStatus.FAILED);
        verify(events, never()).publishEvent(any(JobEnded.class));
    }

    /**
     * SRS-15-25: a run that completed although one of its steps recorded a failure (a skipped
     * record within the skip limit) is COMPLETED; neither its request nor its journal names an
     * error.
     */
    @Test
    void SRS_15_25_aCompletedRunWithAStepFailureRecordedNamesNoError() {
        listener.beforeJob(execution(BatchStatus.STARTED));
        JobExecution completed = execution(BatchStatus.COMPLETED);
        StepExecution worker =
                new StepExecution(2L, "payrollComputationWorker:partition0", completed);
        worker.addFailureException(new IllegalStateException("skipped within the limit"));
        completed.addStepExecutions(List.of(worker));

        listener.afterJob(completed);

        assertThat(request.getStatus()).isEqualTo(JobStatus.COMPLETED);
        assertThat(request.getErrorMessage()).isNull();
        verify(journal)
                .record(
                        eq(7L),
                        eq(JobJournal.Event.COMPLETED),
                        eq("node-1"),
                        eq(JobStatus.RUNNING),
                        eq(JobStatus.COMPLETED),
                        eq(40L),
                        eq("read 0, written 0, skipped 0"));
    }
}
