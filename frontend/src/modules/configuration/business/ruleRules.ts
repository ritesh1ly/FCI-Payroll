import type { ProposedRuleVersion, RuleBand, RuleRow, RuleStatus, ValueShape } from '../api/rulesApi';

/**
 * M-08 screen rules (SRS-19-1, SRS-19-5, SRS-19-11, SRS-19-12): filters, the action a proposal needs
 * and the form's own checks. The server checks everything again (RuleProposalChecks); these only
 * say early what is missing. Pure functions.
 */

/** The pill of each derived status (SRS-19-11). */
export const RULE_STATUS_PILL: Record<RuleStatus, string> = {
  ACTIVE: 'p-ok',
  APPROVED_FUTURE: 'p-info',
  PENDING_APPROVAL: 'p-warn',
  SUPERSEDED: 'p-gray',
  REJECTED: 'p-bad',
  DRAFT: 'p-gray',
};

/** The catalogue's status filter: in force, not configured, a change pending, or read only. */
export type StatusFilter = '' | 'ACTIVE' | 'NOT_CONFIGURED' | 'PENDING' | 'READ_ONLY';

export interface CatalogueFilter {
  text: string;
  type: string;
  domain: string;
  status: StatusFilter;
}

export const NO_FILTER: CatalogueFilter = { text: '', type: '', domain: '', status: '' };

/** A rule is "not configured" when it is in use and no version is in force (R-RULE: no default). */
export function notConfigured(row: RuleRow): boolean {
  return row.active && row.inForce === null;
}

/**
 * A register screen (M-18, M-19) shows the rules it owns (rule_definition.owning_module); without a
 * register the whole catalogue (M-08).
 */
export function inRegister(row: RuleRow, register: string | undefined): boolean {
  return register === undefined || row.owningModule === register;
}

/** The tiles of a register, counted from its own rules (the M-08 summary counts the whole catalogue). */
export function registerSummary(rows: readonly RuleRow[]): {
  rules: number;
  pending: number;
  notConfigured: number;
} {
  return {
    rules: rows.length,
    pending: rows.filter(hasPendingChange).length,
    notConfigured: rows.filter(notConfigured).length,
  };
}

export function hasPendingChange(row: RuleRow): boolean {
  return row.pending !== null || row.pendingEnd !== null;
}

export function matches(row: RuleRow, f: CatalogueFilter): boolean {
  const text = f.text.trim().toLowerCase();
  if (text && !row.key.toLowerCase().includes(text) && !row.name.toLowerCase().includes(text)) {
    return false;
  }
  if (f.type && row.type !== f.type) return false;
  if (f.domain && row.domain !== f.domain) return false;
  switch (f.status) {
    case 'ACTIVE':
      return row.inForce !== null;
    case 'NOT_CONFIGURED':
      return notConfigured(row);
    case 'PENDING':
      return hasPendingChange(row);
    case 'READ_ONLY':
      return !row.maintained;
    default:
      return true;
  }
}

/** The permission a proposal needs: CREATE for a rule's first version, EDIT after (as the server). */
export function proposalAction(row: RuleRow): 'CREATE' | 'EDIT' {
  return row.versions === 0 ? 'CREATE' : 'EDIT';
}

/** A new version can be proposed: maintained here, nothing pending, and the permission held. */
export function mayPropose(row: RuleRow, can: (action: string) => boolean): boolean {
  return row.maintained && !hasPendingChange(row) && can(proposalAction(row));
}

/** An end date can be proposed: as above, for a rule that has a version (SRS-19-6). */
export function mayEnd(row: RuleRow, can: (action: string) => boolean): boolean {
  return row.maintained && !hasPendingChange(row) && row.versions > 0 && can('EDIT');
}

/** A version starting before today is paid as arrears for the periods already computed (SRS-19-16). */
export function isRetrospective(effectiveFrom: string, today: string): boolean {
  return effectiveFrom !== '' && effectiveFrom < today;
}

// ---------------------------------------------------------------- the proposal form

/** A band as typed: text, converted only on submission. */
export interface BandDraft {
  from: string;
  to: string;
  rateOrAmount: string;
  step: string;
  qualifier: string;
}

export interface VersionDraft {
  effectiveFrom: string;
  effectiveTo: string;
  value: string;
  formula: string;
  bands: BandDraft[];
  roundingRuleKey: string;
  circularId: number | null;
  reason: string;
}

export const EMPTY_BAND: BandDraft = { from: '', to: '', rateOrAmount: '', step: '', qualifier: '' };

/** The API's limit on the bands of one version (RuleCatalogueController: @Size(max = 200)). */
export const MAX_BANDS = 200;

