import { type PageResult } from '@/shared/ui';
import { API_BASE, api } from '@/shared/api';

/** The DDL le_status values (plan 13 phase 13d). */
export type LeArrearStatus =
  'CALCULATED' | 'SUBMITTED' | 'APPROVED' | 'INVOICED' | 'PAID' | 'CANCELLED' | 'NOT_ELIGIBLE';

/** One record: the calculation sheet (BR-ARR-LE-002). */
export interface LeArrearRow {
  id: number;
  recordNumber: string;
  eis: string;
  name: string;
  separationDate: string | null;
  order: string | null;
  revisionEffective: string;
  /** YYYYMM. */
  month: string;
  days: number | null;
  oldBasic: number | null;
  oldDa: number | null;
  revisedBasic: number | null;
  revisedDa: number | null;
  paid: number | null;
  due: number | null;
  arrear: number | null;
  tds: number;
  status: LeArrearStatus;
  ineligibleReason: string | null;
  batch: string | null;
}

export interface LeArrearFilter {
  status?: string;
  eis?: string;
}

export interface RunRequest {
  eis?: string;
  circularId: number;
  revisionEffective: string;
  reason: string;
}

export interface RunResult {
  eventId: number;
  members: number;
  records: number;
  skipped: { eis: string; reason: string }[];
}

/** An Active M-25 order to run against (the circulars in force today). */
export interface RevisionOrder {
  id: number;
  number: string;
  subject: string | null;
  effectiveFrom: string | null;
}

export interface Submitted {
  approvalId: number;
  status: string;
}

/** The tile "Records for this order": the latest order of the offices and its records not cancelled. */
export interface LeArrearSummary {
  order: string | null;
  records: number;
}

/** An order stored with a record (the document module's attachment view, as the card shows it). */
export interface LeOrder {
  id: number;
  fileName: string;
  documentTypeCode: string | null;
  documentTypeName: string | null;
  referenceNumber: string | null;
  uploadedBy: string;
  uploadedAt: string;
}

/** A document type of document_type_master, with whether its reference number is mandatory. */
export interface LeDocumentType {
  code: string;
  name: string;
  referenceRequired: boolean;
}

/** The particulars of one order file attached on the order-copy card. */
export interface NewLeOrder {
  documentType: string;
  reference: string;
  orderDate: string;
  issuingOffice: string;
  file: File;
}

function query(f: LeArrearFilter): string {
  const p = new URLSearchParams();
  if (f.status) p.set('status', f.status);
  if (f.eis) p.set('eis', f.eis);
  return p.toString();
}

/** The only place I-24 talks to /api/v1/leave-encashment-arrears. */
export const leaveArrearApi = {
  records: (f: LeArrearFilter, page: number, size: number) => {
    const q = query(f);
    return api<PageResult<LeArrearRow>>(
      `/v1/leave-encashment-arrears?${q ? `${q}&` : ''}page=${page}&size=${size}`,
    );
  },
  run: (r: RunRequest) => api<RunResult>('/v1/leave-encashment-arrears/runs', { method: 'POST', body: r }),
  submit: (ids: number[], remark: string) =>
    api<Submitted>('/v1/leave-encashment-arrears/submissions', {
      method: 'POST',
      body: { ids, remark },
    }),
  /** The M-25 circulars in force today (the revision order lookup). */
  orders: () => api<RevisionOrder[]>('/v1/masters/circulars/current'),
  summary: () => api<LeArrearSummary>('/v1/leave-encashment-arrears/summary'),
  /** SRS-G-37: the Maker raises the cancellation; the Payroll Checker's approval cancels the record. */
  cancel: (id: number, reason: string) =>
    api<Submitted>(`/v1/leave-encashment-arrears/${id}/cancellations`, { method: 'POST', body: { reason } }),
  /** The order-copy card (FRS §6.4.3, A-41): the record's stored orders, one attached, one file's address. */
  documents: (id: number) => api<LeOrder[]>(`/v1/leave-encashment-arrears/${id}/documents`),
  attachDocument: (id: number, o: NewLeOrder) => {
    const form = new FormData();
    form.append('documentType', o.documentType);
    if (o.reference.trim() !== '') form.append('reference', o.reference.trim());
    form.append('orderDate', o.orderDate);
    if (o.issuingOffice.trim() !== '') form.append('issuingOffice', o.issuingOffice.trim());
    form.append('file', o.file);
    return api<LeOrder>(`/v1/leave-encashment-arrears/${id}/documents`, { method: 'POST', body: form });
  },
  documentUrl: (id: number, fileId: number) =>
    `${API_BASE}/v1/leave-encashment-arrears/${id}/documents/${fileId}`,
  documentTypes: () => api<LeDocumentType[]>('/v1/document-types'),
  reportUrl: (f: LeArrearFilter) => {
    const q = query(f);
    return `${API_BASE}/v1/leave-encashment-arrears/report${q ? `?${q}` : ''}`;
  },
};
