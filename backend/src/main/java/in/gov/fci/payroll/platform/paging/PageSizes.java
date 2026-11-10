package in.gov.fci.payroll.platform.paging;

import java.util.List;

/**
 * The page sizes a list screen offers and the one it starts with (owner requirement 2026-10-24,
 * docs/plan/X-pagination.md): configuration, implemented by the configuration module from its A-07
 * settings, so the platform never reads a setting table itself.
 */
public interface PageSizes {

    /** The sizes offered, ascending (the series 10, 20, 50, 100 up to the configured maximum). */
    List<Integer> offered();

    /** The size a list starts with: one of {@link #offered()}. */
    int defaultSize();
}
