import type { StoredOrder } from '@/shared/ui/orderCopy';
import { type PageResult } from '@/shared/ui';
import { API_BASE, api } from '@/shared/api';

/** One member of the Increment Due list with the proposal (plan 17 phase 17c). */
export interface DueRow {
  employeeId: number;
  eis: string;
  name: string;
  office: string;
  dueDate: string;
  oldBasic: number | null;
  revisedBasic: number | null;
  /**
   * BARRED, ABOVE_MAXIMUM, NOT_A_STAGE, STAGES_NOT_CONFIGURED, NOT_CONFIGURED, NO_SCALE; STAGNATION
   * (at the maximum and eligible: informative).
   */
  flags: string[];
  barredBy: string[];
  pending: boolean;
  /** ANNUAL, or STAGNATION when the stagnation increment is proposed (BR-INCR-STAG-007). */
  incrementType: 'ANNUAL' | 'STAGNATION';
  /**
   * A member at the scale maximum: ELIGIBLE, NOT_CONFIGURED, NOT_AT_MAXIMUM, NOT_QUALIFIED, BARRED,
   * AT_CAP; null below the maximum.
   */
  stagnation: string | null;
  /** The test that fired, when eligible (BR-INCR-STAG-001). */
  stagnationTest: string | null;
  /** I-05 l.4001: the member's pay scale code (HRMS); null without a scale in force. */
  payScaleCode: string | null;
  /** I-05 l.4002: the next increment due date once this increment is applied. */
  nextDueDate: string;
}

export interface RunOutcome {
  approvalIds: number[];
  refused: { eis: string; reason: string }[];
}

export type CaseType =
  | 'PROMOTION_FIXATION'
  | 'INDIVIDUAL_REVISION'
  | 'ADDITIONAL_INCREMENT'
  | 'STAGNATION_INCREMENT'
  | 'BASIC_CORRECTION'
  | 'DATE_CORRECTION'
  | 'REVERSE_ENTRY'
  | 'OBSERVATION_REFIXATION';

export interface CaseRequest {
  eis: string;
  caseType: CaseType;
  effectiveDate: string;
  basic?: number;
  correctedDueDate?: string;
  orderNumber: string;
  orderDate: string;
  issuingAuthority: string;
  reason: string;
  /** SRS-13-18: the order copy stored first (storeOrder). */
  orderAttachmentId?: number;
  orderProvisionalKey?: string;
  /** SRS-13-27: an observation refixation names its observation (Audit / Accounts) and reference. */
  observationSource?: 'AUDIT' | 'ACCOUNTS';
  observationReference?: string;
}

/** SRS-13-18 / SRS-G-30: the order copy of a case. */
export interface CaseOrderCopy {
  eis: string;
  documentType: string;
  reference: string;
  orderDate: string;
  issuingAuthority: string;
  file: File;
}

export interface Proposal {
  employeeId: number;
  eis: string;
  caseType: CaseType;
  effectiveDate: string;
  oldBasic: number | null;
  revisedBasic: number | null;
  oldDueDate: string | null;
  correctedDueDate: string | null;
  test: string | null;
  /** SRS-13-24: the Basic history entry a reversal undoes. */
  reversedEntry?: number | null;
}

export interface HistoryRow {
  effectiveFrom: string;
  sequence: number;
  effectiveTo: string | null;
  event: string;
  basic: number;
  oldBasic: number | null;
  source: string;
  reason: string;
  order: string | null;
  /** The Checker who approved the event (or the system / migration). */
  by: string;
  /** SRS-13-26 (US-GAP-55): the Maker who submitted it; null for an event without an approval. */
  maker: string | null;
  /** SRS-13-24: the old entry of an approved correction, kept and marked as corrected. */
  corrected?: boolean;
}

/** One pay scale with the version in force (M-03). */
export interface ScaleRow {
  bandId: number;
  code: string;
  name: string;
  scaleType: string | null;
  min: number | null;
  max: number | null;
  stagnationAmount: number | null;
  effectiveFrom: string | null;
  stages: number;
  pending: boolean;
  /** SC-17-0030: a scale defined on M-03 is active once its first version is approved. */
  active: boolean;
  /** The band's increment rule; the value is a rule-engine setting. */
  incrementRuleKey?: string | null;
}

/** The drawn Grade to Designation to Scale Mapping (API-17-3). */
export interface MappingRow {
  gradeCode: string | null;
  gradeName: string | null;
  designationCode: string;
  designationName: string;
  scaleCode: string | null;
  headcount: number;
}

/** The drawn I-05 tiles and rule figures (API-17-2). */
export interface WorklistCounts {
  due: number;
  annual: number;
  stagnation: number;
  fixations: number;
  retrospective: number;
  latestArrearEvent: number | null;
  atMaximum: number;
  duplicates: number;
}

/** One line of the drawn Increment / Fixation Worklist (API-17-2). */
export interface WorklistRow {
  kind: 'DUE' | 'APPLIED' | 'FIXATION' | 'HRMS_TASK' | 'CASE';
  key: string;
  employeeId: number;
  eis: string;
  employee: string;
  scale: string | null;
  type: string | null;
  presentBasic: number | null;
  incrementPct: number | null;
  revisedBasic: number | null;
  effective: string;
  atMaximum: boolean;
  arrearEvent: number | null;
  arrearAmount: number | null;
  status: string;
  flags: string[];
  barredBy: string[];
  orderReference: string | null;
  approvalId: number | null;
}

