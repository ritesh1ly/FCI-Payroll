import { isAsciiText } from '@/modules/general';
import type { Regime, SectionProposal, StateProposal } from '../api/taxApi';

/**
 * The rules of the M-18 tax masters as the screen checks them before submission (SRS-7-2, SRS-7-4,
 * SRS-7-20; BR-TAX-002, BR-TAX-006). They mirror the server's (`TaxConfigurationRules`); the server
 * checks again, also the order of versions the screen cannot see on other pages.
 */

export const REGIMES: readonly Regime[] = ['OLD', 'NEW', 'BOTH'];

/** Section codes as the Act writes them: 80C, 80CCD(1B), 24(b), 10(13A); the bracket is optional. */
const SECTION_HEAD = /^[0-9]{1,3}[A-Z]{0,5}$/;
const SECTION_BRACKET = /^\([0-9A-Za-z]{1,4}\)$/;
const STATE = /^[A-Z]{2,10}$/;
/** NUMBER(12,2): ten digits before the point, two after. */
const RUPEES = /^\d{1,10}$/;
const PAISE = /^\d{1,2}$/;
const PERIODICITY = /^[A-Z_]{0,20}$/;

function isSectionCode(code: string): boolean {
  const open = code.indexOf('(');
  if (open < 0) return SECTION_HEAD.test(code);
  return SECTION_HEAD.test(code.slice(0, open)) && SECTION_BRACKET.test(code.slice(open));
}

function isLimit(limit: string): boolean {
  const [rupees, paise, ...rest] = limit.split('.');
  return rest.length === 0 && RUPEES.test(rupees ?? '') && (paise === undefined || PAISE.test(paise));
}

/**
 * The label keys of what stops a section version (empty: it may be submitted). {@code after} is the
 * start of the version it replaces, when the screen knows it.
 */
export function sectionProblems(draft: SectionProposal & { reason: string }, after?: string): string[] {
  const out: string[] = [];
  if (!isSectionCode(draft.code)) out.push('error.TAX-422-SECTION-CODE');
  if (!isAsciiText(draft.description, 500)) out.push('m18s.problem.description');
  if (draft.limit !== null && draft.limit !== '' && !isLimit(draft.limit))
    out.push('error.TAX-422-SECTION-LIMIT');
  if (!REGIMES.includes(draft.regime)) out.push('error.TAX-422-SECTION-REGIME');
  // An empty date sorts before every date, so one comparison refuses both.
  if (draft.effectiveFrom <= (after ?? '')) out.push('m18s.problem.order');
  if (!isAsciiText(draft.reason, 500)) out.push('m18s.problem.reason');
  return out;
}

/** An end date (empty: open-ended) before the start. */
function endsBeforeStart(to: string, from: string): boolean {
  return to !== '' && to < from;
}

/** The label keys of what stops a PT state proposal (empty: it may be submitted). */
export function stateProblems(draft: StateProposal & { reason: string }): string[] {
  const out: string[] = [];
  if (
    !STATE.test(draft.code) ||
    draft.name.trim() === '' ||
    draft.effectiveFrom === '' ||
    endsBeforeStart(draft.effectiveTo ?? '', draft.effectiveFrom)
  ) {
    out.push('error.TAX-422-PT-STATE');
  }
  const registration = draft.registrationNumber ?? '';
  if (!isAsciiText(draft.name, 100) || (registration.trim() !== '' && !isAsciiText(registration, 50)))
    out.push('m18p.problem.text');
  if (!PERIODICITY.test(draft.returnPeriodicity ?? '')) out.push('m18p.problem.periodicity');
  if (!isAsciiText(draft.reason, 500)) out.push('m18s.problem.reason');
  return out;
}
