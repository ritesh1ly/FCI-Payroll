import { type PageResult } from '@/shared/ui';
import { API_BASE, api } from '@/shared/api';

/** DDL loan_header.loan_status. */
export type LoanStatus = 'ACTIVE' | 'FORECLOSED' | 'RESCHEDULED' | 'CLOSED' | 'STOPPED' | 'SETTLED_IN_FNF';

/** A loan as I-04 lists it: principal and interest separately (SRS-12-1). */
export interface LoanRow {
  id: number;
  loanNumber: string;
  eis: string;
  employee: string;
  loanType: string;
  sanctioned: number;
  recoveryStart: string;
  principalInstalments: number;
  instalment: number;
  paidInstalments: number;
  principalOutstanding: number;
  interestAmount: number;
  interestOutstanding: number;
  status: LoanStatus;
  /** HRMS: an employee loan, read-only here (SRS-12-17); LABOUR_ENT: set up on I-04. */
  source: 'HRMS' | 'LABOUR_ENT' | 'MIGRATION';
  /** The member has a separation date: a stopped loan is halted for it (SRS-12-5). */
  separated?: boolean;
  /** SRS-12-1: the principal instalments of the schedule in force add up to the loan. */
  scheduleReconciled?: boolean;
}

/** The loan tiles of I-04 (SRS-12-1, SRS-12-3). */
export interface LoanSummary {
  /** The month of the recovery figures; null when no month has instalments. */
  yymm: string | null;
  activeLoans: number;
  outstanding: number;
  recoveryPrincipal: number;
  recoveryInterest: number;
  importFailures: number;
  /** The legacy HRMS object with the most open rejections. */
  failingInterface: string | null;
}

/** One advance paid out and its invoice (BR-AUDIT-009, BR-AUDIT-010). */
export interface LoanDisbursement {
  id: number;
  entry: string;
  eis: string;
  employee: string;
  paymentType: string;
  source: LoanRow['source'] | null;
  amount: number;
  invoiceNumber: string | null;
  invoiceStatus: string;
  sentToApps: string | null;
  appsCancelled: boolean;
}

/** An order copy stored with a loan (SRS-G-30). */
export interface LoanOrder {
  id: number;
  fileName: string;
  documentTypeCode: string | null;
  documentTypeName: string | null;
  referenceNumber: string | null;
  uploadedBy: string;
  uploadedAt: string;
}

export interface ScheduleLine {
  number: number;
  payrollYymm: string;
  principal: number;
  interest: number;
}

/** A schedule version; superseded ones are kept (SRS-12-20). */
export interface ScheduleVersion {
  versionNo: number;
  reason: 'ORIGINAL' | 'RESCHEDULE' | 'FORECLOSURE' | 'CORRECTION' | 'MIGRATION';
  effectiveFrom: string;
  supersededAt: string | null;
  lines: ScheduleLine[];
}

export interface LoanDetail {
  loan: LoanRow;
  versions: ScheduleVersion[];
  /** SRS-12-23: the first unrecovered line of the version in force; null when not under recovery. */
  nextInstalment?: ScheduleLine | null;
}

/** A sanction request waiting for the Checker or returned to the Maker (SRS-12-7). */
export interface LoanRequest {
  approvalId: number;
  eis: string;
  employee: string;
  loanType: string | null;
  amount: number | null;
  status: 'PENDING_APPROVAL' | 'PENDING_ATTACHMENT' | 'RETURNED';
  submittedBy: string;
  submittedAt: string | null;
  returnReason: string | null;
}

export interface LoanType {
  code: string;
  name: string;
  interestBearing: boolean;
  maxInstalments: number | null;
}

/** The terms the Maker enters from the sanction order. */
export interface SanctionInput {
  eis: string;
  loanType: string;
  sanctioned: number;
  principalInstalments: number;
  instalment: number;
  firstInstalment: number | null;
  interestAmount: number | null;
  interestInstalments: number;
  interestInstalment: number | null;
  startYymm: string;
  reviewedOpenRequest: boolean;
}

