package in.gov.fci.payroll.loans.api;

import java.math.BigDecimal;

/**
 * SRS-12-10 / BR-LOAN-009 (plan 09 phase 09e): a foreclosure or reschedule changed the member's
 * recovery instalment, so net pay, from {@code fromYymm}; published in the decision's transaction
 * for the member-facing notification of eSeva (module 14, PENDING 09-notify).
 *
 * @param newInstalment the instalment from then on (zero after a foreclosure)
 */
public record LoanInstalmentChanged(
        long employeeId,
        String loanNumber,
        String change,
        BigDecimal newInstalment,
        String fromYymm) {}
