import type {
  Certificate,
  ControlTotal,
  NewFiling,
  OutputField,
  StatutoryReturn,
  VersionProposal,
  WhatIfRequest,
} from '../api/statutoryApi';

/**
 * The checks of C-07, M-17, C-10 and C-11 before a request is sent (the server checks again: SRS
 * M-17, BR-TAX-012, BR-TAX-013, SRS-7-8, SRS-7-21, SRS-7-31). Each problem is an i18n key.
 */

const PRINTABLE = /^[\x20-\x7E]*$/;
/** Text of a text area: printable ASCII with tab and line breaks (R-ASCII, as @PrintableAscii). */
const TEXT_LINES = /^[\x20-\x7E\t\r\n]*$/;
const FIN_YEAR = /^\d{4}-\d{2}$/;

/** Rupees and paise, zero or more: up to 12 digits, then at most two after the point. */
function isPaise(s: string): boolean {
  const [rupees, paise, extra] = s.split('.');
  return (
    extra === undefined && /^\d{1,12}$/.test(rupees ?? '') && (paise === undefined || /^\d{1,2}$/.test(paise))
  );
}

/** OutputRules.AGGREGATES on the server. */
export const AGGREGATES = ['SUM', 'COUNT', 'MIN', 'MAX'] as const;

export function isFinYear(s: string): boolean {
  return FIN_YEAR.test(s);
}

/** A reason of C-07 ("TAX-422-RATE-NOT-CONFIGURED:TAX.SLAB.OLD") as its message key and argument. */
export function reasonOf(reason: string): { key: string; args: string[] } {
  const at = reason.indexOf(':');
  return at < 0
    ? { key: `error.${reason}`, args: [] }
    : { key: `error.${reason.slice(0, at)}`, args: [reason.slice(at + 1)] };
}

function labelProblems(label: string, max: number): string | null {
  const l = label.trim();
  if (l === '' || l.length > max || !PRINTABLE.test(label)) return 'm17.badLabel';
  return null;
}

function fieldProblem(f: OutputField): string | null {
  const ref = f.reference.trim();
  if (ref === '') return 'm17.needReference';
  if (f.binding === 'AGGREGATE' && !(AGGREGATES as readonly string[]).includes(ref))
    return 'm17.badAggregate';
  if (f.binding === 'COMPONENT' && !/^\d{1,6}$/.test(ref)) return 'm17.badComponent';
  if (f.binding === 'FIELD_SOURCE' && !/^[a-z_][a-z0-9_]*\.[a-z_][a-z0-9_]*$/.test(ref))
    return 'm17.badFieldSource';
  if (ref.length > 1000 || !TEXT_LINES.test(ref)) return 'm17.badReference';
  return null;
}

function controlProblem(c: ControlTotal): string | null {
  if (labelProblems(c.label, 200)) return 'm17.badLabel';
  if (!isPaise(String(c.tolerance).trim())) return 'm17.badTolerance';
  return null;
}

/**
 * SRS M-17, BR-TAX-013: a version starts after the latest one, binds at least one field, every
 * label once, every reference to something that resolves, every control total a paise tolerance.
 */
export function versionProblems(p: VersionProposal, latestFrom: string | null): string[] {
  const out = new Set<string>();
  if (!/^\d{4}-\d{2}-\d{2}$/.test(p.effectiveFrom)) out.add('m17.needFrom');
  else if (latestFrom !== null && p.effectiveFrom <= latestFrom) out.add('m17.afterLatest');
  if (p.fields.length === 0) out.add('m17.needField');
  const seen = new Set<string>();
  for (const f of p.fields) {
    const bad = labelProblems(f.label, 200) ?? fieldProblem(f);
    if (bad) out.add(bad);
    if (seen.has(f.label.trim())) out.add('m17.duplicateLabel');
    seen.add(f.label.trim());
  }
  for (const c of p.controls) {
    const bad = controlProblem(c);
    if (bad) out.add(bad);
  }
  if (!/^[A-Z0-9_.]{0,100}$/.test(p.roundingRuleKey)) out.add('m17.badRounding');
  if (p.layoutNote.length > 4000 || !TEXT_LINES.test(p.layoutNote)) out.add('m17.badNote');
  if (p.reason.trim() === '' || !TEXT_LINES.test(p.reason)) out.add('m17.needReason');
  return [...out];
}

