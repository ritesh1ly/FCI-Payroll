import { type PageResult } from '@/shared/ui';
import { api } from '@/shared/api';

/** O-02: a suspension of payment release (disb_release_suspension). */
export interface Suspension {
  id: number;
  scope: 'ALL_UNITS' | 'UNIT';
  unitCode: string | null;
  channel: 'ALL' | 'BANK_FILE' | 'EBS_AP_POSTING' | 'APPS_REMITTANCE';
  suspendedBy: string;
  suspendedAt: string;
  reason: string;
  resumedBy: string | null;
  resumedAt: string | null;
  resumeReason: string | null;
  pendingApprovalId: number | null;
}

export interface SuspendInput {
  scope: Suspension['scope'];
  unitCode?: string;
  channel: Suspension['channel'];
  reason: string;
}

/** The only place the jobs module talks to /api/v1/release-suspensions. */
export const suspensionApi = {
  list: (page: number, size: number) =>
    api<PageResult<Suspension>>(`/v1/release-suspensions?page=${page}&size=${size}`),
  suspend: (body: SuspendInput) => api<{ id: number }>('/v1/release-suspensions', { method: 'POST', body }),
  resume: (id: number, reason: string) =>
    api<{ id: number }>(`/v1/release-suspensions/${id}/resumptions`, { method: 'POST', body: { reason } }),
};
