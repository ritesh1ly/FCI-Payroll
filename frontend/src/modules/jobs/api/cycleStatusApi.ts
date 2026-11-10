import { type PageResult } from '@/shared/ui';
import { api } from '@/shared/api';

/** A figure, or the reason it has none yet (a reason code, e.g. AWAITING_FCI). */
export interface Figure {
  count: number | null;
  unavailable: string | null;
}

/** D-01 Home - Cycle Status of the user's scope. */
export interface CycleStatus {
  period: string;
  scopeCode: string;
  scopeLabel: string;
  rollUp: boolean;
  units: number;
  byStatus: Record<string, number>;
  kpis: {
    payable: number;
    gross: number;
    deductions: number;
    net: number;
    exceptions: Figure;
    pendingApproval: Figure;
    retroEvents: Figure;
    interfaceHealth: Figure;
  };
  types: {
    payrollType: string;
    population: number | null;
    computed: number | null;
    exceptions: number | null;
    status: string | null;
    unavailable: string | null;
  }[];
  blockers: { code: string; figure: Figure; fixOn: string | null }[];
  /** The control items of the wireframe's D-01 (OD-121); absent until their modules report them (PENDING D01-1). */
  fallbackUploads?: Figure;
  gangShortfalls?: Figure;
}

export interface CycleUnit {
  unitCode: string;
  unitLabel: string;
  level: string;
  status: string;
  members: number | null;
  net: number | null;
  ownUnit: boolean;
}

/** The only place the jobs module talks to /api/v1/cycle-status. */
export const cycleStatusApi = {
  status: () => api<CycleStatus>('/v1/cycle-status'),
  units: (page: number, size: number) =>
    api<PageResult<CycleUnit>>(`/v1/cycle-status/units?page=${page}&size=${size}`),
};