/** The latest effective date of an output's versions, or null when it has none. */
export function latestFrom(versions: { effectiveFrom: string }[]): string | null {
  return versions.reduce<string | null>(
    (max, v) => (max === null || v.effectiveFrom > max ? v.effectiveFrom : max),
    null,
  );
}

// ---------------------------------------------------------------- C-10

/**
 * C-10 main flow: the member population as typed (EIS numbers separated by commas or spaces); an
 * empty text is every member of the paying unit. {@code ok} is false when an entry is not an EIS.
 */
export function membersOf(text: string): { eis: string[]; ok: boolean } {
  const eis = [...new Set(text.split(/[\s,]+/).filter((s) => s !== ''))];
  return { eis, ok: eis.length <= 1000 && eis.every((e) => /^[0-9A-Za-z]{1,20}$/.test(e)) };
}

/** SRS-7-9: Part A is uploaded (or replaced) until the certificate is released. */
export function mayUploadPartA(c: Certificate): boolean {
  return c.status === 'PENDING_PART_A' || c.status === 'MERGED';
}

/** A released certificate is reissued against a correction return, never changed. */
export function mayReissue(c: Certificate): boolean {
  return c.status === 'RELEASED';
}

/** BR-TAX-012: the release goes with the merged certificates only. */
export function mergedCount(rows: Certificate[]): number {
  return rows.filter((c) => c.status === 'MERGED').length;
}

// ---------------------------------------------------------------- C-11

/** SRS-7-7: the payroll months of a quarter of a financial year, as YYYYMM. */
export function quarterMonths(finYear: string, quarter: string): string[] {
  const start = Number(finYear.slice(0, 4));
  const q = Number(quarter.slice(1));
  return [0, 1, 2].map((k) => {
    const m = 3 + (q - 1) * 3 + k + 1;
    const y = m > 12 ? start + 1 : start;
    return `${y}${String(m > 12 ? m - 12 : m).padStart(2, '0')}`;
  });
}

/** The originals a correction can be made against: released or filed, not corrected yet. */
export function correctable(rows: StatutoryReturn[]): StatutoryReturn[] {
  const corrected = new Set(rows.filter((r) => r.correction).map((r) => r.correctsReturnId));
  return rows.filter(
    (r) => !r.correction && (r.status === 'RELEASED' || r.status === 'FILED') && !corrected.has(r.id),
  );
}

/** Statuses of an original that is regenerated in place (C-11 alternate flow; server isRegenerable). */
const REGENERABLE: readonly StatutoryReturn['status'][] = ['DRAFT', 'GENERATED', 'VALIDATED'];

/**
 * One original per quarter (DDL uk_statutory_return): the quarters that may be generated, those
 * without an original and those whose original is regenerated in place (draft, including one an
 * abandoned background job left, generated or validated). A released or filed one is corrected.
 */
export function openQuarters(rows: StatutoryReturn[]): string[] {
  const held = new Set(
    rows.filter((r) => !r.correction && !REGENERABLE.includes(r.status)).map((r) => r.quarter),
  );
  return ['Q1', 'Q2', 'Q3', 'Q4'].filter((q) => !held.has(q as StatutoryReturn['quarter']));
}

/** The Module 08 background jobs (C-13; SRS-NF-7). */
export type TaxJob = 'TAX_FORM16_PART_B' | 'TAX_24Q_RETURN';

/**
 * C-10 / C-11 "Run in background": the C-13 submit form with the job and its year (and quarter)
 * filled in. The Maker picks the unit and gives the reason there; a Payroll Checker approves it.
 */
export function backgroundJobLink(job: TaxJob, params: Record<string, string>): string {
  return `/payroll?${new URLSearchParams({ s: 'C-13', job, ...params }).toString()}`;
}

/** SRS-7-31: the filing date is not ahead of today; the signatory is named; evidence with its particulars. */
export function filingProblems(f: NewFiling, today: string): string[] {
  const out: string[] = [];
  if (!/^\d{4}-\d{2}-\d{2}$/.test(f.filingDate)) out.push('c11.needFilingDate');
  else if (f.filingDate > today) out.push('c11.filingAhead');
  if (f.signatoryName.trim() === '' || f.signatoryDesignation.trim() === '') out.push('c11.needSignatory');
  if (![f.signatoryName, f.signatoryDesignation, f.acknowledgementToken].every((s) => PRINTABLE.test(s)))
    out.push('c11.printable');
  if (f.file && (f.evidence.documentDate === '' || f.evidence.issuingAuthority.trim() === ''))
    out.push('c11.needEvidence');
  return out;
}

