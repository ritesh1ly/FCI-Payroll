import { type PageResult } from '@/shared/ui';
import { api, API_BASE } from '@/shared/api';
import type { StoredOrder } from '@/shared/ui/orderCopy';

/** The DDL case stages (UC-06 steps; plan 11 D11-4). */
export type CaseStage =
  | 'SEPARATION_RECEIVED'
  | 'CLEARANCES_RAISED'
  | 'CLEARANCES_RECEIVED'
  | 'COMPUTED'
  | 'CPF_LINKED'
  | 'APPROVED'
  | 'POSTED'
  | 'CLOSED';

/** One case of the O-05 list. */
export interface CaseRow {
  caseId: number;
  caseReference: string;
  eis: string;
  name: string;
  /** "code - name" (UI-DESC). */
  office: string | null;
  separationType: string;
  separationDate: string;
  stage: CaseStage;
  awaiting: string[];
  status: string | null;
  netPayable: number | null;
  /** STAFF, or the labour_profile payroll type. */
  payrollType: string;
  /** Pending salary + leave encashment + arrears + ex-gratia (SRS-10-13); null before computing. */
  payrollDues: number | null;
  totalRecoveries: number | null;
  recoveredFromGratuity: number | null;
  /** The computed settlement waits for the Payroll Checker's decision (SRS-10-14). */
  awaitingChecker: boolean;
  /** A recovery against gratuity waits for Headquarters (SRS-10-22). */
  hqApprovalPending: boolean;
}

/** The four O-05 tiles of the session's offices. */
export interface Summary {
  separations: number;
  settled: number;
  settledAmount: number;
  byType: { type: string; cases: number }[];
  awaitingClearance: number;
  hqApproval: number;
  netRecovery: number;
}

/** One settlement payment and its invoice (SRS-G-37, SRS-10-31). */
export interface InvoiceRow {
  caseId: number;
  caseReference: string;
  eis: string;
  name: string;
  creditMemo: boolean;
  amount: number;
  invoiceNo: string | null;
  invoiceStatus: string;
  sentToApps: string | null;
  appsCancelled: boolean;
  settlementStatus: string;
}

export interface Awaiting {
  clearance: string;
  cases: number;
}

export interface Clearance {
  type: string;
  status: string;
  remark: string | null;
}

export interface Recovery {
  id: number;
  type: string;
  reference: string | null;
  period: string | null;
  amount: number;
  source: 'PAYROLL_DUES' | 'GRATUITY';
  gratuityReason: string | null;
  gratuityOrderRef: string | null;
  hqApprovalId: number | null;
  /** SRS-10-22: the Headquarters approval of a recovery from gratuity, as its approval item stands. */
  hqApprovalStatus: string | null;
}

export interface ExGratia {
  id: number;
  letterNo: string;
  letterDate: string;
  amount: number;
  status: string;
}

export interface CaseDetail {
  summary: CaseRow;
  settlementId: number;
  clearances: Clearance[];
  pendingSalary: number | null;
  leaveEncashment: number | null;
  arrears: number | null;
  exGratia: number | null;
  gratuity: number | null;
  totalRecoveries: number | null;
  recoveredFromGratuity: number | null;
  netRecovery: boolean;
  recoveries: Recovery[];
  exGratiaEntries: ExGratia[];
  cpfStatus: string | null;
  cpfAmount: number | null;
  /** SRS-10-3 (O-05 l.2937): the CPF final payment's sanction reference. */
  cpfSanctionNumber: string | null;
  benefitsHeld: boolean;
  invoiceStatus: string | null;
  /** The computed settlement waits for the Payroll Checker's decision (SRS-10-14). */
  awaitingChecker: boolean;
}

/** A document type of the order card (SRS-G-30). */
export interface DocumentType {
  code: string;
  name: string;
  referenceRequired: boolean;
}

export interface Submitted {
  approvalId: number;
  status: string;
}

const base = (id: number) => `/v1/settlement-cases/${id}`;

