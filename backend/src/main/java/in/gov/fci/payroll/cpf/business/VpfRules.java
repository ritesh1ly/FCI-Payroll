package in.gov.fci.payroll.cpf.business;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.Optional;

/**
 * The member's VPF subscription change (plan 14 phase 14c; BR-ESEVA-006, SRS-6-1; DDL {@code
 * vpf_change_request}; D14-4). Pure functions: no state, no I/O.
 */
public final class VpfRules {

    /** DDL {@code requested_vpf_amount NUMBER(12,2)}. */
    static final BigDecimal MAX = new BigDecimal("9999999999.99");

    /** DDL {@code vpf_change_request.request_status}. */
    public enum Status {
        SUBMITTED,
        APPROVED,
        REJECTED,
        RETURNED,
        APPLIED,
        CANCELLED
    }

    private VpfRules() {}

    /**
     * What is wrong with the requested monthly VPF: AMOUNT (missing, negative, more than two
     * decimals or above NUMBER(12,2)) or UNCHANGED (the subscription in force already).
     */
    public static Optional<String> defect(BigDecimal requested, BigDecimal current) {
        if (requested == null
                || requested.signum() < 0
                || requested.stripTrailingZeros().scale() > 2
                || requested.compareTo(MAX) > 0) {
            return Optional.of("AMOUNT");
        }
        BigDecimal now = current == null ? BigDecimal.ZERO : current;
        if (requested.compareTo(now) == 0) {
            return Optional.of("UNCHANGED");
        }
        return Optional.empty();
    }

    /**
     * D14-4: the change applies from the member's next open payroll month: this month, or the next
     * one once this month's payroll is locked.
     */
    public static YearMonth effectiveMonth(YearMonth current, boolean currentLocked) {
        return currentLocked ? current.plusMonths(1) : current;
    }

    /** The financial year of a month as the CPF ledger keys it ("2026-27"). */
    public static String finYear(YearMonth month) {
        int start = month.getMonthValue() >= 4 ? month.getYear() : month.getYear() - 1;
        return String.format("%d-%02d", start, (start + 1) % 100);
    }

    /** YYYYMM. */
    public static String yymm(YearMonth month) {
        return String.format("%04d%02d", month.getYear(), month.getMonthValue());
    }

    /** BR-ESEVA-006: the Payroll Maker verifies a request just made or one the Checker returned. */
    public static boolean verifiable(Status status, boolean awaitingChecker) {
        return status == Status.SUBMITTED && !awaitingChecker;
    }
}
