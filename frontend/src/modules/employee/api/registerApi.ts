import { type PageResult } from '@/shared/ui';
import { API_BASE, api } from '@/shared/api';
import type { EmployeeFilter } from './employeeApi';
import type { TaskOrderCopy } from './hrmsChangeApi';

/** One office of the M-01 filter. */
export interface Office {
  code: string;
  label: string;
}

/** M-01 Member ID History (SRS-1-76). */
export interface MemberIdRow {
  idType: string;
  memberId: string | null;
  category: string | null;
  zone: string | null;
  effectiveFrom: string;
  effectiveTo: string | null;
  status: string;
  reason: string | null;
}

/** M-01 Staff Number History (SRS-16-7). */
export interface StaffNumberRow {
  oldNumber: string | null;
  newNumber: string;
  origin: string | null;
  destination: string | null;
  effectiveDate: string | null;
  event: string;
}

/** Record Detail's further fields and the live HRMS read (SRS-1-70). */
export interface RecordExtras {
  cadre: string | null;
  payScaleCode: string | null;
  oldBasic: number | null;
  bankCode: string | null;
  legacyEmpNum: string | null;
  recordOrigin: string | null;
  hrmsAvailable: boolean;
  hrmsBasic: number | null;
  hrmsStatus: string | null;
}

/** One order stored with a record (the fields the order card reads). */
export interface StoredDocument {
  id: number;
  fileName: string;
  documentTypeCode: string | null;
  documentTypeName: string | null;
  referenceNumber: string | null;
  uploadedBy: string;
  uploadedAt: string;
}

/** I-17 Life-cycle events received. */
export interface ReceivedEvent {
  id: number;
  eventType: string;
  eis: string | null;
  employee: string | null;
  effective: string;
  endDate: string | null;
  subsistencePercent: number | null;
  releaseFrom: string | null;
  releaseTo: string | null;
  interveningPeriod: string | null;
  reason: string | null;
  source: string;
  hrmsRequestId: string | null;
  orderReference: string | null;
  taskId: number | null;
  taskNo: string | null;
  taskStatus: string | null;
}

/** M-11 Employee Transfer Register. */
export interface TransferRow {
  id: number;
  panMasked: string | null;
  eis: string;
  employee: string;
  oldStaffNo: string | null;
  newStaffNo: string | null;
  origin: string | null;
  destination: string | null;
  cpfMemberId: string | null;
  effectiveDate: string;
  taskId: number | null;
  taskNo: string | null;
  taskStatus: string | null;
  approvalStatus: string | null;
}

export interface TransferSummary {
  period: string;
  received: number;
  reissued: number;
  withChecker: number;
}

/** The I-21 tiles. */
export interface TaskSummary {
  pending: number;
  beforeFreeze: number;
  afterFreeze: number;
  withChecker: number;
  ageing: number;
  blocking: number;
  autoApplied: number;
}

/** One row of the HRMS Change Register tab. */
export interface RegisterRow {
  kind: 'TASK' | 'REQUEST';
  reference: string;
  received: string | null;
  eis: string;
  employee: string;
  field: string;
  oldValue: string | null;
  newValue: string | null;
  hrmsRequestId: string | null;
  effective: string | null;
  order: string | null;
  maker: string | null;
  checker: string | null;
  status: string;
  daysPending: number | null;
}

export interface FieldGroup {
  group: string;
  fields: string[];
  payEffects: string[];
}

export interface DobTask {
  id: number;
  taskNo: string;
  eis: string;
  employee: string;
  currentDob: string | null;
  newDob: string | null;
  newRetirement: string | null;
  retirementPast: boolean;
  retiresSoon: boolean;
  status: string;
  approvalId: number | null;
  rejectionReason: string | null;
}

export interface BulkOrder {
  id: number;
  orderRef: string;
  orderNo: string;
  orderDate: string;
  authority: string;
  subject: string | null;
  taskCount: number;
  status: string;
  approvalId: number | null;
  ready: number;
  held: number;
}

