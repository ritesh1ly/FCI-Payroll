package in.gov.fci.payroll.jobs.batch.support;

import in.gov.fci.payroll.platform.export.ZipBundle;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.listener.StepExecutionListener;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;

/**
 * A bulk download as a background job (OD-149; SRS-NF-2: bulk payslip download as a background job
 * with progress and a notification; SRS-NF-7: committed unit by unit, a failed unit stops only
 * itself, restartable): one ZIP of single PDFs, rendered at run time from the data in the database,
 * written to a temporary file (bounded memory), {@value #BATCH} documents per call so the C-13
 * progress (read / written / skipped) moves while it runs. A document whose render fails is left
 * out, listed in the ZIP's manifest and counted as skipped (the job log names it); it never fails
 * the file. When every document is written the ZIP is handed to {@link Plan#store} (the requester's
 * download). A restart starts the file again: the rendering is deterministic, so nothing is
 * repeated wrongly or lost.
 *
 * <p>Not final: a {@code @StepScope} bean is a CGLIB subclass proxy (one tasklet per step
 * execution, which is what makes a restart start the file again).
 *
 * @param <T> one document's source (a member, a certificate)
 */
public class ZipJobTasklet<T> implements Tasklet, StepExecutionListener {

    private static final Logger LOG = LoggerFactory.getLogger(ZipJobTasklet.class);

    /** Documents rendered per call (one transaction, one progress update). */
    static final int BATCH = 50;

    /** What a bulk job renders and where its file goes. */
    public interface Plan<T> {
        /** The documents in their fixed order; refuses (throws) when the job may not run. */
        List<T> items();

        /** The timestamp of every entry, from the locked data. */
        LocalDateTime entryTime();

        /** The entry name of one document. */
        String name(T item);

        /** One document's PDF, rendered from the database. */
        byte[] render(T item);

        /** Hands the finished ZIP over (the document store, for the requester). */
        void store(byte[] zip, int written, int leftOut);
    }

    private final Plan<T> plan;
    private List<T> items;
    private int next;
    private Path file;
    private OutputStream out;
    private ZipBundle zip;

    public ZipJobTasklet(Plan<T> plan) {
        this.plan = plan;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunk)
            throws IOException {
        if (items == null) {
            items = List.copyOf(plan.items());
            file = Files.createTempFile("fci-bulk-", ".zip");
            out = Files.newOutputStream(file);
            zip = new ZipBundle(out, plan.entryTime());
        }
        int end = Math.min(items.size(), next + BATCH);
        int written = 0;
        for (; next < end; next++) {
            T item = items.get(next);
            String name = plan.name(item);
            contribution.incrementReadCount();
            byte[] pdf;
            try {
                pdf = plan.render(item);
            } catch (RuntimeException failed) {
                // SRS-NF-7: a failed member stops only itself; named in the manifest and the log.
                LOG.warn(
                        "event=BULK_DOCUMENT_LEFT_OUT entry={} error={}",
                        ZipBundle.entryName(name),
                        failed.getClass().getSimpleName());
                zip.leftOut(name, failed.getClass().getSimpleName());
                contribution.incrementProcessSkipCount();
                continue;
            }
            zip.add(name, pdf);
            written++;
        }
        contribution.incrementWriteCount(written);
        if (next < items.size()) {
            return RepeatStatus.CONTINUABLE;
        }
        zip.close();
        out.close();
        byte[] bytes = Files.readAllBytes(file);
        plan.store(bytes, zip.added(), zip.leftOut());
        chunk.getStepContext()
                .getStepExecution()
                .getExecutionContext()
                .putString(
                        BatchKeys.SUMMARY,
                        "documents "
                                + items.size()
                                + ", in the file "
                                + zip.added()
                                + ", left out "
                                + zip.leftOut());
        return RepeatStatus.FINISHED;
    }

    /** The temporary file never outlives the step, whatever its outcome. */
    @Override
    public ExitStatus afterStep(StepExecution step) {
        try {
            if (out != null) {
                out.close();
            }
            if (file != null) {
                Files.deleteIfExists(file);
            }
        } catch (IOException e) {
            LOG.warn("event=BULK_TEMP_NOT_DELETED error={}", e.getClass().getSimpleName());
        }
        return step.getExitStatus();
    }
}
