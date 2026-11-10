import { type PageResult } from '@/shared/ui';
import { API_BASE, api } from '@/shared/api';
import type { OtpProof } from '@/modules/workflow';

/** The payroll types a master can be scoped to (DDL v4.0 circular_order_scope.payroll_type). */
export type PayrollType = 'STAFF' | 'DEPT_LABOUR' | 'DPS_LABOUR' | 'NWNP_LABOUR';

/** net_pay_floor_rule.floor_basis (DDL CHECK). */
export type FloorBasis = 'ABSOLUTE_AMOUNT' | 'PERCENT_OF_GROSS' | 'NONE';

// ---------------------------------------------------------------- M-14

export interface FloorVersion {
  id: number;
  effectiveFrom: string;
  effectiveTo: string | null;
  floorBasis: FloorBasis;
  floorValue: number | null;
  deductionCeilingPct: number | null;
  sourceInstrument: string | null;
  confirmed: boolean;
  status: string;
  approvalId: number | null;
}

export interface FloorRow {
  payrollType: PayrollType;
  current: FloorVersion | null;
  pending: FloorVersion | null;
}

export interface ProposedFloor {
  effectiveFrom: string;
  floorBasis: FloorBasis;
  floorValue: number | null;
  deductionCeilingPct: number | null;
  sourceInstrument: string | null;
  confirmed: boolean;
  reason: string;
}

export interface ExclusionRow {
  payCode: number;
  name: string;
  type: string;
  excluded: boolean;
}

export interface Exclusions {
  components: ExclusionRow[];
  pending: number[] | null;
  approvalId: number | null;
}

// ---------------------------------------------------------------- M-15

export interface NormVersion {
  id: number;
  versionNo: number;
  effectiveFrom: string;
  effectiveTo: string | null;
  ceilingBasis: string | null;
  ceilingValue: number;
  periodicity: string;
  eligibilityCategory: string;
  memberScope: string;
  confirmed: boolean;
  status: string;
  approvalId: number | null;
  /** SRS-G-38 (US-GAP-211): the M-25 circular the version is linked to. */
  circularOrderId: number | null;
}

export interface NormRow {
  claimType: string;
  name: string;
  current: NormVersion | null;
  pending: NormVersion | null;
}

/** The drawn tiles of M-15 (API-04-15). */
export interface NormCounts {
  claimTypes: number;
  percentageRules: number;
  pendingApproval: number;
}

export interface ProposedNorm {
  effectiveFrom: string;
  ceilingBasis: string | null;
  ceilingValue: number | null;
  periodicity: string;
  eligibilityCategory: string;
  memberScope: string;
  confirmed: boolean;
  reason: string;
  /** SRS-G-38 (US-GAP-211): the Active M-25 circular of the revision. */
  circularOrderId: number | null;
}

// ---------------------------------------------------------------- M-25

export interface CircularView {
  id: number;
  number: string;
  date: string;
  issuingAuthority: string;
  issuingLocationId: number | null;
  subject: string;
  category: string;
  appliesAllOffices: boolean;
  payrollTypes: PayrollType[];
  offices: { scopeType: string; payrollType: string | null; locationId: number; office: string | null }[];
  effectiveFrom: string | null;
  effectiveTo: string | null;
  supersedesId: number | null;
  supersedesNumber: string | null;
  documentId: number | null;
  status: string;
  statusReason: string | null;
  approvalId: number | null;
  createdBy: string;
  createdAt: string;
  changedAt: string | null;
  /** SRS M-25 field Where Used: how many items are linked (the list is where-used). */
  whereUsed: number;
}

export interface NewCircular {
  number: string;
  date: string;
  issuingAuthority: string;
  subject: string;
  category: string;
  payrollTypes: PayrollType[];
  appliesAllOffices: boolean;
  officeIds: number[];
  effectiveFrom: string;
  effectiveTo: string | null;
  supersedesId: number | null;
  reason: string;
}

export interface CircularQuery {
  text?: string;
  category?: string;
  status?: string;
  /** SRS-G-38: circulars dated from / to (YYYY-MM-DD, both inclusive). */
  from?: string;
  to?: string;
}

export interface OfficeOption {
  id: number;
  code: string;
  name: string;
  type: string;
}

export interface CircularUse {
  kind: string;
  entity: string;
  key: string;
  screen: string | null;
  status: string;
  linkedAt: string | null;
}

// ---------------------------------------------------------------- M-26

