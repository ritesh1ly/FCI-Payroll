import { type PageResult } from '@/shared/ui';
import { API_BASE, api } from '@/shared/api';

export type Fitment = 'FACTOR' | 'PERCENTAGE' | 'FIXED_AMOUNT';
export type Rounding = 'RUPEE' | 'NEXT_10' | 'NEXT_100';

export interface RevisionSummary {
  id: number;
  code: string;
  name: string;
  order: string | null;
  /** Comma-separated payroll types. */
  payrollTypes: string;
  status: string;
  step: number;
  createdAt: string;
  /** ALL_INDIA or SELECTED. */
  scope: string | null;
  effectiveFrom: string | null;
  /** The first component's fitment rule, its value and rounding. */
  rule: string | null;
  ruleValue: number | null;
  rounding: string | null;
}

/** The four tiles; the member figures are those of the latest fixed revision, within the offices. */
export interface RevisionBoard {
  events: number;
  approved: number;
  inSimulation: number;
  fixedCode: string | null;
  fixedName: string | null;
  membersFixed: number;
  exceptions: number;
  arrearsPaid: number;
  officesDone: number;
  offices: number;
}

/** One row of the Revision Status Report (DO-wise). */
export interface RevisionOffice {
  office: string;
  members: number;
  fixed: number;
  pending: number;
  exception: number;
  paid: number;
}

export interface RevisionStep {
  no: number;
  code: string;
  status: 'PENDING' | 'IN_PROGRESS' | 'DONE' | 'SKIPPED';
  by: string | null;
  at: string | null;
}

export interface RevisionDetail {
  summary: RevisionSummary;
  effectiveFrom: string;
  rule: Fitment;
  value: number;
  minimum: number | null;
  maximum: number | null;
  rounding: Rounding;
  instalments: number;
  steps: RevisionStep[];
  members: number;
  estimatedArrear: number;
}

export interface RevisionMember {
  eis: string;
  name: string;
  office: string;
  oldPay: number | null;
  newPay: number | null;
  estimate: number | null;
  status: string;
}

export interface DefinitionRequest {
  name: string;
  circularId: number;
  payrollTypes: string[];
  effectiveFrom: string;
  fitment: Fitment;
  value: number;
  minimum?: number;
  maximum?: number;
  rounding: Rounding;
  instalments?: number;
  reason: string;
}

/** An Active M-25 order (the circulars in force today). */
export interface RevisionOrder {
  id: number;
  number: string;
  subject: string | null;
  /** CIRCULAR_CATEGORY code; I-25 offers only WAGE_REVISION (SRS l.3164). */
  category: string | null;
}

export interface Submitted {
  approvalId: number;
  status: string;
}

const base = (id: number) => `/v1/wage-revisions/${id}`;

/** The only place I-25 talks to /api/v1/wage-revisions. */
export const wageRevisionApi = {
  list: (page: number, size: number) =>
    api<PageResult<RevisionSummary>>(`/v1/wage-revisions?page=${page}&size=${size}`),
  detail: (id: number) => api<RevisionDetail>(base(id)),
  board: () => api<RevisionBoard>('/v1/wage-revisions/summary'),
  /** SRS-8-25 step 8: the status report on the screen, office by office. */
  offices: (id: number, page: number, size: number) =>
    api<PageResult<RevisionOffice>>(`${base(id)}/offices?page=${page}&size=${size}`),
  define: (d: DefinitionRequest) => api<RevisionDetail>('/v1/wage-revisions', { method: 'POST', body: d }),
  simulate: (id: number) => api<RevisionDetail>(`${base(id)}/simulations`, { method: 'POST', body: {} }),
  /** The Payroll Checker at Headquarters approves in W-01 with the OTP WAGE_REVISION_HQ. */
  submit: (id: number, remark: string) =>
    api<Submitted>(`${base(id)}/submissions`, { method: 'POST', body: { remark } }),
  members: (id: number, page: number, size: number) =>
    api<PageResult<RevisionMember>>(`${base(id)}/members?page=${page}&size=${size}`),
  orders: () => api<RevisionOrder[]>('/v1/masters/circulars/current'),
  membersUrl: (id: number) => `${API_BASE}${base(id)}/members/export`,
  /** SRS-8-25 steps 5 - 6 (plan 17 phase 17g): the bulk fixation of an approved revision. */
  fix: (id: number) =>
    api<{ fixed: number; exceptions: number; arrears: number }>(`${base(id)}/fixations`, {
      method: 'POST',
      body: {},
    }),
  /** SRS-8-25 step 8: the Revision Status Report. */
  statusUrl: (id: number) => `${API_BASE}${base(id)}/status-report`,
};
