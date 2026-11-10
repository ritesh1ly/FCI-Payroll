package in.gov.fci.payroll.arrear.business;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The payment of approved arrears in a cycle (plan 13 phase 13a; SRS-8-3, SRS-8-17, SRS-8-21;
 * D13-1, D13-2). Pure: no framework, no I/O.
 *
 * <p>The member's differentials are summed per component (the pay code they belong to): a component
 * with a positive total is paid as an earning under its arrear pay code, apart from the current
 * earnings; the components with a negative total make the recovery, taken only as far as the net
 * pay stays above the floor; the rest is carried to the next open cycle.
 */
public final class ArrearRules {

    private ArrearRules() {}

    /** One differential of a member: the component's pay code and the signed amount. */
    public record Differential(int payCode, BigDecimal amount) {}

    /** What the member is paid and what is recovered. */
    public record Split(Map<Integer, BigDecimal> earnings, BigDecimal recovery) {}

    /** SRS-8-3 / 8-21: positive component totals to pay, negative ones to recover. */
    public static Split split(List<Differential> differentials) {
        Map<Integer, BigDecimal> totals = new TreeMap<>();
        for (Differential d : differentials) {
            totals.merge(d.payCode(), d.amount(), BigDecimal::add);
        }
        Map<Integer, BigDecimal> pay = new TreeMap<>();
        BigDecimal recover = BigDecimal.ZERO;
        for (Map.Entry<Integer, BigDecimal> e : totals.entrySet()) {
            if (e.getValue().signum() > 0) {
                pay.put(e.getKey(), e.getValue());
            } else if (e.getValue().signum() < 0) {
                recover = recover.add(e.getValue().negate());
            }
        }
        return new Split(pay, recover);
    }

    /** One component's differential of a month: what was paid, what is due, the difference. */
    public record Recomputed(int payCode, BigDecimal original, BigDecimal recomputed) {
        public BigDecimal arrear() {
            return recomputed.subtract(original);
        }
    }

    /**
     * SRS-8-3 / 8-13: the differentials of one month, component by component: what is due now (the
     * recompute) less what was paid; a component on one side only counts as zero on the other; a
     * component without a difference is left out.
     */
    public static List<Recomputed> differentials(
            Map<Integer, BigDecimal> paid, Map<Integer, BigDecimal> due) {
        Map<Integer, Recomputed> out = new TreeMap<>();
        for (Map.Entry<Integer, BigDecimal> e : paid.entrySet()) {
            out.put(
                    e.getKey(),
                    new Recomputed(
                            e.getKey(),
                            e.getValue(),
                            due.getOrDefault(e.getKey(), BigDecimal.ZERO)));
        }
        for (Map.Entry<Integer, BigDecimal> e : due.entrySet()) {
            out.putIfAbsent(e.getKey(), new Recomputed(e.getKey(), BigDecimal.ZERO, e.getValue()));
        }
        return out.values().stream().filter(r -> r.arrear().signum() != 0).toList();
    }

    /**
     * SRS-8-6 (BR-EMPR-007): a back-dated change reaches further back than the Headquarters limit
     * when its first month is before the current month less the limit (the same reach as the C-13
     * arrear jobs). Such an event is computed only as a Headquarters-approved exception.
     *
     * @param maxRetroMonths the back-date limit in months (A-07 MAX_RETRO_PERIOD)
     */
    public static boolean beyondWindow(
            LocalDate effectiveFrom, YearMonth current, int maxRetroMonths) {
        return YearMonth.from(effectiveFrom).isBefore(current.minusMonths(maxRetroMonths));
    }

    /**
     * SRS-8-17: the recovery taken this month: at most what keeps the net pay at the floor; never
     * below zero. The same rule recovers a leave recovery (SRS-LV-19, BR-LEAVE-011): the leave
     * module raises it as an arrear event (source LEAVE) and the rest is carried to the next open
     * cycle and into the settlement (ArrearLockEffect, SettlementWork).
     *
     * @param deducted what the earlier deductions took
     * @param floor the net-pay floor amount of the month
     */
    public static BigDecimal recoverable(
            BigDecimal due, BigDecimal gross, BigDecimal deducted, BigDecimal floor) {
        BigDecimal room = gross.subtract(deducted).subtract(floor);
        return due.min(room.max(BigDecimal.ZERO));
    }
}
