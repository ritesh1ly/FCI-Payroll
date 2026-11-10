package in.gov.fci.payroll.payroll.business;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

/**
 * The rules of an I-19 Maintain Direct Payroll entry (plan 05 phase 05g), as pure functions.
 *
 * <ul>
 *   <li>SRS-15-2 / SRS-15-19: entries only in the supplementary / MDP window of a locked period.
 *   <li>SRS I-19 field table: the amount is a deduction only (no credit through this screen),
 *       Numeric (12,2).
 *   <li>SRS-G-1 / SRS-G-37: an entry is amended or cancelled through maker-checker, never while
 *       another decision on it waits, never once cancelled or posted.
 * </ul>
 */
public final class MdpRules {

    /** DDL {@code mdp_transaction.mdp_status}. */
    public enum Status {
        ENTERED,
        AMENDED,
        APPROVED,
        CANCELLED,
        POSTED
    }

    private static final BigDecimal LIMIT = new BigDecimal("9999999999.99");

    private MdpRules() {}

    /** The MDP window of the period is open on {@code today} (both ends inclusive). */
    public static boolean windowOpen(LocalDate today, LocalDate from, LocalDate to) {
        return from != null && to != null && !today.isBefore(from) && !today.isAfter(to);
    }

    /**
     * What is wrong with the amount: NOT_A_DEDUCTION (zero or negative: no credit through I-19),
     * TOO_PRECISE (more than two decimals), TOO_LARGE (beyond Numeric 12,2).
     */
    public static Optional<String> amountDefect(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            return Optional.of("NOT_A_DEDUCTION");
        }
        if (amount.stripTrailingZeros().scale() > 2) {
            return Optional.of("TOO_PRECISE");
        }
        return amount.compareTo(LIMIT) > 0 ? Optional.of("TOO_LARGE") : Optional.empty();
    }

    /**
     * Why the entry may not be amended or cancelled now (its status, or PENDING while a decision on
     * it waits), or empty. A cancelled or posted entry is final; an entered, amended or approved
     * one may be changed under maker-checker.
     */
    public static Optional<String> changeDefect(Status status, boolean decisionPending) {
        if (status == Status.CANCELLED || status == Status.POSTED) {
            return Optional.of(status.name());
        }
        return decisionPending ? Optional.of("PENDING") : Optional.empty();
    }
}
