package in.gov.fci.payroll.tax.business;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Builds a member's taxable year from the staff payroll results (plan 16 phase 16e-1; SRS-7-3,
 * SRS-7-12: "project the year from current pay; once a month is locked, use the actuals"), as a
 * pure function:
 *
 * <ul>
 *   <li>a locked month is actual, at its taxable earnings;
 *   <li>a later month not locked is projected at the latest month that has a result (the current
 *       pay), locked or not;
 *   <li>a month before the first result paid nothing through this system: actual at zero
 *       (OPEN-QUESTION D16-21: earlier months of a mid-year joiner or of the year before go-live
 *       come from migration, module 24);
 *   <li>no result in the year: no year ("earnings not available").
 * </ul>
 *
 * TDS deducted is the TDS line of a locked month (16e-2); the employer's NPS is zero until module
 * 18.
 */
public final class EarningsYear {

    /** One month with a result as payroll gives it. */
    public record Result(
            String yymm,
            boolean locked,
            BigDecimal taxableGross,
            List<TaxableEarnings.Line> lines,
            BigDecimal tdsDeducted,
            BigDecimal employerNps) {

        public Result(
                String yymm,
                boolean locked,
                BigDecimal taxableGross,
                List<TaxableEarnings.Line> lines,
                BigDecimal tdsDeducted) {
            this(yymm, locked, taxableGross, lines, tdsDeducted, BigDecimal.ZERO);
        }

        public Result(
                String yymm,
                boolean locked,
                BigDecimal taxableGross,
                List<TaxableEarnings.Line> lines) {
            this(yymm, locked, taxableGross, lines, BigDecimal.ZERO, BigDecimal.ZERO);
        }
    }

    private EarningsYear() {}

    /** The year of {@code finYear} (2026-27) from the results of its months, in any order. */
    public static Optional<TaxableEarnings.Year> of(String finYear, List<Result> results) {
        if (results.isEmpty()) {
            return Optional.empty();
        }
        YearMonth april = YearMonth.of(Integer.parseInt(finYear.substring(0, 4)), 4);
        List<Result> sorted =
                results.stream().sorted(java.util.Comparator.comparing(Result::yymm)).toList();
        String first = sorted.get(0).yymm();
        Result current = sorted.get(sorted.size() - 1);
        List<TaxableEarnings.Month> months = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            String yymm = yymm(april.plusMonths(i));
            Result r = sorted.stream().filter(x -> x.yymm().equals(yymm)).findFirst().orElse(null);
            if (r != null && r.locked()) {
                months.add(month(yymm, true, r));
            } else if (yymm.compareTo(first) < 0) {
                months.add(
                        new TaxableEarnings.Month(
                                yymm,
                                true,
                                BigDecimal.ZERO,
                                BigDecimal.ZERO,
                                BigDecimal.ZERO,
                                List.of()));
            } else {
                months.add(month(yymm, false, r != null ? r : current));
            }
        }
        return Optional.of(new TaxableEarnings.Year(List.copyOf(months)));
    }

    /**
     * Plan 16 phase 16e-2 (SRS-7-3): the year as the payroll run sees it while computing {@code
     * yymm}: the months before it as {@code year} has them (zero and actual before the first
     * result), {@code yymm} at the earnings just computed (not actual), and every later month
     * projected at them. TDS already deducted stays on the months that carry it.
     */
    public static TaxableEarnings.Year withMonth(
            Optional<TaxableEarnings.Year> year,
            String finYear,
            String yymm,
            BigDecimal taxableGross,
            List<TaxableEarnings.Line> lines) {
        return withMonth(year, finYear, yymm, taxableGross, BigDecimal.ZERO, lines);
    }

    /**
     * As {@link #withMonth(Optional, String, String, BigDecimal, List)}, with the month's employer
     * NPS (plan 18 phase 18b; SRS-9-21), projected on the later months as the earnings are.
     */
    public static TaxableEarnings.Year withMonth(
            Optional<TaxableEarnings.Year> year,
            String finYear,
            String yymm,
            BigDecimal taxableGross,
            BigDecimal employerNps,
            List<TaxableEarnings.Line> lines) {
        YearMonth april = YearMonth.of(Integer.parseInt(finYear.substring(0, 4)), 4);
        List<TaxableEarnings.Month> months = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            String m = yymm(april.plusMonths(i));
            if (m.compareTo(yymm) < 0) {
                months.add(
                        year.flatMap(
                                        y ->
                                                y.months().stream()
                                                        .filter(x -> x.yymm().equals(m))
                                                        .findFirst())
                                .orElse(
                                        new TaxableEarnings.Month(
                                                m,
                                                true,
                                                BigDecimal.ZERO,
                                                BigDecimal.ZERO,
                                                BigDecimal.ZERO,
                                                List.of())));
            } else {
                months.add(
                        new TaxableEarnings.Month(
                                m, false, taxableGross, BigDecimal.ZERO, employerNps, lines));
            }
        }
        return new TaxableEarnings.Year(List.copyOf(months));
    }

    private static TaxableEarnings.Month month(String yymm, boolean actual, Result basis) {
        // TDS deducted counts only on a locked month (an actual): a projection deducted nothing.
        return new TaxableEarnings.Month(
                yymm,
                actual,
                basis.taxableGross(),
                actual ? basis.tdsDeducted() : BigDecimal.ZERO,
                basis.employerNps(),
                basis.lines());
    }

    private static String yymm(YearMonth m) {
        return String.format("%04d%02d", m.getYear(), m.getMonthValue());
    }
}
