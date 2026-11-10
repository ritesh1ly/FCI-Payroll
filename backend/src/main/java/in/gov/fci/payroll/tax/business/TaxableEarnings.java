package in.gov.fci.payroll.tax.business;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * The taxable earnings of a member's financial year, month by month, from the payroll engines (plan
 * 08 D-4; SRS-7-3, SRS-7-10, SRS-7-12, SRS-7-27, SRS-7-29). A port: the engines (modules 16, 20-22)
 * write the results; until they do, the shipped implementation answers empty ("earnings not
 * available") and nothing is computed on assumed earnings.
 *
 * <p>The engines decide what is taxable ({@code pay_component.taxable_flag}), including LTC
 * encashment in March and the employer contribution on a CPF part-final withdrawal (SRS-7-10).
 * Dating a payment by its invoice submission date rather than the lock date is not built: it waits
 * for the engines' invoice date (PENDING 08-engines, Q-078).
 */
public interface TaxableEarnings {

    /**
     * One earning of a month as the payslip names it (SRS-7-27: "Leave Encashment", not a code).
     */
    record Line(String description, BigDecimal amount) {}

    /**
     * One payroll month of the year.
     *
     * @param yymm the month, YYYYMM
     * @param actual a locked month (actuals); otherwise projected from current pay (SRS-7-12)
     * @param taxableGross the taxable earnings of the month
     * @param tdsDeducted TDS deducted in the month (zero for a projected month)
     * @param employerNps the employer's NPS contribution of the month (SRS-7-27, SRS-7-29)
     */
    record Month(
            String yymm,
            boolean actual,
            BigDecimal taxableGross,
            BigDecimal tdsDeducted,
            BigDecimal employerNps,
            List<Line> lines) {}

    /** The twelve months of a financial year, April first. */
    record Year(List<Month> months) {

        public BigDecimal gross() {
            return months.stream()
                    .map(Month::taxableGross)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        public BigDecimal tdsDeducted() {
            return months.stream().map(Month::tdsDeducted).reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        public BigDecimal employerNps() {
            return months.stream().map(Month::employerNps).reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        /** The months not yet locked: the TDS still due is spread over them (at least one). */
        public int remainingMonths() {
            return (int) Math.max(1, months.stream().filter(m -> !m.actual()).count());
        }

        /** Every month locked: the actual view of the year (SRS-7-12, SRS-7-19). */
        public boolean allActual() {
            return months.stream().allMatch(Month::actual);
        }
    }

    /** The member's year; empty while payroll does not provide earnings. */
    Optional<Year> year(Long employeeId, String finYear);
}
