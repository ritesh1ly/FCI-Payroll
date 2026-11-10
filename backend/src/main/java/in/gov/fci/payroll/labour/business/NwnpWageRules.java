package in.gov.fci.payroll.labour.business;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Objects;

/**
 * NWNP wage rules as SRS-5-6, SRS-5-12 and UC C-05 state them (plan 22 phase 22d; NW-5, NW-11,
 * NW-14; D22-9). The C-05 run that applies them (the MGW representation and precedence of SRS-5-11,
 * the period grain and the month-end sequence of SRS-5-10) waits for FCI (PENDING NWNP-22d-1):
 * these are the stated formulas only, kept apart so the engine applies them unchanged. Pure
 * functions: no state, no I/O; amounts are never rounded here (rounded once at the payment point,
 * kb/02-cross-cutting/rounding-money.md).
 */
public final class NwnpWageRules {

    /** The computed wage and whether the floor raised it (flagged for the Payroll Checker). */
    public record Wage(BigDecimal amount, boolean raisedToFloor) {}

    private static final BigDecimal SEVEN = BigDecimal.valueOf(7);
    private static final BigDecimal TWO = BigDecimal.valueOf(2);

    private NwnpWageRules() {}

    /** SRS-5-6: Present But No Work is paid at MGW / 7 per hour present. */
    public static BigDecimal hourlyRate(BigDecimal mgw) {
        Objects.requireNonNull(mgw, "MGW");
        return mgw.divide(SEVEN, MathContext.DECIMAL64);
    }

    /** SRS-5-6: the pay of the Present But No Work hours. */
    public static BigDecimal presentButNoWorkPay(BigDecimal mgw, BigDecimal hours) {
        return hourlyRate(mgw).multiply(nonNegative(hours, "hours"));
    }

    /**
     * UC C-05 / SRS-5-6: Continue Work hours earn double OTA on the MGW / 7 basis (NW-14, D22-9:
     * "OTA calculated on that basis" read as twice the hourly rate).
     */
    public static BigDecimal continueWorkOta(BigDecimal mgw, BigDecimal hours) {
        return hourlyRate(mgw).multiply(TWO).multiply(nonNegative(hours, "hours"));
    }

    /**
     * SRS-5-3 / UC C-05: the piece-rate earnings of an output, the rate held per its Unit of
     * Measure (D22-5); {@code unitSize} is the quantity one rate pays for (100 for "per 100 bags").
     */
    public static BigDecimal pieceEarnings(
            BigDecimal ratePerUnit, BigDecimal output, int unitSize) {
        Objects.requireNonNull(ratePerUnit, "piece rate");
        if (unitSize <= 0) {
            throw new IllegalArgumentException("unit size " + unitSize);
        }
        return ratePerUnit
                .multiply(nonNegative(output, "output"))
                .divide(BigDecimal.valueOf(unitSize), MathContext.DECIMAL64);
    }

    /**
     * C-05 Computed Wage and SRS-5-12: MGW plus piece-rate earnings, raised to the floor where
     * lower and then flagged; an equal wage is not raised.
     */
    public static Wage computedWage(BigDecimal mgw, BigDecimal pieceEarnings, BigDecimal floor) {
        Objects.requireNonNull(mgw, "MGW");
        Objects.requireNonNull(pieceEarnings, "piece earnings");
        Objects.requireNonNull(floor, "floor");
        BigDecimal wage = mgw.add(pieceEarnings);
        return wage.compareTo(floor) < 0 ? new Wage(floor, true) : new Wage(wage, false);
    }

    private static BigDecimal nonNegative(BigDecimal value, String name) {
        Objects.requireNonNull(value, name);
        if (value.signum() < 0) {
            throw new IllegalArgumentException(name + " " + value);
        }
        return value;
    }
}
