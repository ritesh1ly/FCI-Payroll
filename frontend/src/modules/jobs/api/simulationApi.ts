import { type PageResult } from '@/shared/ui';
import { api, API_BASE } from '@/shared/api';

export type SimPopulation = 'OFFICE' | 'MEMBER' | 'MEMBER_LIST';
export type SimWhatIf = 'AS_IS' | 'DA_RATE';

/** C-09 tiles of the current cycle; the two limits are null while not configured on A-07. */
export interface SimulationTiles {
  period: string;
  unitCode: string;
  unitLabel: string;
  maxSelection: number | null;
  interactiveLimit: number | null;
  runsThisCycle: number;
  mineThisCycle: number;
  largestThisCycle: number;
  retained: number;
}

/** One run record (rule_simulation); the result rows are never kept. */
export interface SimulationRun {
  id: number;
  reference: string;
  requestedBy: string;
  requestedAt: string;
  period: string;
  population: SimPopulation;
  populationCount: number;
  whatIf: SimWhatIf;
  daIda: number | null;
  daCda: number | null;
  mode: 'INTERACTIVE' | 'BACKGROUND';
  status: 'QUEUED' | 'RUNNING' | 'COMPLETE' | 'FAILED';
  done: number;
  jobRequestId: number | null;
  resultsRetained: boolean;
  failure: string | null;
}

export interface SimulationSide {
  computed: number;
  stopped: number;
  notComputed: Record<string, number>;
  gross: number;
  deductions: number;
  net: number;
}

export interface SimulationMember {
  eis: string;
  name: string;
  status: string;
  reason: string | null;
  gross: number | null;
  net: number | null;
  whatIfGross: number | null;
  whatIfNet: number | null;
}

export interface SimulationDetail {
  run: SimulationRun;
  asIs: SimulationSide | null;
  whatIf: SimulationSide | null;
  components: { payCode: number; name: string; asIs: number | null; whatIf: number | null }[];
  /** False when the data changed since the run; null while it is not complete. */
  current: boolean | null;
  members: SimulationMember[];
}

export interface SimulationInput {
  population: SimPopulation;
  members: string[];
  whatIf: SimWhatIf;
  daIda?: string;
  daCda?: string;
}

/** The only place the jobs module talks to /api/v1/simulations. */
export const simulationApi = {
  tiles: () => api<SimulationTiles>('/v1/simulations/tiles'),
  list: (page: number, size: number, search = '') =>
    api<PageResult<SimulationRun>>(
      `/v1/simulations?page=${page}&size=${size}${search ? `&search=${encodeURIComponent(search)}` : ''}`,
    ),
  /** SRS l.1789: the run records, the simulation marker on every row. */
  exportUrl: (search = '') =>
    `${API_BASE}/v1/simulations/export${search ? `?search=${encodeURIComponent(search)}` : ''}`,
  detail: (id: number) => api<SimulationDetail>(`/v1/simulations/${id}`),
  run: (body: SimulationInput) => api<SimulationDetail>('/v1/simulations', { method: 'POST', body }),
};