/** SRS-7-21: one challan per line, cin,bsr,date,amount,reference,period (the server checks each). */
/**
 * SRS C-11 l.2625 "User Input": the checks of a keyed challan before it is sent (the server checks
 * again as for a file line). Each problem is an i18n key.
 */
export function challanProblems(c: {
  cin: string;
  bsr: string;
  date: string;
  amount: string;
  reference: string;
  period: string;
}): string[] {
  const out: string[] = [];
  if (!/^[A-Za-z0-9]{1,30}$/.test(c.cin.trim())) out.push('c11.problem.cin');
  if (!/^[A-Za-z0-9]{1,20}$/.test(c.bsr.trim())) out.push('c11.problem.bsr');
  if (c.date === '') out.push('c11.problem.date');
  const amount = c.amount.trim();
  const [rupees = '', paise, ...rest] = amount.split('.');
  const amountOk =
    rest.length === 0 && /^\d{1,16}$/.test(rupees) && (paise === undefined || /^\d{1,2}$/.test(paise));
  if (!amountOk || Number(amount) <= 0) out.push('c11.problem.amount');
  if (c.reference.length > 50 || c.reference.includes(',')) out.push('c11.problem.reference');
  if (!/^(Q[1-4]|\d{6})$/.test(c.period.trim())) out.push('c11.problem.period');
  return out;
}

export function challanFileProblem(file: File | null): string | null {
  if (!file) return 'c11.needFile';
  if (file.size === 0) return 'c11.emptyFile';
  if (file.size > 10 * 1024 * 1024) return 'c11.fileTooLarge';
  return null;
}

// ---------------------------------------------------------------- SRS-7-28 what-if

/** The what-if as typed: '' is "as approved"; amounts as text. */
export interface WhatIfDraft {
  regime: '' | 'OLD' | 'NEW';
  claims: { sectionCode: string; amount: string }[];
  salaryChange: string;
  otherIncome: string;
}

const SECTION_CODE = /^[0-9A-Za-z()]{1,20}$/;

/** An amount in rupees and paise, optionally signed, at most 10 rupee digits (NUMBER(12,2)). */
function isMoney(s: string, signed: boolean): boolean {
  const body = signed && s.startsWith('-') ? s.slice(1) : s;
  const [rupees, paise, ...rest] = body.split('.');
  return (
    rest.length === 0 && /^\d{1,10}$/.test(rupees ?? '') && (paise === undefined || /^\d{1,2}$/.test(paise))
  );
}

/** SRS-7-28 (the server checks again, TAX-422-WHAT-IF): what keeps the what-if from being run. */
export function whatIfProblems(d: WhatIfDraft): string[] {
  const out: string[] = [];
  const claims = d.claims.filter((c) => c.sectionCode.trim() !== '' || c.amount.trim() !== '');
  const codes = claims.map((c) => c.sectionCode.trim());
  if (claims.some((c) => !SECTION_CODE.test(c.sectionCode.trim()) || !isMoney(c.amount.trim(), false)))
    out.push('c07w.badClaim');
  if (new Set(codes).size !== codes.length) out.push('c07w.duplicateClaim');
  if (d.salaryChange.trim() !== '' && !isMoney(d.salaryChange.trim(), true)) out.push('c07w.badSalary');
  if (d.otherIncome.trim() !== '' && !isMoney(d.otherIncome.trim(), false)) out.push('c07w.badIncome');
  const varied =
    d.regime !== '' ||
    claims.length > 0 ||
    Number(d.salaryChange || 0) !== 0 ||
    Number(d.otherIncome || 0) !== 0;
  if (!varied) out.push('c07w.nothingVaried');
  return out;
}

/** The request of a draft without problems. */
export function whatIfRequest(d: WhatIfDraft): WhatIfRequest {
  return {
    regime: d.regime === '' ? null : d.regime,
    claims: d.claims
      .filter((c) => c.sectionCode.trim() !== '')
      .map((c) => ({ sectionCode: c.sectionCode.trim(), amount: Number(c.amount.trim()) })),
    salaryChange: d.salaryChange.trim() === '' ? null : Number(d.salaryChange.trim()),
    otherIncome: d.otherIncome.trim() === '' ? null : Number(d.otherIncome.trim()),
  };
}