export interface LabelView {
  id: number;
  key: string;
  type: string;
  masterEntity: string | null;
  masterCode: string | null;
  language: string;
  scope: string;
  scopeLocationId: number | null;
  scopeOffice: string | null;
  text: string;
  localisable: boolean;
  versionNo: number;
  effectiveFrom: string;
  effectiveTo: string | null;
  status: string;
  approvalId: number | null;
  changedBy: string;
}

export interface ProposedLabel {
  key: string;
  type: string;
  masterEntity: string | null;
  masterCode: string | null;
  language: string;
  scope: string;
  text: string;
  localisable: boolean;
  effectiveFrom: string;
  reason: string;
}

export interface LabelQuery {
  text?: string;
  language?: string;
  scope?: string;
  status?: string;
}

// ---------------------------------------------------------------- M-27

export interface OperationVersion {
  id: number;
  versionNo: number;
  effectiveFrom: string;
  effectiveTo: string | null;
  name: string;
  departmental: boolean;
  dps: boolean;
  nwnp: boolean;
  bagBased: boolean;
  normType: string | null;
  rateNormLink: string;
  dusting: boolean;
  allowancePayCode: number | null;
  presentButNoWork: boolean;
  circularId: number | null;
  status: string;
  approvalId: number | null;
  /** SRS M-27 field Circular: the number of the governing circular. */
  circularNumber: string | null;
}

/** The M-27 list filter: text (code, name or label) and the user's language. */
export interface OperationQuery {
  text: string;
  lang: string;
}

export interface OperationRow {
  code: string;
  current: OperationVersion | null;
  pending: OperationVersion | null;
  /**
   * SRS-G-39 (NEW-G1-06): the M-26 name the user sees (own language, office, region, Global) where
   * it differs from the standard name; null otherwise. Older servers omit it.
   */
  shownName?: string | null;
}

export interface ProposedOperation {
  effectiveFrom: string;
  name: string;
  departmental: boolean;
  dps: boolean;
  nwnp: boolean;
  bagBased: boolean;
  normType: string | null;
  rateNormLink: string;
  dusting: boolean;
  allowancePayCode: number | null;
  presentButNoWork: boolean;
  circularId: number | null;
  reason: string;
}

function query(params: Record<string, string | undefined>): string {
  const q = new URLSearchParams();
  Object.entries(params).forEach(([k, v]) => {
    if (v) q.set(k, v);
  });
  const s = q.toString();
  return s ? `?${s}` : '';
}

const withOtp = <T extends object>(body: T, otp?: OtpProof) => ({ ...body, ...(otp ? { otp } : {}) });

