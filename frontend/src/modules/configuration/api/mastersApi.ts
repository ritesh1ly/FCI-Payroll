import { type PageResult } from '@/shared/ui';
import type { StoredOrder } from '@/shared/ui/orderCopy';
import { api, API_BASE } from '@/shared/api';
import type { OtpProof } from '@/modules/workflow';

/** The rate-master screens and their API paths (each with its own server-side permission gate). */
export type RateScreen = 'M-04' | 'M-22';
const RATE_PATH: Record<RateScreen, string> = { 'M-04': 'staff-rates', 'M-22': 'special-rates' };

/** One line of a rate set (rate_set_line, target DDL v4.0). */
export interface RateLine {
  designationId: number | null;
  categoryCode: string | null;
  payPattern: string | null;
  basicSlabFrom: number | null;
  basicSlabTo: number | null;
  cityClass: string | null;
  rateCode: string;
  rateValue: number;
  unit: string | null;
}

/** One dated version of a rate set. */
export interface RateVersion {
  id: number;
  versionNo: number;
  effectiveFrom: string;
  effectiveTo: string | null;
  state: string;
  approvalId: number | null;
  circularOrderId: number | null;
  lines: RateLine[];
}

/** A catalogue entry: its version in force (null = not seeded) and its pending proposal. */
export interface RateMaster {
  code: string;
  name: string;
  group: string | null;
  payCode: number | null;
  current: RateVersion | null;
  pending: RateVersion | null;
}

/** M-23 other parameter version (other_parameter). */
export interface ParameterVersion {
  id: number;
  versionNo: number;
  effectiveFrom: string;
  effectiveTo: string | null;
  numericValue: number | null;
  ceilingValue: number | null;
  percentValue: number | null;
  windowFromTime: string | null;
  windowToTime: string | null;
  instalmentCount: number | null;
  payrollTypeScope: string | null;
  confirmed: boolean;
  status: string;
  approvalId: number | null;
}

export interface ParameterRow {
  code: string;
  name: string;
  payCode: number | null;
  current: ParameterVersion | null;
  pending: ParameterVersion | null;
  /** The MHS row: received from HRMS, no value held in Payroll (the API refuses a version). */
  hrmsOwned?: boolean;
}

export interface ProposedParameter {
  effectiveFrom: string;
  numericValue: number | null;
  ceilingValue: number | null;
  percentValue: number | null;
  windowFromTime: string | null;
  windowToTime: string | null;
  instalmentCount: number | null;
  payrollTypeScope: string | null;
  confirmed: boolean;
  reason: string;
}

/** M-24 natural account of a pay code (gl_account_mapping). */
export interface MappingVersion {
  id: number;
  versionNo: number;
  effectiveFrom: string;
  effectiveTo: string | null;
  naturalAccount: string;
  status: string;
  approvalId: number | null;
}

export interface ComponentRow {
  payCode: number;
  name: string;
  type: string;
  payslipGroup: string | null;
  retired: boolean;
  current: MappingVersion | null;
  pending: MappingVersion | null;
}

/** One row of the M-04 Dearness Allowance table: a version of DA_IDA or DA_CDA. */
export interface DearnessRow {
  id: number;
  code: string;
  pattern: string;
  rate: number | null;
  computedOn: string | null;
  effectiveFrom: string;
  effectiveTo: string | null;
  versionNo: number;
  arrearReference: string | null;
  arrearAmount: number | null;
  status: 'ACTIVE' | 'SCHEDULED' | 'SUPERSEDED' | 'AWAITING_APPROVAL' | 'REJECTED';
}

/** One row of the M-04 HRA & CCA table: X, Y, Z, or DOUBLE for the drawn Double HRA row. */
export interface CityClassRow {
  cityClass: string;
  hraPercent: number | null;
  hraCdaPercent: number | null;
  ccaAmount: number | null;
  inForce: boolean;
  pending: boolean;
}

/** One of the seven M-24 mapping masters. */
export interface AccountingMaster {
  code: string;
  effectiveFrom: string | null;
  records: number;
  pending: boolean;
}

/** One record of an M-24 mapping master (read only). */
export interface AccountingRecord {
  key: string;
  name: string | null;
  detail: string | null;
  effectiveFrom: string | null;
  effectiveTo: string | null;
}

/** The M-22 filters. */
export interface RateFilter {
  pattern: string;
  group: string;
  q: string;
}

export interface OrderParticulars {
  documentType: string;
  reference: string;
  orderDate: string;
  issuingAuthority: string;
  file: File;
}

function rateQuery(f: RateFilter): string {
  const q = new URLSearchParams();
  if (f.pattern) q.set('pattern', f.pattern);
  if (f.group) q.set('group', f.group);
  if (f.q.trim()) q.set('q', f.q.trim());
  return q.toString();
}

