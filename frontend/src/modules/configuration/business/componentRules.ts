import type { PayrollTypeCode } from '../api/componentsApi';

/**
 * M-07 checks before a change is sent (plan 06 phase 06d; SRS M-07 field tables, FAP-14); the server
 * checks again (a code never reused, the basis rule existing). Each problem is an i18n key.
 */

export const PAYROLL_TYPES: readonly PayrollTypeCode[] = [
  'STAFF',
  'DEPT_LABOUR',
  'DPS_LABOUR',
  'NWNP_LABOUR',
];
export const SOURCES = ['ENT', 'CALC', 'HRMS', 'RULE', 'SYS', 'ESS', 'INTF'] as const;

/** The component form as typed. */
export interface ComponentDraft {
  payCode: string;
  name: string;
  type: '' | 'EARNING' | 'DEDUCTION';
  payslipGroup: string;
  basisRuleKey: string;
  /** SRS l.1909, DDL pay_component.eligibility_rule_key: optional. */
  eligibilityRuleKey: string;
  source: string;
  naturalAccount: string;
  taxable: '' | 'Y' | 'N' | 'PARTLY';
  /** Drawn as a Yes / No select with no default (SRS l.1912: mandatory). */
  inCpfBase: '' | 'Y' | 'N';
  /** SRS-LV-15 / BR-LEAVE-007 (US-GAP-298): the components subject to LWP are marked on M-07. */
  subjectToLwp: boolean;
  payrollTypes: PayrollTypeCode[];
  effectiveFrom: string;
  reason: string;
}

export const EMPTY_COMPONENT: ComponentDraft = {
  payCode: '',
  name: '',
  type: '',
  payslipGroup: '',
  basisRuleKey: '',
  eligibilityRuleKey: '',
  source: '',
  naturalAccount: '',
  taxable: '',
  inCpfBase: '',
  subjectToLwp: false,
  payrollTypes: [],
  effectiveFrom: '',
  reason: '',
};

const PRINTABLE = /^[\x20-\x7E]*$/;
/** Text of a text area: printable ASCII with tab and line breaks (R-ASCII, as @PrintableAscii). */
const TEXT_LINES = /^[\x20-\x7E\t\r\n]*$/;

/** What keeps the definition from being sent. */
export function componentProblems(d: ComponentDraft): string[] {
  const out: string[] = [];
  if (!/^\d{1,3}$/.test(d.payCode) || Number(d.payCode) < 1) out.push('m07.bad.code');
  if (d.name.trim() === '' || d.name.trim().length > 100 || !PRINTABLE.test(d.name)) out.push('m07.bad.name');
  if (d.type === '') out.push('m07.bad.type');
  if (d.payslipGroup !== '' && !/^[A-Z_]{1,40}$/.test(d.payslipGroup)) out.push('m07.bad.group');
  // SRS l.1907, l.1925: Driver / Basis is mandatory.
  if (!/^[A-Z0-9_.]{1,100}$/.test(d.basisRuleKey)) out.push('m07.bad.basis');
  if (d.eligibilityRuleKey !== '' && !/^[A-Z0-9_.*]{1,100}$/.test(d.eligibilityRuleKey))
    out.push('m07.bad.elig');
  if (!(SOURCES as readonly string[]).includes(d.source)) out.push('m07.bad.source');
  if (!/^[A-Z0-9][A-Z0-9-]{1,19}$/.test(d.naturalAccount)) out.push('m07.bad.account');
  if (d.taxable === '') out.push('m07.bad.taxable');
  if (d.inCpfBase === '') out.push('m07.bad.cpf');
  if (d.payrollTypes.length === 0) out.push('m07.bad.payrollTypes');
  if (!/^\d{4}-\d{2}-\d{2}$/.test(d.effectiveFrom)) out.push('m07.bad.from');
  if (d.reason.trim() === '' || d.reason.trim().length > 500 || !TEXT_LINES.test(d.reason))
    out.push('m07.bad.reason');
  return out;
}

/** FAP-14: a revision adds payroll types and never removes one. */
export function removesPayrollType(
  current: readonly PayrollTypeCode[],
  proposed: readonly PayrollTypeCode[],
): boolean {
  return current.some((t) => !proposed.includes(t));
}
