import { api } from '@/shared/api';
import type {
  Halt,
  Control,
  Decision,
  Job,
  JobCommand,
  JobDetail,
  JobFilter,
  JobTypes,
  Page,
  SkippedItem,
} from './types';

/**
 * C-13 Batch Jobs: the only place the module talks to /api/v1/jobs. The backend is authoritative
 * for every rule (permission, data scope, state machine, restart window).
 */

/** Query string of the history list; empty filters are left out. */
export function jobQuery(filter: JobFilter): string {
  const params = new URLSearchParams();
  if (filter.status) params.set('status', filter.status);
  if (filter.jobType) params.set('jobType', filter.jobType);
  if (filter.from) params.set('from', filter.from);
  if (filter.to) params.set('to', filter.to);
  if (filter.requestedBy?.trim()) params.set('requestedBy', filter.requestedBy.trim());
  params.set('page', String(filter.page));
  params.set('size', String(filter.size));
  params.set('sort', filter.sort);
  params.set('direction', filter.direction);
  return params.toString();
}

export const jobsApi = {
  types: () => api<JobTypes>('/v1/jobs/types'),
  list: (filter: JobFilter) => api<Page<Job>>(`/v1/jobs?${jobQuery(filter)}`),
  detail: (id: number) => api<JobDetail>(`/v1/jobs/${id}`),
  skipped: (id: number, page: number, size: number) =>
    api<Page<SkippedItem>>(`/v1/jobs/${id}/skipped?page=${page}&size=${size}`),
  /** Maker: the job goes to a Payroll Checker first (AWAITING_APPROVAL). */
  submit: (jobType: string, params: Record<string, string>, priority: number, reason: string) =>
    api<Job>('/v1/jobs', { method: 'POST', body: { jobType, params, priority, reason } }),
  /** Checker: approve (the job enters the batch queue) or reject / return it. */
  decide: (
    approvalId: number,
    decision: Decision,
    reason: string,
    otp?: { challengeId: number; code: string },
  ) =>
    api(`/v1/approvals/${approvalId}/decision`, {
      method: 'POST',
      body: { decision, ...(reason ? { reason } : {}), ...(otp ? { otp } : {}) },
    }),
  control: (id: number, command: JobCommand, reason: string) =>
    api<Control>(`/v1/jobs/${id}/${command.toLowerCase()}`, { method: 'POST', body: { reason } }),
  /** Plan 05j: asks for the job to be abandoned for good; a Payroll Checker decides in W-01. */
  abandon: (id: number, reason: string) =>
    api<Job>(`/v1/jobs/${id}/abandon`, { method: 'POST', body: { reason } }),
  /** Plan 05j: the kill switch, read by every C-13 viewer. */
  halt: () => api<Halt>('/v1/jobs/halt'),
  /** System Administrator at HQ: halts all batch processing at once. */
  engageHalt: (reason: string) => api<Halt>('/v1/jobs/halt', { method: 'POST', body: { reason } }),
  /** System Administrator at HQ: asks for the release; a Payroll Checker at HQ decides. */
  requestRelease: (reason: string) =>
    api<Halt>('/v1/jobs/halt/release', { method: 'POST', body: { reason } }),
};
