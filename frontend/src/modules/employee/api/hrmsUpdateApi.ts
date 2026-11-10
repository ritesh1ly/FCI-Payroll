import { type PageResult } from '@/shared/ui';
import { API_BASE, api } from '@/shared/api';

/** SRS-1-70: a field with pay effect the request may name. */
export interface UpdateField {
  code: string;
  label: string;
  group: string;
}

/** SRS-1-70: one HRMS Update Request with its outcome (hrms_update_request). */
export interface UpdateRequest {
  id: number;
  requestNo: string;
  eis: string;
  employee: string;
  fieldCode: string;
  fieldLabel: string;
  channel: 'ESEVA' | 'PAYROLL_MAKER';
  raisedBy: string;
  raisedAt: string;
  hrmsReference: string | null;
  effectiveDate: string;
  fetchResult: string | null;
  status: string;
  messageCode: string | null;
  taskNo: string | null;
  /** E-01 l.3308: the linked I-21 task status (APPROVED: the outcome reads "Approved and applied"). */
  taskStatus?: string | null;
}

export interface UpdateInput {
  /** The Maker names the employee; in eSeva it is the signed-in employee. */
  eis?: string;
  fieldCode: string;
  hrmsReference?: string;
  effectiveDate: string;
}

/** An attached order copy (never the bytes). */
export interface OrderCopy {
  id: number;
  documentTypeName: string;
  referenceNumber: string | null;
  orderDate: string | null;
  fileName: string;
  uploadedBy: string;
  uploadedAt: string;
}

/** The order copy as the form sends it (SRS-1-70: optional). */
export interface OrderUpload {
  documentType: string;
  reference: string;
  orderDate: string;
  issuingAuthority: string;
  file: File;
}

const BASE = '/v1/hrms-update-requests';
const docs = (id: number, mine: boolean) => `${BASE}/${mine ? 'mine/' : ''}${id}/documents`;

function paging(page: number, size: number): string {
  return new URLSearchParams({ page: String(page), size: String(size) }).toString();
}

/** The only place the employee module talks to /api/v1/hrms-update-requests. */
export const hrmsUpdateApi = {
  fields: () => api<UpdateField[]>(`${BASE}/fields`),
  list: (page: number, size: number) => api<PageResult<UpdateRequest>>(`${BASE}?${paging(page, size)}`),
  raise: (body: UpdateInput) => api<UpdateRequest>(BASE, { method: 'POST', body }),
  refetch: (id: number) => api<UpdateRequest>(`${BASE}/${id}/fetches`, { method: 'POST' }),
  mine: (page: number, size: number) => api<PageResult<UpdateRequest>>(`${BASE}/mine?${paging(page, size)}`),
  raiseMine: (body: UpdateInput) => api<UpdateRequest>(`${BASE}/mine`, { method: 'POST', body }),
  documents: (id: number, mine: boolean) => api<OrderCopy[]>(docs(id, mine)),
  attach: (id: number, mine: boolean, order: OrderUpload) => {
    const form = new FormData();
    form.append('documentType', order.documentType);
    if (order.reference) form.append('reference', order.reference);
    form.append('orderDate', order.orderDate);
    form.append('issuingAuthority', order.issuingAuthority);
    form.append('file', order.file);
    return api<OrderCopy>(docs(id, mine), { method: 'POST', body: form });
  },
  documentUrl: (id: number, mine: boolean, fileId: number) => `${API_BASE}${docs(id, mine)}/${fileId}`,
};
