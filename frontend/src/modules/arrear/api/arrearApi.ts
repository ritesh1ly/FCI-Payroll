import { type PageResult } from '@/shared/ui';
import { API_BASE, api } from '@/shared/api';

/** The DDL arrear_event statuses (plan 13 phase 13a). */
export type EventStatus = 'RAISED' | 'COMPUTED' | 'APPROVED' | 'POSTED' | 'REVERSED' | 'REJECTED';

/** One event of the I-08 list, with its member count and totals. */
export interface EventRow {
  id: number;
  eventCode: string;
  eventName: string;
  reference: string;
  effectiveFrom: string;
  effectiveTo: string | null;
  /** "code - name" (UI-DESC). */
  office: string | null;
  status: EventStatus;
  /** The cycle (YYYYMM) the approved event posts into. */
  postedToYymm: string | null;
  members: number;
  arrears: number;
  recoveries: number;
  raisedBy: string;
  raisedAt: string;
  /** The owning module of a back-dated event (LEAVE ...), or RATE_CHANGE for a bulk one. */
  source: string | null;
  /** A single-member retrospective event (plan 13 phase 13b): the Maker may compute it again. */
  singleMember: boolean;
  /** Plan 13 phase 13g: the source module stated the amounts; never computed again. */
  stated: boolean;
  /** SRS-8-6: the event starts before the Headquarters back-date limit. */
  beyondWindow: boolean;
  /** SRS-8-6: the Headquarters exception asked for (PENDING) or given (APPROVED); null when none. */
  hqException: 'PENDING' | 'APPROVED' | null;
}

/** One differential of an event: member, month, component; original vs recomputed. */
export interface DetailRow {
  eis: string;
  name: string;
  yymm: string;
  payCode: number;
  component: string;
  original: number | null;
  recomputed: number | null;
  arrear: number;
  status: string;
}

export interface EventFilter {
  status?: string;
  eventCode?: string;
}

/** A recompute: computed, or the reason the member cannot be. */
export interface Computation {
  computed: boolean;
  reason: string | null;
}

export interface Submitted {
  approvalId: number;
  status: string;
}

/** The approval item a controlled undo request opened (route C02_UNDO). */
export interface UndoRequested {
  approvalId: number;
}

function query(f: EventFilter): string {
  const p = new URLSearchParams();
  if (f.status) p.set('status', f.status);
  if (f.eventCode) p.set('eventCode', f.eventCode);
  return p.toString();
}

/** I-08 as drawn (OD-121): the four tiles; statutory is null until the next cycle run reports it. */
export interface ArrearSummary {
  openEvents: number;
  awaitingApproval: number;
  arrears: number;
  arrearMembers: number;
  recoveries: number;
  recoveryMembers: number;
  statutory: number | null;
}

/** One month of an event's span with its sums (I-08 "Period-wise differential"). */
export interface PeriodRow {
  yymm: string;
  original: number;
  revised: number;
  basicDiff: number;
  daDiff: number;
  hraDiff: number;
  netArrear: number;
}

/** The retro controls: null = the Headquarters setting is not configured (R-RULE). */
export interface RetroControls {
  maxRetroMonths: number | null;
  postRetirementMonths: number | null;
}

export interface ArrearEventType {
  code: string;
  name: string;
  category: string;
}

/** The Maker's new retro event (SRS-8-8). */
export interface NewRetroEvent {
  eventCode: string;
  reference: string;
  payrollType: string;
  effectiveFrom: string;
  effectiveTo: string | null;
  circularId: number | null;
}

/** An order stored with an event (the document module's attachment view, as the card shows it). */
export interface EventOrder {
  id: number;
  fileName: string;
  documentTypeCode: string | null;
  documentTypeName: string | null;
  referenceNumber: string | null;
  uploadedBy: string;
  uploadedAt: string;
}

/** A document type of document_type_master, with whether its reference number is mandatory. */
export interface OrderDocumentType {
  code: string;
  name: string;
  referenceRequired: boolean;
}

/** The particulars of one order file attached on the order-copy card. */
export interface NewEventOrder {
  documentType: string;
  reference: string;
  orderDate: string;
  issuingOffice: string;
  file: File;
}

/** The only place the arrear module talks to /api/v1/arrear-events. */
export const arrearApi = {
  summary: () => api<ArrearSummary>('/v1/arrear-events/summary'),
  controls: () => api<RetroControls>('/v1/arrear-events/controls'),
  types: () => api<ArrearEventType[]>('/v1/arrear-events/types'),
  periods: (id: number) => api<PeriodRow[]>(`/v1/arrear-events/${id}/periods`),
  create: (event: NewRetroEvent) => api<{ id: number }>('/v1/arrear-events', { method: 'POST', body: event }),
  events: (f: EventFilter, page: number, size: number) => {
    const q = query(f);
    return api<PageResult<EventRow>>(`/v1/arrear-events?${q ? `${q}&` : ''}page=${page}&size=${size}`);
  },
  details: (id: number, page: number, size: number) =>
    api<PageResult<DetailRow>>(`/v1/arrear-events/${id}/details?page=${page}&size=${size}`),
  /** Approve &amp; Merge: the Payroll Checker decides in W-01. */
  submit: (id: number, remark: string) =>
    api<Submitted>(`/v1/arrear-events/${id}/submissions`, { method: 'POST', body: { remark } }),
  /** Plan 13 phase 13b: a single-member event computed again once the data is complete. */
  recompute: (id: number) =>
    api<Computation>(`/v1/arrear-events/${id}/computations`, { method: 'POST', body: {} }),
  /** SRS-8-6: ask Headquarters to process an event beyond the back-date limit, with its order. */
  requestException: (id: number, reason: string, circularId: number) =>
    api<Submitted>(`/v1/arrear-events/${id}/hq-exceptions`, { method: 'POST', body: { reason, circularId } }),
  /**
   * SRS-21-6 / SRS-15-3: the controlled undo of an approved event, asked on the cycle it posts into
   * (period YYYY-MM); the Payroll Checker verifies and the Administrator authorises with OTP.
   */
  requestUndo: (id: number, period: string, reason: string) =>
    api<UndoRequested>(`/v1/cycles/${period}/undo-requests`, {
      method: 'POST',
      body: { transactionEntity: 'arrear_event', transactionKey: String(id), reason },
    }),
  /** The order-copy card (FRS §6.4.3, A-41): the event's stored orders, one attached, one file's address. */
  orders: (id: number) => api<EventOrder[]>(`/v1/arrear-events/${id}/documents`),
  attachOrder: (id: number, o: NewEventOrder) => {
    const form = new FormData();
    form.append('documentType', o.documentType);
    if (o.reference.trim() !== '') form.append('reference', o.reference.trim());
    form.append('orderDate', o.orderDate);
    if (o.issuingOffice.trim() !== '') form.append('issuingOffice', o.issuingOffice.trim());
    form.append('file', o.file);
    return api<EventOrder>(`/v1/arrear-events/${id}/documents`, { method: 'POST', body: form });
  },
  orderUrl: (id: number, fileId: number) => `${API_BASE}/v1/arrear-events/${id}/documents/${fileId}`,
  documentTypes: () => api<OrderDocumentType[]>('/v1/document-types'),
  registerUrl: (f: EventFilter) => {
    const q = query(f);
    return `${API_BASE}/v1/arrear-events/register${q ? `?${q}` : ''}`;
  },
};
