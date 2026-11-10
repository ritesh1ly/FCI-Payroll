package in.gov.fci.payroll.platform.paging;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * A validated page request: the page number (from 0) and one of the offered sizes ({@link
 * Paging#resolve}).
 */
public record PageSpec(int page, int size) {

    /** The request for the database, in the list's own stable order. */
    public Pageable pageable(Sort order) {
        return PageRequest.of(page, size, order);
    }

    /** The request for a repository query whose order is in its JPQL. */
    public Pageable pageable() {
        return PageRequest.of(page, size);
    }
}
