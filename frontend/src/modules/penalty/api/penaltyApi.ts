import type { StoredOrder } from '@/shared/ui/orderCopy';
import { type PageResult } from '@/shared/ui';
import { API_BASE, api } from '@/shared/api';

/** DDL penalty_order.order_status. */
export type OrderStatus = 'ACTIVE' | 'CANCELLED' | 'PARTLY_REVOKED' | 'REVOKED' | 'COMPLETED' | 'PAUSED_FNF';

/** An order as I-06 lists it (SRS-14-15: by its Penalty ID). */
export interface OrderRow {
  id: number;
  penaltyRef: string;
  eis: string;
  employee: string;
  nature: string;
  orderReference: string;
  /** ENT: keyed on I-06; HRMS: received through I-21, read-only here (SRS-14-1). */
  source: 'ENT' | 'HRMS';
  amount: number | null;
  instalments: number | null;
  periodFrom: string | null;
  periodTo: string | null;
  recovered: number;
  /** SRS-14-19: what the revokes waived. */
  waived?: number;
  outstanding: number;
  status: OrderStatus;
  backdated: boolean;
}

export interface ScheduleLine {
  payrollYymm: string;
  scheduled: number;
  recovered: number;
  status: 'PENDING' | 'RECOVERED' | 'PARTIAL' | 'WAIVED' | 'DEFERRED';
  reason: string | null;
}

export interface RevokeRow {
  id: number;
  type: 'PARTIAL' | 'FULL';
  amount: number | null;
  periodFrom: string | null;
  periodTo: string | null;
  orderReference: string;
  reason: string;
  status: 'PENDING' | 'APPROVED' | 'RETURNED' | 'CANCELLED';
}

export interface OrderDetail {
  order: OrderRow;
  schedule: ScheduleLine[];
  revokes: RevokeRow[];
}

/** A request waiting for the Checker or returned to the Maker (PN-17). */
export interface OrderRequest {
  approvalId: number;
  /** The Penalty ID assigned when the order was recorded (SRS-14-15, US-GAP-229). */
  penaltyRef: string | null;
  eis: string;
  employee: string;
  amount: number | null;
  status: 'PENDING_APPROVAL' | 'PENDING_ATTACHMENT' | 'RETURNED';
  submittedBy: string;
  submittedAt: string | null;
  returnReason: string | null;
  summary: string | null;
}

/** What the Maker enters from the order. */
export interface OrderInput {
  eis: string;
  nature: string;
  orderReference: string;
  effectiveDate: string;
  /** Absent on a labour order that recovers nothing (SRS-14-8). */
  amount?: number;
  instalments?: number;
  startYymm?: string;
  /** The end of a labour increment stop or Basic reduction; none while open. */
  periodTo?: string | null;
}

/** The drawn I-06 tiles and panel figures (API-12-3). */
export interface PenaltyCounts {
  orders: number;
  outstanding: number;
  payments: number;
  paymentsAwaiting: number;
  paymentsAwaitingAmount: number;
  minNetGuard: number;
  incentiveRecovery: number;
  carriedToFnf: number;
}

/** One line of the drawn Order Register (API-12-3). */
export interface RegisterRow {
  kind: 'ORDER' | 'PAYMENT' | 'REQUEST';
  id: number;
  reference: string | null;
  eis: string;
  employee: string;
  source: 'ENT' | 'HRMS';
  nature: string | null;
  reason: string | null;
  amount: number | null;
  instalments: number | null;
  perPeriod: number | null;
  approvedBy: string | null;
  status: string;
  belowFloor: boolean;
}

/** SRS-14-10: the Min-Net Guard of a request on the floor route. */
export interface MinNetGuard {
  approvalId: number;
  reference: string;
  eis: string;
  employee: string;
  gross: number | null;
  existingDeductions: number | null;
  proposedInstalment: number;
  resultingNet: number;
  floor: number | null;
}

export interface OrderPreview {
  eis: string;
  name: string;
  currentBasic: number | null;
  nature: string;
  amount: number;
  instalments: number;
  schedule: { payrollYymm: string; amount: number }[];
  adjusted: boolean;
  latestNet: number;
  netAfter: number;
  floor: number | null;
  belowFloor: boolean;
  backdated: boolean;
  /** SRS-14-18: the Basic after the order (a recovery leaves it unchanged); null when not known. */
  proposedBasic?: number | null;
  /** SRS l.2995: the Maker deferred the recovery to F&F instead of breaching the floor. */
  deferredToFnf?: boolean;
}

