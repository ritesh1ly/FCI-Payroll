package in.gov.fci.payroll.jobs.batch.launch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.jobs.batch.lock.JobLockService;
import in.gov.fci.payroll.jobs.batch.support.BatchHistory;
import in.gov.fci.payroll.jobs.batch.support.BatchInstance;
import in.gov.fci.payroll.jobs.batch.support.BatchKeys;
import in.gov.fci.payroll.jobs.business.model.BatchJobRequest;
import in.gov.fci.payroll.jobs.business.model.JobStatus;
import in.gov.fci.payroll.jobs.business.model.JobType;
import in.gov.fci.payroll.jobs.orchestration.JobJournal;
import in.gov.fci.payroll.jobs.persistence.JobRepositories;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.context.ApplicationContext;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Launching a picked request on this node (SRS-EH-7: the same job with the same parameters never
 * runs twice; SRS-15-25: a run that cannot start is left restartable). A launch is refused when the
 * same job and parameters run on any node or the job lock is held, and when the node has no free
 * job thread: the request goes back to where it was and the lock is released. A launch that Spring
 * Batch refuses before the job starts fails the request with the reason.
 */
class JobLaunchServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-23T06:00:00Z");

    private final JobRepositories.JobRequests requests = mock(JobRepositories.JobRequests.class);
    private final JobRepositories.Halts halts = mock(JobRepositories.Halts.class);
    private final JobLockService locks = mock(JobLockService.class);
    private final BatchHistory history = mock(BatchHistory.class);
    private final JobOperator operator = mock(JobOperator.class);
    private final ApplicationContext context = mock(ApplicationContext.class);
    private final ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
    private final JobJournal journal = mock(JobJournal.class);
    private final BatchInstance instance = mock(BatchInstance.class);
    private final Job job = mock(Job.class);

    private final JobLaunchService launcher =
            new JobLaunchService(
                    requests,
                    halts,
                    locks,
                    history,
                    operator,
                    context,
                    executor,
                    journal,
                    instance,
                    new TransactionTemplate(mock(PlatformTransactionManager.class)),
                    Clock.fixed(NOW, ZoneOffset.UTC));

    private BatchJobRequest request;

    @BeforeEach
    void picked() {
        request =
                BatchJobRequest.submit(
                        JobType.PAYROLL_DRY_RUN,
                        "{\"locationId\":\"10\",\"payPeriod\":\"2026-09\"}",
                        "hash-1",
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
        when(instance.id()).thenReturn("node-1");
        when(job.getName()).thenReturn("payrollDryRun");
        when(context.getBean(JobType.PAYROLL_DRY_RUN.jobBean(), Job.class)).thenReturn(job);
        when(locks.acquire(anyString(), eq(7L))).thenReturn(true);
        // The job thread runs the launch at once, as a free executor thread would.
        doAnswer(
                        i -> {
                            i.<Runnable>getArgument(0).run();
                            return null;
                        })
                .when(executor)
                .execute(any(Runnable.class));
    }

    private static JobExecution running(long id, String paramHash) {
        JobParameters parameters =
                new JobParametersBuilder()
                        .addString(BatchKeys.PARAM_HASH, paramHash, false)
                        .toJobParameters();
        return new JobExecution(id, new JobInstance(id, "payrollDryRun"), parameters);
    }

    @Test
    void SRS_EH_7_theSameJobWithTheSameParametersRunningOnAnyNodeIsNotLaunchedAgain() {
        when(history.findRunningJobExecutions("payrollDryRun"))
                .thenReturn(Set.of(running(5L, "other-hash"), running(6L, "hash-1")));

        assertThat(launcher.launch(request, JobStatus.PENDING)).isFalse();

        assertThat(request.getStatus()).as("back in the queue").isEqualTo(JobStatus.PENDING);
        verify(locks, never()).acquire(anyString(), anyLong());
        verify(journal)
                .record(
                        eq(7L),
                        eq(JobJournal.Event.LOCK_REFUSED),
                        eq("node-1"),
                        eq(JobStatus.PICKED),
                        eq(JobStatus.PENDING),
                        any(),
                        eq("The same job with the same parameters is running"));
        assertThat(launcher.running()).isEmpty();
    }

    @Test
    void SRS_EH_7_aJobWhoseLockAnotherNodeHoldsIsNotLaunched() {
        when(history.findRunningJobExecutions("payrollDryRun"))
                .thenReturn(Set.of(running(5L, "other-hash")));
        when(locks.acquire(anyString(), eq(7L))).thenReturn(false);

        assertThat(launcher.launch(request, JobStatus.PAUSED)).isFalse();

        assertThat(request.getStatus()).as("a resume waits as PAUSED").isEqualTo(JobStatus.PAUSED);
        verify(executor, never()).execute(any(Runnable.class));
    }

    @Test
    void SRS_15_25_withoutAFreeJobThreadTheRequestWaitsAndItsLockIsReleased() {
        doThrow(new TaskRejectedException("queue full"))
                .when(executor)
                .execute(any(Runnable.class));

        assertThat(launcher.launch(request, JobStatus.FAILED)).isFalse();

        assertThat(request.getStatus()).as("a restart waits as FAILED").isEqualTo(JobStatus.FAILED);
        verify(locks).release(7L);
        verify(journal)
                .record(
                        eq(7L),
                        eq(JobJournal.Event.LOCK_REFUSED),
                        eq("node-1"),
                        eq(JobStatus.PICKED),
                        eq(JobStatus.FAILED),
                        any(),
                        eq("No free job thread on node-1"));
        assertThat(launcher.running()).isEmpty();
    }

    @Test
    void SRS_15_25_aLaunchSpringBatchRefusesFailsTheRequestWithItsReason() throws Exception {
        when(operator.start(eq(job), any(JobParameters.class)))
                .thenThrow(new JobInstanceAlreadyCompleteException("already complete"));

        assertThat(launcher.launch(request, JobStatus.PENDING)).as("handed to a thread").isTrue();

        assertThat(request.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(request.getErrorMessage())
                .isEqualTo("JobInstanceAlreadyCompleteException: already complete");
        verify(requests).save(request);
        verify(journal)
                .record(
                        eq(7L),
                        eq(JobJournal.Event.FAILED),
                        eq("node-1"),
                        eq(JobStatus.PICKED),
                        eq(JobStatus.FAILED),
                        any(),
                        eq("Launch refused: JobInstanceAlreadyCompleteException"));
        verify(locks).release(7L);
        assertThat(launcher.running()).isEmpty();
    }

    @Test
    void SRS_15_25_aLaunchedJobCountsAsRunningOnThisNodeUntilItFinishes() throws Exception {
        // The job thread is still busy: the launch only hands the run over.
        doAnswer(i -> null).when(executor).execute(any(Runnable.class));

        assertThat(launcher.launch(request, JobStatus.PENDING)).isTrue();

        assertThat(launcher.running()).containsExactly(7L);
        assertThat(launcher.freeSlots(2)).isEqualTo(1);
        assertThat(launcher.freeSlots(1)).isZero();
        launcher.finished(7L);
        assertThat(launcher.running()).isEmpty();
        assertThat(launcher.freeSlots(2)).isEqualTo(2);
        verify(locks, never()).release(anyLong());
    }

    @Test
    void OD_02_whileBatchProcessingIsHaltedNothingIsLaunched() {
        when(halts.existsByReleasedAtIsNull()).thenReturn(true);

        assertThat(launcher.launch(request, JobStatus.PENDING)).isFalse();

        assertThat(request.getStatus())
                .as("back in the queue for after the release")
                .isEqualTo(JobStatus.PENDING);
        verify(locks, never()).acquire(anyString(), any());
        verify(executor, never()).execute(any(Runnable.class));
        verify(journal)
                .record(
                        eq(7L),
                        eq(JobJournal.Event.LOCK_REFUSED),
                        eq("node-1"),
                        eq(JobStatus.PICKED),
                        eq(JobStatus.PENDING),
                        any(),
                        eq("Batch processing is halted"));
        assertThat(launcher.running()).isEmpty();
    }
}
