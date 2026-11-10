import { isAsciiText } from '@/modules/general';
import type { Balances, CorrectionStatus, LedgerPart, NewCorrection } from '../api/cpfApi';

/**
 * The rules of an I-22 opening-balance correction as the screen checks them before submission
 * (SRS-6-43, SRS-6-44, BR-CPF-025, SRS I-22 field table). They mirror the server's
 * (`CorrectionRules`, `OpeningBalanceController`); the server checks again. Amounts are compared in
 * paise as integers, never as binary fractions, so 0.1 + 0.2 never differs from 0.3.
 */

export const PARTS: readonly LedgerPart[] = ['EMPLOYEE', 'EMPLOYER', 'VPF'];

/** The request field (and balance key) of each part. */
export const FIELD: Record<LedgerPart, 'employee' | 'employer' | 'vpf'> = {
  EMPLOYEE: 'employee',
  EMPLOYER: 'employer',
  VPF: 'vpf',
};

/** The status pill of a correction. */
export const CORRECTION_STATUS_PILL: Record<CorrectionStatus, string> = {
  DRAFT: 'p-gray',
  PENDING_AGM_CPF: 'p-warn',
  PENDING_HQ_CPF: 'p-warn',
  RETURNED: 'p-info',
  APPROVED: 'p-info',
  REJECTED: 'p-bad',
  BLOCKED_CPF_DIVISION: 'p-bad',
  APPLIED: 'p-ok',
  CANCELLED: 'p-gray',
};

/** Corrected Opening Balance: Numeric (12,2) as the server takes it; a sign only to name it. */
const RUPEES = /^-?\d{1,12}$/;
const CENTS = /^\d{1,2}$/;

/** An amount typed or received, in paise; null when the text is not an amount. */
export function paise(value: string | number): bigint | null {
  const [rupees = '', cents, extra] = (typeof value === 'number' ? value.toFixed(2) : value.trim()).split(
    '.',
  );
  if (!RUPEES.test(rupees) || extra !== undefined || (cents !== undefined && !CENTS.test(cents))) return null;
  const negative = rupees.startsWith('-');
  const whole = BigInt(negative ? rupees.slice(1) : rupees) * 100n + BigInt((cents ?? '').padEnd(2, '0'));
  return negative ? -whole : whole;
}

/** Paise as a signed amount with two decimals and Indian grouping ("+1,00,000.50"). */
export function signed(p: bigint): string {
  const negative = p < 0n;
  const abs = negative ? -p : p;
  const rupees = (abs / 100n).toLocaleString('en-IN');
  const cents = String(abs % 100n).padStart(2, '0');
  return `${negative ? '−' : p > 0n ? '+' : ''}${rupees}.${cents}`;
}

/** One corrected part: the current amount, the corrected one, and the difference (all paise). */
export interface PartChange {
  part: LedgerPart;
  old: bigint;
  corrected: bigint;
  difference: bigint;
}

/**
 * The parts the draft corrects: a typed amount that differs from the current one. A part left
 * empty, unparseable or unchanged is not a change (SRS-6-43: "at least one part").
 */
export function changes(current: Balances, draft: NewCorrection): PartChange[] {
  const out: PartChange[] = [];
  for (const part of PARTS) {
    const typed = draft[FIELD[part]];
    const corrected = typed === null || typed.trim() === '' ? null : paise(typed);
    const old = paise(current[FIELD[part]]) ?? 0n;
    if (corrected !== null && corrected !== old) {
      out.push({ part, old, corrected, difference: corrected - old });
    }
  }
  return out;
}

/**
 * Whether a typed part cannot be sent: not an amount of up to 12 digits and 2 decimals. A negative
 * amount is an amount (refused by {@link correctionProblems} with its own message).
 */
function malformed(typed: string | null): boolean {
  return typed !== null && typed.trim() !== '' && paise(typed) === null;
}

/**
 * The label keys of what stops the draft (empty: it may be submitted). {@code hasEvidence}: the
 * order-copy control is complete.
 */
export function correctionProblems(current: Balances, draft: NewCorrection, hasEvidence: boolean): string[] {
  const out: string[] = [];
  if (PARTS.some((p) => malformed(draft[FIELD[p]]))) out.push('i22.problem.amount');
  const changed = changes(current, draft);
  if (changed.some((c) => c.corrected < 0n)) out.push('i22.problem.negative');
  else if (changed.length === 0) out.push('error.CPF-422-NO-CHANGE');
  if (draft.reasonCode === '') out.push('i22.problem.reason');
  if (draft.interestEffectiveDate === '') out.push('i22.problem.interestDate');
  if (draft.asAtDate === '') out.push('i22.problem.asAt');
  if (!isAsciiText(draft.remarks, 1000, true)) out.push('i22.problem.remarks');
  if (!hasEvidence) out.push('error.CPF-422-EVIDENCE');
  return out;
}

/** Whether a correction may still be decided or reviewed (it blocks a second one, SRS-6-44). */
export function isOpen(status: CorrectionStatus): boolean {
  return status === 'PENDING_AGM_CPF' || status === 'PENDING_HQ_CPF' || status === 'BLOCKED_CPF_DIVISION';
}
