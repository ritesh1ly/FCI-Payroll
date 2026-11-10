package in.gov.fci.payroll.platform.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

/** SRS-G-13: every row of the query, in order, batch by batch, memory kept flat. */
class ExportReaderTest {

    private final EntityManager entityManager = mock(EntityManager.class);
    private final ExportReader reader =
            new ExportReader(
                    entityManager, new ExportProperties(4, 20, Duration.ofMinutes(10), 100, 10));

    /** An ordered "table" of {@code n} rows, served page by page with its total. */
    private static Page<Integer> page(int n, Pageable p) {
        List<Integer> all = IntStream.rangeClosed(1, n).boxed().toList();
        int from = (int) Math.min(p.getOffset(), n);
        return new PageImpl<>(all.subList(from, Math.min(from + p.getPageSize(), n)), p, n);
    }

    @Test
    void SRS_G_13_readsEveryRowInOrderAcrossBatchesAndClearsAfterEach() throws IOException {
        for (int n : new int[] {0, 1, 9, 10, 11, 20, 25}) {
            List<Integer> seen = new ArrayList<>();
            List<Integer> sizes = new ArrayList<>();
            EntityManager em = mock(EntityManager.class);
            ExportReader r =
                    new ExportReader(
                            em, new ExportProperties(4, 20, Duration.ofMinutes(10), 100, 10));
            long total =
                    r.readAll(
                            p -> page(n, p),
                            rows -> {
                                sizes.add(rows.size());
                                seen.addAll(rows);
                            });
            assertThat(total).as("n=%d", n).isEqualTo(n);
            assertThat(seen)
                    .as("n=%d", n)
                    .containsExactlyElementsOf(IntStream.rangeClosed(1, n).boxed().toList());
            assertThat(sizes).allMatch(s -> s > 0 && s <= 10);
            // One query per batch (an empty list still asks once), the context cleared after each.
            verify(em, times(Math.max(1, (n + 9) / 10))).clear();
        }
    }

    @Test
    void theCountIsOneSmallQuery() {
        List<Pageable> asked = new ArrayList<>();
        long count =
                reader.count(
                        p -> {
                            asked.add(p);
                            return page(1234, p);
                        });
        assertThat(count).isEqualTo(1234);
        assertThat(asked).singleElement().satisfies(p -> assertThat(p.getPageSize()).isEqualTo(1));
    }

    @Test
    void aFailingBatchStopsTheExport() {
        assertThatThrownBy(
                        () ->
                                reader.readAll(
                                        p -> page(30, p),
                                        rows -> {
                                            if (rows.get(0) > 10) {
                                                throw new IOException("client went away");
                                            }
                                        }))
                .isInstanceOf(IOException.class);
    }
}