/** The only place the settlement module talks to /api/v1/settlement-cases. */
export const settlementApi = {
  cases: (eis: string | undefined, page: number, size: number) => {
    const p = new URLSearchParams();
    if (eis) p.set('eis', eis);
    p.set('page', String(page));
    p.set('size', String(size));
    return api<PageResult<CaseRow>>(`/v1/settlement-cases?${p.toString()}`);
  },
  awaiting: () => api<Awaiting[]>('/v1/settlement-cases/awaiting'),
  summary: () => api<Summary>('/v1/settlement-cases/summary'),
  invoices: (page: number, size: number) =>
    api<PageResult<InvoiceRow>>(`/v1/settlement-cases/invoices?page=${page}&size=${size}`),
  orders: (id: number) => api<StoredOrder[]>(`${base(id)}/orders`),
  documentTypes: () => api<DocumentType[]>('/v1/document-types'),
  /** SRS-G-30: an order copy stored with the case (multipart); one file a call. */
  attachOrder: (
    id: number,
    order: {
      documentType: string;
      reference: string;
      orderDate: string;
      issuingAuthority: string;
      file: File;
    },
  ) => {
    const form = new FormData();
    form.append('documentType', order.documentType);
    if (order.reference) form.append('reference', order.reference);
    form.append('orderDate', order.orderDate);
    if (order.issuingAuthority) form.append('issuingAuthority', order.issuingAuthority);
    form.append('file', order.file);
    return api<StoredOrder>(`${base(id)}/orders`, { method: 'POST', body: form });
  },
  /** A stored order is downloaded through the browser's session (a link, not a fetch). */
  fileUrl: (id: number, fileId: number) => `${API_BASE}${base(id)}/files/${fileId}`,
  detail: (id: number) => api<CaseDetail>(base(id)),
  noDues: (id: number, dues: number, remark: string) =>
    api<CaseDetail>(`${base(id)}/no-dues`, { method: 'POST', body: { dues, remark } }),
  compute: (id: number) => api<CaseDetail>(`${base(id)}/computations`, { method: 'POST', body: {} }),
  exclude: (id: number, clearanceType: string, reason: string) =>
    api<CaseDetail>(`${base(id)}/exclusions`, { method: 'POST', body: { clearanceType, reason } }),
  /** SRS-10-25: an ex-gratia entry at its letter's amount, the letter attached (multipart). */
  exGratia: (
    id: number,
    entry: { letterNo: string; letterDate: string; amount: number; issuingAuthority: string; letter: File },
  ) => {
    const form = new FormData();
    form.append('letterNo', entry.letterNo);
    form.append('letterDate', entry.letterDate);
    form.append('amount', String(entry.amount));
    form.append('issuingAuthority', entry.issuingAuthority);
    form.append('letter', entry.letter);
    return api<Submitted>(`${base(id)}/ex-gratia`, { method: 'POST', body: form });
  },
  /**
   * SRS-10-22: a recovery taken from the gratuity (with the order, for HQ approval) or back from the
   * payroll dues (multipart).
   */
  source: (
    id: number,
    recoveryId: number,
    entry: {
      source: 'PAYROLL_DUES' | 'GRATUITY';
      reason: string;
      orderRef?: string;
      orderDate?: string;
      issuingAuthority?: string;
      order?: File;
    },
  ) => {
    const form = new FormData();
    form.append('source', entry.source);
    if (entry.reason) form.append('reason', entry.reason);
    if (entry.orderRef) form.append('orderRef', entry.orderRef);
    if (entry.orderDate) form.append('orderDate', entry.orderDate);
    if (entry.issuingAuthority) form.append('issuingAuthority', entry.issuingAuthority);
    if (entry.order) form.append('order', entry.order);
    return api<CaseDetail>(`${base(id)}/recoveries/${recoveryId}/source`, { method: 'PUT', body: form });
  },
  /** The Checker's approval asks for the OTP in W-01 (FNF_APPROVAL), not here. */
  submit: (id: number) => api<Submitted>(`${base(id)}/submissions`, { method: 'POST', body: {} }),
  /** The Checker's release approval asks for the OTP in W-01 (DISBURSEMENT_RELEASE). */
  release: (id: number) => api<Submitted>(`${base(id)}/releases`, { method: 'POST', body: {} }),
};
