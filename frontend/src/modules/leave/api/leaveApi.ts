import { type PageResult } from '@/shared/ui';
import { API_BASE, api } from '@/shared/api';

/** One leave type of M-13 as received from HRMS, with the pay effect M-20 holds for it today. */
export interface LeaveTypeRow {
  code: string;
  name: string;
  encashable: boolean;
  halfDay: boolean;
  /** RULE: a DDL wage effect, or null when M-20 holds none (the event is then held, never paid as zero). */
  payEffect: string | null;
  lastReceived: string | null;
}

export interface LeaveTypeCounts {
  received: number;
  encashable: number;
  payReducing: number;
  lastSync: string | null;
}

/** The status I-11 shows for a leave event (SRS I-11 field table). */
export type LeaveEventStatus = 'PENDING' | 'APPLIED' | 'BACK_DATED' | 'REJECTED';

/** One leave event received from HRMS (I-11, view only). */
export interface LeaveEventRow {
  applicationId: number;
  sanctionRef: string;
  eis: string;
  name: string;
  /** "code - name" of the member's office (UI-DESC). */
  office: string | null;
  leaveType: string;
  leaveTypeName: string | null;
  from: string;
  to: string;
  days: number;
  eventType: 'ADDITION' | 'CANCELLATION';
  received: string | null;
  payEffect: string | null;
  status: LeaveEventStatus;
  /** SRS I-11 "Applied in": the cycle (YYYY-MM) the effect applies in; null until applied. */
  appliedIn: string | null;
  /** The rule the pay effect applies (BR-LEAVE-007 or BR-LEAVE-010); null if none. */
  rule: string | null;
  /** US-GAP-59: a back-dated event's arrear (positive) or recovery (negative); null otherwise. */
  effectAmount?: number | null;
}

/** The Leave Recovery Report's filters (SRS-LV-23, FRS V0.5 §7.15.7.2); months are YYYYMM. */
export interface LeaveRecoveryFilter {
  fromMonth: string;
  toMonth: string;
  officeId?: number;
  eis?: string;
  leaveType?: string;
  eventType?: string;
  status?: string;
}

export interface LeaveEventCounts {
  received: number;
  applied: number;
  backDated: number;
  held: number;
}

export interface LeaveEventFilter {
  status?: LeaveEventStatus;
  eis?: string;
  leaveType?: string;
}

/** One M-20 version (Status: Pending approval / Active / Superseded / Returned / Rejected). */
export interface LeaveParameterVersion {
  id: number;
  code: string;
  leaveType: string | null;
  number: number | null;
  text: string | null;
  versionNo: number;
  effectiveFrom: string;
  effectiveTo: string | null;
  status: string;
  approvalId: number | null;
  /** The M-25 circular linked once the version was approved, if an order applies. */
  circularNumber: string | null;
}

/** An Active M-25 circular a version may name (SRS M-20, SRS-G-38). */
export interface LeaveCircular {
  id: number;
  number: string;
  subject: string | null;
}

export interface NewLeaveParameterVersion {
  code: string;
  leaveType: string | null;
  number: number | null;
  text: string | null;
  effectiveFrom: string;
  reason: string;
  /** Where an order applies: the Active M-25 circular (optional). */
  circularId: number | null;
}

function paged(base: string, page: number, size: number, extra?: URLSearchParams): string {
  const p = extra ?? new URLSearchParams();
  p.set('page', String(page));
  p.set('size', String(size));
  return `${base}?${p.toString()}`;
}

/** The only place the leave module talks to /api/v1/leave-*. */
/** One leave type's latest balance from HRMS (E-01, SRS-LV-32). */
export interface LeaveBalance {
  leaveType: string;
  leaveTypeName: string;
  days: number;
  asOf: string;
}

export const leaveApi = {
  types: (page: number, size: number) => api<PageResult<LeaveTypeRow>>(paged('/v1/leave-types', page, size)),
  typeCounts: () => api<LeaveTypeCounts>('/v1/leave-types/counts'),
  events: (filter: LeaveEventFilter, page: number, size: number) => {
    const p = new URLSearchParams();
    if (filter.status) p.set('status', filter.status);
    if (filter.eis) p.set('eis', filter.eis);
    if (filter.leaveType) p.set('leaveType', filter.leaveType);
    return api<PageResult<LeaveEventRow>>(paged('/v1/leave-events', page, size, p));
  },
  eventCounts: () => api<LeaveEventCounts>('/v1/leave-events/counts'),
  /** The Active M-25 circulars (a choice list, not paged). */
  circulars: () => api<LeaveCircular[]>('/v1/masters/circulars/current'),
  /** M-13 Export (a download link through the browser's session). */
  typesExportUrl: () => `${API_BASE}/v1/leave-types/export`,
  /** I-11 Export with the filters on screen. */
  eventsExportUrl: (filter: LeaveEventFilter) => {
    const p = new URLSearchParams();
    if (filter.status) p.set('status', filter.status);
    if (filter.eis) p.set('eis', filter.eis);
    if (filter.leaveType) p.set('leaveType', filter.leaveType);
    const q = p.toString();
    return `${API_BASE}/v1/leave-events/export${q ? `?${q}` : ''}`;
  },
  /** SRS-LV-23: the Leave Recovery Report with its filters. */
  recoveryReportUrl: (filter: LeaveRecoveryFilter) => {
    const p = new URLSearchParams();
    for (const [k, v] of Object.entries(filter)) if (v !== undefined && v !== '') p.set(k, String(v));
    return `${API_BASE}/v1/leave-events/recovery-report?${p.toString()}`;
  },
  versions: (code: string | undefined, page: number, size: number) => {
    const p = new URLSearchParams();
    if (code) p.set('code', code);
    return api<PageResult<LeaveParameterVersion>>(paged('/v1/leave-pay-parameters', page, size, p));
  },
  propose: (body: NewLeaveParameterVersion) =>
    api<LeaveParameterVersion>('/v1/leave-pay-parameters', { method: 'POST', body }),
  /** E-01 (SRS-LV-32): the member's latest balance per leave type, from HRMS. */
  balancesMine: () => api<LeaveBalance[]>('/v1/leave-balances/mine'),
};