/** The only place the Module 04 screens talk to /api/v1/masters (M-14, M-15, M-25, M-26, M-27). */
export const mdmApi = {
  floors: (page: number, size: number) =>
    api<PageResult<FloorRow>>(`/v1/masters/net-pay-floors?page=${page}&size=${size}`),
  floorHistory: (type: string, page: number, size: number) =>
    api<PageResult<FloorVersion>>(`/v1/masters/net-pay-floors/${type}/versions?page=${page}&size=${size}`),
  proposeFloor: (type: string, body: ProposedFloor, otp?: OtpProof) =>
    api<FloorVersion>(`/v1/masters/net-pay-floors/${type}/versions`, {
      method: 'POST',
      body: withOtp(body, otp),
    }),
  exclusions: () => api<Exclusions>('/v1/masters/supplementary-exclusions'),
  /** The drawn tile Min-Net Guard Triggered (I-06) (API-04-14). */
  floorGuardCount: () => api<number>('/v1/masters/net-pay-floors/guard-count'),
  proposeExclusions: (payCodes: number[], reason: string, otp?: OtpProof) =>
    api<Exclusions>('/v1/masters/supplementary-exclusions', {
      method: 'POST',
      body: withOtp({ payCodes, reason }, otp),
    }),

  normCounts: () => api<NormCounts>('/v1/masters/ceiling-norms/counts'),
  ceilingBreaches: () => api<number>('/v1/claims/ceiling-breaches'),
  norms: (page: number, size: number) =>
    api<PageResult<NormRow>>(`/v1/masters/ceiling-norms?page=${page}&size=${size}`),
  normHistory: (claimType: string, page: number, size: number) =>
    api<PageResult<NormVersion>>(
      `/v1/masters/ceiling-norms/${encodeURIComponent(claimType)}/versions?page=${page}&size=${size}`,
    ),
  proposeNorm: (claimType: string, body: ProposedNorm, otp?: OtpProof) =>
    api<NormVersion>(`/v1/masters/ceiling-norms/${encodeURIComponent(claimType)}/versions`, {
      method: 'POST',
      body: withOtp(body, otp),
    }),

  circulars: (q: CircularQuery, page: number, size: number) =>
    api<PageResult<CircularView>>(
      `/v1/masters/circulars${query({ text: q.text, category: q.category, status: q.status, from: q.from, to: q.to, page: String(page), size: String(size) })}`,
    ),
  /** The circulars in force today (a choice list, not paged: docs/plan/X-pagination.md exemptions). */
  currentCirculars: () => api<CircularView[]>('/v1/masters/circulars/current'),
  circularsHref: `${API_BASE}/v1/masters/circulars`,
  /** SRS M-25 action Export: the register as filtered. */
  circularsExportHref: (q: CircularQuery) =>
    `${API_BASE}/v1/masters/circulars/export${query({ text: q.text, category: q.category, status: q.status, from: q.from, to: q.to })}`,
  documentHref: (id: number) => `${API_BASE}/v1/masters/circulars/${id}/document`,
  circularOffices: () => api<OfficeOption[]>('/v1/masters/circulars/offices'),
  whereUsed: (id: number, page: number, size: number) =>
    api<PageResult<CircularUse>>(`/v1/masters/circulars/${id}/where-used?page=${page}&size=${size}`),
  recordCircular: (body: NewCircular, file: File, otp?: OtpProof) => {
    const form = new FormData();
    form.append('circular', new Blob([JSON.stringify(withOtp(body, otp))], { type: 'application/json' }));
    form.append('file', file);
    return api<CircularView>('/v1/masters/circulars', { method: 'POST', body: form });
  },
  withdrawCircular: (id: number, reason: string, otp?: OtpProof) =>
    api<CircularView>(`/v1/masters/circulars/${id}/withdraw`, {
      method: 'POST',
      body: withOtp({ reason }, otp),
    }),

  labels: (q: LabelQuery, page: number, size: number) =>
    api<PageResult<LabelView>>(
      `/v1/masters/labels${query({ text: q.text, language: q.language, scope: q.scope, status: q.status, page: String(page), size: String(size) })}`,
    ),
  missingHindi: (page: number, size: number) =>
    api<PageResult<LabelView>>(`/v1/masters/labels/missing-hindi?page=${page}&size=${size}`),
  labelsExportHref: (language?: string, scope?: string) =>
    `${API_BASE}/v1/masters/labels/export${query({ language, scope })}`,
  proposeLabel: (body: ProposedLabel, otp?: OtpProof) =>
    api<LabelView>('/v1/masters/labels', { method: 'POST', body: withOtp(body, otp) }),
  withdrawLabel: (id: number, reason: string, otp?: OtpProof) =>
    api<LabelView>(`/v1/masters/labels/${id}/withdraw`, { method: 'POST', body: withOtp({ reason }, otp) }),
  uploadLabels: (file: File, reason: string) => {
    const form = new FormData();
    form.append('file', file);
    return api<LabelView[]>(`/v1/masters/labels/upload${query({ reason })}`, { method: 'POST', body: form });
  },

  /** SRS-G-39 (NEW-G1-05): a search by code, standard name or any M-26 label; names in `lang`. */
  operations: (q: OperationQuery, page: number, size: number) =>
    api<PageResult<OperationRow>>(
      `/v1/masters/operations${query({ q: q.text, lang: q.lang, page: String(page), size: String(size) })}`,
    ),
  /** SRS M-27 action Export: the master as filtered. */
  operationsExportHref: (q: OperationQuery) =>
    `${API_BASE}/v1/masters/operations/export${query({ q: q.text, lang: q.lang })}`,
  operationHistory: (code: string, page: number, size: number) =>
    api<PageResult<OperationVersion>>(
      `/v1/masters/operations/${encodeURIComponent(code)}/versions?page=${page}&size=${size}`,
    ),
  proposeOperation: (code: string, body: ProposedOperation, otp?: OtpProof) =>
    api<OperationVersion>(`/v1/masters/operations/${encodeURIComponent(code)}/versions`, {
      method: 'POST',
      body: withOtp(body, otp),
    }),
  endDateOperation: (code: string, lastDay: string, reason: string, otp?: OtpProof) =>
    api<OperationRow>(`/v1/masters/operations/${encodeURIComponent(code)}/end-date`, {
      method: 'POST',
      body: withOtp({ lastDay, reason }, otp),
    }),
};