/** Another band can be added while the version holds fewer than {@link MAX_BANDS}. */
export function canAddBand(bands: readonly BandDraft[]): boolean {
  return bands.length < MAX_BANDS;
}

/** Adds a band that starts where the last one ends; the first band of an empty list starts blank. */
export function appendBand(bands: readonly BandDraft[]): BandDraft[] {
  const last = bands.at(-1);
  return [...bands, { ...EMPTY_BAND, from: last ? last.to : '' }];
}

export function emptyDraft(today: string): VersionDraft {
  return {
    effectiveFrom: today,
    effectiveTo: '',
    value: '',
    formula: '',
    bands: [{ ...EMPTY_BAND }],
    roundingRuleKey: '',
    circularId: null,
    reason: '',
  };
}

// Printable ASCII only (R-ASCII: the database character set is US7ASCII).
const PRINTABLE_ASCII = /^[\x20-\x7E]*$/;
/** Text of a text area: printable ASCII with tab and line breaks (R-ASCII, as @PrintableAscii). */
const TEXT_LINES = /^[\x20-\x7E\t\r\n]*$/;

/**
 * SRS M-08 "Rule Value(s)": Numeric (12,2): an optional minus, 1 to 10 digits, and optionally a
 * point with 1 or 2 digits (written without nested repetition, so it cannot backtrack).
 */
const AMOUNT = /^-?\d{1,10}(\.\d|\.\d\d)?$/;

/** The text (spaces around it ignored) fits a Numeric (12,2) value. */
export function fitsAmount(text: string): boolean {
  return AMOUNT.test(text.trim());
}

/**
 * What is still missing or wrong in the form, as message keys (empty: ready to submit). Only the
 * part the rule's shape takes is checked; the server checks contiguity and the grammar.
 */
export function draftProblems(shape: ValueShape, d: VersionDraft): string[] {
  const out: string[] = [];
  if (!d.effectiveFrom) out.push('m08.need.from');
  if (d.effectiveTo && d.effectiveFrom && d.effectiveTo < d.effectiveFrom) out.push('m08.need.toAfterFrom');
  if (shape === 'SCALAR') {
    if (!d.value.trim()) out.push('m08.need.value');
    else if (!fitsAmount(d.value)) out.push('m08.need.amount');
  } else if (shape === 'SLAB') {
    out.push(...bandProblems(d.bands));
  } else if (!d.formula.trim()) {
    out.push('m08.need.formula');
  } else if (!PRINTABLE_ASCII.test(d.formula)) {
    out.push('m08.need.ascii');
  }
  if (d.circularId === null) out.push('m08.need.circular');
  if (!d.reason.trim()) out.push('m08.need.reason');
  else if (!TEXT_LINES.test(d.reason)) out.push('m08.need.ascii');
  return [...new Set(out)];
}

function bandProblems(bands: BandDraft[]): string[] {
  if (bands.length === 0) return ['m08.need.bands'];
  const out: string[] = [];
  for (const b of bands) {
    if (!b.from.trim() || !b.rateOrAmount.trim()) {
      out.push('m08.need.bands');
      continue;
    }
    const numbers = [b.from, b.to, b.rateOrAmount, b.step].filter((v) => v.trim() !== '');
    if (numbers.some((v) => !fitsAmount(v))) out.push('m08.need.amount');
    else if (b.to.trim() && Number(b.to) <= Number(b.from)) out.push('m08.need.bandRange');
  }
  return out;
}

// Number() ignores the spaces around a value itself (the same white space as String.trim()).
const number = (text: string): number | null => (text.trim() === '' ? null : Number(text));

/** The proposal sent: only the part the rule's shape takes; everything else null (no stray value). */
export function toProposal(shape: ValueShape, d: VersionDraft): ProposedRuleVersion {
  const bands: RuleBand[] | null =
    shape === 'SLAB'
      ? d.bands.map((b, i) => ({
          order: i + 1,
          from: Number(b.from),
          to: number(b.to),
          rateOrAmount: Number(b.rateOrAmount),
          step: number(b.step),
          qualifier: b.qualifier.trim() === '' ? null : b.qualifier.trim().toUpperCase(),
        }))
      : null;
  return {
    effectiveFrom: d.effectiveFrom,
    effectiveTo: d.effectiveTo === '' ? null : d.effectiveTo,
    value: shape === 'SCALAR' ? number(d.value) : null,
    formula: shape === 'NUMBER_FORMULA' || shape === 'CONDITION_FORMULA' ? d.formula.trim() : null,
    bands,
    roundingRuleKey: d.roundingRuleKey === '' ? null : d.roundingRuleKey,
    circularId: d.circularId,
    reason: d.reason.trim(),
  };
}
