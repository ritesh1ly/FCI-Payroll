import type { BankOverview, GlRow } from '../api/bankAdviceApi';

/** DDL bank_advice.advice_status past the release (chk_bank_advice_release). */
const RELEASED = new Set(['RELEASED', 'SENT', 'ACKNOWLEDGED']);

/**
 * SRS l.1709 Release Status of the month: Suspended (BR-SEC-006) wins, then Released, then Generated.
 * OPEN-QUESTION (PENDING): "Pending Checker (OTP)" has no bank_advice status and no route yet (G43).
 */
export function releaseStatus(o: Pick<BankOverview, 'suspended' | 'bankRows'>): string {
  if (o.suspended) return 'SUSPENDED';
  const statuses = o.bankRows.map((b) => b.status);
  if (statuses.some((s) => RELEASED.has(s))) return 'RELEASED';
  return statuses.includes('GENERATED') ? 'GENERATED' : 'NOT_GENERATED';
}

/** The pill of a bank's advice status. */
export function adviceTone(status: string): string {
  if (RELEASED.has(status)) return 'p-ok';
  if (status === 'GENERATED') return 'p-info';
  return status === 'CANCELLED' ? 'p-bad' : 'p-gray';
}

/** A foot total of the GL preview; the display of figures the API sends, in rupees. */
export function sumOf(rows: GlRow[], side: 'debit' | 'credit'): number {
  return rows.reduce((sum, r) => sum + (r[side] ?? 0), 0);
}
