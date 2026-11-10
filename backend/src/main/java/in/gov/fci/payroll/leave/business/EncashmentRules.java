package in.gov.fci.payroll.leave.business;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * Leave encashment (plan 19 phase 19e; SRS-LV-16, SRS-LV-17, SRS-LV-21, SRS-LV-22, SRS-LV-4,
 * SRS-LV-34; BR-LEAVE-008, BR-LEAVE-009, BR-LEAVE-013, BR-FNF-009). Pure: no framework, no I/O.
 *
 * <p>Value = encashable days x (pay elements) / divisor, at the pay on the encashment or separation
 * date, rounded once to the rupee half-up. In service: only a type HRMS marks encashable, never
 * Disability Leave, never above the encashable balance (refused, the balance shown), not for a
 * member under suspension unless the order permits; a request above the per-occasion or the
 * remaining per-year limit is capped and the cap shown. At separation: the encashable balance
 * limited to the aggregate ceiling.
 */
public final class EncashmentRules {

    /** The HRMS code of Disability Leave (SRS M-13 examples: "DL"). */
    public static final String DISABILITY_LEAVE = "DL";

    private EncashmentRules() {}

    /** The days to encash and whether a limit capped them. */
    public record Days(BigDecimal days, boolean capped) {}

    /**
     * SRS-LV-17 / LV-21 / LV-22 / LV-4: why an in-service request cannot be encashed, or empty.
     *
     * <p>OPEN-QUESTION (CLR-71, US-GAP-310): the request is checked against the balance before any
     * limit caps it; the order of the two rules is not stated.
     */
    public static Optional<String> inServiceDefect(
            String leaveType,
            boolean encashable,
            BigDecimal requested,
            BigDecimal balance,
            boolean suspended,
            boolean orderPermits) {
        if (DISABILITY_LEAVE.equals(leaveType)) {
            return Optional.of("DISABILITY_LEAVE");
        }
        if (!encashable) {
            return Optional.of("NOT_ENCASHABLE");
        }
        if (requested == null || requested.signum() <= 0) {
            return Optional.of("DAYS");
        }
        if (balance == null) {
            return Optional.of("NO_BALANCE");
        }
        if (requested.compareTo(balance) > 0) {
            return Optional.of("ABOVE_BALANCE " + balance.stripTrailingZeros().toPlainString());
        }
        if (suspended && !orderPermits) {
            return Optional.of("SUSPENDED");
        }
        return Optional.empty();
    }

    /**
     * SRS-LV-17: the request capped by the per-occasion limit and the year's remaining days.
     *
     * @param remainingThisYear the per-year limit less the days already encashed in the year
     */
    public static Days inService(
            BigDecimal requested, BigDecimal perOccasion, BigDecimal remainingThisYear) {
        BigDecimal days = requested.min(perOccasion).min(remainingThisYear.max(BigDecimal.ZERO));
        return new Days(days, days.compareTo(requested) < 0);
    }

    /**
     * BR-FNF-009 / SRS-LV-17: the balance at separation limited to the aggregate ceiling.
     *
     * <p>OPEN-QUESTION (CLR-68, US-GAP-302): half-pay leave is "treated as in the current system"
     * (M-20 l.4179); its days are counted as the others until FCI gives that treatment.
     */
    public static Days atSeparation(BigDecimal balance, BigDecimal ceiling) {
        BigDecimal days = balance.max(BigDecimal.ZERO).min(ceiling);
        return new Days(days, days.compareTo(balance) < 0);
    }

    /** SRS-LV-16: days x pay / divisor, rounded once to the rupee half-up (SRS-DM-6). */
    public static BigDecimal value(BigDecimal days, BigDecimal pay, BigDecimal divisor) {
        return days.multiply(pay)
                .divide(divisor, 10, RoundingMode.HALF_UP)
                .setScale(0, RoundingMode.HALF_UP);
    }

    /** SRS-LV-16: the rate per day the statement shows, to the paisa. */
    public static BigDecimal ratePerDay(BigDecimal pay, BigDecimal divisor) {
        return pay.divide(divisor, 2, RoundingMode.HALF_UP);
    }
}