/** What the checks found (SRS-12-11, SRS-12-14). */
export interface SanctionPreview {
  eis: string;
  loanType: string;
  terms: {
    sanctioned: number;
    principalInstalments: number;
    instalment: number;
    startYymm: string;
  };
  schedule: ScheduleLine[];
  outstanding: number;
  maxNewSanction: number;
  reduced: boolean;
  openRequest: number | null;
}

/** The document particulars of SRS-G-30 with the file. */
export interface LoanDocument {
  documentType: string;
  reference: string;
  orderDate: string;
  issuingAuthority: string;
  file: File;
}

export interface RescheduleInput {
  principalInstalments: number;
  instalment: number;
  interestInstalments: number;
  interestInstalment: number | null;
  startYymm: string;
}

export interface Submitted {
  id: number;
  status: string;
}

function documentForm(doc: LoanDocument, jsonPart?: { name: string; value: unknown }): FormData {
  const form = new FormData();
  if (jsonPart) {
    form.append(jsonPart.name, new Blob([JSON.stringify(jsonPart.value)], { type: 'application/json' }));
  }
  form.append('documentType', doc.documentType);
  if (doc.reference) form.append('reference', doc.reference);
  form.append('orderDate', doc.orderDate);
  form.append('issuingAuthority', doc.issuingAuthority);
  form.append('file', doc.file);
  return form;
}

/** US-GAP-202 (SRS-12-18): the order of a foreclosure or reschedule, beside its other document. */
function withOrder(form: FormData, order: LoanDocument): FormData {
  form.append('orderDocumentType', order.documentType);
  if (order.reference) form.append('orderReference', order.reference);
  form.append('orderIssuedOn', order.orderDate);
  form.append('orderAuthority', order.issuingAuthority);
  form.append('orderFile', order.file);
  return form;
}

function query(params: Record<string, string | number | undefined>): string {
  const p = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) if (v !== undefined && v !== '') p.set(k, String(v));
  return p.toString();
}

/** The only place the loans module talks to /api/v1/loans. */
export const loansApi = {
  list: (filter: { eis?: string; status?: string }, page: number, size: number) =>
    api<PageResult<LoanRow>>(`/v1/loans?${query({ ...filter, page, size })}`),
  detail: (id: number) => api<LoanDetail>(`/v1/loans/${id}`),
  requests: (page: number, size: number) =>
    api<PageResult<LoanRequest>>(`/v1/loans/requests?${query({ page, size })}`),
  types: () => api<LoanType[]>('/v1/loans/types'),
  summary: (yymm: string) => api<LoanSummary>(`/v1/loans/summary?${query({ yymm })}`),
  disbursements: (page: number, size: number) =>
    api<PageResult<LoanDisbursement>>(`/v1/loans/disbursements?${query({ page, size })}`),
  orders: (id: number) => api<LoanOrder[]>(`/v1/loans/${id}/orders`),
  attachOrder: (id: number, order: LoanDocument) =>
    api<LoanOrder>(`/v1/loans/${id}/orders`, { method: 'POST', body: documentForm(order) }),
  orderFileUrl: (id: number, fileId: number) => `${API_BASE}/v1/loans/${id}/orders/${fileId}`,
  /** SRS-12-26: the Loan Recovery & Outstanding Report's link (format chosen in the dialog). */
  reportUrl: (from: string, to: string) =>
    `${API_BASE}/v1/loans/report?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`,
  preview: (body: SanctionInput) =>
    api<SanctionPreview>('/v1/loans/sanctions/previews', { method: 'POST', body }),
  submit: (body: SanctionInput, order: LoanDocument) =>
    api<Submitted>('/v1/loans/sanctions', {
      method: 'POST',
      body: documentForm(order, { name: 'sanction', value: body }),
    }),
  foreclose: (id: number, order: LoanDocument, receipt: LoanDocument) =>
    api<Submitted>(`/v1/loans/${id}/foreclosures`, {
      method: 'POST',
      body: withOrder(documentForm(receipt), order),
    }),
  reschedule: (id: number, body: RescheduleInput, order: LoanDocument, signed: LoanDocument) =>
    api<Submitted>(`/v1/loans/${id}/reschedules`, {
      method: 'POST',
      body: withOrder(documentForm(signed, { name: 'schedule', value: body }), order),
    }),
};
