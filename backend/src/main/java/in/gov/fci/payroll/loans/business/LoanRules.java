package in.gov.fci.payroll.loans.business;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The recovery schedule of a loan or advance (plan 09 phase 09a; SRS-12-1, SRS-12-3, SRS-12-4,
 * SRS-12-5, SRS-12-6, SRS-12-11, SRS-12-13, SRS-12-14, SRS-12-16, SRS-G-19; D09-2, D09-3, D09-6,
 * D09-9). Payroll never computes interest: the interest amount and its instalments are as HRMS or
 * the sanction order give them. Every amount is a {@code BigDecimal} at two decimals; nothing here
 * is rounded except the final instalment, which takes the residual (SRS-G-19).
 */
public final class LoanRules {

    /** One instalment: its number, month (YYYYMM), principal and interest. */
    public record Line(int number, String payrollYymm, BigDecimal principal, BigDecimal interest) {

        public BigDecimal total() {
            return principal.add(interest);
        }
    }

    /**
     * What the sanction (or the order) states.
     *
     * @param firstInstalment the first principal instalment if it differs, else null
     * @param interestInstalment the interest instalment amount, required with interest (D09-2)
     */
    public record Terms(
            BigDecimal sanctioned,
            int principalInstalments,
            BigDecimal instalment,
            BigDecimal firstInstalment,
            BigDecimal interestAmount,
            int interestInstalments,
            BigDecimal interestInstalment,
            String startYymm) {}

    /** The instalment and tenure that fit the deduction ceiling (SRS-12-13, SRS-12-14). */
    public record Fit(BigDecimal instalment, int instalments, boolean reduced, boolean fits) {}

    private static final DateTimeFormatter YYMM = DateTimeFormatter.ofPattern("uuuuMM");

    private LoanRules() {}

    /**
     * I-04 field table (SRS L2713, SRS-12-1): interest instalments are "Required where the
     * loan/advance type carries interest", and a type that bears none takes none:
     * INTEREST_NOT_BORNE (interest on a type without), INTEREST_REQUIRED (none on a type with).
     */
    public static Optional<String> interestDefect(boolean interestBearing, Terms t) {
        if (!interestBearing && t.interestInstalments() > 0) {
            return Optional.of("INTEREST_NOT_BORNE");
        }
        if (interestBearing && t.interestInstalments() < 1) {
            return Optional.of("INTEREST_REQUIRED");
        }
        return Optional.empty();
    }

    /**
     * What is wrong with the terms: AMOUNT (sanction not positive, or an amount with more than two
     * decimals), INSTALMENTS (no principal instalment), INSTALMENT (not positive), START (not a
     * month), INTEREST (an interest amount without instalments or an instalment amount, or the
     * reverse), NOT_RECONCILED (the instalments do not add up to the sanction or the interest:
     * SRS-12-6, D09-9).
     */
    public static Optional<String> defect(Terms t) {
        if (!positive(t.sanctioned())
                || !paise(
                        t.sanctioned(),
                        t.instalment(),
                        t.firstInstalment(),
                        t.interestAmount(),
                        t.interestInstalment())) {
            return Optional.of("AMOUNT");
        }
        if (t.principalInstalments() < 1) {
            return Optional.of("INSTALMENTS");
        }
        if (!positive(t.instalment())
                || (t.firstInstalment() != null && !positive(t.firstInstalment()))) {
            return Optional.of("INSTALMENT");
        }
        if (month(t.startYymm()).isEmpty()) {
            return Optional.of("START");
        }
        boolean interest = positive(t.interestAmount());
        if (interest != (t.interestInstalments() > 0)
                || interest != positive(t.interestInstalment())) {
            return Optional.of("INTEREST");
        }
        if (!reconciles(t)) {
            return Optional.of("NOT_RECONCILED");
        }
        return Optional.empty();
    }

    /**
     * SRS-12-6 / D09-9: the principal instalments add up to the sanction: the final instalment, the
     * residual after the others, is positive and exceeds a regular instalment by no more than the
     * rupee rounding of the instalments before it (one rupee each).
     */
    public static boolean reconciles(Terms t) {
        if (!fair(residual(t), t.instalment(), t.principalInstalments())) {
            return false;
        }
        if (t.interestInstalments() < 1) {
            return true;
        }
        BigDecimal interestResidual =
                t.interestAmount()
                        .subtract(
                                t.interestInstalment()
                                        .multiply(
                                                BigDecimal.valueOf(t.interestInstalments() - 1L)));
        return fair(interestResidual, t.interestInstalment(), t.interestInstalments());
    }

    /** A final instalment: positive, at most a regular one plus a rupee per earlier instalment. */
    private static boolean fair(BigDecimal residual, BigDecimal instalment, int count) {
        BigDecimal slack = BigDecimal.valueOf(Math.max(0, count - 1));
        return residual.signum() > 0 && residual.compareTo(instalment.add(slack)) <= 0;
    }

