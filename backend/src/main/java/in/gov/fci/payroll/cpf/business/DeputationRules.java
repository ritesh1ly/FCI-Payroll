package in.gov.fci.payroll.cpf.business;

import in.gov.fci.payroll.cpf.business.model.CpfDeputationRecovery.EntryType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The I-09 deputation adjustment rules (plan 07 phase 07g; SRS-6-27 … 6-30, BR-CPF-021 … 023).
 * Amounts are rupees and paise; nothing here is a rate or limit.
 */
public final class DeputationRules {

    private static final DateTimeFormatter YYMM = DateTimeFormatter.ofPattern("yyyyMM");

    private DeputationRules() {}

    /** SRS-6-28, BR-CPF-021: outstanding = sanctioned amount − the approved instalments. */
    public static BigDecimal outstanding(BigDecimal sanctioned, Collection<BigDecimal> approved) {
        return approved.stream().reduce(sanctioned, BigDecimal::subtract);
    }

    /**
     * SRS-6-29, BR-CPF-022: the balance after an instalment; empty when it would go below zero (the
     * instalment is refused, the excess recorded apart, never netted).
     */
    public static Optional<BigDecimal> after(BigDecimal outstanding, BigDecimal instalment) {
        BigDecimal left = outstanding.subtract(instalment);
        return left.signum() < 0 ? Optional.empty() : Optional.of(left);
    }

    /**
     * What is wrong with an entry, or empty: AMOUNT (an instalment or excess not above zero, a
     * contribution with a negative part or nothing received, more than paise), MONTH (not a month
     * of the deputation: from its first month to its last, or open-ended).
     */
    public static Optional<String> defect(
            EntryType type,
            BigDecimal instalment,
            BigDecimal employee,
            BigDecimal employer,
            BigDecimal vpf,
            String yymm,
            LocalDate from,
            LocalDate to) {
        boolean amounts =
                type == EntryType.CONTRIBUTION_RECEIPT
                        ? paise(employee)
                                && paise(employer)
                                && paise(vpf)
                                && employee.add(employer).add(vpf).signum() > 0
                        : paise(instalment) && instalment.signum() > 0;
        if (!amounts) {
            return Optional.of("AMOUNT");
        }
        Optional<YearMonth> month = month(yymm);
        if (month.isEmpty()
                || month.get().isBefore(YearMonth.from(from))
                || (to != null && month.get().isAfter(YearMonth.from(to)))) {
            return Optional.of("MONTH");
        }
        return Optional.empty();
    }

    /**
     * SRS-6-30: the months of the deputation up to {@code until} with no approved contribution
     * receipt (flagged on the CPF Control Register), oldest first.
     */
    public static List<String> monthsWithoutReceipt(
            LocalDate from, LocalDate to, YearMonth until, Set<String> received) {
        YearMonth last = to == null ? until : min(YearMonth.from(to), until);
        List<String> missing = new ArrayList<>();
        for (YearMonth m = YearMonth.from(from); !m.isAfter(last); m = m.plusMonths(1)) {
            String yymm = m.format(YYMM);
            if (!received.contains(yymm)) {
                missing.add(yymm);
            }
        }
        return missing;
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

    private static YearMonth min(YearMonth a, YearMonth b) {
        return a.isBefore(b) ? a : b;
    }

    private static boolean paise(BigDecimal v) {
        return v != null && v.signum() >= 0 && v.stripTrailingZeros().scale() <= 2;
    }
}
