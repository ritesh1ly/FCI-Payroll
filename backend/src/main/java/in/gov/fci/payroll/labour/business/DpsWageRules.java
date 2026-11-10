package in.gov.fci.payroll.labour.business;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Objects;

/**
 * DPS wage rules as SRS-4-2 / SRS-4-3 / SRS-4-4 state them (plan 21 phase 21d; DP-2 … DP-4). The
 * C-04 run that applies them (the actual earned wage of a DPS labour, the comparison grain, the
 * proration over the period and the rounding point) waits for FCI (PENDING LBR-21d-1): these are
 * the stated formulas only, kept apart so the engine applies them unchanged. Pure functions: no
 * state, no I/O; amounts are never rounded here (rounded once at the payment point,
 * kb/02-cross-cutting/rounding-money.md).
 */
public final class DpsWageRules {

    /** The basis the floor came from: MGW + Interim Relief, or the GoI rate inclusive of IR. */
    public enum Basis {
        MGW_IR,
        GOI
    }

    /** The floor and its basis, kept on the calculation trace (SC-21-0021). */
    public record Floor(BigDecimal amount, Basis basis) {}

    private static final BigDecimal SEVEN = BigDecimal.valueOf(7);
    private static final BigDecimal THIRTY = BigDecimal.valueOf(30);

    private DpsWageRules() {}

    /**
     * SRS-4-2: the wage floor is the higher of (MGW + IR) and the GoI rate inclusive of IR; on a
     * tie the MGW + IR basis is kept (pay codes 188 / 189). Every value is required: an
     * unconfigured one is refused by the caller, never taken as zero.
     */
    public static Floor floor(BigDecimal mgw, BigDecimal interimRelief, BigDecimal goiRate) {
        Objects.requireNonNull(mgw, "MGW");
        Objects.requireNonNull(interimRelief, "Interim Relief");
        Objects.requireNonNull(goiRate, "GoI rate");
        BigDecimal mgwIr = mgw.add(interimRelief);
        return goiRate.compareTo(mgwIr) > 0
                ? new Floor(goiRate, Basis.GOI)
                : new Floor(mgwIr, Basis.MGW_IR);
    }

    /** SRS-4-2: the DPS Actual Wage (pay code 190) is the earned wage above the floor, else 0. */
    public static BigDecimal actualWageAboveFloor(BigDecimal earned, BigDecimal floor) {
        BigDecimal excess = earned.subtract(floor);
        return excess.signum() > 0 ? excess : BigDecimal.ZERO;
    }

    /**
     * SRS-4-3: where work is not enough and the actual earnings fall below the MGW, the OTA hourly
     * rate is the MGW over 7 effective duty hours (weekdays only: the caller applies it to weekday
     * hours). Answers null when the earnings reach the MGW (no OTA on this basis).
     */
    public static BigDecimal insufficientWorkOtaRate(BigDecimal mgw, BigDecimal earned) {
        if (earned.compareTo(mgw) >= 0) {
            return null;
        }
        return mgw.divide(SEVEN, MathContext.DECIMAL64);
    }

    /**
     * SRS-4-4: the temporary advance of a DPS labour is capped at MGW x 30 x the months the Rule
     * Engine allows for the advance reason (a configurable value: unset, the caller refuses).
     */
    public static BigDecimal advanceCap(BigDecimal mgw, int monthsAllowed) {
        if (monthsAllowed < 0) {
            throw new IllegalArgumentException("months allowed " + monthsAllowed);
        }
        return mgw.multiply(THIRTY).multiply(BigDecimal.valueOf(monthsAllowed));
    }
}
