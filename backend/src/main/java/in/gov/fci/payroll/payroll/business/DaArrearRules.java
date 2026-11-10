package in.gov.fci.payroll.payroll.business;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * DA_ARREAR (MST-DA-001, BR-ARR-DA-002/003): the differential of one DA line of one past month. The
 * recomputed DA is the DA % in force for THAT month (as approved now) on the basis the paid line
 * used (so a suspended member's subsistence basis stays what it was); the arrear is recomputed
 * minus paid, signed (negative = recovery), rounded to the rupee like the computation. Pure: no
 * framework, no I/O.
 */
public final class DaArrearRules {

    /** One differential: what was paid, what is due now, the arrear. */
    public record Differential(BigDecimal original, BigDecimal recomputed, BigDecimal arrear) {}

    private DaArrearRules() {}

    /** Empty when nothing is owed either way. */
    public static Optional<Differential> differential(
            BigDecimal paid, BigDecimal basis, BigDecimal daPercentNow) {
        if (paid == null || basis == null || daPercentNow == null) {
            throw new PayDataException(
                    "A DA line without its basis or a DA rate cannot be recomputed");
        }
        BigDecimal recomputed =
                basis.multiply(daPercentNow)
                        .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP)
                        .setScale(0, RoundingMode.HALF_UP)
                        .setScale(2, RoundingMode.UNNECESSARY);
        BigDecimal arrear = recomputed.subtract(paid);
        return arrear.signum() == 0
                ? Optional.empty()
                : Optional.of(new Differential(paid, recomputed, arrear));
    }
}
