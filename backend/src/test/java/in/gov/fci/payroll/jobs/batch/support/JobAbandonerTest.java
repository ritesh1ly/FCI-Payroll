package in.gov.fci.payroll.jobs.batch.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.jobs.api.AbandonListener;
import in.gov.fci.payroll.jobs.business.model.BatchJobRequest;
import in.gov.fci.payroll.jobs.business.model.JobStatus;
import in.gov.fci.payroll.jobs.business.model.JobType;
import in.gov.fci.payroll.jobs.orchestration.JobJournal;
import in.gov.fci.payroll.jobs.persistence.JobRepositories;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.launch.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * SRS-15-27 (plan 05j): abandoning ends the request for good, marks its Spring Batch execution
 * ABANDONED and tells the owning module; the failure message and end time stay; an execution still
 * running is never abandoned.
 */
class JobAbandonerTest {

    private static final Instant NOW = Instant.parse("2026-10-24T06:00:00Z");

    private final JobOperator operator = mock(JobOperator.class);
    private final BatchHistory history = mock(BatchHistory.class);
    private final JobRepositories.JobRequests requests = mock(JobRepositories.JobRequests.class);
    private final JobJournal journal = mock(JobJournal.class);
    private final AbandonListener listener = mock(AbandonListener.class);
    private final JobAbandoner abandoner =
            new JobAbandoner(
                    operator,
                    history,
                    requests,
                    journal,
                    List.of(listener),
                    mock(PlatformTransactionManager.class),
                    Clock.fixed(NOW, ZoneOffset.UTC));

    /** Request 7, run as execution 77 an hour ago, failed half an hour ago with "boom". */
    private static BatchJobRequest failed() {
        BatchJobRequest r =
                BatchJobRequest.submit(
                        JobType.PAYROLL_COMPUTATION,
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
        r.finished(JobStatus.FAILED, 10, 8, 0, "8 of 20", "boom", NOW.minusSeconds(1_800));
        return r;
    }

    private JobExecution execution(BatchStatus status) {
        JobExecution execution =
                new JobExecution(
                        77L, new JobInstance(7L, "payrollComputation"), new JobParameters());
        execution.setStatus(status);
        when(history.getJobExecution(77L)).thenReturn(execution);
        return execution;
    }

    @Test
    void SRS_15_27_anAbandonedJobIsFinalItsExecutionAbandonedAndItsOwnerTold() throws Exception {
        BatchJobRequest request = failed();
        JobExecution execution = execution(BatchStatus.FAILED);

        assertThat(abandoner.abandon(request, "checker@x", "ABANDON by checker@x: Approval 55"))
                .isTrue();

        verify(operator).abandon(execution);
        assertThat(request.getStatus()).isEqualTo(JobStatus.ABANDONED);
        assertThat(request.getErrorMessage()).as("the failure stays on record").isEqualTo("boom");
        assertThat(request.getFinishedAt())
                .as("the end time is the failure's")
                .isEqualTo(NOW.minusSeconds(1_800));
        verify(requests).save(request);
        verify(listener).abandoned(7L, "PAYROLL_COMPUTATION");
        verify(journal)
                .record(
                        7L,
                        JobJournal.Event.ABANDONED,
                        "checker@x",
                        JobStatus.FAILED,
                        JobStatus.ABANDONED,
                        77L,
                        "ABANDON by checker@x: Approval 55");
    }

    @Test
    void SRS_15_27_anExecutionStillRunningIsNotAbandonedAndNothingChanges() throws Exception {
        BatchJobRequest request = failed();
        execution(BatchStatus.STARTED);

        assertThat(abandoner.abandon(request, "checker@x", "x")).isFalse();

        verify(operator, never()).abandon(any(JobExecution.class));
        assertThat(request.getStatus()).isEqualTo(JobStatus.FAILED);
        verify(requests, never()).save(any());
        verifyNoInteractions(listener, journal);
    }

    @Test
    void SRS_15_27_anExecutionThatStartsRunningMeanwhileIsNotAbandoned() throws Exception {
        BatchJobRequest request = failed();
        JobExecution execution = execution(BatchStatus.FAILED);
        when(operator.abandon(execution)).thenThrow(new JobExecutionAlreadyRunningException("x"));

        assertThat(abandoner.abandon(request, "checker@x", "x")).isFalse();
        assertThat(request.getStatus()).isEqualTo(JobStatus.FAILED);
        verifyNoInteractions(listener);
    }

    @Test
    void SRS_15_27_anExecutionAlreadyAbandonedOrGoneOrNeverStartedIsNoObstacle() throws Exception {
        BatchJobRequest request = failed();
        execution(BatchStatus.ABANDONED);
        assertThat(abandoner.abandon(request, "system.housekeeping", "expired")).isTrue();
        verify(operator, never()).abandon(any(JobExecution.class));

        BatchJobRequest gone = failed();
        when(history.getJobExecution(77L)).thenReturn(null);
        assertThat(abandoner.abandon(gone, "system.housekeeping", "expired")).isTrue();

        BatchJobRequest neverRan =
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
        ReflectionTestUtils.setField(neverRan, "id", 8L);
        neverRan.cancel(NOW.minusSeconds(3_600));
        assertThat(abandoner.abandon(neverRan, "system.housekeeping", "expired")).isTrue();
        assertThat(neverRan.getStatus()).isEqualTo(JobStatus.ABANDONED);
        verify(listener).abandoned(8L, "PAYROLL_DRY_RUN");
        verify(journal)
                .record(
                        8L,
                        JobJournal.Event.ABANDONED,
                        "system.housekeeping",
                        JobStatus.STOPPED,
                        JobStatus.ABANDONED,
                        null,
                        "expired");
    }
}
