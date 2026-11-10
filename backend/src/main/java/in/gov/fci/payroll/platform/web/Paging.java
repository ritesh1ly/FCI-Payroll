package in.gov.fci.payroll.platform.web;

import in.gov.fci.payroll.platform.error.CommonErrorCode;
import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.platform.paging.PageSizes;
import in.gov.fci.payroll.platform.paging.PageSpec;
import org.springframework.stereotype.Component;

/**
 * Resolves the {@code page} and {@code size} parameters of a list endpoint (SRS-NF-3;
 * docs/plan/X-pagination.md): missing means page 0 and the configured default size; a size that is
 * not offered or a negative page is refused (400 {@code COM-400-PAGE}), never clamped, so a forged
 * size can never load a whole table.
 */
@Component
public class Paging {

    private final PageSizes sizes;

    public Paging(PageSizes sizes) {
        this.sizes = sizes;
    }

    public PageSpec resolve(Integer page, Integer size) {
        int p = page == null ? 0 : page;
        int s = size == null ? sizes.defaultSize() : size;
        if (p < 0 || !sizes.offered().contains(s)) {
            throw new FciException(CommonErrorCode.PAGE_REQUEST);
        }
        return new PageSpec(p, s);
    }
}
