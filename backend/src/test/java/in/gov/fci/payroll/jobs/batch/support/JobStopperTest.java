package in.gov.fci.payroll.jobs.batch.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.launch.JobExecutionNotRunningException;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * C-13 STOP / PAUSE (SRS-NF-2, SRS-EH-7): a stop is signalled only to a running execution; no id,
 * an execution not found, one no longer running, or one that ends between the check and the stop
 * answers false.
 */
class JobStopperTest {

    private final JobOperator operator = mock(JobOperator.class);
    private final BatchHistory history = mock(BatchHistory.class);
    private final JobStopper stopper =
            new JobStopper(operator, history, mock(PlatformTransactionManager.class));

    private JobExecution execution(long id, boolean running) {
        JobExecution e = mock(JobExecution.class);
        when(e.isRunning()).thenReturn(running);
        when(history.getJobExecution(id)).thenReturn(e);
        return e;
    }

    @Test
    void SRS_NF_2_noIdOrAnUnknownExecutionIsNotStopped() {
        assertThat(stopper.stop(null)).isFalse();
        verifyNoInteractions(history);
        assertThat(stopper.stop(7L)).isFalse();
    }

    @Test
    void SRS_NF_2_onlyARunningExecutionIsStoppedAndOneEndingMeanwhileIsNot() throws Exception {
        execution(8L, false);
        assertThat(stopper.stop(8L)).isFalse();
        JobExecution running = execution(9L, true);
        when(operator.stop(running)).thenReturn(true);
        assertThat(stopper.stop(9L)).isTrue();
        JobExecution ending = execution(10L, true);
        when(operator.stop(ending)).thenThrow(new JobExecutionNotRunningException("ended"));
        assertThat(stopper.stop(10L)).isFalse();
    }
}
