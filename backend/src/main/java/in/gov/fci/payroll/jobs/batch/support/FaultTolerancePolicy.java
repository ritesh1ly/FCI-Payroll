package in.gov.fci.payroll.jobs.batch.support;

import in.gov.fci.payroll.jobs.config.BatchProperties;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;

/**
 * The fault tolerance every chunk step uses (values in application-batch.yml):
 *
 * <ul>
 *   <li><b>retry</b> transient database errors (deadlock loser, lock timeout, lost connection:
 *       {@link TransientDataAccessException}) and {@link RecoverableDataAccessException}, with
 *       exponential backoff, for at most {@code fci.batch.retry.limit} attempts in all;
 *   <li><b>skip</b> bad records ({@link InvalidRecordException}, failure class MEMBER_DATA) up to
 *       {@code fci.batch.skip.limit} per step, each written to the failure log;
 *   <li>anything else fails the step; the job can be restarted from the last committed chunk.
 * </ul>
 */
public final class FaultTolerancePolicy {

    private FaultTolerancePolicy() {}

    public static final Class<? extends Throwable> RETRYABLE = TransientDataAccessException.class;

    /** A failed operation that may succeed after recovery steps (e.g. a reconnect). */
    public static final Class<? extends Throwable> RETRYABLE_RECOVERABLE =
            RecoverableDataAccessException.class;

    public static final Class<? extends Throwable> SKIPPABLE = InvalidRecordException.class;

    /**
     * The retry of a chunk step. {@code fci.batch.retry.limit} counts attempts in all (the first
     * one included, as Spring Batch's retry limit always did); Spring's {@link RetryPolicy} counts
     * the retries after the first attempt, hence {@code limit - 1}. Only the two retryable classes
     * are retried; every other failure goes to the skip policy or fails the step.
     */
    public static RetryPolicy retryPolicy(BatchProperties properties) {
        BatchProperties.Retry retry = properties.retry();
        return RetryPolicy.builder()
                .includes(RETRYABLE, RETRYABLE_RECOVERABLE)
                .maxRetries(Math.max(0, retry.limit() - 1L))
                .delay(retry.initialInterval())
                .multiplier(retry.multiplier())
                .maxDelay(retry.maxInterval())
                .build();
    }
}
