package in.gov.fci.payroll.jobs.batch.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.gov.fci.payroll.jobs.config.BatchProperties;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.util.backoff.BackOffExecution;

/**
 * The behaviour the jobs module kept across the Spring Batch 5 → 6 upgrade (Spring Boot 4): an
 * unknown execution is null (JobExplorer's contract), and the retry of a chunk step keeps its
 * attempts, its retryable failures and its exponential backoff.
 */
class BatchUpgradeContractsTest {

    @Test
    void anUnknownExecutionIsNullAsJobExplorerReturnedItAndKnownOnesPassThrough() {
        JobRepository repository = mock(JobRepository.class);
        JobExecution known = mock(JobExecution.class);
        JobInstance instance = mock(JobInstance.class);
        when(repository.getJobExecution(1L)).thenReturn(known);
        when(repository.getJobExecution(2L))
                .thenThrow(new EmptyResultDataAccessException("none", 1));
        when(repository.getJobExecutions(instance)).thenReturn(List.of(known));
        when(repository.findRunningJobExecutions("job")).thenReturn(Set.of(known));
        // The lookup runs in a transaction of its own (NOT_SUPPORTED): a mock manager stands in.
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        BatchHistory history = BatchHistory.of(repository, transactions);
        assertThat(history.getJobExecution(1L)).isSameAs(known);
        assertThat(history.getJobExecution(2L)).isNull();
        assertThat(history.getJobExecutions(instance)).containsExactly(known);
        assertThat(history.findRunningJobExecutions("job")).containsExactly(known);
    }

    private static BatchProperties properties(int limit) {
        BatchProperties properties = mock(BatchProperties.class);
        when(properties.retry())
                .thenReturn(
                        new BatchProperties.Retry(
                                limit, Duration.ofMillis(500), 2.0, Duration.ofSeconds(10)));
        return properties;
    }

    @Test
    void theRetryLimitCountsAttemptsInAllWithExponentialBackoffUpToTheMaximum() {
        RetryPolicy policy = FaultTolerancePolicy.retryPolicy(properties(3));
        BackOffExecution backOff = policy.getBackOff().start();
        // Three attempts in all: two retries after the first, then the step gives up.
        assertThat(backOff.nextBackOff()).isEqualTo(500L);
        assertThat(backOff.nextBackOff()).isEqualTo(1000L);
        assertThat(backOff.nextBackOff()).isEqualTo(BackOffExecution.STOP);

        BackOffExecution capped =
                FaultTolerancePolicy.retryPolicy(properties(10)).getBackOff().start();
        long last = 0;
        for (int i = 0; i < 9; i++) {
            last = capped.nextBackOff();
        }
        assertThat(last).isEqualTo(10_000L);
        // A limit of one attempt retries nothing.
        assertThat(
                        FaultTolerancePolicy.retryPolicy(properties(1))
                                .getBackOff()
                                .start()
                                .nextBackOff())
                .isEqualTo(BackOffExecution.STOP);
    }

    @Test
    void SRS_15_25_anInfrastructureFailureIsRetriedAndAnyOtherFailureIsNot() {
        RetryPolicy policy = FaultTolerancePolicy.retryPolicy(properties(3));
        assertThat(policy.shouldRetry(new CannotAcquireLockException("deadlock loser"))).isTrue();
        assertThat(policy.shouldRetry(new RecoverableDataAccessException("reconnect"))).isTrue();
        assertThat(policy.shouldRetry(new DataIntegrityViolationException("bad row"))).isFalse();
        assertThat(policy.shouldRetry(new InvalidRecordException("bad member"))).isFalse();
        assertThat(policy.shouldRetry(new IllegalStateException("bug"))).isFalse();
    }
}
