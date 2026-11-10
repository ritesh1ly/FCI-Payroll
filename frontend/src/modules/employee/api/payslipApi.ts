import { type PageResult } from '@/shared/ui';
import { api } from '@/shared/api';

/** SRS-17-1: one locked month of the member. */
export interface PayslipMonth {
  yymm: string;
  gross: number;
  deductions: number;
  net: number;
}

/** SRS-1-10: one line as the payslip shows it (a deduction positive). */
export interface PayslipLine {
  payCode: number;
  name: string;
  group: string | null;
  amount: number;
}

export interface Payslip {
  yymm: string;
  eis: string;
  name: string;
  designation: string | null;
  office: string | null;
  payPattern: string | null;
  panMasked: string | null;
  bankAccountMasked: string | null;
  /** SRS-9-2: the PRAN as a reference (NPS members only). */
  pran: string | null;
  earnings: PayslipLine[];
  deductions: PayslipLine[];
  gross: number;
  totalDeductions: number;
  net: number;
}

/** The only place the employee module talks to /api/v1/payslips/mine. */
export const payslipApi = {
  months: (page: number, size: number) =>
    api<PageResult<PayslipMonth>>(`/v1/payslips/mine?page=${page}&size=${size}`),
  payslip: (yymm: string) => api<Payslip>(`/v1/payslips/mine/${encodeURIComponent(yymm)}`),
  /** OD-149: the most months of one bulk download (null: not configured). */
  bulkSettings: () => api<{ maxPeriods: number | null }>('/v1/payslips/mine/bulk-settings'),
};
