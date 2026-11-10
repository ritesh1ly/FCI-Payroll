import { api } from '@/shared/api';
import { type PageResult } from '@/shared/ui';
import type { GangRole, PayrollType } from './labourApi';

/** The rate in force for a gang role (M-05); a value not configured is null. */
export interface EngineRate {
  role: GangRole | null;
  mgw: number | null;
  interimRelief: number | null;
  goiRate: number | null;
  basicWage: number | null;
  vda: number | null;
}

/** The top of C-03 / C-04 / C-05; a figure no run stored is null. */
export interface ComputationSummary {
  period: string;
  population: number;
  depots: number;
  gangs: number;
  computed: number;
  notReady: number;
  wage: number | null;
  rates: EngineRate[];
}

/** A row of a gang-wise table: the inputs of a run until a run stores its figures. */
export interface GangResult {
  gangId: number;
  gang: string;
  gangName: string;
  depot: string;
  labour: number;
  sardar: number;
  mandal: number;
  handling: number;
  ancillary: number;
  presentDays: number | null;
  presentLabour: number;
  slips: number;
  bags: number | null;
  noWorkHours: number | null;
  mln: number | null;
  incentive: number | null;
  status: string;
}

/** The tiles of C-06. */
export interface IncentiveSummary {
  period: string;
  depots: number;
  depotsWithNorms: number;
  gangsComputed: number;
  recomputations: number;
  value: number | null;
}

/** A whole-gang re-computation (DDL labour_incentive_recompute). */
export interface Recomputation {
  id: number;
  gang: string;
  period: string;
  reason: string;
  original: number | null;
  revised: number | null;
}

/** Incentive Basic beside Salary Basic (DDL labour_incentive_basic). */
export interface IncentiveBasic {
  id: number;
  labourCode: string;
  name: string;
  salaryBasic: number | null;
  incentiveBasic: number;
  effectiveFrom: string;
}

/** The path segment of each engine (LabourComputationController). */
const ENGINE_PATH: Record<PayrollType, string> = {
  DEPT_LABOUR: 'departmental',
  DPS_LABOUR: 'dps',
  NWNP_LABOUR: 'nwnp',
};

/** API-20-9 in PENDING: C-03 to C-06 read side. */
export const computationApi = {
  summary: (engine: PayrollType, period: string) =>
    api<ComputationSummary>(`/v1/labour/computations/${ENGINE_PATH[engine]}/summary?period=${period}`),
  gangs: (engine: PayrollType, period: string, page: number, size: number) =>
    api<PageResult<GangResult>>(
      `/v1/labour/computations/${ENGINE_PATH[engine]}/gangs?period=${period}&page=${page}&size=${size}`,
    ),
  incentiveSummary: (period: string) =>
    api<IncentiveSummary>(`/v1/labour/incentives/summary?period=${period}`),
  incentiveGangs: (period: string, page: number, size: number) =>
    api<PageResult<GangResult>>(`/v1/labour/incentives/gangs?period=${period}&page=${page}&size=${size}`),
  recomputations: (page: number, size: number) =>
    api<PageResult<Recomputation>>(`/v1/labour/incentives/recomputations?page=${page}&size=${size}`),
  basics: (page: number, size: number) =>
    api<PageResult<IncentiveBasic>>(`/v1/labour/incentives/basics?page=${page}&size=${size}`),
};
