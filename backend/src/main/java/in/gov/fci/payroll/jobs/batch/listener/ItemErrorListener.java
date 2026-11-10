package in.gov.fci.payroll.jobs.batch.listener;

import in.gov.fci.payroll.jobs.batch.support.ItemKey;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.listener.ItemProcessListener;
import org.springframework.batch.core.listener.ItemReadListener;
import org.springframework.batch.core.listener.ItemWriteListener;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.context.annotation.Profile;
import org.springframework.core.retry.RetryException;
import org.springframework.stereotype.Component;

/**
 * Read / process / write errors of the chunk steps, logged with the item key (never the record's
 * personal data) and counted ({@code fci.batch.item.errors}). Whether an error is retried, skipped
 * or fails the step is decided by the step's fault-tolerance policy, not here.
 */
@Component
@Profile("batch")
public class ItemErrorListener
        implements ItemReadListener<Object>,
                ItemProcessListener<Object, Object>,
                ItemWriteListener<Object> {

    private static final Logger log = LoggerFactory.getLogger(ItemErrorListener.class);

    private final MeterRegistry meters;

    public ItemErrorListener(MeterRegistry meters) {
        this.meters = meters;
    }

    @Override
    public void onReadError(Exception ex) {
        count("read");
        log.warn("event=ITEM_READ_ERROR exception={}", cause(ex));
    }

    @Override
    public void onProcessError(Object item, Exception ex) {
        count("process");
        log.warn("event=ITEM_PROCESS_ERROR item={} exception={}", ItemKey.of(item), cause(ex));
    }

    @Override
    public void onWriteError(Exception ex, Chunk<? extends Object> items) {
        count("write");
        log.warn("event=ITEM_WRITE_ERROR items={} exception={}", items.size(), cause(ex));
    }

    /**
     * The failure's class. Spring Batch 6's chunk-oriented step reports a failure inside its retry
     * as a {@link RetryException} wrapping the real one: the log names the real one.
     */
    static String cause(Throwable ex) {
        Throwable real = ex instanceof RetryException && ex.getCause() != null ? ex.getCause() : ex;
        return real.getClass().getName();
    }

    private void count(String phase) {
        meters.counter("fci.batch.item.errors", "phase", phase).increment();
    }
}
