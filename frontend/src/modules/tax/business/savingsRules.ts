import { isAsciiText } from '@/modules/general';
import type { NewSavingsEntry, SectionOption } from '../api/taxApi';
import { isAmount } from './declarationRules';

/**
 * The rules of an I-18 entry and the transport rebate as the screen checks them before submission
 * (SRS-7-11, SRS-7-20, SRS-7-25; BR-TAX-015). They mirror the server's (`SavingsRules`); the server
 * checks again.
 */

/** SRS-7-11: the heads of other income the SRS names (offered as suggestions; any head is accepted). */
export const INCOME_HEADS: readonly string[] = ['Vehicle perquisite', 'Honorarium', 'NSC interest'];

/** The months of a financial year 2026-27 as YYYYMM: April 2026 to March 2027. */
export function monthsOf(finYear: string): string[] {
  const start = Number(finYear.slice(0, 4));
  return Array.from({ length: 12 }, (_, i) => {
    const m = i + 4;
    const year = m > 12 ? start + 1 : start;
    return `${year}${String(m > 12 ? m - 12 : m).padStart(2, '0')}`;
  });
}

/** The label keys of what stops an entry (empty: it may be saved). */
export function entryProblems(
  draft: NewSavingsEntry,
  sections: readonly SectionOption[],
  today: string,
): string[] {
  const out: string[] = [];
  if (draft.type === 'EXTERNAL_SAVINGS' && !sections.some((s) => s.code === draft.sectionCode)) {
    out.push('error.TAX-422-ENTRY-KIND');
  }
  if (draft.type === 'OTHER_INCOME' && !isAsciiText(draft.incomeHead, 60))
    out.push('error.TAX-422-ENTRY-KIND');
  const tds = draft.tdsOtherSource.trim() === '' ? '0' : draft.tdsOtherSource;
  if (!isAmount(draft.amount) || Number(draft.amount) <= 0 || !isAmount(tds))
    out.push('error.TAX-422-ENTRY-AMOUNT');
  if (draft.description.trim() !== '' && !isAsciiText(draft.description, 500))
    out.push('i18.problem.description');
  const d = draft.document;
  if (
    d.documentType === '' ||
    d.documentDate === '' ||
    d.documentDate > today ||
    !isAsciiText(d.issuingAuthority, 200) ||
    (d.reference.trim() !== '' && !isAsciiText(d.reference, 60))
  ) {
    out.push('e01t.problem.proof');
  }
  return out;
}

/** The label keys of what stops the rebate months: at least one month, each new, above zero. */
export function rebateProblems(
  months: Record<string, string>,
  approved: ReadonlySet<string>,
  reason: string,
): string[] {
  const out: string[] = [];
  const given = Object.entries(months).filter(([, v]) => v.trim() !== '');
  if (
    given.length === 0 ||
    given.some(
      ([m, v]) =>
        approved.has(m) || !isAmount(v) || Number(v) <= 0 || (v.trim().split('.')[0] ?? '').length > 10,
    )
  ) {
    out.push('i18.problem.rebate');
  }
  if (!isAsciiText(reason, 1000)) out.push('m18s.problem.reason');
  return out;
}
