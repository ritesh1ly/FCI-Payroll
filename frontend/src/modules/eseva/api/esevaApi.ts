import { type PageResult } from '@/shared/ui';
import { api } from '@/shared/api';

/** DDL ess_submission.submission_type. */
export type SubmissionType =
  | 'REIMBURSEMENT_CLAIM'
  | 'LOAN_REQUEST'
  | 'CPF_ADVANCE_REQUEST'
  | 'CPF_WITHDRAWAL_REQUEST'
  | 'CPF_FINAL_PAYMENT_REQUEST'
  | 'TAX_DECLARATION'
  | 'REGIME_CHOICE'
  | 'VPF_SUBSCRIPTION'
  | 'HRMS_UPDATE_REQUEST'
  | 'PAYROLL_QUERY';

/** DDL ess_submission.submission_status. */
export type SubmissionStatus = 'SUBMITTED' | 'ROUTED' | 'ACTIONED' | 'REJECTED';

/** One row of the Self-Service Submission Log (E-02, staff only; SRS-17-18). */
export interface SubmissionRow {
  id: number;
  eis: string;
  member: string;
  office: string | null;
  type: SubmissionType;
  reference: string;
  value: string | null;
  submittedAt: string;
  status: SubmissionStatus;
  approvalId: number | null;
}

export interface SubmissionFilter {
  eis?: string;
  type?: SubmissionType;
  status?: SubmissionStatus;
}

/** A support preview: the member masked, read-only (SRS-17-6, SRS-17-10). */
export interface Preview {
  sessionId: number;
  openedAt: string;
  /** Always false: no submission through a member's view (SRS-17-6). */
  submitEnabled: boolean;
  member: {
    eis: string;
    name: string;
    office: string | null;
    employeeType: string | null;
    employmentStatus: string | null;
    panMasked: string | null;
    aadhaarMasked: string | null;
    bankAccountMasked: string | null;
    mobileMasked: string | null;
    emailMasked: string | null;
  };
  payslipMonths: { yymm: string; gross: number; deductions: number; net: number }[];
  submissions: {
    type: SubmissionType;
    value: string | null;
    status: SubmissionStatus;
    submittedAt: string;
  }[];
}

/** SRS-SEC-9: the privacy notice of the first eSeva sign-in. */
export interface Notice {
  version: string;
  acknowledged: boolean;
}

/** One in-app notice of the member (SRS-17-12). */
export interface MemberNotice {
  id: number;
  /** ESEVA_SUBMITTED, ESEVA_ROUTED, ESEVA_ACTIONED or ESEVA_REJECTED. */
  type: string;
  relatedEntity: string | null;
  relatedKey: string | null;
  message: string | null;
  read: boolean;
  createdAt: string;
}

/** One document of My Documents (SRS-17-1, BR-ESEVA-004). No file is offered until LIB-1. */
export interface MemberDocument {
  kind: 'PAY_SLIP' | 'FORM_16' | 'FORM_12BA' | 'CPF_SLIP';
  /** YYYYMM for a pay slip, the financial year otherwise. */
  period: string;
  issued: string | null;
  /** ISSUED_NO_COPY (OD-153): a Form 16 released before issued copies were kept, no PDF on file. */
  state: 'FINAL' | 'ISSUED' | 'ISSUED_NO_COPY' | 'NOT_YET_AVAILABLE' | 'NOT_DUE' | 'NOT_AVAILABLE';
  id: number | null;
}

/** One row of My Requests: the member's own submission (SRS-17-17). */
export interface MemberRequest {
  type: SubmissionType;
  value: string | null;
  status: SubmissionStatus;
  submittedAt: string;
}

