package in.gov.fci.payroll.configuration.orchestration;

import in.gov.fci.payroll.configuration.api.SystemParameters;
import in.gov.fci.payroll.platform.paging.PageSizes;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The page sizes of every list screen from the A-07 settings {@code UI_PAGE_SIZE_MAX} and {@code
 * UI_PAGE_SIZE_DEFAULT} (owner requirement 2026-10-24, docs/plan/X-pagination.md): the owner's
 * series 10, 20, 50, 100 up to the maximum; the default is the configured one if offered, else the
 * largest offered size below it, so a list never starts on a size it cannot show.
 */
@Component
public class ConfiguredPageSizes implements PageSizes {

    /** The owner's series; the maximum setting can only shorten it. */
    static final List<Integer> SERIES = List.of(10, 20, 50, 100);

    private final SystemParameters parameters;

    public ConfiguredPageSizes(SystemParameters parameters) {
        this.parameters = parameters;
    }

    @Override
    public List<Integer> offered() {
        return offered(parameters.pageSizeMax());
    }

    @Override
    public int defaultSize() {
        return defaultOf(parameters.pageSizeDefault(), offered());
    }

    /** The series up to {@code max}; at least the smallest size. */
    static List<Integer> offered(int max) {
        List<Integer> sizes = SERIES.stream().filter(s -> s <= max).toList();
        return sizes.isEmpty() ? List.of(SERIES.get(0)) : sizes;
    }

    /**
     * {@code configured} if offered, else the largest offered size below it (else the smallest).
     */
    static int defaultOf(int configured, List<Integer> offered) {
        return offered.stream()
                .filter(s -> s <= configured)
                .reduce((a, b) -> b)
                .orElse(offered.get(0));
    }
}
