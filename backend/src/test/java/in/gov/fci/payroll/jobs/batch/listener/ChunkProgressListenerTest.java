package in.gov.fci.payroll.jobs.batch.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import in.gov.fci.payroll.jobs.batch.lock.JobLockService;
import in.gov.fci.payroll.jobs.batch.support.BatchKeys;
import in.gov.fci.payroll.jobs.config.BatchProperties;
import in.gov.fci.payroll.platform.logging.LogCapture;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.scope.context.StepSynchronizationManager;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.item.Chunk;

/**
 * The job-lock heartbeat after a committed chunk: at most once per heartbeat interval and request,
 * never for a step that carries no job request (an ad-hoc launch), and chunk failures logged with
 * the step and its counts but never the items.
 */
class ChunkProgressListenerTest {

    /** A clock the test moves forward between chunks. */
    private static final class SteppingClock extends Clock {
        private Instant now = Instant.parse("2026-10-24T06:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final JobLockService locks = mock(JobLockService.class);
    private final SteppingClock clock = new SteppingClock();
    private final ChunkProgressListener listener =
            new ChunkProgressListener(locks, properties(Duration.ofSeconds(30)), clock);

    private static BatchProperties properties(Duration heartbeat) {
        BatchProperties properties = mock(BatchProperties.class);
        when(properties.lock())
                .thenReturn(new BatchProperties.Lock(heartbeat, Duration.ofMinutes(3)));
        return properties;
    }

    /** Registers {@code step} as the step running on this thread, as a Batch step does. */
    private static StepExecution running(String name, JobParameters parameters) {
        JobExecution job = new JobExecution(40L, new JobInstance(3L, "payrollDryRun"), parameters);
        StepExecution step = new StepExecution(1L, name, job);
        StepSynchronizationManager.register(step);
        return step;
    }

    private static JobParameters request(long id) {
        return new JobParametersBuilder().addLong(BatchKeys.REQUEST_ID, id).toJobParameters();
    }

    @AfterEach
    void unregister() {
        StepSynchronizationManager.release();
    }

    @Test
    void aChunkOutsideAStepOrOfAStepWithoutAJobRequestIsNotHeartbeat() {
        listener.afterChunk(new Chunk<>());
        running("payrollComputationStep", new JobParameters());
        listener.afterChunk(new Chunk<>());
        verify(locks, never()).heartbeat(any());
    }

    @Test
    void theHeartbeatRunsAtMostOncePerIntervalForEachRequest() {
        running("payrollComputationStep", request(7L));
        listener.afterChunk(new Chunk<>());
        listener.afterChunk(new Chunk<>());
        clock.advance(Duration.ofSeconds(29));
        listener.afterChunk(new Chunk<>());
        verify(locks, times(1)).heartbeat(7L);

        clock.advance(Duration.ofSeconds(2));
        listener.afterChunk(new Chunk<>());
        verify(locks, times(2)).heartbeat(7L);

        // Another request on the same node keeps its own beat.
        StepSynchronizationManager.release();
        running("payrollComputationWorker:partition0", request(8L));
        listener.afterChunk(new Chunk<>());
        verify(locks, times(1)).heartbeat(8L);
        verify(locks, times(2)).heartbeat(7L);
    }

    @Test
    void aChunkFailureIsLoggedWithTheStepAndCountsButNeverTheItems() {
        StepExecution step = running("payrollComputationStep", request(7L));
        step.setReadCount(12);
        try (LogCapture logs = LogCapture.of(ChunkProgressListener.class, Level.WARN)) {
            listener.onChunkError(
                    new IllegalStateException("writer down"),
                    new Chunk<>(List.of("E100|basic=52000")));
            assertThat(logs.messages())
                    .containsExactly(
                            "event=CHUNK_FAILED step=payrollComputationStep readCount=12 items=1"
                                    + " cause=IllegalStateException")
                    .noneMatch(m -> m.contains("52000"));
        }
        StepSynchronizationManager.release();
        try (LogCapture logs = LogCapture.of(ChunkProgressListener.class, Level.WARN)) {
            listener.onChunkError(null, null);
            assertThat(logs.messages())
                    .containsExactly(
                            "event=CHUNK_FAILED step=null readCount=null items=0 cause=null");
        }
    }
}
