/**
 * Report screen rules (plan 15; SRS-18-10, SRS-18-23, SRS-18-24). The server checks each again;
 * these only decide what the screens offer.
 */
import type { VersionProposal } from '../api/reportApi';

/** OD-123: the catalogue families the screen offers are those of the entries the API returned. */
export function familiesOf(entries: readonly { family: string }[]): string[] {
  return [...new Set(entries.map((e) => e.family))].sort();
}

/** SRS-18-12 (a): current members, separated members, or both (the default). No reference list holds them. */
export const MEMBER_STATUSES = ['BOTH', 'CURRENT', 'SEPARATED'] as const;

/** SRS-18-28: the levels of the summary, a parameter of the API. No reference list holds them. */
export const LEVELS = ['UNIT', 'REGION', 'ZONE', 'ALL'] as const;

/** DDL report_definition_version.layout_format CHECK; no reference list holds them; '' is none chosen. */
export const LAYOUT_FORMATS = ['', 'PDF', 'EXCEL', 'CSV', 'PRESCRIBED_FILE', 'HTML'] as const;

/** Out of scope (CLAUDE.md section 2, FCI-6): inactive since V081_51, and never offered even if returned. */
const OUT_OF_SCOPE_REPORTS = new Set(['PENSION_SLIP', 'PRMS_MEDICAL']);

/** O-04 l.3500: the catalogue entries the Report select offers. */
export function reportChoices<T extends { key: string }>(entries: readonly T[]): T[] {
  return entries.filter((e) => !OUT_OF_SCOPE_REPORTS.has(e.key));
}

/** The twelve reports of the M-12 table "CPF Control & Audit - report detail" (Annexure A.4.6), as drawn. */
export const CPF_DETAIL_KEYS = [
  'CPF_SANCTION_PAYMENT',
  'CPF_LIST_OF_SANCTION',
  'CPF_CONTROL_REGISTER',
  'CPF_OBCB',
  'CPF_BONUS_INTEREST',
  'CPF_MAKER_PENDING',
  'ZONE_SURRENDER',
  'PF_ADJUSTMENT',
  'CPF_FINAL_BILL',
  'CPF_NEW_JOINEES',
  'FCI_ESEVA_REPORT',
  'FCI_ESEVA_ARREAR_REPORT',
] as const;

/** The definition the M-12 card "Definition Detail" draws. */
export const DETAIL_KEY = 'LABOUR_INCENTIVE_DETAIL';

/** The employee rows of the O-04 Annexure A.11 table, as drawn; each reads its status from its catalogue entry. */
export const A11_EMPLOYEE_KEYS = [
  'PERSONAL_PAY_HIGHER_QUALIFICATION',
  'INVOICE_SUMMARY',
  'PENDING_INVOICES',
  'SCR_EXCEL_COMPONENT_FILTER',
  'PAY_FIXATION_BASIC_HISTORY',
  'LOAN_OUTSTANDING_IOGA_TRANSFER',
  'EMPLOYEE_UNIT_WISE_PAYMENT',
  'PENALTY_REPORT',
  'HRMS_CHANGE_REGISTER',
  'LE_ARREAR_INVOICE',
  'HRMS_FALLBACK_RECONCILIATION',
] as const;

/** YYYYMM with a month 01-12. */
export function isYymm(value: string): boolean {
  if (!/^\d{6}$/.test(value)) return false;
  const month = Number(value.slice(4));
  return month >= 1 && month <= 12;
}

/** "YYYY-MM-DD" today or later. */
function fromToday(day: string, today: string): boolean {
  return /^\d{4}-\d{2}-\d{2}$/.test(day) && day >= today;
}

/** DDL report_field_binding.binding_type. */
export const BINDINGS = ['COMPONENT', 'FIELD_SOURCE', 'AGGREGATE', 'LITERAL', 'DERIVED'] as const;

/** DDL report_control_total.reconciles_against. */
export const RECONCILES = ['POSTED_PAYROLL', 'GL_POSTING_STATEMENT', 'BANK_ADVICE', 'CHALLAN'] as const;

