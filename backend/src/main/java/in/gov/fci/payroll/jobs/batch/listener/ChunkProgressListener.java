package in.gov.fci.payroll.jobs.batch.listener;

import in.gov.fci.payroll.jobs.batch.lock.JobLockService;
import in.gov.fci.payroll.jobs.batch.support.BatchKeys;
import in.gov.fci.payroll.jobs.config.BatchProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.listener.ChunkListener;
import org.springframework.batch.core.scope.context.StepContext;
import org.springframework.batch.core.scope.context.StepSynchronizationManager;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * After every committed chunk: refreshes the job lock's heartbeat (at most every {@code
 * fci.batch.lock.heartbeat-interval}), so other nodes know the job is alive; logs chunk errors.
 */
@Component
@Profile("batch")
public class ChunkProgressListener implements ChunkListener<Object, Object> {

    private static final Logger log = LoggerFactory.getLogger(ChunkProgressListener.class);

    private final JobLockService locks;
    private final BatchProperties properties;
    private final Clock clock;
    private final Map<Long, Instant> lastBeat = new ConcurrentHashMap<>();

    public ChunkProgressListener(JobLockService locks, BatchProperties properties, Clock clock) {
        this.locks = locks;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * The step running on this thread: every Batch step registers its execution with {@link
     * StepSynchronizationManager} for its whole run (a partition's worker on its own thread), so
     * the chunk callbacks, which receive only the items, still know their step.
     */
    private static StepExecution currentStep() {
        StepContext context = StepSynchronizationManager.getContext();
        return context == null ? null : context.getStepExecution();
    }

    @Override
    public void afterChunk(Chunk<Object> chunk) {
        StepExecution step = currentStep();
        Long requestId =
                step == null ? null : step.getJobParameters().getLong(BatchKeys.REQUEST_ID);
        if (requestId == null) {
            return;
        }
        Instant now = clock.instant();
        Instant last = lastBeat.get(requestId);
        if (last == null || last.plus(properties.lock().heartbeatInterval()).isBefore(now)) {
            lastBeat.put(requestId, now);
            locks.heartbeat(requestId);
        }
    }

    @Override
    public void onChunkError(Exception exception, Chunk<Object> chunk) {
        StepExecution step = currentStep();
        log.warn(
                "event=CHUNK_FAILED step={} readCount={} items={} cause={}",
                step == null ? null : step.getStepName(),
                step == null ? null : step.getReadCount(),
                chunk == null ? 0 : chunk.size(),
                exception == null ? null : exception.getClass().getSimpleName());
    }
}
