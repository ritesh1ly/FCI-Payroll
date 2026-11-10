import { api } from '@/shared/api';

/** M-10: one period of the payroll calendar (payroll_calendar, target DDL v4.0); dates as YYYY-MM-DD. */
export interface CalendarPeriod {
  month: string;
  start: string;
  end: string;
  freezeTarget: string;
  computeBy: string | null;
  verifyBy: string | null;
  lockTarget: string;
  disbursement: string | null;
  supplementaryFrom: string;
  supplementaryTo: string;
  mdpFrom: string;
  mdpTo: string;
}

export interface PeriodView {
  period: CalendarPeriod;
  /** A cycle was initiated for it at some office: its dates are fixed (plan 05 D-12). */
  initiated: boolean;
  /** The state of the cycle at the session's unit (null: not initiated there; SRS-15-12). */
  cycleStatus: string | null;
  /** The supplementary or MDP window of that instance is open (a flag beside the status). */
  windowOpen: boolean;
}

export interface CalendarYear {
  finYear: string;
  periods: PeriodView[];
  pendingApprovalId: number | null;
}

/** SRS-15-1: a step of the Engine Run Order (cycle_run_order). */
export interface RunStep {
  sequence: number;
  code: string;
  name: string;
  dependsOn: string | null;
  ownerRole: string | null;
  payrollType: string | null;
  /** The engine state of the session's unit in the current period (SRS l.1637); null: none yet. */
  status: string | null;
}

export interface RunOrder {
  steps: RunStep[];
  pendingApprovalId: number | null;
}

/** The only place the jobs module talks to /api/v1/payroll-calendars and /api/v1/run-order-steps. */
/** M-10 Cycle Parameters panel (read-only; each value kept on its own screen). */
export interface CycleParameters {
  authorities: {
    action: string;
    level: number;
    levelName: string;
    role: string;
    office: string;
    otp: boolean;
  }[];
  mdpHeads: { payCode: string; name: string }[];
  backDateWindowMonths: number | null;
  staffNetPayFloor: { basis: string; value: number; confirmed: boolean } | null;
  checks: {
    code: string;
    name: string;
    stage: 'PRE_INITIATION' | 'PRE_LOCK';
    appliesTo: string;
    owner: string | null;
    severity: 'BLOCK' | 'WARN';
    active: boolean;
    correctionScreen: string | null;
  }[];
}

export const calendarApi = {
  cycleParameters: () => api<CycleParameters>('/v1/cycle-parameters'),
  years: () => api<string[]>('/v1/payroll-calendars'),
  year: (finYear: string) => api<CalendarYear>(`/v1/payroll-calendars/${encodeURIComponent(finYear)}`),
  propose: (finYear: string, periods: CalendarPeriod[], reason: string) =>
    api<{ approvalId: number }>(`/v1/payroll-calendars/${encodeURIComponent(finYear)}/proposals`, {
      method: 'POST',
      body: { periods, reason },
    }),
  runOrder: () => api<RunOrder>('/v1/run-order-steps'),
  proposeOrder: (order: string[], reason: string) =>
    api<{ approvalId: number }>('/v1/run-order-steps/proposals', { method: 'POST', body: { order, reason } }),
};