export interface RevokeInput {
  type: 'PARTIAL' | 'FULL';
  amount: number | null;
  fromYymm: string | null;
  toYymm: string | null;
  orderReference: string;
  orderDate: string;
  reason: string;
}

export interface PaymentInput {
  eis: string;
  payCode: number;
  amount: number;
  payrollYymm: string;
  reason: string;
  applicationDate: string | null;
  sanctionReference: string;
  sanctionDate: string | null;
}

export interface PaymentRow {
  id: number;
  eis: string;
  payCode: number | null;
  /** The pay component's name (code + name on screen: owner 2026-10-27). */
  payName: string | null;
  payrollYymm: string;
  amount: number;
  taxable: boolean;
  applicationDate: string | null;
  sanctionReference: string;
  sanctionDate: string | null;
  status: 'RECORDED' | 'SUBMITTED' | 'APPROVED' | 'PAID' | 'REJECTED' | 'CANCELLED';
  invoiceStatus: string;
  /** SRS l.3030 (SRS-G-37): when the invoice went to APPS; null until sent. */
  invoiceSentAt?: string | null;
  /** The Checker's reason when returned (RECORDED) or rejected (US-GAP-230). */
  checkerReason: string | null;
  invoiceNo?: number | null;
  appsCancelStatus?: string;
  employee?: string;
}

/** The order copy (SRS-14-12, SRS-G-30). */
export interface OrderCopy {
  documentType: string;
  reference: string;
  orderDate: string;
  issuingAuthority: string;
  file: File;
}

export interface Submitted {
  id: number;
  status: string;
}

function copyForm(copy: OrderCopy, part: { name: string; value: unknown }): FormData {
  const form = new FormData();
  form.append(part.name, new Blob([JSON.stringify(part.value)], { type: 'application/json' }));
  form.append('documentType', copy.documentType);
  if (copy.reference) form.append('reference', copy.reference);
  form.append('orderDate', copy.orderDate);
  form.append('issuingAuthority', copy.issuingAuthority);
  form.append('file', copy.file);
  return form;
}

function query(params: Record<string, string | number | undefined>): string {
  const p = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) if (v !== undefined && v !== '') p.set(k, String(v));
  return p.toString();
}

/** The only place the penalty module talks to /api/v1/penalties. */
export const penaltyApi = {
  list: (filter: { eis?: string; status?: string }, page: number, size: number) =>
    api<PageResult<OrderRow>>(`/v1/penalties?${query({ ...filter, page, size })}`),
  detail: (id: number) => api<OrderDetail>(`/v1/penalties/${id}`),
  counts: () => api<PenaltyCounts>('/v1/penalties/counts'),
  register: (page: number, size: number) =>
    api<PageResult<RegisterRow>>(`/v1/penalties/register?${query({ page, size })}`),
  minNet: () => api<MinNetGuard | undefined>('/v1/penalties/min-net'),
  orderCopies: (id: number) => api<StoredOrder[]>(`/v1/penalties/${id}/orders`),
  orderCopyUrl: (id: number, fileId: number) => `${API_BASE}/v1/penalties/${id}/files/${fileId}`,
  requests: (page: number, size: number) =>
    api<PageResult<OrderRequest>>(`/v1/penalties/requests?${query({ page, size })}`),
  preview: (body: OrderInput) => api<OrderPreview>('/v1/penalties/previews', { method: 'POST', body }),
  submit: (body: OrderInput, copy: OrderCopy) =>
    api<Submitted>('/v1/penalties', { method: 'POST', body: copyForm(copy, { name: 'order', value: body }) }),
  revoke: (id: number, body: RevokeInput, copy: OrderCopy) =>
    api<Submitted>(`/v1/penalties/${id}/revokes`, {
      method: 'POST',
      body: copyForm(copy, { name: 'revoke', value: body }),
    }),
  cancel: (id: number, reason: string, copy: OrderCopy) =>
    api<Submitted>(`/v1/penalties/${id}/cancellations`, {
      method: 'POST',
      body: copyForm(copy, { name: 'cancellation', value: { reason } }),
    }),
  payments: (page: number, size: number) =>
    api<PageResult<PaymentRow>>(`/v1/penalties/over-and-above?${query({ page, size })}`),
  pay: (body: PaymentInput) => api<Submitted>('/v1/penalties/over-and-above', { method: 'POST', body }),
  cancelPayment: (id: number, reason: string) =>
    api<Submitted>(`/v1/penalties/over-and-above/${id}/cancellations`, { method: 'POST', body: { reason } }),
  /** SRS-14-19: the Penalty Report link (format chosen in the dialog). */
  reportUrl: (from: string, to: string) =>
    `${API_BASE}/v1/penalties/report?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`,
};