export interface OrderTask {
  id: number;
  taskNo: string;
  eis: string;
  employee: string;
  fromValue: string | null;
  toValue: string | null;
  status: string;
  fieldCode: string;
}

const eis = (value: string) => encodeURIComponent(value.trim());

function filterQuery(filter: EmployeeFilter & { office?: string }): string {
  const p = new URLSearchParams();
  (['q', 'payPattern', 'status', 'office'] as const).forEach((k) => {
    const v = filter[k];
    if (v) p.set(k, v);
  });
  return p.toString();
}

/** The reads the Employee screens draw beside their use cases (PENDING API-16-8). */
export const registerApi = {
  offices: () => api<Office[]>('/v1/employees/offices'),
  masterExportUrl: (filter: EmployeeFilter & { office?: string }) =>
    `${API_BASE}/v1/employees/export?${filterQuery(filter)}`,
  memberIds: (employee: string, page: number, size: number) =>
    api<PageResult<MemberIdRow>>(`/v1/employees/${eis(employee)}/member-ids?page=${page}&size=${size}`),
  staffNumbers: (employee: string, page: number, size: number) =>
    api<PageResult<StaffNumberRow>>(`/v1/employees/${eis(employee)}/staff-numbers?page=${page}&size=${size}`),
  extras: (employee: string) => api<RecordExtras>(`/v1/employees/${eis(employee)}/hrms-record`),
  orders: (employee: string) => api<StoredDocument[]>(`/v1/employees/${eis(employee)}/documents`),
  orderUrl: (employee: string, fileId: number) =>
    `${API_BASE}/v1/employees/${eis(employee)}/documents/${fileId}`,
  attachOrder: (employee: string, copy: TaskOrderCopy) => {
    const form = new FormData();
    form.append('documentType', copy.documentType);
    if (copy.reference) form.append('reference', copy.reference);
    form.append('orderDate', copy.orderDate);
    if (copy.issuingAuthority) form.append('issuingAuthority', copy.issuingAuthority);
    form.append('file', copy.file);
    return api<StoredDocument>(`/v1/employees/${eis(employee)}/documents`, { method: 'POST', body: form });
  },
  events: (page: number, size: number) =>
    api<PageResult<ReceivedEvent>>(`/v1/lifecycle-events/received?page=${page}&size=${size}`),
  eventsExportUrl: (report: boolean) => `${API_BASE}/v1/lifecycle-events/export?report=${report}`,
  transfers: (page: number, size: number) =>
    api<PageResult<TransferRow>>(`/v1/transfers/register?page=${page}&size=${size}`),
  transferSummary: () => api<TransferSummary>('/v1/transfers/summary'),
  transfersExportUrl: () => `${API_BASE}/v1/transfers/export`,
  taskSummary: () => api<TaskSummary>('/v1/hrms-change-tasks/summary'),
  register: (q: string, page: number, size: number) =>
    api<PageResult<RegisterRow>>(
      `/v1/hrms-change-tasks/register?${q ? `q=${encodeURIComponent(q)}&` : ''}page=${page}&size=${size}`,
    ),
  fieldGroups: () => api<FieldGroup[]>('/v1/hrms-change-tasks/field-groups'),
  dobTasks: (page: number, size: number) =>
    api<PageResult<DobTask>>(`/v1/hrms-change-tasks/dob-tasks?page=${page}&size=${size}`),
  /** No size asks for the configured default: a size not offered is refused (COM-400-PAGE, SRS-NF-3). */
  orderList: (page: number, size?: number) =>
    api<PageResult<BulkOrder>>(
      `/v1/hrms-change-orders?page=${page}${size === undefined ? '' : `&size=${size}`}`,
    ),
  orderTasks: (id: number, page: number, size: number) =>
    api<PageResult<OrderTask>>(`/v1/hrms-change-orders/${id}/tasks?page=${page}&size=${size}`),
};
