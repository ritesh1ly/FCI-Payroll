import { type PageResult } from '@/shared/ui';
import { api } from '@/shared/api';
import type { OtpProof } from '@/modules/workflow';

/** I-17 life-cycle event (employee_lifecycle_event, target DDL v4.0). */
export interface LifecycleEvent {
  id: number;
  eis: string | null;
  employeeName: string | null;
  eventType: string;
  eventDate: string;
  endDate: string | null;
  reason: string;
  subsistencePercent: number | null;
  releaseFromYymm: string | null;
  releaseToYymm: string | null;
  orderReference: string | null;
  source: string;
  createdBy: string;
  status: string;
  approvalId: number | null;
  /** The I-21 task that applied it (SRS-1-63), when it came from HRMS. */
  hrmsChangeTaskId: number | null;
  hrmsRequestId: string | null;
  /** Reinstatement: the intervening-period treatment as received (I-17 l.3882). */
  interveningPeriod: string | null;
}

/** M-11 staff transfer (employee_transfer). */
export interface Transfer {
  id: number;
  eis: string | null;
  employeeName: string | null;
  fromLocation: string | null;
  toLocation: string | null;
  transferDate: string;
  relievingDate: string | null;
  joiningDate: string | null;
  joiningTime: string | null;
  orderReference: string | null;
  /** SRS l.1352: System, read-only - the Last Pay Certificate issued for the transfer, or null. */
  lpcReference: string | null;
  splitWage: boolean;
  fromUnitDays: number | null;
  toUnitDays: number | null;
  reason: string | null;
  status: string;
  approvalId: number | null;
}

export interface NewTransfer {
  toLocation: string;
  transferDate: string;
  relievingDate: string | null;
  joiningDate: string | null;
  joiningTime: string | null;
  orderReference: string | null;
  newStaffNumber: string | null;
  splitWage: boolean;
  fromUnitDays: number | null;
  toUnitDays: number | null;
  reason: string;
  /** SRS-G-31: HRMS holds the order letter (only its reference is recorded). */
  orderHeldInHrms?: boolean;
  /** The order copy stored first (storeTransferOrder). */
  orderAttachmentId?: number | null;
  orderProvisionalKey?: string | null;
}

/** SRS-16-18 / SRS-G-31: the transfer order copy and its particulars. */
export interface TransferOrderCopy {
  documentType: string;
  reference: string;
  orderDate: string;
  issuingAuthority: string;
  file: File;
}

export interface StoredTransferOrder {
  attachmentId: number;
  provisionalKey: string;
}

export interface Destination {
  code: string;
  label: string;
  type: string;
}

const eis = (value: string) => encodeURIComponent(value.trim());

/** The only place the employee module talks to the I-17 / M-11 endpoints. */
export const eventsApi = {
  recentEvents: (page: number, size: number) =>
    api<PageResult<LifecycleEvent>>(`/v1/lifecycle-events?page=${page}&size=${size}`),
  events: (employee: string, page: number, size: number) =>
    api<PageResult<LifecycleEvent>>(
      `/v1/employees/${eis(employee)}/lifecycle-events?page=${page}&size=${size}`,
    ),
  recentTransfers: (page: number, size: number) =>
    api<PageResult<Transfer>>(`/v1/transfers?page=${page}&size=${size}`),
  destinations: () => api<Destination[]>('/v1/transfers/destinations'),
  storeTransferOrder: (employee: string, copy: TransferOrderCopy) => {
    const form = new FormData();
    form.append('documentType', copy.documentType);
    if (copy.reference) form.append('reference', copy.reference);
    form.append('orderDate', copy.orderDate);
    form.append('issuingAuthority', copy.issuingAuthority);
    form.append('file', copy.file);
    return api<StoredTransferOrder>(`/v1/employees/${eis(employee)}/transfer-orders`, {
      method: 'POST',
      body: form,
    });
  },
  recordTransfer: (employee: string, body: NewTransfer, otp?: OtpProof) =>
    api<Transfer>(`/v1/employees/${eis(employee)}/transfers`, {
      method: 'POST',
      body: { ...body, ...(otp ? { otp } : {}) },
    }),
};
