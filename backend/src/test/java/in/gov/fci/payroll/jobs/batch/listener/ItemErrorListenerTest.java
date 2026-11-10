package in.gov.fci.payroll.jobs.batch.listener;

import static org.assertj.core.api.Assertions.assertThat;

import in.gov.fci.payroll.jobs.batch.support.InvalidRecordException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.core.retry.RetryException;

/** Item errors are logged by their real cause and counted per phase. */
class ItemErrorListenerTest {

    @Test
    void theRealCauseIsNamedWhenTheChunkStepWrapsItInARetryException() {
        assertThat(
                        ItemErrorListener.cause(
                                new RetryException("retry", new InvalidRecordException("x"))))
                .isEqualTo(InvalidRecordException.class.getName());
        assertThat(ItemErrorListener.cause(new IllegalStateException("plain")))
                .isEqualTo(IllegalStateException.class.getName());
    }

    @Test
    void everyPhaseIsCounted() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ItemErrorListener listener = new ItemErrorListener(meters);
        listener.onReadError(new IllegalStateException("r"));
        listener.onProcessError("item", new IllegalStateException("p"));
        listener.onWriteError(new IllegalStateException("w"), new Chunk<>(List.of("a", "b")));
        for (String phase : List.of("read", "process", "write")) {
            assertThat(meters.counter("fci.batch.item.errors", "phase", phase).count())
                    .as(phase)
                    .isEqualTo(1.0);
        }
    }
}
