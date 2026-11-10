package in.gov.fci.payroll.nps.business;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The NPS record received from HRMS (plan 18 phase 18a; SRS-9-1, SRS-9-1a, SRS-9-2, BR-PEN-001 …
 * 004; DDL {@code nps_deduction}). Payroll never computes an NPS amount: it classifies what HRMS
 * sent and deducts the employee amount as received.
 */
public final class NpsRules {

    /** DDL {@code nps_deduction.mismatch_status}. */
    public enum Mismatch {
        CONSISTENT,
        FLAG_WITHOUT_AMOUNT,
        AMOUNT_WITHOUT_FLAG,
        RESOLVED_IN_HRMS;

        /** A mismatch holds the lock (SRS-9-1a). */
        public boolean blocksLock() {
            return this == FLAG_WITHOUT_AMOUNT || this == AMOUNT_WITHOUT_FLAG;
        }
    }

    /**
     * PRAN as the I-14 field: alphanumeric, at most 12 (a reference; never checked with the CRA).
     */
    private static final Pattern PRAN = Pattern.compile("^[A-Za-z0-9]{1,12}$");

    private static final DateTimeFormatter YYMM = DateTimeFormatter.ofPattern("uuuuMM");

    /** DDL NUMBER(12,2): ten integer digits. */
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("9999999999.99");

    private NpsRules() {}

    /**
     * SRS-9-1a / BR-PEN-001: a flag without an employee amount, or an amount (employee or employer)
     * without the flag, is a mismatch; never defaulted. A record that was a mismatch and is now
     * consistent is RESOLVED_IN_HRMS.
     *
     * <p>OPEN-QUESTION (PENDING 18-zero-amount): the SRS does not say whether a flagged member with
     * an employee amount of zero is "a flag without an amount"; it is treated as one, so the lock
     * waits for HRMS rather than deducting nothing silently.
     */
    public static Mismatch status(
            boolean npsFlag,
            BigDecimal employeeAmount,
            BigDecimal employerAmount,
            Mismatch before) {
        Mismatch now;
        if (npsFlag) {
            now = positive(employeeAmount) ? Mismatch.CONSISTENT : Mismatch.FLAG_WITHOUT_AMOUNT;
        } else {
            now =
                    positive(employeeAmount) || positive(employerAmount)
                            ? Mismatch.AMOUNT_WITHOUT_FLAG
                            : Mismatch.CONSISTENT;
        }
        if (now == Mismatch.CONSISTENT && before != null && before.blocksLock()) {
            return Mismatch.RESOLVED_IN_HRMS;
        }
        return now;
    }

    /**
     * What is wrong with the record as received: NO_EMPLOYEE, PERIOD, AMOUNT (negative, more than
     * two decimals or above NUMBER(12,2)), PRAN (not alphanumeric of at most 12), EFFECTIVE_DATE
     * (after the month).
     */
    public static Optional<String> defect(
            String eis,
            String payrollYymm,
            BigDecimal employeeAmount,
            BigDecimal employerAmount,
            String pran,
            LocalDate effectiveFrom) {
        if (eis == null || eis.isBlank()) {
            return Optional.of("NO_EMPLOYEE");
        }
        Optional<YearMonth> month = month(payrollYymm);
        if (month.isEmpty()) {
            return Optional.of("PERIOD");
        }
        if (!amount(employeeAmount) || !amount(employerAmount)) {
            return Optional.of("AMOUNT");
        }
        if (pran != null && !PRAN.matcher(pran).matches()) {
            return Optional.of("PRAN");
        }
        if (effectiveFrom != null && effectiveFrom.isAfter(month.get().atEndOfMonth())) {
            return Optional.of("EFFECTIVE_DATE");
        }
        return Optional.empty();
    }

    /**
     * SRS-9-23 (US-GAP-291, OD-55): whether a later record of a month still open changes what the
     * month pays, so it applies only through its I-21 task. It does when the month held a
     * consistent record (CONSISTENT or RESOLVED_IN_HRMS) and the new record is consistent too, but
     * differs in the NPS flag, the employee amount or the employer amount (compared as amounts: no
     * amount is none, and 1500 is 1500.00). A change of the PRAN (a reference, SRS-9-2) or of the
     * effective date alone has no pay effect; a new record that is a mismatch is raised on X-05 at
     * once (SRS-9-1a); a record correcting a mismatch is the month's first consistent one.
     */
    public static boolean payEffect(
            Mismatch before,
            boolean flagBefore,
            BigDecimal employeeBefore,
            BigDecimal employerBefore,
            Mismatch now,
            boolean flag,
            BigDecimal employeeAmount,
            BigDecimal employerAmount) {
        if (before == null || before.blocksLock() || now.blocksLock()) {
            return false;
        }
        return flagBefore != flag
                || !sameAmount(employeeBefore, employeeAmount)
                || !sameAmount(employerBefore, employerAmount);
    }

    private static boolean sameAmount(BigDecimal a, BigDecimal b) {
        return (a == null ? BigDecimal.ZERO : a).compareTo(b == null ? BigDecimal.ZERO : b) == 0;
    }

    /** YYYYMM as a month, if it is one. */
    public static Optional<YearMonth> month(String payrollYymm) {
        if (payrollYymm == null || payrollYymm.length() != 6) {
            return Optional.empty();
        }
        try {
            return Optional.of(YearMonth.parse(payrollYymm, YYMM));
        } catch (DateTimeParseException notAMonth) {
            return Optional.empty();
        }
    }

    /**
     * SRS-9-18: no NPS from the separation date: a member separated before the month starts has
     * none for the month (the month of separation is deducted as received).
     *
     * <p>OPEN-QUESTION CLR-130 (BMAD TR-042) and CLR-59: whether a separation dated the 1st leaves
     * that month's NPS undeducted is not stated; as built it is deducted.
     */
    public static boolean separatedBefore(LocalDate separationDate, YearMonth month) {
        return separationDate != null && separationDate.isBefore(month.atDay(1));
    }

    private static boolean positive(BigDecimal amount) {
        return amount != null && amount.signum() > 0;
    }

    private static boolean amount(BigDecimal amount) {
        return amount == null
                || (amount.signum() >= 0
                        && amount.scale() <= 2
                        && amount.compareTo(MAX_AMOUNT) <= 0);
    }
}
