package in.gov.fci.payroll.penalty.business;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The penalty / recovery order rules (plan 12 phase 12a; SRS-14-1, SRS-14-15, SRS-G-19; I-06 field
 * table). Nothing here is a rate or limit; the deduction ceiling is the loans' rule ({@code
 * loans.api.DeductionCeiling}, SRS-14-7) and the net-pay floor is M-14's ({@code NetPayFloors}).
 */
public final class PenaltyRules {

    /** One month of a recovery schedule. */
    public record Line(String payrollYymm, BigDecimal amount) {}

    private static final DateTimeFormatter YYMM = DateTimeFormatter.ofPattern("uuuuMM");

    /** I-06 "Number of Instalments": Numeric (3). */
    public static final int MAX_INSTALMENTS = 999;

    private PenaltyRules() {}

    /**
     * SRS-14-15, BR-PEN2-007: the system-generated Penalty ID, e.g. PEN-2026-00041 (the DDL's own
     * example; plan 12 D12-12).
     */
    public static String penaltyId(int year, long sequence) {
        return String.format("PEN-%04d-%05d", year, sequence);
    }

    /**
     * What is wrong with an order's recovery terms, or empty: AMOUNT (not above zero, or more than
     * paise), INSTALMENTS (fewer than one, more than 999, or more than the amount has rupees),
     * START (not a month, or before the first open month: "open or future cycles").
     */
    public static Optional<String> defect(
            BigDecimal amount, int instalments, String startYymm, YearMonth firstOpen) {
        if (amount == null || amount.signum() <= 0 || amount.stripTrailingZeros().scale() > 2) {
            return Optional.of("AMOUNT");
        }
        if (instalments < 1
                || instalments > MAX_INSTALMENTS
                || regular(amount, instalments).signum() <= 0) {
            return Optional.of("INSTALMENTS");
        }
        Optional<YearMonth> start = month(startYymm);
        if (start.isEmpty() || start.get().isBefore(firstOpen)) {
            return Optional.of("START");
        }
        return Optional.empty();
    }

    /**
     * SRS-14-1, I-06 "Per-period deduction = Recovery Amount ÷ Number of Instalments": each month
     * the amount ÷ instalments rounded down to the rupee, the last month the residual (SRS-G-19),
     * so the schedule adds up to the amount exactly and the last instalment is never below the
     * others (plan 12 D12-3).
     */
    public static List<Line> schedule(BigDecimal amount, int instalments, String startYymm) {
        YearMonth month = month(startYymm).orElseThrow();
        BigDecimal each = regular(amount, instalments);
        List<Line> lines = new ArrayList<>();
        BigDecimal scheduled = BigDecimal.ZERO;
        for (int i = 1; i <= instalments; i++) {
            BigDecimal line = i == instalments ? amount.subtract(scheduled) : each;
            scheduled = scheduled.add(line);
            lines.add(new Line(month.format(YYMM), line.setScale(2, RoundingMode.UNNECESSARY)));
            month = month.plusMonths(1);
        }
        return List.copyOf(lines);
    }

    /** One month of an order as a revoke sees it. */
    public record Month(
            String payrollYymm, BigDecimal scheduled, BigDecimal recovered, boolean due) {}

    /**
     * What a revoke or cancellation does (SRS-14-16, SRS-14-17, SRS-14-20, BR-PEN2-008):
     *
     * @param waive the months no longer recovered (index into the order's months)
     * @param reduce a month still due recovered in part: its index and new scheduled amount
     * @param reversal what was already recovered within the waiver: the reversing arrear due
     */
    public record Waiver(List<Integer> waive, Optional<Reduce> reduce, BigDecimal reversal) {}

    /** A month whose scheduled amount falls by part of the waiver. */
    public record Reduce(int index, BigDecimal scheduled) {}

    /**
     * SRS-14-16: waives {@code amount} of the order (null: the whole order). The months still due
     * are waived from the last backwards (the recovery ends earlier); a month only partly covered
     * is reduced; what the waiver exceeds the amount still due by was already recovered and becomes
     * the reversing arrear.
     */
    public static Waiver waiveAmount(List<Month> months, BigDecimal amount) {
        List<Integer> waive = new ArrayList<>();
        BigDecimal left = amount;
        Optional<Reduce> reduce = Optional.empty();
        for (int i = months.size() - 1; i >= 0; i--) {
            Month m = months.get(i);
            if (!m.due() || (left != null && left.signum() <= 0)) {
                continue;
            }
            if (left == null || left.compareTo(m.scheduled()) >= 0) {
                waive.add(i);
                left = left == null ? null : left.subtract(m.scheduled());
            } else {
                reduce = Optional.of(new Reduce(i, m.scheduled().subtract(left)));
                left = BigDecimal.ZERO;
            }
        }
        BigDecimal reversal =
                left == null
                        ? months.stream()
                                .map(Month::recovered)
                                .reduce(BigDecimal.ZERO, BigDecimal::add)
                        : left.max(BigDecimal.ZERO);
        return new Waiver(List.copyOf(waive), reduce, reversal);
    }

    /**
     * SRS-14-16: waives the months {@code from} … {@code to} (YYYYMM, both included): those still
     * due are waived, and what was recovered in them becomes the reversing arrear.
     */
    public static Waiver waivePeriod(List<Month> months, String from, String to) {
        List<Integer> waive = new ArrayList<>();
        BigDecimal reversal = BigDecimal.ZERO;
        for (int i = 0; i < months.size(); i++) {
            Month m = months.get(i);
            if (m.payrollYymm().compareTo(from) < 0 || m.payrollYymm().compareTo(to) > 0) {
                continue;
            }
            if (m.due()) {
                waive.add(i);
            }
            reversal = reversal.add(m.recovered());
        }
        return new Waiver(List.copyOf(waive), Optional.empty(), reversal);
    }

    /** One month's part of the reversing arrear: what is refunded of that month's recovery. */
    public record Refund(String payrollYymm, BigDecimal amount) {}

    /**
     * SRS-14-16 / SRS-21-10 (plan 13 phase 13g, D13-14): the reversing arrear month by month. The
     * {@code reversal} is taken from what was recovered in the months {@code from} … {@code to}
     * (null: every month), the latest first, so a partial waiver refunds the most recent
     * recoveries; the result is in month order.
     */
    public static List<Refund> refunds(
            List<Month> months, BigDecimal reversal, String from, String to) {
        List<Refund> out = new ArrayList<>();
        BigDecimal left = reversal;
        for (int i = months.size() - 1; i >= 0 && left.signum() > 0; i--) {
            Month m = months.get(i);
            boolean inside =
                    from == null
                            || (m.payrollYymm().compareTo(from) >= 0
                                    && m.payrollYymm().compareTo(to) <= 0);
            if (!inside || m.recovered().signum() <= 0) {
                continue;
            }
            BigDecimal part = m.recovered().min(left);
            out.addFirst(new Refund(m.payrollYymm(), part));
            left = left.subtract(part);
        }
        return List.copyOf(out);
    }

    /** A YYYYMM month, if it is one. */
    public static Optional<YearMonth> month(String yymm) {
        if (yymm == null || !yymm.matches("\\d{6}")) {
            return Optional.empty();
        }
        try {
            return Optional.of(YearMonth.parse(yymm, YYMM));
        } catch (DateTimeParseException notAMonth) {
            return Optional.empty();
        }
    }

    private static BigDecimal regular(BigDecimal amount, int instalments) {
        return amount.divide(BigDecimal.valueOf(instalments), 0, RoundingMode.DOWN);
    }
}
