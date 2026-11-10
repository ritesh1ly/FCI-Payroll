package in.gov.fci.payroll.arrear.business;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * The fitment of a wage revision (plan 13 phase 13e; SRS-8-25 step 2 and step 3). Pure: no
 * framework, no I/O.
 *
 * <p>The new Basic is the old one by the order's fitment rule - a factor, a percentage added, or a
 * fixed amount added - held within its minimum and maximum and rounded as the order says. The
 * simulation's estimated arrear is the Basic difference for each month from the effective month to
 * the month before the current one: before the linked effects (DA, HRA, CPF ...) the arrear engine
 * computes once pay is fixed.
 */
public final class WageRevisionRules {

    private WageRevisionRules() {}

    /** The fitment rule types this release computes (pay matrix and labour rate sets wait). */
    public enum Fitment {
        FACTOR,
        PERCENTAGE,
        FIXED_AMOUNT
    }

    /** The roundings an order may name. */
    public enum Rounding {
        /** To the rupee, half up (SRS-DM-6). */
        RUPEE,
        /** Up to the next multiple of 10. */
        NEXT_10,
        /** Up to the next multiple of 100. */
        NEXT_100
    }

    /** One component's rule as defined. */
    public record Rule(
            Fitment type,
            BigDecimal value,
            BigDecimal minimum,
            BigDecimal maximum,
            Rounding rounding) {}

    /** Why a rule is not complete, or empty. */
    public static Optional<String> defect(Rule r) {
        if (r.type() == null || r.value() == null || r.rounding() == null) {
            return Optional.of("the rule type, its value and the rounding are required");
        }
        if (r.value().signum() <= 0) {
            return Optional.of("the fitment value must be positive");
        }
        if (r.minimum() != null && r.maximum() != null && r.minimum().compareTo(r.maximum()) > 0) {
            return Optional.of("the minimum is above the maximum");
        }
        return Optional.empty();
    }

    /** SRS-8-25 step 2: the new pay from the old. */
    public static BigDecimal fit(BigDecimal old, Rule r) {
        BigDecimal raw =
                switch (r.type()) {
                    case FACTOR -> old.multiply(r.value());
                    case PERCENTAGE ->
                            old.add(
                                    old.multiply(r.value())
                                            .divide(
                                                    BigDecimal.valueOf(100),
                                                    10,
                                                    RoundingMode.HALF_UP));
                    case FIXED_AMOUNT -> old.add(r.value());
                };
        BigDecimal held = raw;
        if (r.minimum() != null) {
            held = held.max(r.minimum());
        }
        if (r.maximum() != null) {
            held = held.min(r.maximum());
        }
        return round(held, r.rounding());
    }

    static BigDecimal round(BigDecimal v, Rounding rounding) {
        return switch (rounding) {
            case RUPEE -> v.setScale(0, RoundingMode.HALF_UP);
            case NEXT_10 -> up(v, BigDecimal.TEN);
            case NEXT_100 -> up(v, BigDecimal.valueOf(100));
        };
    }

    private static BigDecimal up(BigDecimal v, BigDecimal step) {
        return v.divide(step, 0, RoundingMode.CEILING).multiply(step);
    }

    /** SRS-8-25 step 3: the months already paid at the old pay (effective month to last month). */
    public static long arrearMonths(YearMonth effective, YearMonth current) {
        return Math.max(0, ChronoUnit.MONTHS.between(effective, current));
    }

    /** SRS-8-25 step 3: the estimated Basic arrear. */
    public static BigDecimal estimate(BigDecimal old, BigDecimal fitted, long months) {
        return fitted.subtract(old).multiply(BigDecimal.valueOf(months));
    }
}