/** DDL report_distribution.consumer_type and delivery_channel. */
export const CONSUMERS = ['INTERNAL_ROLE', 'ORG_UNIT', 'EXTERNAL_AUTHORITY', 'MEMBER'] as const;
export const CHANNELS = ['SCREEN', 'EMAIL', 'FILE_DROP', 'PORTAL', 'PRINT'] as const;

const WHOLE = /^\d{1,12}$/;
const ROUNDING_KEY = /^[A-Z][A-Z0-9_.]{0,99}$/;
const DATA_TYPE = /^[A-Z][A-Z0-9_]{0,19}$/;
const CENTS = /^\d{1,2}$/;

/** A tolerance from 0 with at most twelve digits and two decimals (DDL NUMBER(14,2)). */
function isTolerance(value: string): boolean {
  const [whole = '', cents, extra] = value.trim().split('.');
  return extra === undefined && WHOLE.test(whole) && (cents === undefined || CENTS.test(cents));
}
const AMOUNT_BINDINGS = new Set(['COMPONENT', 'AGGREGATE', 'DERIVED']);

/**
 * SRS-18-9 (US-GAP-34): what is missing from the Report Definition Model, as a label key, or null:
 * every parameter needs its code, data type and validation rule; every field its label and
 * reference; every control total its label and a tolerance from 0, and an amount field to be
 * computed from; every consumer a name. Whether a binding resolves is the server's check.
 */
export function modelBlocked(p: VersionProposal): string | null {
  // SRS-18-9: the server's patterns (ReportModelRules): a rounding rule key and a data type in capitals.
  if (p.roundingRuleKey && !ROUNDING_KEY.test(p.roundingRuleKey)) return 'm12.why.rounding';
  for (const x of p.parameters ?? []) {
    if (!x.code.trim() || !x.dataType.trim() || !x.validationRule.trim()) return 'm12.why.parameter';
    if (!DATA_TYPE.test(x.dataType)) return 'm12.why.parameter';
  }
  for (const f of p.fields ?? []) {
    if (!f.label.trim() || !f.reference.trim()) return 'm12.why.field';
  }
  const controls = p.controls ?? [];
  for (const c of controls) {
    if (!c.label.trim() || !isTolerance(String(c.tolerance))) return 'm12.why.control';
  }
  if (controls.length > 0 && !(p.fields ?? []).some((f) => AMOUNT_BINDINGS.has(f.binding))) {
    return 'm12.why.computable';
  }
  for (const d of p.distribution ?? []) {
    if (!d.consumerName.trim()) return 'm12.why.consumer';
  }
  return null;
}

/** The request of a proposed version: tolerances as numbers, codes as typed. */
export function toVersionRequest(p: VersionProposal): VersionProposal {
  return {
    ...p,
    controls: (p.controls ?? []).map((c) => ({ ...c, tolerance: Number(String(c.tolerance).trim()) })),
  };
}

/** SRS-18-10: what is missing from a proposed version, as a label key, or null. */
export function proposalBlocked(p: VersionProposal, today: string): string | null {
  if (!fromToday(p.effectiveFrom, today)) return 'm12.why.effective';
  const reason = (p.blockedReason ?? '').trim();
  if (p.blocked && (reason.length === 0 || reason.length > 500 || !/^[\x20-\x7E\t\r\n]*$/.test(reason))) {
    return 'm12.why.reason';
  }
  return modelBlocked(p);
}

/**
 * Why the O-04 summary may not be exported now (an i18n key; SRS-18-8): the month is not valid, the
 * summary is not generated yet, is still loading, could not be loaded, or its control totals do not match the posted
 * payroll. Only the last one halts the export on the totals (review 15-6).
 */
export function exportBlockedWhy(
  monthOk: boolean,
  summary: { isPending: boolean; isError: boolean; fetchStatus?: string },
): string {
  if (!monthOk) return 'o04.why.month';
  if (summary.isError) return 'o04.why.error';
  // G59: the summary runs on Generate only, so before that press there is nothing to wait for.
  if (summary.isPending) return summary.fetchStatus === 'idle' ? 'o04.why.generate' : 'o04.why.loading';
  return 'o04.why.mismatch';
}
