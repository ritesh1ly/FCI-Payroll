import { type PageResult } from '@/shared/ui';
import { API_BASE, api } from '@/shared/api';

/** I-21: one HRMS change task (hrms_change_task); Restricted values arrive masked. */
export interface HrmsTask {
  id: number;
  taskNo: string;
  eis: string;
  employee: string;
  fieldCode: string;
  fieldLabel: string;
  fieldGroup: string;
  currentValue: string | null;
  newValue: string | null;
  effectiveDate: string;
  hrmsRequestId: string | null;
  source: string;
  status: string;
  targetPeriod: string;
  receivedBeforeFreeze: boolean;
  receivedAt: string;
  daysPending: number;
  otpRequired: boolean;
  orderNo: string | null;
  orderDate: string | null;
  issuingAuthority: string | null;
  orderHeldInHrms: boolean;
  makerUser: string | null;
  checkerUser: string | null;
  returnReason: string | null;
  rejectionReason: string | null;
  approvalId: number | null;
  /** SRS-1-67: the bulk order the task was sent under (HCO-), if any. */
  orderRef: string | null;
  /** SRS-1-68: pending beyond the ageing days set on A-07 (never while not set). */
  overdue: boolean;
  /** I-21 Reason for Change (SRS l.3850): as HRMS sent it, read-only; null when none. */
  hrmsReason: string | null;
}

/** SRS-1-22: the DOB effects before approval (dates null while not configured). */
export interface DobPreview {
  currentBirthDate: string | null;
  newBirthDate: string;
  currentRetirement: string | null;
  newRetirement: string | null;
  epsCutoff: string | null;
  retirementPast: boolean;
  retiresSoon: boolean;
  retirementAgeConfigured: boolean;
}

export interface HrmsTaskDetail {
  task: HrmsTask;
  dob: DobPreview | null;
  applicable: boolean;
  /** What approving would do (US-GAP-58: the encashment cap and amount), or null. */
  effect: string | null;
  /** UC I-21 l.3813 (US-GAP-276): the pay impact by month (YYYY-MM); null without pay effect. */
  impact?: PayImpact | null;
}

export interface PayImpact {
  appliesFrom: string;
  paidFrom: string;
  arrearFrom: string | null;
  arrearTo: string | null;
}

export interface HrmsTaskFilter {
  status?: string;
  field?: string;
  eis?: string;
  /** SRS-1-30: the name and DOB corrections only (the Name / DOB Change Register). */
  nameDob?: boolean;
  /** The drawn search: employee, field, HRMS Request ID. */
  q?: string;
}

/** SRS-1-67: one order entered once for several tasks of one paying unit. */
export interface BulkOrderInput {
  orderNo: string;
  orderDate: string;
  issuingAuthority: string;
  subject?: string;
  taskIds: number[];
  /** SRS-1-67 / SRS-G-31: the order's one copy, stored first. */
  copyAttachmentId?: number;
  copyProvisionalKey?: string;
}

export interface BulkOrderCreated {
  orderId: number;
  orderRef: string;
  approvalId: number;
  tasks: number;
}

export interface RegisterCount {
  code: string;
  count: number;
}

/** SRS-1-30: the register's counts by status and by field for the filters. */
export interface RegisterCounts {
  total: number;
  byStatus: RegisterCount[];
  byField: RegisterCount[];
}

export interface OrderInput {
  orderNo?: string;
  orderDate?: string;
  issuingAuthority?: string;
  heldInHrms: boolean;
}

function filters(filter: HrmsTaskFilter): URLSearchParams {
  const p = new URLSearchParams();
  if (filter.status) p.set('status', filter.status);
  if (filter.field) p.set('field', filter.field);
  if (filter.eis) p.set('eis', filter.eis);
  if (filter.nameDob) p.set('nameDob', 'true');
  if (filter.q) p.set('q', filter.q);
  return p;
}

function query(filter: HrmsTaskFilter, page: number, size: number): string {
  const p = filters(filter);
  p.set('page', String(page));
  p.set('size', String(size));
  return p.toString();
}

/** An order copy attached to an I-21 task (SRS-G-31). */
export interface TaskDocument {
  id: number;
  documentTypeCode: string | null;
  documentTypeName: string | null;
  referenceNumber: string | null;
  orderDate: string | null;
  fileName: string;
  uploadedBy: string;
  uploadedAt: string;
}

export interface TaskOrderCopy {
  documentType: string;
  reference: string;
  orderDate: string;
  issuingAuthority: string;
  file: File;
}

/** The only place the employee module talks to /api/v1/hrms-change-tasks. */
export const hrmsChangeApi = {
  list: (filter: HrmsTaskFilter, page: number, size: number) =>
    api<PageResult<HrmsTask>>(`/v1/hrms-change-tasks?${query(filter, page, size)}`),
  detail: (id: number) => api<HrmsTaskDetail>(`/v1/hrms-change-tasks/${id}`),
  /** SRS-1-28 / SRS-G-31: the task's order copies, and one more attached (only added once sent). */
  documents: (id: number) => api<TaskDocument[]>(`/v1/hrms-change-tasks/${id}/documents`),
  attach: (id: number, copy: TaskOrderCopy) => {
    const form = new FormData();
    form.append('documentType', copy.documentType);
    if (copy.reference) form.append('reference', copy.reference);
    form.append('orderDate', copy.orderDate);
    form.append('issuingAuthority', copy.issuingAuthority);
    form.append('file', copy.file);
    return api<TaskDocument>(`/v1/hrms-change-tasks/${id}/documents`, { method: 'POST', body: form });
  },
  documentUrl: (id: number, fileId: number) => `${API_BASE}/v1/hrms-change-tasks/${id}/documents/${fileId}`,
  submit: (id: number, body: OrderInput) =>
    api<{ approvalId: number }>(`/v1/hrms-change-tasks/${id}/submissions`, { method: 'POST', body }),
  counts: (filter: HrmsTaskFilter) => {
    const p = filters(filter).toString();
    return api<RegisterCounts>(`/v1/hrms-change-tasks/counts${p ? `?${p}` : ''}`);
  },
  storeOrderCopy: (copy: TaskOrderCopy) => {
    const form = new FormData();
    form.append('documentType', copy.documentType);
    if (copy.reference) form.append('reference', copy.reference);
    form.append('orderDate', copy.orderDate);
    form.append('issuingAuthority', copy.issuingAuthority);
    form.append('file', copy.file);
    return api<{ attachmentId: number; provisionalKey: string }>('/v1/hrms-change-orders/copies', {
      method: 'POST',
      body: form,
    });
  },
  createOrder: (body: BulkOrderInput) =>
    api<BulkOrderCreated>('/v1/hrms-change-orders', { method: 'POST', body }),
  /** SRS-1-71 / SRS-1-30: the register's export link (the format is chosen in the export dialog). */
  exportUrl: (filter: HrmsTaskFilter) => {
    const p = filters({ ...filter, nameDob: undefined, q: undefined });
    p.set('register', filter.nameDob ? 'NAME_DOB' : 'HRMS');
    return `${API_BASE}/v1/hrms-change-tasks/export?${p.toString()}`;
  },
};
