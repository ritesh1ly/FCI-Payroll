/**
 * I-25 screen rules (plan 13 phase 13e; SRS-8-25). The server checks each again; these only decide
 * what the screen offers.
 */
import type { DefinitionRequest, RevisionDetail, RevisionOffice } from '../api/wageRevisionApi';

/** The staff payroll types this release revises (labour waits for the labour engines). */
export const STAFF_TYPES = ['STAFF_IDA', 'STAFF_CDA'] as const;
export const FITMENTS = ['FACTOR', 'PERCENTAGE', 'FIXED_AMOUNT'] as const;
export const ROUNDINGS = ['RUPEE', 'NEXT_10', 'NEXT_100'] as const;

/** Step 2: a definition the server will take (name, order, types, date, rule, reason). */
export function definitionReady(d: Partial<DefinitionRequest>): boolean {
  return (
    !!d.name?.trim() &&
    typeof d.circularId === 'number' &&
    (d.payrollTypes?.length ?? 0) > 0 &&
    !!d.effectiveFrom &&
    !!d.fitment &&
    typeof d.value === 'number' &&
    Number.isFinite(d.value) &&
    d.value > 0 &&
    // Review X3: a bound typed as something other than a number is never sent as null.
    (d.minimum === undefined || Number.isFinite(d.minimum)) &&
    (d.maximum === undefined || Number.isFinite(d.maximum)) &&
    !!d.rounding &&
    !!d.reason?.trim() &&
    (d.minimum === undefined || d.maximum === undefined || d.minimum <= d.maximum)
  );
}

/** The CIRCULAR_CATEGORY of a revision order: "an active circular of category Wage Revision" (SRS l.3164). */
export const REVISION_ORDER_CATEGORY = 'WAGE_REVISION';

/** Step 1: the Active orders a revision may name (the server refuses another category, MST-422-CIRCULAR-CATEGORY). */
export function revisionOrders<T extends { category: string | null }>(orders: readonly T[]): T[] {
  return orders.filter((o) => o.category === REVISION_ORDER_CATEGORY);
}

/** Step 3: a defined (or re-)simulated revision is simulated. */
export function maySimulate(d: RevisionDetail): boolean {
  return d.summary.status === 'DEFINED' || d.summary.status === 'SIMULATED';
}

/** Step 4: only a simulated revision goes for approval. */
export function maySubmit(d: RevisionDetail): boolean {
  return d.summary.status === 'SIMULATED';
}

/** Steps 5 to 8: pay is fixed, so the Revision Status Report has something to say. */
export function isFixed(status: string): boolean {
  return ['PAY_FIXED', 'ARREARS_COMPUTED', 'PAYING', 'COMPLETED'].includes(status);
}

/** The OTP of step 4 stands behind a revision from its approval on (the drawn OTP pill). */
export function isOtpApproved(status: string): boolean {
  return status === 'APPROVED' || isFixed(status);
}

/** The pill of a revision's status, as drawn: Approved, Simulated. */
export function revisionTone(status: string): string {
  if (status === 'SIMULATED') return 'p-warn';
  if (status === 'REJECTED' || status === 'CANCELLED') return 'p-bad';
  return status === 'DRAFT' || status === 'DEFINED' ? 'p-gray' : 'p-ok';
}

/** SRS-8-25 step 8: an office's state, read from its counts: fixed, in exception, paid. */
export function officeState(o: RevisionOffice): 'EXCEPTIONS' | 'PAID' | 'PENDING' {
  if (o.exception > 0) return 'EXCEPTIONS';
  return o.paid >= o.members ? 'PAID' : 'PENDING';
}

/** Step 5: only an approved revision is fixed in bulk. */
export function mayFix(d: RevisionDetail): boolean {
  return d.summary.status === 'APPROVED';
}
