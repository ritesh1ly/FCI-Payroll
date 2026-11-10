package in.gov.fci.payroll.tax.business;

import in.gov.fci.payroll.platform.error.FciException;
import in.gov.fci.payroll.tax.business.model.ItRegimeDetail.Regime;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

/**
 * The income-tax computation of one member and financial year (SRS-7-1 … SRS-7-4, SRS-7-11,
 * SRS-7-13, SRS-7-19, SRS-7-26, SRS-7-27; BR-TAX-001 … 006; KB Module 08 §3). Pure: every rate
 * arrives from the rule snapshot of the year (unseeded = not configured, never assumed). Money is
 * worked at paise (R-01); only the monthly TDS, a deduction component, is rounded to the rupee
 * half-up (R-02). How each rule's bands apply (plan 08 D-9):
 *
 * <ul>
 *   <li>regime slabs ({@code TAX.SLAB.OLD} / {@code .NEW}): progressive, each band's percentage on
 *       the income inside the band;
 *   <li>surcharge ({@code TAX.SURCHARGE}): the band covering the taxable income gives a percentage
 *       of the slab tax;
 *   <li>cess ({@code TAX.CESS}): a percentage of slab tax plus surcharge;
 *   <li>no valid PAN ({@code TAX.NOPAN.RATE}, SRS-7-13): the annual tax is the higher of the normal
 *       tax and the taxable income at that rate (Q-08-7); payroll continues;
 *   <li>PT ({@code PT.SLAB}): the band of the state covering the monthly gross gives the amount.
 * </ul>
 */
public final class TaxCalculator {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private TaxCalculator() {}

    /** One slab band: covers {@code from <= x < to} ({@code to} null = and above). */
    public record Band(BigDecimal from, BigDecimal to, BigDecimal rateOrAmount) {

        boolean covers(BigDecimal x) {
            return x.compareTo(from) >= 0 && (to == null || x.compareTo(to) < 0);
        }
    }

    /**
     * The rates of the year for the member's regime. Empty optionals: the rule is not configured.
     */
    public record Rates(
            List<Band> slabs,
            Optional<List<Band>> surcharge,
            Optional<BigDecimal> cessPercent,
            Optional<BigDecimal> noPanPercent) {}

    /**
     * What the computation needs.
     *
     * @param grossIncome taxable earnings of the year: actuals of the locked months plus the
     *     projection of the rest (SRS-7-12), and approved other income (SRS-7-11, BR-TAX-015)
     * @param deductions the allowed deductions and exemptions that apply under the regime, capped
     *     at their section limits (SRS-7-2, SRS-7-16); approved lines only (BR-TAX-014)
     * @param panValid a valid PAN is on record (HRMS)
     * @param tdsDeducted TDS deducted so far in the year
     * @param remainingMonths the payroll months left in the year, this month included (at least 1)
     */
    public record Input(
            Regime regime,
            BigDecimal grossIncome,
            BigDecimal deductions,
            boolean panValid,
            BigDecimal tdsDeducted,
            int remainingMonths) {}

    /** The computation, every figure at paise except the monthly TDS (rupees). */
    public record Result(
            BigDecimal taxableIncome,
            BigDecimal slabTax,
            BigDecimal surcharge,
            BigDecimal cess,
            BigDecimal annualTax,
            boolean higherRateApplied,
            BigDecimal monthlyTds) {}

    /**
     * SRS-7-3: the annual tax on the taxable income and the monthly TDS that spreads what is still
     * due over the remaining months; never negative (an over-deduction is shown, not refunded,
     * Q-077).
     */
    public static Result compute(Input in, Rates rates) {
        if (in.regime() == null) {
            throw new FciException(TaxErrorCode.REGIME_REQUIRED);
        }
        if (in.remainingMonths() < 1) {
            throw new IllegalArgumentException("remaining months must be at least 1");
        }
        BigDecimal taxable = paise(in.grossIncome().subtract(in.deductions()).max(BigDecimal.ZERO));
        BigDecimal slabTax = paise(slabTax(taxable, rates.slabs()));
        BigDecimal surcharge =
                slabTax.signum() == 0
                        ? paise(BigDecimal.ZERO)
                        : paise(
                                percentOf(
                                        slabTax,
                                        surchargePercent(
                                                taxable,
                                                required(rates.surcharge(), "TAX.SURCHARGE"))));
        BigDecimal cess =
                slabTax.signum() == 0
                        ? paise(BigDecimal.ZERO)
                        : paise(
                                percentOf(
                                        slabTax.add(surcharge),
                                        required(rates.cessPercent(), "TAX.CESS")));
        BigDecimal annual = slabTax.add(surcharge).add(cess);
        boolean higher = false;
        if (!in.panValid()) {
            BigDecimal atNoPanRate =
                    paise(percentOf(taxable, required(rates.noPanPercent(), "TAX.NOPAN.RATE")));
            if (atNoPanRate.compareTo(annual) > 0) {
                annual = atNoPanRate;
                higher = true;
            }
        }
        BigDecimal due = annual.subtract(in.tdsDeducted()).max(BigDecimal.ZERO);
        BigDecimal monthly =
                due.divide(BigDecimal.valueOf(in.remainingMonths()), 0, RoundingMode.HALF_UP);
        return new Result(taxable, slabTax, surcharge, cess, annual, higher, monthly);
    }

    /** Progressive slab tax: each band's percentage on the part of the income inside it. */
    static BigDecimal slabTax(BigDecimal income, List<Band> bands) {
        BigDecimal tax = BigDecimal.ZERO;
        for (Band b : bands) {
            if (income.compareTo(b.from()) <= 0) {
                continue;
            }
            BigDecimal top = b.to() == null ? income : income.min(b.to());
            tax = tax.add(percentOf(top.subtract(b.from()), b.rateOrAmount()));
        }
        return tax;
    }

    /** The surcharge percentage of the band covering the taxable income; none when no band does. */
    static BigDecimal surchargePercent(BigDecimal taxable, List<Band> bands) {
        return bands.stream()
                .filter(b -> b.covers(taxable))
                .map(Band::rateOrAmount)
                .findFirst()
                .orElse(BigDecimal.ZERO);
    }

    /**
     * SRS-7-4, BR-TAX-006: the monthly Professional Tax of the state's band covering the monthly
     * gross; nothing where PT does not apply in the state or no band covers the gross.
     */
    public static BigDecimal professionalTax(
            boolean ptApplies, BigDecimal monthlyGross, List<Band> stateBands) {
        if (!ptApplies) {
            return BigDecimal.ZERO;
        }
        return stateBands.stream()
                .filter(b -> b.covers(monthlyGross))
                .map(Band::rateOrAmount)
                .findFirst()
                .map(a -> a.setScale(0, RoundingMode.HALF_UP))
                .orElse(BigDecimal.ZERO);
    }

    /**
     * SRS-7-19, BR-TAX-005: at year-end the actual tax of the locked year against the TDS deducted;
     * any difference is a mismatch flagged for correction before Form 16 or the Q4 return.
     */
    public static BigDecimal yearEndDifference(BigDecimal actualTax, BigDecimal tdsDeducted) {
        return paise(actualTax.subtract(tdsDeducted));
    }

    private static <T> T required(Optional<T> value, String ruleKey) {
        return value.orElseThrow(
                () ->
                        new FciException(
                                TaxErrorCode.RATE_NOT_CONFIGURED,
                                new Object[] {ruleKey},
                                List.of(),
                                null));
    }

    private static BigDecimal percentOf(BigDecimal amount, BigDecimal percent) {
        return amount.multiply(percent).divide(HUNDRED, 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal paise(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }
}