export interface VersionRequest {
  min: number;
  max: number;
  stagnationAmount?: number;
  effectiveFrom: string;
  circularId: number;
  stages: number[];
  reason: string;
  /** SRS M-03 Wage Revision: the I-25 revision code the scale belongs to (mandatory). */
  wageRevision: string;
}

/** SC-17-0030 (M-03 Scale Code / Type): a new scale with its first version. */
export interface NewScaleRequest {
  code: string;
  name: string;
  scaleType: 'IDA' | 'CDA';
  version: VersionRequest;
}

/** A staff wage revision a scale version may belong to (the M-03 lookup). */
export interface WageRevisionOption {
  code: string;
  name: string;
  payrollTypes: string;
  status: string;
}

export interface Proposed {
  approvalId: number;
  status: string;
  aboveMaximum: string[];
}

export interface Order {
  id: number;
  number: string;
  subject: string | null;
}

export interface Submitted {
  approvalId: number;
  status: string;
}

/** The only place the increment module talks to its endpoints. */
export const incrementApi = {
  due: (month: string, page: number, size: number) =>
    api<PageResult<DueRow>>(`/v1/increments/due?month=${month}&page=${page}&size=${size}`),
  submitRun: (employeeIds: number[], remark: string) =>
    api<RunOutcome>('/v1/increments/submissions', { method: 'POST', body: { employeeIds, remark } }),
  trackingUrl: (month: string) => `${API_BASE}/v1/increments/tracking?month=${month}`,
  preview: (c: CaseRequest) => api<Proposal>('/v1/pay-fixation-cases/previews', { method: 'POST', body: c }),
  submitCase: (c: CaseRequest) => api<Submitted>('/v1/pay-fixation-cases', { method: 'POST', body: c }),
  storeOrder: (copy: CaseOrderCopy) => {
    const form = new FormData();
    form.append('eis', copy.eis);
    form.append('documentType', copy.documentType);
    if (copy.reference) form.append('reference', copy.reference);
    form.append('orderDate', copy.orderDate);
    form.append('issuingAuthority', copy.issuingAuthority);
    form.append('file', copy.file);
    return api<{ attachmentId: number; provisionalKey: string }>('/v1/pay-fixation-cases/orders', {
      method: 'POST',
      body: form,
    });
  },
  history: (eis: string) => api<HistoryRow[]>(`/v1/basic-history?eis=${encodeURIComponent(eis)}`),
  historyUrl: (eis: string) => `${API_BASE}/v1/basic-history/report?eis=${encodeURIComponent(eis)}`,
  counts: (month: string) => api<WorklistCounts>(`/v1/increments/counts?month=${month}`),
  worklist: (month: string, page: number, size: number) =>
    api<PageResult<WorklistRow>>(`/v1/increments/worklist?month=${month}&page=${page}&size=${size}`),
  caseOrders: (approvalId: number) => api<StoredOrder[]>(`/v1/pay-fixation-cases/${approvalId}/orders`),
  caseOrderUrl: (approvalId: number, fileId: number) =>
    `${API_BASE}/v1/pay-fixation-cases/${approvalId}/files/${fileId}`,
  scaleOrders: (code: string) => api<StoredOrder[]>(`/v1/pay-scales/${encodeURIComponent(code)}/orders`),
  attachScaleOrder: (code: string, order: FormData) =>
    api<StoredOrder>(`/v1/pay-scales/${encodeURIComponent(code)}/orders`, { method: 'POST', body: order }),
  scaleOrderUrl: (code: string, fileId: number) =>
    `${API_BASE}/v1/pay-scales/${encodeURIComponent(code)}/files/${fileId}`,
  mapping: (page: number, size: number) =>
    api<PageResult<MappingRow>>(`/v1/pay-scales/mapping?page=${page}&size=${size}`),
  scales: (page: number, size: number) =>
    api<PageResult<ScaleRow>>(`/v1/pay-scales?page=${page}&size=${size}`),
  stages: (code: string) => api<number[]>(`/v1/pay-scales/${encodeURIComponent(code)}/stages`),
  /** SRS l.3952: the versions of a scale, newest first. */
  versions: (code: string) => api<ScaleVersion[]>(`/v1/pay-scales/${encodeURIComponent(code)}/versions`),
  propose: (code: string, v: VersionRequest) =>
    api<Proposed>(`/v1/pay-scales/${encodeURIComponent(code)}/versions`, { method: 'POST', body: v }),
  define: (s: NewScaleRequest) => api<Proposed>('/v1/pay-scales', { method: 'POST', body: s }),
  orders: () => api<Order[]>('/v1/masters/circulars/current'),
  wageRevisions: () => api<WageRevisionOption[]>('/v1/pay-scales/wage-revisions'),
};

/** One version of a pay scale (M-03 l.3952): an open Effective To is the version in force or later. */
export interface ScaleVersion {
  versionNo: number;
  min: number;
  max: number;
  stagnationAmount: number | null;
  effectiveFrom: string;
  effectiveTo: string | null;
}
