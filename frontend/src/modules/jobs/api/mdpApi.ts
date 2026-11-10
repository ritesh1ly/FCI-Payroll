import { type PageResult } from '@/shared/ui';
import { api, API_BASE } from '@/shared/api';

/** I-19: one entry of the Maintain Direct Payroll register (mdp_transaction). */
export interface MdpEntry {
  id: number;
  reference: string;
  period: string;
  eis: string;
  member: string;
  payCode: number;
  head: string;
  amount: number;
  status: 'ENTERED' | 'AMENDED' | 'APPROVED' | 'CANCELLED' | 'POSTED';
  reason: string;
  enteredBy: string;
  enteredAt: string;
  checkedBy: string | null;
  decidedAt: string | null;
  pendingApprovalId: number | null;
}

/** The four tiles of I-19 as drawn (OD-121): the window, its entries, the heads and the value. */
export interface MdpSummary {
  /** YYYY-MM of the window (or the newest locked period); null before the unit's first lock. */
  period: string | null;
  windowOpen: boolean;
  lockedAt: string | null;
  approved: number;
  pending: number;
  heads: number;
  value: number;
  approvedValue: number;
}

/** An order stored with an entry (the document module's attachment view, as the order card shows it). */
export interface MdpOrder {
  id: number;
  fileName: string;
  documentTypeCode: string | null;
  documentTypeName: string | null;
  referenceNumber: string | null;
  uploadedBy: string;
  uploadedAt: string;
}

/** The particulars of one order file attached on the order card. */
export interface NewMdpOrder {
  documentType: string;
  reference: string;
  orderDate: string;
  issuingOffice: string;
  file: File;
}

/** A document type of document_type_master, with whether its reference number is mandatory. */
export interface MdpDocumentType {
  code: string;
  name: string;
  referenceRequired: boolean;
}

export interface MdpHead {
  payCode: number;
  name: string;
}

export interface MdpFilter {
  period?: string;
  status?: string;
  payCode?: string;
  eis?: string;
}

export interface MdpCapture {
  period: string;
  eis: string;
  payCode: number;
  amount: number;
  reason: string;
}

function query(filter: MdpFilter): string {
  const q = new URLSearchParams();
  for (const [key, value] of Object.entries(filter)) {
    if (value) q.set(key, value);
  }
  return q.toString();
}

/** The only place the jobs module talks to /api/v1/mdp-transactions. */
export const mdpApi = {
  register: (filter: MdpFilter, page: number, size: number) =>
    api<PageResult<MdpEntry>>(`/v1/mdp-transactions?${query(filter)}&page=${page}&size=${size}`),
  exportUrl: (filter: MdpFilter) => `${API_BASE}/v1/mdp-transactions/export?${query(filter)}`,
  summary: () => api<MdpSummary>('/v1/mdp-transactions/summary'),
  heads: () => api<MdpHead[]>('/v1/mdp-transactions/heads'),
  /** The drawn order card (FRS §6.4.3, A-41): an entry's stored orders, one attached, one file's address. */
  orders: (id: number) => api<MdpOrder[]>(`/v1/mdp-transactions/${id}/documents`),
  attachOrder: (id: number, o: NewMdpOrder) => {
    const form = new FormData();
    form.append('documentType', o.documentType);
    if (o.reference.trim() !== '') form.append('reference', o.reference.trim());
    form.append('orderDate', o.orderDate);
    if (o.issuingOffice.trim() !== '') form.append('issuingOffice', o.issuingOffice.trim());
    form.append('file', o.file);
    return api<MdpOrder>(`/v1/mdp-transactions/${id}/documents`, { method: 'POST', body: form });
  },
  orderUrl: (id: number, fileId: number) => `${API_BASE}/v1/mdp-transactions/${id}/documents/${fileId}`,
  documentTypes: () => api<MdpDocumentType[]>('/v1/document-types'),
  openPeriods: () => api<string[]>('/v1/mdp-transactions/open-periods'),
  capture: (body: MdpCapture) =>
    api<{ approvalId: number }>('/v1/mdp-transactions', { method: 'POST', body }),
  amend: (id: number, amount: number, reason: string) =>
    api<{ approvalId: number }>(`/v1/mdp-transactions/${id}/amendments`, {
      method: 'POST',
      body: { amount, reason },
    }),
  cancel: (id: number, reason: string) =>
    api<{ approvalId: number }>(`/v1/mdp-transactions/${id}/cancellations`, {
      method: 'POST',
      body: { reason },
    }),
};