/** E-01 as drawn, of one member: the net pay band, the five tiles, My Documents, My Requests (API-14-2). */
export interface MemberOverview {
  latest: { yymm: string; gross: number; deductions: number; net: number } | null;
  cpf: { finYear: string; balance: number; vpfMonthly: number | null } | null;
  loan: {
    outstanding: number;
    number: string;
    type: string;
    paid: number;
    total: number;
    open: number;
  } | null;
  /** {@code amount} null: the TDS pay code is not configured (never shown as 0). */
  tds: { finYear: string; amount: number | null; regime: 'OLD' | 'NEW' | null };
  requests: { open: number; hrms: number; cpf: number; other: number };
  myRequests: MemberRequest[];
  documents: MemberDocument[];
}

/** One payslip line (a deduction positive). */
export interface SlipLine {
  payCode: number;
  name: string;
  group: string | null;
  amount: number;
}

/** A locked month's payslip of the member (SRS-17-1, SRS-17-7, SRS-17-10). */
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
  earnings: SlipLine[];
  deductions: SlipLine[];
  gross: number;
  totalDeductions: number;
  net: number;
  /** SRS-17-7: the component set by employee type. */
  layoutVariant?: 'STAFF' | 'LABOUR';
  provisional?: boolean;
}

/** The identity band of the member's own session (HRMS, read-only; /v1/eseva/me). */
export interface OwnBand {
  eis: string;
  firstName: string;
  lastName: string | null;
  designation: string | null;
  payPattern: string | null;
  band: string | null;
  location: string | null;
  cpfCode: string | null;
}

/** The only place the eSeva module talks to /api/v1/eseva. */
export const esevaApi = {
  submissions: (filter: SubmissionFilter, page: number, size: number) => {
    const p = new URLSearchParams();
    if (filter.eis) p.set('eis', filter.eis);
    if (filter.type) p.set('type', filter.type);
    if (filter.status) p.set('status', filter.status);
    p.set('page', String(page));
    p.set('size', String(size));
    return api<PageResult<SubmissionRow>>(`/v1/eseva/submissions?${p.toString()}`);
  },
  /** FRS V0.5 l.6958: the staff user closes the support preview (its closing time is kept). */
  closePreview: (sessionId: number) =>
    api<unknown>(`/v1/eseva/support-previews/${sessionId}/closures`, { method: 'POST', body: {} }),
  preview: (eis: string, reason: string) =>
    api<Preview>('/v1/eseva/support-previews', { method: 'POST', body: { eis, reason } }),
  /** E-01 as drawn: the member's own (E-01 SELF), or the member of the own open session (E-01 VIEW). */
  overviewMine: () => api<MemberOverview>('/v1/eseva/overview/mine'),
  overviewOfPreview: (sessionId: number) =>
    api<MemberOverview>(`/v1/eseva/support-previews/${sessionId}/overview`),
  ownBand: () => api<OwnBand>('/v1/eseva/me'),
  payslipMine: (yymm: string) => api<MemberPayslip>(`/v1/payslips/mine/${encodeURIComponent(yymm)}`),
  payslipOfPreview: (sessionId: number, yymm: string) =>
    api<MemberPayslip>(`/v1/payslips/previews/${sessionId}/${encodeURIComponent(yymm)}`),
  notice: () => api<Notice>('/v1/eseva/privacy-notice/mine'),
  notices: (page: number, size: number) =>
    api<PageResult<MemberNotice>>(`/v1/eseva/notifications/mine?page=${page}&size=${size}`),
  markRead: (id: number) =>
    api<unknown>(`/v1/eseva/notifications/mine/${id}/reads`, { method: 'POST', body: {} }),
  staffNotices: (page: number, size: number) =>
    api<PageResult<MemberNotice>>(`/v1/notifications/mine?page=${page}&size=${size}`),
  markStaffRead: (id: number) =>
    api<unknown>(`/v1/notifications/mine/${id}/reads`, { method: 'POST', body: {} }),
  acknowledge: () =>
    api<Notice>('/v1/eseva/privacy-notice/mine/acknowledgements', { method: 'POST', body: {} }),
};
