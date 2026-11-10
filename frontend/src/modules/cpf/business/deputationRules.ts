import type { DeputationEntryType, DeputedMember, NewDeputationEntry } from '../api/cpfApi';

/**
 * The I-09 checks before a remittance is sent (plan 07 phase 07g; SRS-6-27 … 6-30, BR-CPF-021 …
 * 023); the server checks again. Each problem is an i18n key.
 */

export const ENTRY_TYPES: readonly DeputationEntryType[] = [
  'RECOVERY_INSTALMENT',
  'CONTRIBUTION_RECEIPT',
  'EXCESS_REFUND',
];

/** Rupees and paise, zero or more, at most 12 rupee digits; '' counts as zero. */
export function isPaise(s: string): boolean {
  if (s.trim() === '') return true;
  const [rupees, paise, ...rest] = s.trim().split('.');
  return (
    rest.length === 0 && /^\d{1,12}$/.test(rupees ?? '') && (paise === undefined || /^\d{1,2}$/.test(paise))
  );
}

function value(s: string): number {
  return s.trim() === '' ? 0 : Number(s.trim());
}

/** The month YYYYMM of a date (YYYY-MM-DD). */
function yymmOf(iso: string): string {
  return iso.slice(0, 4) + iso.slice(5, 7);
}

/** A month of the deputation: from its first month to its last, or open-ended. */
export function inDeputation(yymm: string, member: DeputedMember): boolean {
  if (!/^\d{4}(0[1-9]|1[0-2])$/.test(yymm)) return false;
  if (yymm < yymmOf(member.deputationFrom)) return false;
  return member.deputationTo === null || yymm <= yymmOf(member.deputationTo);
}

/** An empty entry of the given type for the member. */
export function emptyEntry(eis: string, type: DeputationEntryType): NewDeputationEntry {
  return {
    eis,
    borrowingOrgId: '',
    type,
    sanctionId: '',
    payrollYymm: '',
    instalment: '',
    employee: '',
    employer: '',
    vpf: '',
  };
}

/** What keeps the entry from being submitted (SRS-6-27 … 6-29). */
export function entryProblems(e: NewDeputationEntry, member: DeputedMember, hasEvidence: boolean): string[] {
  const out: string[] = [];
  if (e.borrowingOrgId === '') out.push('i09.bad.org');
  if (!inDeputation(e.payrollYymm, member)) out.push('i09.bad.month');
  if (e.type === 'CONTRIBUTION_RECEIPT') {
    const parts = [e.employee, e.employer, e.vpf];
    if (!parts.every(isPaise) || parts.reduce((sum, p) => sum + value(p), 0) <= 0) out.push('i09.bad.amount');
  } else if (!isPaise(e.instalment) || value(e.instalment) <= 0) {
    out.push('i09.bad.amount');
  }
  if (e.type === 'RECOVERY_INSTALMENT') {
    const advance = member.advances.find((a) => String(a.sanctionId) === e.sanctionId);
    if (!advance) out.push('i09.bad.advance');
    else if (isPaise(e.instalment) && value(e.instalment) > advance.outstanding) out.push('i09.bad.over');
  }
  if (!hasEvidence) out.push('i09.bad.evidence');
  return out;
}

/** I-09 Remittance Evidence: "PDF / JPG / PNG, up to 5 files" (SRS I-09 field table). */
export const MAX_EVIDENCE_FILES = 5;
