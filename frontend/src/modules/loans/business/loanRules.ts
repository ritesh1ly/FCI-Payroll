/**
 * I-04 Loans & Advances screen rules (plan 09 phase 09f; SRS-12-1, SRS-12-17, SRS-12-21,
 * SRS-12-22, SRS-12-23). The server decides; these only say what to offer.
 */
import type { LoanRow, LoanStatus } from '../api/loansApi';

const YYMM = /^\d{4}(0[1-9]|1[0-2])$/;
const OPEN: readonly LoanStatus[] = ['ACTIVE', 'RESCHEDULED', 'STOPPED'];

/** A month as the API takes it: YYYYMM. */
export function isYymm(value: string): boolean {
  return YYMM.test(value);
}

/** Whether the loan still carries an outstanding balance. */
export function isOpen(loan: Pick<LoanRow, 'status'>): boolean {
  return OPEN.includes(loan.status);
}

/**
 * SRS-12-17: an employee loan from HRMS changes only through HRMS (I-21); an I-04 loan that is
 * still being recovered may be foreclosed or rescheduled.
 */
export function changeable(loan: Pick<LoanRow, 'status' | 'source'>): boolean {
  return loan.source !== 'HRMS' && (loan.status === 'ACTIVE' || loan.status === 'RESCHEDULED');
}

/** The status the ledger draws: the record's status, and why a loan cannot be recovered. */
export type LedgerStatus = LoanRow['status'] | 'HALTED_SEPARATION' | 'SCHEDULE_OFF';

/** SRS-12-1, SRS-12-5: instalments that miss the principal come first, then a separation halt. */
export function ledgerStatus(
  loan: Pick<LoanRow, 'status' | 'separated' | 'scheduleReconciled'>,
): LedgerStatus {
  if (loan.scheduleReconciled === false) return 'SCHEDULE_OFF';
  return loan.status === 'STOPPED' && loan.separated === true ? 'HALTED_SEPARATION' : loan.status;
}

/** The pill of a ledger status as drawn. */
export function ledgerPill(status: LedgerStatus): string {
  if (status === 'SCHEDULE_OFF') return 'p-bad';
  if (status === 'STOPPED' || status === 'HALTED_SEPARATION') return 'p-warn';
  return status === 'ACTIVE' || status === 'RESCHEDULED' ? 'p-ok' : 'p-gray';
}

/** The first loan the Foreclose / Reschedule buttons can open: the one chosen, else the first changeable. */
export function changeTarget<T extends Pick<LoanRow, 'status' | 'source'>>(
  chosen: T | null,
  rows: readonly T[],
): T | null {
  return chosen && changeable(chosen) ? chosen : (rows.find(changeable) ?? null);
}

/** Rupees as crore with two decimals, as the drawn tiles read. */
export function crore(value: number): string {
  return (value / 10_000_000).toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

/** SRS-12-23: the recovery tracker: recovered, outstanding and the share recovered. */
export function tracker(
  loan: Pick<LoanRow, 'sanctioned' | 'principalOutstanding' | 'interestAmount' | 'interestOutstanding'>,
) {
  const total = loan.sanctioned + (loan.interestAmount ?? 0);
  const outstanding = (loan.principalOutstanding ?? 0) + (loan.interestOutstanding ?? 0);
  const recovered = Math.max(0, total - outstanding);
  return { total, recovered, outstanding, percent: total > 0 ? Math.round((recovered * 100) / total) : 0 };
}

/**
 * The sanction form is ready to check: an employee, a type, a positive amount and instalment, at
 * least one instalment, a recovery month; interest only with its instalments and amount.
 */
export function sanctionReady(f: {
  eis: string;
  loanType: string;
  sanctioned: string;
  principalInstalments: string;
  instalment: string;
  interestAmount: string;
  interestInstalments: string;
  interestInstalment: string;
  startYymm: string;
}): boolean {
  const positive = (v: string) => v.trim() !== '' && Number(v) > 0;
  const interest = f.interestAmount.trim() !== '' && Number(f.interestAmount) > 0;
  const interestOk = interest
    ? positive(f.interestInstalments) && positive(f.interestInstalment)
    : f.interestInstalments.trim() === '' || Number(f.interestInstalments) === 0;
  return (
    f.eis.trim() !== '' &&
    f.loanType !== '' &&
    positive(f.sanctioned) &&
    positive(f.principalInstalments) &&
    Number.isInteger(Number(f.principalInstalments)) &&
    positive(f.instalment) &&
    isYymm(f.startYymm) &&
    interestOk
  );
}

/**
 * SRS-12-4 / SRS-12-1: a reschedule is worked out on the remaining balance, interest apart. The
 * remaining interest needs its own instalments and amount (the server refuses INTEREST otherwise);
 * a loan with no interest outstanding takes none.
 */
export function rescheduleReady(
  loan: Pick<LoanRow, 'interestOutstanding'>,
  f: {
    instalments: string;
    instalment: string;
    interestInstalments: string;
    interestInstalment: string;
    start: string;
  },
): boolean {
  const positive = (v: string) => v.trim() !== '' && Number(v) > 0;
  const interestOk = hasInterestOutstanding(loan)
    ? positive(f.interestInstalments) &&
      Number.isInteger(Number(f.interestInstalments)) &&
      positive(f.interestInstalment)
    : f.interestInstalments.trim() === '' || Number(f.interestInstalments) === 0;
  return (
    positive(f.instalments) &&
    Number.isInteger(Number(f.instalments)) &&
    positive(f.instalment) &&
    isYymm(f.start) &&
    interestOk
  );
}

/** Whether the loan still owes interest, so a reschedule must spread it (SRS-12-1). */
export function hasInterestOutstanding(loan: Pick<LoanRow, 'interestOutstanding'>): boolean {
  return (loan.interestOutstanding ?? 0) > 0;
}
