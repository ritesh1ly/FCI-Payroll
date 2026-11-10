import { isAsciiText } from '@/modules/general';
import type { DeclarationLine, NewDeclaration, SectionOption, TaxRegime } from '../api/taxApi';

/**
 * The rules of a member's income-tax declaration as E-01 and I-07 check them before submission
 * (SRS-7-1, SRS-7-16, SRS-7-20, SRS-7-24; BR-TAX-001, BR-TAX-002). They mirror the server's
 * (`DeclarationRules`); the server checks again, and decides the window and the frozen state.
 */

/** NUMBER(14,2): twelve digits before the point, two after. */
const RUPEES = /^\d{1,12}$/;
const PAISE = /^\d{1,2}$/;

/** A rupee amount as typed: digits, an optional point and up to two decimals. */
export function isAmount(value: string): boolean {
  const [rupees, paise, ...rest] = value.trim().split('.');
  return rest.length === 0 && RUPEES.test(rupees ?? '') && (paise === undefined || PAISE.test(paise));
}

/** The sections a member may declare under the regime (OLD, NEW or BOTH in the master). */
export function sectionsFor(sections: readonly SectionOption[], regime: TaxRegime | null): SectionOption[] {
  return regime === null ? [] : sections.filter((s) => s.regime === 'BOTH' || s.regime === regime);
}

/** SRS-7-16, BR-TAX-002: the lower of the declared amount and the section limit. */
export function allowedOf(declared: number, limit: number | null): number {
  return limit === null ? declared : Math.min(declared, limit);
}

/** The label keys of what stops the member's submission (empty: it may be submitted). */
export function declarationProblems(
  draft: NewDeclaration,
  sections: readonly SectionOption[],
  today: string,
): string[] {
  const out = new Set<string>();
  if (draft.regime === null) out.add('error.TAX-422-REGIME-REQUIRED');
  const available = new Set(sectionsFor(sections, draft.regime).map((s) => s.code));
  const seen = new Set<string>();
  for (const l of draft.lines) {
    if (!isAmount(l.declared) || Number(l.declared) <= 0) out.add('error.TAX-422-DECLARATION-AMOUNT');
    if (draft.regime !== null && !available.has(l.sectionCode)) out.add('e01t.problem.section');
    const key = `${l.kind}|${l.sectionCode}`;
    if (seen.has(key)) out.add('e01t.problem.duplicate');
    seen.add(key);
    const p = l.proof;
    if (
      p !== null &&
      (p.documentType === '' ||
        p.documentDate === '' ||
        p.documentDate > today ||
        !isAsciiText(p.issuingAuthority, 200) ||
        (p.reference.trim() !== '' && !isAsciiText(p.reference, 60)))
    ) {
      out.add('e01t.problem.proof');
    }
  }
  return [...out];
}

/** The label keys of what stops the Maker's verification: every open line from 0 to its allowed amount. */
export function verificationProblems(
  lines: readonly DeclarationLine[],
  amounts: Record<number, string>,
): string[] {
  const bad = lines
    .filter((l) => !l.frozen)
    .some((l) => {
      const v = amounts[l.id] ?? '';
      return !isAmount(v) || Number(v) > l.allowed;
    });
  return bad ? ['error.TAX-422-VERIFIED-AMOUNT'] : [];
}
