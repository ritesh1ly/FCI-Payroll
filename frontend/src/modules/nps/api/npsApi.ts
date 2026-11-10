import { type PageResult } from '@/shared/ui';
import { API_BASE, api } from '@/shared/api';

/** The status I-14 shows: a mismatch first, else where the record is in the month (SRS-9-1a). */
export type NpsStatus =
  'FLAG_WITHOUT_AMOUNT' | 'AMOUNT_WITHOUT_FLAG' | 'RECEIVED' | 'APPLIED' | 'STOPPED_SEPARATION';

/** One NPS record of the month as HRMS sent it (I-14, view only). */
export interface NpsRow {
  id: number;
  eis: string;
  employee: string;
  office: string | null;
  npsFlag: boolean;
  /** SRS-9-2: a reference only, never validated with the NPS CRA. */
  pran: string | null;
  employeeAmount: number | null;
  employerAmount: number | null;
  /** CALC: the employee amount, once applied at the lock. */
  deducted: number | null;
  effectiveFrom: string | null;
  status: NpsStatus;
  /** IDA / CDA / FND and the fund: the sub-line of the member as drawn. */
  payPattern?: string | null;
  providentFund?: string | null;
}

/** The I-14 tiles: HRMS's amounts summed, never recalculated (SRS-9-1, SRS-9-1a). */
export interface NpsSummary {
  /** The month shown: the month asked for or the unit's latest before it; null when it has none. */
  yymm: string | null;
  members: number;
  employeeAmount: number;
  employerAmount: number;
  mismatches: number;
}

export interface NpsCount {
  status: NpsStatus;
  count: number;
}

/** I-14 filters: the month (YYYYMM), the status shown, an employee number. */
export interface NpsFilter {
  yymm: string;
  status?: NpsStatus;
  eis?: string;
}

function filters(filter: NpsFilter): URLSearchParams {
  const p = new URLSearchParams();
  p.set('yymm', filter.yymm);
  if (filter.status) p.set('status', filter.status);
  if (filter.eis) p.set('eis', filter.eis);
  return p;
}

/** The only place the NPS module talks to /api/v1/nps-deductions. */
export const npsApi = {
  list: (filter: NpsFilter, page: number, size: number) => {
    const p = filters(filter);
    p.set('page', String(page));
    p.set('size', String(size));
    return api<PageResult<NpsRow>>(`/v1/nps-deductions?${p.toString()}`);
  },
  counts: (yymm: string) => api<NpsCount[]>(`/v1/nps-deductions/counts?yymm=${encodeURIComponent(yymm)}`),
  summary: (yymm: string) => api<NpsSummary>(`/v1/nps-deductions/summary?yymm=${encodeURIComponent(yymm)}`),
  /** I-14 Export: the register as on screen (the format is chosen in the export dialog). */
  exportUrl: (filter: NpsFilter) => `${API_BASE}/v1/nps-deductions/export?${filters(filter).toString()}`,
  /** SRS-9-6: the NPS Deduction Report for the months from .. to. */
  reportUrl: (from: string, to: string) =>
    `${API_BASE}/v1/nps-deductions/report?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`,
};
