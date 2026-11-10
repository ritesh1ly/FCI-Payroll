package in.gov.fci.payroll.payroll.api;

/**
 * A member computed in an Actual run whose payment is held (plan 16 phase 16d; SRS-1-7, SRS-1-41):
 * Salary Withheld (an approved HOLD event, paid on the release order), or the M-01 Hold or
 * Stop-Payment flag, or Absconding received from HRMS. Published in the result's transaction; the
 * X-05 worklist raises the exception (module 01), so the payment is not released while it is open.
 *
 * @param reason SALARY_WITHHELD, HOLD_FLAG, STOP_PAYMENT_FLAG or ABSCONDING
 */
public record MemberPaymentHeld(long cycleInstanceId, long employeeId, String reason) {}
