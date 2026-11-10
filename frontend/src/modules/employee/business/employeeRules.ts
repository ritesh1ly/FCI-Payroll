import type { StatusCounts } from '../api/employeeApi';

/** M-01 / R-01 rules over the rows the server returned (pure functions). */

/** Record statuses that need the Payroll section's attention. */
export const EXCEPTION_STATUSES = ['PAN_CPF_MISSING', 'BANK_INVALID', 'CHANGE_PENDING'];

/** The head-count columns of R-01. */
export const HEADCOUNT_STATUSES = ['ACTIVE', 'CHANGE_PENDING', 'PAYROLL_SETUP_PENDING'] as const;

/** The M-01 tiles from the server's counts over the whole filtered scope (SRS-1-11): set-up pending, active, exceptions (EXCEPTION_STATUSES). */
export function statusCounts(counts: StatusCounts | undefined): {
  setup: number;
  active: number;
  exceptions: number;
} {
  if (!counts) return { setup: 0, active: 0, exceptions: 0 };
  return {
    setup: counts.payrollSetupPending,
    active: counts.active,
    exceptions: counts.panCpfMissing + counts.bankInvalid + counts.changePending,
  };
}
