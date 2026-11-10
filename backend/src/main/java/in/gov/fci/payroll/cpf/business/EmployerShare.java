package in.gov.fci.payroll.cpf.business;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;

/**
 * The employer's CPF contribution of a month and its pension split (plan 07 phase 07i part 2;
 * SRS-6-1, SRS-6-7, BR-CPF-017, SRS-G-20, CF-07):
 *
 * <ul>
 *   <li>employer contribution = M-18 CPF.RATE.EMPLOYER % of the CPF wage base, rounded to the rupee
 *       half-up like any component (R-02);
 *   <li>EPS (CPF.EPS.RATE %) and the higher-pension part (CPF.HIGHERPENSION.RATE %) of the same
 *       base are kept to the paise (R-01); their combined total is rounded to the rupee once
 *       (R-05), the difference kept as {@code employee_pf_detail.rounding_adjustment}.
 * </ul>
 *
 * The split is part of the employer contribution ("split the employer contribution", SRS-6-7), so a
 * rounded pension total above it is a configuration that cannot hold ({@link #splitExceeds}).
 *
 * @param employer the employer contribution, whole rupees
 * @param eps the EPS part, paise
 * @param pohw the higher-pension part, paise
 * @param rounding the rupee rounding of eps + pohw (between -0.50 and 0.50)
 */
public record EmployerShare(
        BigDecimal employer, BigDecimal eps, BigDecimal pohw, BigDecimal rounding) {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /** The shares of {@code base} at the given percentages. */
    public static EmployerShare of(
            BigDecimal base, BigDecimal employerRate, BigDecimal epsRate, BigDecimal pohwRate) {
        BigDecimal employer = percent(base, employerRate, 0);
        BigDecimal eps = percent(base, epsRate, 2);
        BigDecimal pohw = percent(base, pohwRate, 2);
        // OPEN-QUESTION: the SRS names no wage ceiling for the EPS base (the EPF Scheme's ceiling
        // is not in SRS-6-7 or M-18); the whole CPF wage base is used until FCI says otherwise
        // (plan 07 D-16, Q-07-8).
        BigDecimal combined = percent(base, epsRate.add(pohwRate), 0);
        return new EmployerShare(employer, eps, pohw, combined.subtract(eps).subtract(pohw));
    }

    /**
     * SRS-1-76, SRS-1-59 (US-GAP-6): EPS stops from the month the member reaches the A-07
     * EPS_CUTOFF_AGE (58, SRS-1-22); from then the whole employer contribution stays in CPF.
     */
    public static boolean epsStopped(LocalDate birthDate, int cutoffAge, YearMonth month) {
        return !month.isBefore(YearMonth.from(birthDate.plusYears(cutoffAge)));
    }

    /** The same employer contribution with no EPS or higher-pension part (EPS stopped). */
    public EmployerShare withoutPension() {
        return new EmployerShare(employer, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    /** The pension parts once rounded (what is booked to EPS in the month). */
    public BigDecimal pension() {
        return eps.add(pohw).add(rounding);
    }

    /** True when the rounded pension parts exceed the employer contribution they split. */
    public boolean splitExceeds() {
        return pension().compareTo(employer) > 0;
    }

    private static BigDecimal percent(BigDecimal base, BigDecimal rate, int scale) {
        return base.multiply(rate).divide(HUNDRED, scale, RoundingMode.HALF_UP);
    }
}
