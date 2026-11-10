/**
 * I-03 checks before a claim is sent (plan 10; SRS I-03 field table); the server checks again and
 * adds overlap, entitlement and the required documents (SRS-11-6, SRS-11-14). Each problem is an i18n key.
 */

export interface ClaimDraft {
  memberNumber: string;
  claimType: string;
  periodFrom: string;
  periodTo: string;
  claimedAmount: string;
}

export const EMPTY_CLAIM: ClaimDraft = {
  memberNumber: '',
  claimType: '',
  periodFrom: '',
  periodTo: '',
  claimedAmount: '',
};

const DATE = /^\d{4}-\d{2}-\d{2}$/;

/** An amount of at most ten digits and two decimals, above zero. */
export function validAmount(text: string): boolean {
  const parts = text.trim().split('.');
  if (parts.length > 2) return false;
  const [whole = '', fraction = ''] = parts;
  if (!/^\d{1,10}$/.test(whole) || (parts.length === 2 && !/^\d{1,2}$/.test(fraction))) return false;
  return Number(text) > 0;
}

/** What keeps the claim from being sent. */
export function claimProblems(d: ClaimDraft): string[] {
  const out: string[] = [];
  if (!/^[A-Za-z0-9]{1,20}$/.test(d.memberNumber.trim())) out.push('i03.bad.member');
  if (d.claimType === '') out.push('i03.bad.type');
  if (!DATE.test(d.periodFrom) || !DATE.test(d.periodTo)) out.push('i03.bad.period');
  else if (d.periodTo < d.periodFrom) out.push('i03.bad.periodOrder');
  if (!validAmount(d.claimedAmount)) out.push('i03.bad.amount');
  return out;
}

/** A medical claim type (SRS-11-19 / 11-21 actions apply to it): its code starts with MEDICAL. */
export function isMedical(claimType: string): boolean {
  return claimType.startsWith('MEDICAL');
}

/** The YYYYMM of a month input (YYYY-MM), or null. */
export function yymm(month: string): string | null {
  const m = /^(\d{4})-(\d{2})$/.exec(month);
  return m && Number(m[2]) >= 1 && Number(m[2]) <= 12 ? `${m[1]}${m[2]}` : null;
}
