/**
 * C-02 pure rules (plan 05 phase 05d), the screen's mirror of the server's: which cycle states a
 * Checker may return and a Maker may ask to un-freeze, and the members list of a return. The server
 * decides; these only enable the actions and explain why one is disabled.
 */

import type { CalculationTrace, PliPayment, ResultRow, TraceLine } from '../api/cycleApi';

export type { ReturnInput, ReturnScope } from '../api/cycleApi';

/** SRS-15-30: only a computed or verified cycle is returned. */
export function returnable(status: string | null): boolean {
  return status === 'COMPUTED' || status === 'VERIFIED';
}

/** SRS-15-31, SRS-15-10: an un-freeze before lock, of a cycle whose inputs were frozen. */
export function unfreezable(status: string | null): boolean {
  return status === 'FROZEN' || status === 'COMPUTED' || status === 'VERIFIED';
}

/** The most members one return names (the server's limit). */
export const MAX_RETURN_MEMBERS = 1000;

/** EIS numbers typed or pasted, separated by spaces, commas, semicolons or lines; each once, in order. */
export function memberList(text: string): string[] {
  const seen = new Set<string>();
  for (const part of text.split(/[\s,;]+/)) {
    if (part !== '') seen.add(part);
  }
  return [...seen].slice(0, MAX_RETURN_MEMBERS);
}

/**
 * Why a correction (return or un-freeze) is not possible now, as a message key, or null. The order
 * is the server's: a run holding the cycle first, then a waiting lock request, then a waiting
 * un-freeze request.
 */
export function correctionBlocked(state: {
  held: boolean;
  lockPending: boolean;
  unfreezePending: boolean;
}): string | null {
  if (state.held) return 'held';
  if (state.lockPending) return 'cyc.blocked.lockPending';
  if (state.unfreezePending) return 'cyc.blocked.unfreezePending';
  return null;
}

/**
 * SRS-15-28 / SRS l.1734: the run number of an ACTUAL computation still running on the cycle, else
 * null. Verify waits for it: the run would supersede the results just verified (the server refuses
 * the same way, CYC-409-RUNNING).
 */
export function runningRunNo(
  runs: readonly { type: string; status: string; runNo: number }[],
): number | null {
  return runs.find((r) => r.type === 'ACTUAL' && r.status === 'RUNNING')?.runNo ?? null;
}

/** C-02 ▶ Re-run Computation: the C-13 job runs on a frozen or computed cycle (BR-CYC-008). */
export function rerunnable(status: string | null): boolean {
  return status === 'FROZEN' || status === 'COMPUTED';
}

/** The list search as the API takes it: an EIS number or a name, no wildcard, 60 characters at most. */
export function searchText(value: string): string {
  return value.replace(/[^A-Za-z0-9 .'-]/g, '').slice(0, 60);
}

/** Rupees as crore with two decimals, as the drawn tiles and totals read. */
export function crore(value: number): string {
  return (value / 10_000_000).toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

const BANK_STATUS = new Set(['MISSING_BANK', 'BANK_DETAILS']);
const NET_STATUS = new Set(['NEGATIVE_NET', 'ZERO_NET', 'FLOOR_BREACH', 'DEDUCTION_CEILING', 'QUARANTINED']);

/** The pill of a row's Status, as drawn: computed, a blocking fault, or a warning. */
export function resultTone(status: string): string {
  if (status === 'COMPUTED') return 'p-ok';
  return NET_STATUS.has(status) || BANK_STATUS.has(status) ? 'p-bad' : 'p-warn';
}

/**
 * Why ✎ Edit is off on a result row, as a message key (BR-AUDIT-005: no role edits a computed result): the
 * drawn reason of the row's state; a member held or computed in an open run has the plain one.
 */
export function resultEditWhy(row: Pick<ResultRow, 'rowStatus' | 'locked'>): string {
  if (BANK_STATUS.has(row.rowStatus)) return 'c02.why.bank';
  if (row.rowStatus === 'HRA_LEASE_CONFLICT') return 'c02.why.lease';
  if (row.rowStatus === 'COMPUTED' || row.rowStatus === 'PAYMENT_HELD') {
    return row.locked ? 'c02.why.locked' : 'c02.why.computed';
  }
  return 'c02.why.exceptions';
}

/** A PLI entry whose invoice left for APPS (BR-AUDIT-009: from then on APPS decides the cancel). */
export function pliSent(p: Pick<PliPayment, 'invoiceStatus'>): boolean {
  return p.invoiceStatus === 'SENT_TO_APPS' || p.invoiceStatus === 'CANCEL_REQUESTED';
}

/** A cancelled PLI entry stays on the list as "Cancelled — new version" (BR-AUDIT-009). */
export function pliCancelled(p: Pick<PliPayment, 'invoiceStatus' | 'type'>): boolean {
  return p.invoiceStatus === 'CANCELLED_IN_APPS' || p.type === 'CANCELLATION';
}

/** The lines of a trace by the drawn row they feed. */
export interface TraceRows {
  basic?: TraceLine;
  da?: TraceLine;
  hra?: TraceLine;
  cpf?: TraceLine;
  vpf?: TraceLine;
  tds?: TraceLine;
  pt?: TraceLine;
  /** Transport / other allowances: every other earning. */
  earnings: TraceLine[];
  /** Loan / advance recovery: every other deduction. */
  recoveries: TraceLine[];
}

export function traceRows(trace: Pick<CalculationTrace, 'lines'>): TraceRows {
  const head = (code: string) => trace.lines.find((l) => l.head === code);
  return {
    basic: head('BASIC'),
    da: head('DA'),
    hra: head('HRA'),
    cpf: head('CPF'),
    vpf: head('VPF'),
    tds: head('TDS'),
    pt: head('PT'),
    earnings: trace.lines.filter((l) => l.head === 'EARNING'),
    recoveries: trace.lines.filter((l) => l.head === 'DEDUCTION'),
  };
}
