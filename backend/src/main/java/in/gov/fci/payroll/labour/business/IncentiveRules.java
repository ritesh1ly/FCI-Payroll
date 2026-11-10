package in.gov.fci.payroll.labour.business;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Departmental gang incentive (plan 20 phase 20d; SRS-3-3, SRS-3-4, SRS-3-5, SRS-3-6, SRS-3-7,
 * SRS-3-8; R-01, R-06; D20-11). Pure functions: no state, no I/O. Bag figures are carried at four
 * decimals and money at two (R-01, never to the rupee here: the rupee is the payroll result's,
 * R-02); rates are used as held (R-06).
 */
public final class IncentiveRules {

    private static final int BAGS = 4;
    private static final int MONEY = 2;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal DAYS_IN_YEAR = BigDecimal.valueOf(365);
    private static final BigDecimal MONTHS = BigDecimal.valueOf(12);
    private static final BigDecimal WORKING_DAYS = BigDecimal.valueOf(26);

    /** One band of the slab table that applies to the bag weight: bags from, to (open), rate %. */
    public record Band(long from, Long to, BigDecimal ratePercent) {}

    /** The pay operands of one labour for the period (the labour's Incentive Basic, DA, HRA). */
    public record Pay(BigDecimal incentiveBasic, BigDecimal da, BigDecimal hra) {}

    private IncentiveRules() {}

    /** SRS-3-4: bags per labour, BPL = (TB x MLN) / (MLSW x TPL). */
    public static BigDecimal bagsPerLabour(
            long totalBags, long minLabourNumber, BigDecimal minSlabWeight, long presentLabour) {
        if (presentLabour <= 0 || minSlabWeight.signum() <= 0) {
            return BigDecimal.ZERO.setScale(BAGS);
        }
        return BigDecimal.valueOf(totalBags)
                .multiply(BigDecimal.valueOf(minLabourNumber))
                .divide(
                        minSlabWeight.multiply(BigDecimal.valueOf(presentLabour)),
                        BAGS,
                        RoundingMode.HALF_UP);
    }

    /**
     * SRS-3-4 / SRS-3-3: bags exceeding the minimum specified limit, BEMSL = BPL - MLN; empty when
     * the output does not exceed the limit (no incentive).
     */
    public static Optional<BigDecimal> excessBags(BigDecimal bagsPerLabour, long minLabourNumber) {
        BigDecimal excess = bagsPerLabour.subtract(BigDecimal.valueOf(minLabourNumber));
        return excess.signum() > 0
                ? Optional.of(excess.setScale(BAGS, RoundingMode.HALF_UP))
                : Optional.empty();
    }

    /** SRS-3-5: the ordinary daily wage, ODW = Basic Pay / 26. */
    public static BigDecimal ordinaryDailyWage(BigDecimal basic) {
        return basic.divide(WORKING_DAYS, MONEY, RoundingMode.HALF_UP);
    }

    /** SRS-3-5: the per-bag wage, PBW = ((HRA + DA) x 12 / 365 + ODW) / MLN. */
    public static BigDecimal perBagWage(Pay pay, long minLabourNumber) {
        BigDecimal daily =
                pay.hra()
                        .add(pay.da())
                        .multiply(MONTHS)
                        .divide(DAYS_IN_YEAR, MONEY, RoundingMode.HALF_UP)
                        .add(ordinaryDailyWage(pay.incentiveBasic()));
        return daily.divide(BigDecimal.valueOf(minLabourNumber), MONEY, RoundingMode.HALF_UP);
    }

    /**
     * SRS-3-5 / SRS-3-6: the incentive of the excess bags, each step of the slab table paid at TPBW
     * = (R / 100) x PBW + PBW of its own band.
     *
     * <p>OPEN-QUESTION (D20-11): the SRS does not say whether a band's rate applies to the bags in
     * that band only (progressive, taken here) or to all excess bags once the band is reached.
     */
    public static BigDecimal slabIncentive(
            BigDecimal excessBags, BigDecimal perBagWage, List<Band> bands) {
        BigDecimal total = BigDecimal.ZERO;
        for (Band b : bands.stream().sorted(Comparator.comparingLong(Band::from)).toList()) {
            BigDecimal low = BigDecimal.valueOf(b.from() - 1);
            if (excessBags.compareTo(low) <= 0) {
                break;
            }
            BigDecimal high =
                    b.to() == null ? excessBags : excessBags.min(BigDecimal.valueOf(b.to()));
            BigDecimal bags = high.subtract(low);
            BigDecimal tpbw = perBagWage.multiply(b.ratePercent()).divide(HUNDRED).add(perBagWage);
            total = total.add(bags.multiply(tpbw));
        }
        return total.setScale(MONEY, RoundingMode.HALF_UP);
    }

    /**
     * SRS-3-7: the grand total per labour is the sum of the Handling, Height and Lead incentives.
     */
    public static BigDecimal grandTotal(BigDecimal handling, BigDecimal height, BigDecimal lead) {
        return handling.add(height).add(lead).setScale(MONEY, RoundingMode.HALF_UP);
    }

    /**
     * SRS-3-8: the Sardar and Mandal, outside the calculation, are paid the highest incentive of
     * the gang's labour, only when present during the work.
     */
    public static BigDecimal leaderShare(List<BigDecimal> labourIncentives, boolean present) {
        if (!present) {
            return BigDecimal.ZERO.setScale(MONEY);
        }
        return labourIncentives.stream()
                .max(Comparator.naturalOrder())
                .orElse(BigDecimal.ZERO)
                .setScale(MONEY, RoundingMode.HALF_UP);
    }

    /** SRS-3-6: the slab table of a bag weight: BAND_2 at or above the boundary, else BAND_1. */
    public static String slabTable(BigDecimal bagWeightKg, BigDecimal band2FromKg) {
        return bagWeightKg != null && bagWeightKg.compareTo(band2FromKg) >= 0 ? "BAND_2" : "BAND_1";
    }
}
