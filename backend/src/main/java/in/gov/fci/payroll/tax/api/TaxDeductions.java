package in.gov.fci.payroll.tax.api;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

/**
 * The monthly income-tax and Professional Tax deductions of a member, for the payroll engines
 * (modules 16, 20-22) to write as components (SRS-7-3, SRS-7-4, SRS-7-13; plan 08 phase 08e). Every
 * rate comes from the year's approved rule versions; a deduction that cannot be computed is
 * answered with its reasons, never with an assumed amount.
 */
public interface TaxDeductions {

    /**
     * The month's deductions.
     *
     * @param tds the monthly TDS in rupees (R-02), empty when not computable
     * @param professionalTax the month's PT in rupees, empty when not computable
     * @param higherRateApplied the higher rate for a missing or invalid PAN applied (SRS-7-13)
     * @param reasons the error codes that stopped a computation (e.g. TAX-422-RATE-NOT-CONFIGURED)
     */
    record Monthly(
            Optional<BigDecimal> tds,
            Optional<BigDecimal> professionalTax,
            boolean higherRateApplied,
            List<String> reasons) {}

    /**
     * The member's deductions for {@code month}; a missing PAN or regime is raised on the X-05
     * worklist (SRS-7-13, SRS-7-1) and payroll continues.
     */
    Monthly monthly(Long employeeId, YearMonth month, String actor);
}
