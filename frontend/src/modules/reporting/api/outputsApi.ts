import { api } from '@/shared/api';
import { type PageResult } from '@/shared/ui';

/** The drawn O-01 levels (SRS l.3405). */
export type PayslipLevel = 'STAFF' | 'LABOUR_DEPOT' | 'LABOUR_GANG';

/** A month of the O-01 Period dropdown (OD-123: from the cycles, never a fixed list). */
export interface PayslipPeriod {
  yymm: string;
  locked: boolean;
  /** Committed results exist in the reader's scope. */
  results?: boolean;
}

/** The four tiles of O-01; a null lastPublished: nothing was published yet. */
export interface PayslipSummary {
  yymm: string;
  locked: boolean;
  toGenerate: number;
  published: number;
  suppressed: number;
  lastPublished: string | null;
  lastPublishedAt: string | null;
  lastPublishedCount: number;
}

/** A row of the Payslip Register (PII free). */
export interface PayslipRow {
  eis: string;
  name: string;
  payrollType: string;
  payPattern: string | null;
  net: number;
  status: string;
  published: boolean;
}

export interface PayslipLine {
  payCode: number;
  name: string;
  group: string;
  amount: number;
}

/** One committed month of a member; PAN and bank account arrive masked. */
export interface MemberPayslip {
  yymm: string;
  eis: string;
  name: string;
  designation: string | null;
  office: string | null;
  payPattern: string | null;
  panMasked: string | null;
  bankAccountMasked: string | null;
  pran: string | null;
  earnings: PayslipLine[];
  deductions: PayslipLine[];
  gross: number;
  totalDeductions: number;
  net: number;
  layoutVariant: string;
  provisional: boolean;
}

/** What one bulk generation wrote. */
export interface PayslipRun {
  yymm: string;
  written: number;
  alreadyGenerated: number;
}

/** The tiles of O-11 for the month (SRS-G-52). */
export interface RemittanceOverview {
  yymm: string;
  locked: boolean;
  payments: number;
  lateArrears: number;
  supplementary: number;
  unmappedPayCodes: number;
  yearEndFinYear: string | null;
  yearEndStatus: string | null;
  yearEndClosedAt: string | null;
}

/** A row of Remittance runs (DDL remittance_run). */
export interface RemittanceRun {
  id: number;
  type: string;
  period: string | null;
  finYear: string | null;
  invoiceFrom: string | null;
  invoiceTo: string | null;
  generated: string | null;
  transmittedBy: string | null;
  transmittedAt: string | null;
  reference: string | null;
  status: string;
}

function query(params: Record<string, string | number | undefined>): string {
  const p = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (v !== undefined && v !== '') p.set(k, String(v));
  }
  return p.toString();
}

/** API-15-n in PENDING: the O-01 and O-11 reads and the bulk generation of a locked month. */
export const outputsApi = {
  periods: () => api<PayslipPeriod[]>('/v1/payslips/periods'),
  summary: (yymm: string, level: PayslipLevel) =>
    api<PayslipSummary>(`/v1/payslips/summary?${query({ yymm, level })}`),
  register: (yymm: string, level: PayslipLevel, text: string, page: number, size: number) =>
    api<PageResult<PayslipRow>>(`/v1/payslips/register?${query({ yymm, level, text, page, size })}`),
  member: (eis: string, yymm: string) =>
    api<MemberPayslip>(`/v1/payslips/members/${encodeURIComponent(eis)}/${yymm}`),
  generate: (yymm: string, level: PayslipLevel) =>
    api<PayslipRun>('/v1/payslip-runs', { method: 'POST', body: { yymm, level } }),
  remittance: () => api<RemittanceOverview>('/v1/remittances/overview'),
  remittanceRuns: (page: number, size: number) =>
    api<PageResult<RemittanceRun>>(`/v1/remittances/runs?${query({ page, size })}`),
};