/** The only place the configuration module talks to /api/v1/masters. */
export const mastersApi = {
  rates: (screen: RateScreen, page: number, size: number) =>
    api<PageResult<RateMaster>>(`/v1/masters/${RATE_PATH[screen]}?page=${page}&size=${size}`),
  rateHistory: (screen: RateScreen, code: string, page: number, size: number) =>
    api<PageResult<RateVersion>>(
      `/v1/masters/${RATE_PATH[screen]}/${encodeURIComponent(code)}/versions?page=${page}&size=${size}`,
    ),
  proposeRate: (
    screen: RateScreen,
    code: string,
    body: {
      effectiveFrom: string;
      lines: RateLine[];
      reason: string;
      circularOrderId?: number | null;
      computedOn?: string | null;
    },
    otp?: OtpProof,
  ) =>
    api<RateVersion>(`/v1/masters/${RATE_PATH[screen]}/${encodeURIComponent(code)}/versions`, {
      method: 'POST',
      body: { ...body, ...(otp ? { otp } : {}) },
    }),
  dearness: (page: number, size: number) =>
    api<PageResult<DearnessRow>>(`/v1/masters/staff-rates/dearness-versions?page=${page}&size=${size}`),
  cityClasses: (page: number, size: number) =>
    api<PageResult<CityClassRow>>(`/v1/masters/staff-rates/city-classes?page=${page}&size=${size}`),
  staffRateOrders: (id: number) => api<StoredOrder[]>(`/v1/masters/staff-rates/versions/${id}/orders`),
  attachStaffRateOrder: (id: number, order: OrderParticulars) => {
    const form = new FormData();
    form.append('documentType', order.documentType);
    if (order.reference) form.append('reference', order.reference);
    form.append('orderDate', order.orderDate);
    if (order.issuingAuthority) form.append('issuingAuthority', order.issuingAuthority);
    form.append('file', order.file);
    return api<StoredOrder>(`/v1/masters/staff-rates/versions/${id}/order`, { method: 'POST', body: form });
  },
  staffRateOrderUrl: (id: number, fileId: number) =>
    `${API_BASE}/v1/masters/staff-rates/versions/${id}/files/${fileId}`,
  documentTypes: () => api<{ code: string; referenceRequired: boolean }[]>('/v1/document-types'),
  specialRates: (filter: RateFilter, page: number, size: number) => {
    const q = rateQuery(filter);
    return api<PageResult<RateMaster>>(
      `/v1/masters/special-rates?${q ? `${q}&` : ''}page=${page}&size=${size}`,
    );
  },
  specialRatesExportUrl: (filter: RateFilter) => {
    const q = rateQuery(filter);
    return `${API_BASE}/v1/masters/special-rates/export${q ? `?${q}` : ''}`;
  },
  accounting: (page: number, size: number) =>
    api<PageResult<AccountingMaster>>(`/v1/masters/accounting?page=${page}&size=${size}`),
  accountingRecords: (code: string, page: number, size: number) =>
    api<PageResult<AccountingRecord>>(
      `/v1/masters/accounting/${encodeURIComponent(code)}/records?page=${page}&size=${size}`,
    ),
  parameters: (page: number, size: number) =>
    api<PageResult<ParameterRow>>(`/v1/masters/other-parameters?page=${page}&size=${size}`),
  parameterHistory: (code: string, page: number, size: number) =>
    api<PageResult<ParameterVersion>>(
      `/v1/masters/other-parameters/${encodeURIComponent(code)}/versions?page=${page}&size=${size}`,
    ),
  proposeParameter: (code: string, body: ProposedParameter, otp?: OtpProof) =>
    api<ParameterVersion>(`/v1/masters/other-parameters/${encodeURIComponent(code)}/versions`, {
      method: 'POST',
      body: { ...body, ...(otp ? { otp } : {}) },
    }),
  mappings: (page: number, size: number) =>
    api<PageResult<ComponentRow>>(`/v1/masters/gl-mappings?page=${page}&size=${size}`),
  mappingHistory: (payCode: number, page: number, size: number) =>
    api<PageResult<MappingVersion>>(`/v1/masters/gl-mappings/${payCode}/versions?page=${page}&size=${size}`),
  proposeMapping: (
    payCode: number,
    body: { effectiveFrom: string; naturalAccount: string; reason: string },
    otp?: OtpProof,
  ) =>
    api<MappingVersion>(`/v1/masters/gl-mappings/${payCode}/versions`, {
      method: 'POST',
      body: { ...body, ...(otp ? { otp } : {}) },
    }),
};
