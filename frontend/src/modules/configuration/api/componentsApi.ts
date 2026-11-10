import { type PageResult } from '@/shared/ui';
import { api, API_BASE } from '@/shared/api';

export type PayrollTypeCode = 'STAFF' | 'DEPT_LABOUR' | 'DPS_LABOUR' | 'NWNP_LABOUR';

/** A catalogue entry as M-07 lists it (SRS M-07 field tables). */
export interface ComponentRow {
  payCode: number;
  name: string;
  type: 'EARNING' | 'DEDUCTION';
  payslipGroup: string | null;
  basisRuleKey: string | null;
  source: string;
  naturalAccount: string | null;
  taxable: 'Y' | 'N' | 'PARTLY';
  inCpfBase: boolean;
  payrollTypes: PayrollTypeCode[];
  labourOnly: boolean;
  effectiveFrom: string;
  effectiveTo: string | null;
  retired: boolean;
  versionNo: number;
  pending: boolean;
  /** SRS-LV-15 / BR-LEAVE-007 (US-GAP-298): the M-07 "Subject to LWP" attribute. */
  subjectToLwp: boolean;
  /** SRS l.1909: the Eligibility Flag (DDL eligibility_rule_key). */
  eligibilityRuleKey: string | null;
}

export interface ComponentUsage {
  consumerType: string;
  consumerKey: string;
  note: string | null;
}

/** What the Rule Administrator defines or revises. */
export interface ComponentInput {
  payCode: number;
  name: string;
  type: 'EARNING' | 'DEDUCTION';
  payslipGroup: string | null;
  basisRuleKey: string | null;
  source: string;
  naturalAccount: string;
  taxable: 'Y' | 'N' | 'PARTLY';
  inCpfBase: boolean;
  payrollTypes: PayrollTypeCode[];
  effectiveFrom: string;
  reason: string;
  /** SRS-LV-15 / BR-LEAVE-007 (US-GAP-298). */
  subjectToLwp: boolean;
  eligibilityRuleKey: string | null;
}

export interface ComponentFilter {
  componentClass?: 'STAFF' | 'LABOUR';
  type?: string;
  source?: string;
  taxable?: string;
  cpf?: string;
  /** The drawn "Search in list". */
  q?: string;
}

function filterParams(f: ComponentFilter) {
  return { class: f.componentClass, type: f.type, source: f.source, taxable: f.taxable, cpf: f.cpf, q: f.q };
}

function query(params: Record<string, string | number | undefined>): string {
  const p = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) if (v !== undefined && v !== '') p.set(k, String(v));
  return p.toString();
}

/** The only place the frontend talks to /api/v1/pay-components (M-07). */
export const componentsApi = {
  list: (f: ComponentFilter, page: number, size: number) =>
    api<PageResult<ComponentRow>>(`/v1/pay-components?${query({ ...filterParams(f), page, size })}`),
  /** The export link (a download through the browser's session, not a fetch). */
  exportUrl: (f: ComponentFilter) => `${API_BASE}/v1/pay-components/export?${query(filterParams(f))}`,
  usages: (code: number) => api<ComponentUsage[]>(`/v1/pay-components/${code}/usages`),
  define: (body: ComponentInput) =>
    api<{ id: number; status: string }>('/v1/pay-components', { method: 'POST', body }),
  revise: (body: ComponentInput) =>
    api<{ id: number; status: string }>(`/v1/pay-components/${body.payCode}/revisions`, {
      method: 'POST',
      body,
    }),
  retire: (code: number, lastDay: string, reason: string) =>
    api<{ id: number; status: string }>(`/v1/pay-components/${code}/retirements`, {
      method: 'POST',
      body: { lastDay, reason },
    }),
};
