package in.gov.fci.payroll.loans.api;

import in.gov.fci.payroll.loans.business.LoanRules;
import java.math.BigDecimal;

/**
 * The Loans &amp; Advances combined-deduction ceiling as other recoveries apply it (SRS-14-7: a
 * penalty or recovery deduction is scheduled "under that rule"): the same fit the loans use
 * (SRS-12-13 / 12-14), on the same rule key, so the two never disagree.
 */
public final class DeductionCeiling {

    /** The M-18 rule key of the ceiling (% of gross; unseeded = not configured). */
    public static final String RULE_KEY = "LOAN.DEDUCTION.CEILING";

    /**
     * The fitted instalment.
     *
     * @param instalment the instalment that keeps the month within the ceiling
     * @param instalments how many it takes to recover the amount
     * @param reduced true when the asked instalment had to be reduced (flagged to the Checker)
     * @param fits false when there is no room at all this month
     */
    public record Fit(BigDecimal instalment, int instalments, boolean reduced, boolean fits) {}

    private DeductionCeiling() {}

    /** SRS-12-13 / 12-14 applied to {@code amount} recovered at {@code instalment} a month. */
    public static Fit fit(
            BigDecimal amount,
            BigDecimal instalment,
            BigDecimal gross,
            BigDecimal existingDeductions,
            BigDecimal ceilingPercent) {
        LoanRules.Fit f =
                LoanRules.fit(amount, instalment, gross, existingDeductions, ceilingPercent);
        return new Fit(f.instalment(), f.instalments(), f.reduced(), f.fits());
    }
}
