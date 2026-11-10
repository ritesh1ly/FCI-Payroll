import { isAsciiText } from '@/modules/general';
import type { PurposeProposal, VerificationTier, WithdrawalCategory, WithdrawalPurpose } from '../api/cpfApi';

/**
 * The rules of a CPF withdrawal purpose version as M-19 checks them before submission (SRS-6-5,
 * SRS-6-14, SRS-6-39, BR-CPF-028). They mirror the server's (`PurposeRules`, `WithdrawalLimit`); the
 * server checks again.
 */

export const CATEGORIES: readonly WithdrawalCategory[] = ['ADVANCE', 'PART_FINAL', 'FINAL'];
export const TIERS: readonly VerificationTier[] = [
  'UNIT_CPF_ADMIN',
  'ZONAL_HQ_CPF_DIVISION',
  'COMPETENT_AUTHORITY',
];

/** The limit rules whose basis the SRS states (server: `WithdrawalLimit`); a purpose may carry none. */
export const LIMIT_KEYS: readonly string[] = [
  'CPF.LIMIT.MGW.MARRIAGE',
  'CPF.LIMIT.MGW.RELIGIOUS',
  'CPF.LIMIT.MGW.HIGHER_EDUCATION',
  'CPF.LIMIT.MGW.HOUSE',
  'CPF.LIMIT.MGW.PLOT',
  'CPF.LIMIT.PAY.RENOVATION',
  'CPF.LIMIT.BALANCE.BEFORE_RETIREMENT',
];

const CODE = /^[A-Z][A-Z0-9_]{1,29}$/;

/** The version of {@code code} that starts last (the one a new version must start after). */
export function latest(purposes: readonly WithdrawalPurpose[], code: string): WithdrawalPurpose | undefined {
  return purposes
    .filter((p) => p.code === code)
    .sort((a, b) => b.effectiveFrom.localeCompare(a.effectiveFrom))[0];
}

/** The label keys of what stops the proposal (empty: it may be submitted). */
export function purposeProblems(
  draft: PurposeProposal & { reason: string },
  purposes: readonly WithdrawalPurpose[],
): string[] {
  const out: string[] = [];
  if (!CODE.test(draft.code)) out.push('error.CPF-422-PURPOSE-CODE');
  else if (draft.code.includes('COVID')) out.push('error.CPF-422-PURPOSE-WITHDRAWN');
  if (!isAsciiText(draft.name, 200)) out.push('m19p.problem.name');
  if (
    draft.limitRuleKey !== null &&
    (draft.category === 'FINAL' || !LIMIT_KEYS.includes(draft.limitRuleKey))
  ) {
    out.push('error.CPF-422-PURPOSE-LIMIT');
  }
  const quota = draft.maxOccurrences;
  if (quota !== null && (!Number.isInteger(quota) || quota < 1 || quota > 999))
    out.push('error.CPF-422-PURPOSE-QUOTA');
  const types = draft.documents.map((d) => d.documentType);
  if (types.some((t) => t === '') || new Set(types).size !== types.length)
    out.push('error.CPF-422-PURPOSE-DOCUMENT');
  const current = latest(purposes, draft.code);
  if (draft.effectiveFrom === '' || (current !== undefined && draft.effectiveFrom <= current.effectiveFrom)) {
    out.push('error.CPF-422-PURPOSE-DATE');
  }
  if (!isAsciiText(draft.reason, 500)) out.push('m19p.problem.reason');
  return out;
}
