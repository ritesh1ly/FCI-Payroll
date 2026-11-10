package in.gov.fci.payroll.platform.paging;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/**
 * One page of a list, as every list endpoint returns it (SRS-NF-3; docs/plan/X-pagination.md): the
 * rows of the page, the page number (from 0), the page size and the total number of rows. A page
 * past the end has no rows and the true total, so the screen can move back.
 *
 * @param <T> the row type
 */
public record Paged<T>(List<T> items, int page, int size, long total) {

    public Paged {
        items = List.copyOf(items);
    }

    /** A page the database returned. */
    public static <T> Paged<T> of(Page<T> page) {
        return new Paged<>(
                page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements());
    }

    /**
     * One page of a list assembled in the service (master data with its version status, lists from
     * several sources): bounded configuration, so the whole list is small; only the page is sent.
     */
    public static <T> Paged<T> slice(List<T> all, PageSpec spec) {
        int from = (int) Math.min((long) spec.page() * spec.size(), all.size());
        int to = Math.min(from + spec.size(), all.size());
        return new Paged<>(all.subList(from, to), spec.page(), spec.size(), all.size());
    }

    /** The same page with each row converted. */
    public <R> Paged<R> map(Function<? super T, ? extends R> convert) {
        return new Paged<>(items.stream().<R>map(convert).toList(), page, size, total);
    }
}
