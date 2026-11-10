package in.gov.fci.payroll.leave.business;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

/**
 * The leave encashment arrear (plan 13 phase 13d; SRS-8-23, BR-ARR-LE-001 to 003). Pure: no
 * framework, no I/O.
 *
 * <p>An encashment already paid is recalculated when the pay it used is revised by an order that
 * takes effect on or before its date (for a retired member, on or before the separation date): the
 * amount due is the encashable days x the revised pay elements / the divisor (M-20, as the
 * encashment itself), and the arrear is that less what was paid for the month - the encashment and
 * every earlier arrear of it - so a second run pays only a further difference, never twice.
 */
public final class LeaveArrearRules {

    private LeaveArrearRules() {}

    /** SRS-8-23: the revision reaches an encashment dated on or after its effective date. */
    public static boolean affected(LocalDate revisionEffective, LocalDate encashmentDate) {
        return !encashmentDate.isBefore(revisionEffective);
    }

    /**
     * DDL chk_le_arrear_elig: a member who separated before the revision took effect is not
     * eligible; the reason is recorded.
     */
    public static Optional<String> ineligible(LocalDate revisionEffective, LocalDate separation) {
        return separation != null && separation.isBefore(revisionEffective)
                ? Optional.of(
                        "Separated on "
                                + separation
                                + ", before the revision took effect on "
                                + revisionEffective)
                : Optional.empty();
    }

    /** BR-ARR-LE-001: days x revised pay / divisor, rounded once like the encashment. */
    public static BigDecimal due(BigDecimal days, BigDecimal revisedPay, BigDecimal divisor) {
        return EncashmentRules.value(days, revisedPay, divisor);
    }

    /** BR-ARR-LE-003: the further difference; empty when nothing more is owed. */
    public static Optional<BigDecimal> arrear(BigDecimal due, BigDecimal paidSoFar) {
        BigDecimal arrear = due.subtract(paidSoFar);
        return arrear.signum() > 0 ? Optional.of(arrear) : Optional.empty();
    }
}
