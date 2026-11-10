package in.gov.fci.payroll.platform.export;

import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

/**
 * Reads every row of an export's query, batch by batch, for {@link ExportSink} (SRS-G-13).
 *
 * <p>Correctness rests on the caller: it runs in ONE read-only transaction at isolation
 * SERIALIZABLE (a snapshot on Oracle and H2), and the query orders by its sort keys ending with a
 * unique id. Then the batches neither overlap nor leave gaps, and rows added or changed by others
 * while the download streams do not move rows between batches: the export is exactly the list as it
 * was when the download began, in the order the screen shows. The total comes from the first
 * batch's count query in the same snapshot; the caller checks it against the rows written.
 *
 * <p>Memory stays flat whatever the list's length: after each batch the persistence context is
 * cleared, so no read entity is kept.
 */
@Component
public class ExportReader {

    /** Handles one batch (maps the entities, writes the rows). */
    @FunctionalInterface
    public interface Batch<T> {
        void handle(List<T> rows) throws IOException;
    }

    private final EntityManager entityManager;
    private final ExportProperties properties;

    public ExportReader(EntityManager entityManager, ExportProperties properties) {
        this.entityManager = entityManager;
        this.properties = properties;
    }

    /** How many rows the query has (one count query; for the size check before streaming). */
    public long count(Function<Pageable, Page<?>> query) {
        return query.apply(PageRequest.of(0, 1)).getTotalElements();
    }

    /**
     * Hands every row of {@code query} to {@code batch}, {@code fci.export.batch-size} at a time,
     * in the query's order. Answers the row count of the snapshot (the first batch's total).
     */
    public <T> long readAll(Function<Pageable, Page<T>> query, Batch<T> batch) throws IOException {
        int size = properties.batchSize();
        Page<T> page = query.apply(PageRequest.of(0, size));
        long total = page.getTotalElements();
        while (true) {
            List<T> rows = page.getContent();
            if (!rows.isEmpty()) {
                batch.handle(rows);
            }
            // Nothing read is kept: memory stays flat over any number of batches.
            entityManager.clear();
            if (!page.hasNext()) {
                return total;
            }
            page = query.apply(page.nextPageable());
        }
    }
}
