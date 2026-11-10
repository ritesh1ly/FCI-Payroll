import { api } from '@/shared/api';
import type { OtpProof } from '@/modules/workflow';

/** A change of a reference value awaiting the checker (M-16). */
export interface PendingChange {
  approvalId: number;
  op: 'CREATE' | 'EDIT';
  code: string;
  labelEn: string;
  labelHi: string | null;
  sortOrder: number | null;
  effectiveFrom: string | null;
  effectiveTo: string | null;
  submittedBy: string;
  submittedAt: string;
}

/** One value of a reference list as M-16 shows it (all values, in force or not). */
export interface ValueRow {
  code: string;
  labelEn: string;
  labelHi: string | null;
  sortOrder: number | null;
  effectiveFrom: string;
  effectiveTo: string | null;
  active: boolean;
  versionNo: number;
  changedBy: string;
  changedAt: string | null;
  pending: PendingChange | null;
}

export interface ListRow {
  code: string;
  name: string;
  module: string | null;
  system: boolean;
  values: ValueRow[];
  pendingNew: PendingChange[];
}

export interface NewValue {
  code: string;
  labelEn: string;
  labelHi: string | null;
  sortOrder: number | null;
  effectiveFrom: string;
  effectiveTo: string | null;
  reason: string;
}

export interface ValueChange {
  labelEn: string;
  labelHi: string | null;
  sortOrder: number | null;
  effectiveTo: string | null;
  reason: string;
}

/** The only place M-16 talks to /api/v1/masters/reference-lists. */
export const referenceListsApi = {
  lists: () => api<ListRow[]>('/v1/masters/reference-lists'),
  proposeNew: (list: string, body: NewValue, otp?: OtpProof) =>
    api<PendingChange>(`/v1/masters/reference-lists/${encodeURIComponent(list)}/values`, {
      method: 'POST',
      body: { ...body, ...(otp ? { otp } : {}) },
    }),
  proposeChange: (list: string, code: string, body: ValueChange, otp?: OtpProof) =>
    api<PendingChange>(
      `/v1/masters/reference-lists/${encodeURIComponent(list)}/values/${encodeURIComponent(code)}`,
      { method: 'POST', body: { ...body, ...(otp ? { otp } : {}) } },
    ),
};
