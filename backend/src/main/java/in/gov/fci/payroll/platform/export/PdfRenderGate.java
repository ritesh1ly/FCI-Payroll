package in.gov.fci.payroll.platform.export;

import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * The bulkhead of PDF rendering (OD-144; performance SLA: 1,000 members downloading payslips at
 * once on salary day, 3 s interactive, SRS-NF-6 "heavy eSeva use does not slow payroll
 * processing"): at most {@code fci.api.pdf.max-concurrent} documents are rendered at once; a
 * request waits at most {@code fci.api.pdf.max-wait} for a slot and is then refused with 503 {@code
 * COM-503-EXPORT-BUSY} and {@code Retry-After: fci.api.pdf.retry-after} ({@link Busy}), never by
 * exhausting the request threads or the heap. The three values are technical settings (PENDING
 * PDF-SETTINGS: to be confirmed by the performance test), not business rules.
 *
 * <p>A render that fails is logged ({@code event=PDF_RENDER_FAILED}, the document name only, never
 * its content), audited ({@link ExportFailed}) and answered as the error it is: no partial file is
 * ever sent, since a document is complete in memory before the response starts.
 */
@Component
public class PdfRenderGate {

    private static final Logger LOG = LoggerFactory.getLogger(PdfRenderGate.class);

    /** The slot could not be had within the wait: 503 with Retry-After. */
    public static final class Busy extends FciException {
        private static final long serialVersionUID = 1L;
        private final long retryAfterSeconds;

        public Busy(long retryAfterSeconds) {
            super(CommonErrorCode.EXPORT_BUSY);
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public long retryAfterSeconds() {
            return retryAfterSeconds;
        }
    }

    private final Semaphore slots;
    private final int maxConcurrent;
    private final Duration maxWait;
    private final long retryAfterSeconds;
    private final ApplicationEventPublisher events;

    public PdfRenderGate(
            @Value("${fci.api.pdf.max-concurrent:16}") int maxConcurrent,
            @Value("${fci.api.pdf.max-wait:2s}") Duration maxWait,
            @Value("${fci.api.pdf.retry-after:5s}") Duration retryAfter,
            ApplicationEventPublisher events) {
        if (maxConcurrent < 1) {
            throw new IllegalArgumentException("fci.api.pdf.max-concurrent must be at least 1");
        }
        this.slots = new Semaphore(maxConcurrent, true);
        this.maxConcurrent = maxConcurrent;
        this.maxWait = maxWait;
        this.retryAfterSeconds = Math.max(1, retryAfter.toSeconds());
        this.events = events;
    }

    /**
     * Renders {@code document} (named {@code name} for the log and audit) within a slot. A refusal
     * of the caller's own read (not found, not permitted) passes through unchanged.
     */
    public byte[] render(String name, Supplier<byte[]> document) {
        boolean acquired;
        try {
            acquired = slots.tryAcquire(maxWait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Busy(retryAfterSeconds);
        }
        if (!acquired) {
            LOG.warn("event=PDF_RENDER_BUSY name={} permits={}", name, maxConcurrent);
            throw new Busy(retryAfterSeconds);
        }
        try {
            return document.get();
        } catch (FciException refusal) {
            throw refusal;
        } catch (RuntimeException failure) {
            LOG.error(
                    "event=PDF_RENDER_FAILED name={} error={}",
                    name,
                    failure.getClass().getSimpleName());
            events.publishEvent(
                    new ExportFailed(name, ExportFormat.PDF, failure.getClass().getSimpleName()));
            throw new FciException(
                    CommonErrorCode.INTERNAL_ERROR, new Object[] {}, List.of(), failure);
        } finally {
            slots.release();
        }
    }

    /** Slots free now (for tests and the actuator). */
    public int available() {
        return slots.availablePermits();
    }
}
