/**
 * I-14 NPS Deduction (from HRMS) screen rules (plan 18 phase 18c; SRS-9-1a, SRS-9-6). The server
 * decides; these only say what to offer.
 */
import type { NpsStatus } from '../api/npsApi';

/** The statuses I-14 filters on, mismatches first (what the user has to chase in HRMS). */
export const NPS_STATUSES: readonly NpsStatus[] = [
  'FLAG_WITHOUT_AMOUNT',
  'AMOUNT_WITHOUT_FLAG',
  'RECEIVED',
  'APPLIED',
  'STOPPED_SEPARATION',
];

const YYMM = /^\d{4}(0[1-9]|1[0-2])$/;

/** A month as the API takes it: YYYYMM. */
export function isYymm(value: string): boolean {
  return YYMM.test(value);
}

/** The month of a date text (YYYY-MM-DD) as YYYYMM. */
export function yymmOf(day: string): string {
  return `${day.slice(0, 4)}${day.slice(5, 7)}`;
}

/** SRS-9-1a: a flag / amount mismatch holds the lock until HRMS corrects it. */
export function isMismatch(status: NpsStatus): boolean {
  return status === 'FLAG_WITHOUT_AMOUNT' || status === 'AMOUNT_WITHOUT_FLAG';
}

/** "2026-07" for "202607": the month as the session and periodLabel read it. */
export function periodOf(yymm: string): string {
  return `${yymm.slice(0, 4)}-${yymm.slice(4, 6)}`;
}

/** The pill of a status as drawn. */
export function statusPill(status: NpsStatus): string {
  if (isMismatch(status)) return 'p-bad';
  return status === 'APPLIED' ? 'p-ok' : 'p-info';
}

/** The sub-line of the member: the pay pattern, and the fund when it is not NPS ("CDA, GPF"). */
export function memberLine(payPattern?: string | null, fund?: string | null): string {
  return [payPattern, fund && fund !== 'NPS' ? fund : null].filter(Boolean).join(', ');
}

/** SRS-9-6: the report covers the months from .. to, both months, in order. */
export function reportRangeOk(from: string, to: string): boolean {
  return isYymm(from) && isYymm(to) && from <= to;
}