    /**
     * SRS-12-1 / D09-3: the schedule, principal instalments first and then interest instalments,
     * one a month from the start month; each part's final instalment is its residual (SRS-G-19).
     */
    public static List<Line> schedule(Terms t) {
        YearMonth month = month(t.startYymm()).orElseThrow();
        List<Line> lines = new ArrayList<>();
        BigDecimal paid = BigDecimal.ZERO;
        for (int i = 1; i <= t.principalInstalments(); i++) {
            BigDecimal amount =
                    i == t.principalInstalments()
                            ? t.sanctioned().subtract(paid)
                            : (i == 1 && t.firstInstalment() != null
                                    ? t.firstInstalment()
                                    : t.instalment());
            paid = paid.add(amount);
            lines.add(new Line(i, yymm(month), scale(amount), zero()));
            month = month.plusMonths(1);
        }
        BigDecimal interestPaid = BigDecimal.ZERO;
        for (int i = 1; i <= t.interestInstalments(); i++) {
            BigDecimal amount =
                    i == t.interestInstalments()
                            ? t.interestAmount().subtract(interestPaid)
                            : t.interestInstalment();
            interestPaid = interestPaid.add(amount);
            lines.add(new Line(t.principalInstalments() + i, yymm(month), zero(), scale(amount)));
            month = month.plusMonths(1);
        }
        return List.copyOf(lines);
    }

    /**
     * SRS-12-13 / SRS-12-14: the instalment that keeps the month's deductions within the ceiling (a
     * percentage of gross): unchanged when it fits; otherwise reduced to the room left (whole
     * rupees) and the tenure extended to recover the same amount. With no room at all the schedule
     * does not fit and is never activated; the sanction itself is not rejected for it.
     */
    public static Fit fit(
            BigDecimal amount,
            BigDecimal instalment,
            BigDecimal gross,
            BigDecimal existingDeductions,
            BigDecimal ceilingPercent) {
        BigDecimal room =
                gross.multiply(ceilingPercent)
                        .divide(BigDecimal.valueOf(100), 2, RoundingMode.DOWN)
                        .subtract(existingDeductions);
        int tenure = tenure(amount, instalment);
        if (instalment.compareTo(room) <= 0) {
            return new Fit(scale(instalment), tenure, false, true);
        }
        BigDecimal reduced = room.setScale(0, RoundingMode.DOWN);
        if (reduced.signum() <= 0) {
            return new Fit(scale(instalment), tenure, false, false);
        }
        return new Fit(scale(reduced), tenure(amount, reduced), true, true);
    }

    /** SRS-12-11: the largest new sanction the combined ceiling still allows. */
    public static BigDecimal maxNewSanction(BigDecimal combinedCeiling, BigDecimal outstanding) {
        BigDecimal room = combinedCeiling.subtract(outstanding);
        return scale(room.max(BigDecimal.ZERO));
    }

    /** SRS-12-16 / BR-LOAN-005: the outstanding principal and interest, cleared in one event. */
    public static BigDecimal foreclosureAmount(BigDecimal principal, BigDecimal interest) {
        return scale(nz(principal).add(nz(interest)));
    }

    /**
     * SRS-12-5 / F-1 / D09-6: after a reversed separation the instalments not recovered follow from
     * the next open month, the same amounts in the same order (the tenure extends).
     */
    public static List<Line> resume(List<Line> notRecovered, String fromYymm) {
        YearMonth month = month(fromYymm).orElseThrow();
        List<Line> out = new ArrayList<>();
        for (Line l : notRecovered) {
            out.add(new Line(l.number(), yymm(month), l.principal(), l.interest()));
            month = month.plusMonths(1);
        }
        return List.copyOf(out);
    }

    /** YYYYMM as a month, if it is one. */
    public static Optional<YearMonth> month(String yymm) {
        if (yymm == null || yymm.length() != 6) {
            return Optional.empty();
        }
        try {
            return Optional.of(YearMonth.parse(yymm, YYMM));
        } catch (DateTimeParseException notAMonth) {
            return Optional.empty();
        }
    }

    private static BigDecimal residual(Terms t) {
        BigDecimal before =
                t.instalment().multiply(BigDecimal.valueOf(t.principalInstalments() - 1L));
        if (t.firstInstalment() != null && t.principalInstalments() > 1) {
            before = before.subtract(t.instalment()).add(t.firstInstalment());
        }
        return t.sanctioned().subtract(before);
    }

    private static int tenure(BigDecimal amount, BigDecimal instalment) {
        return amount.divide(instalment, 0, RoundingMode.UP).intValueExact();
    }

    private static String yymm(YearMonth m) {
        return String.format("%04d%02d", m.getYear(), m.getMonthValue());
    }

    private static boolean paise(BigDecimal... amounts) {
        for (BigDecimal a : amounts) {
            if (a != null && a.stripTrailingZeros().scale() > 2) {
                return false;
            }
        }
        return true;
    }

    private static boolean positive(BigDecimal v) {
        return v != null && v.signum() > 0;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(2);
    }

    private static BigDecimal scale(BigDecimal v) {
        return v.setScale(2, RoundingMode.UNNECESSARY);
    }
}
