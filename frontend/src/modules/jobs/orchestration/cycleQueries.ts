'use client';

import { type QueryClient, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useGoverned } from '@/modules/workflow';
import { cycleApi, type ResultFilter, type ReturnInput } from '../api/cycleApi';
import { cycleStatusKeys } from './cycleStatusQueries';

/**
 * C-02 use cases: the cycle board polls while a run is going on; each transition is one call (the
 * freeze and the lock request with the OTP step); the server checks permission, state and
 * separation of duties.
 */
export const cycleKeys = {
  cycle: (period: string) => ['cycle', period] as const,
  results: (period: string, payPattern = '', status = '', search = '') =>
    ['cycle', period, 'results', payPattern, status, search] as const,
  resultsPeriod: (period: string) => ['cycle', period, 'results-period'] as const,
  summary: (period: string, filter: ResultFilter) => ['cycle', period, 'summary', filter] as const,
  trace: (period: string, id: number | null) => ['cycle', period, 'trace', id] as const,
  pli: (period: string) => ['cycle', period, 'pli'] as const,
  lines: (period: string, id: number) => ['cycle', period, 'lines', id] as const,
  gates: (period: string) => ['cycle', period, 'gates'] as const,
  preRun: (period: string) => ['cycle', period, 'pre-run'] as const,
  quarantine: (period: string) => ['cycle', period, 'quarantine'] as const,
  register: () => ['cycle', 'register'] as const,
  overview: (period: string) => ['cycle', period, 'overview'] as const,
  periodRegister: () => ['cycle', 'register', 'periods'] as const,
  report: (period: string) => ['cycle', period, 'pre-payroll-report'] as const,
  orders: (period: string) => ['cycle', period, 'orders'] as const,
};

/**
 * The period's queries plus the cross-period register (SRS-15-17) and the D-01 cycle status of the
 * scope (SRS l.1562: the home shows the current state, not the one before the action).
 */
export function invalidateCycle(queryClient: QueryClient, period: string): Promise<unknown> {
  return Promise.all([
    queryClient.invalidateQueries({ queryKey: ['cycle', period] }),
    queryClient.invalidateQueries({ queryKey: cycleKeys.register() }),
    queryClient.invalidateQueries({ queryKey: cycleStatusKeys.status() }),
  ]);
}

/** SRS-15-17: the register's export link. */
export function registerExportUrl(): string {
  return cycleApi.registerExportUrl();
}

/** C-02: the period the result sections show (the session's, or the unit's latest with a run). */
export function useResultsPeriod(period: string) {
  return useQuery({
    queryKey: cycleKeys.resultsPeriod(period),
    queryFn: async () => (await cycleApi.resultsPeriod(period)) ?? null,
    enabled: period !== '',
  });
}

/** C-02: the four tiles and the totals line. */
export function useResultsSummary(period: string, filter: ResultFilter) {
  return useQuery({
    queryKey: cycleKeys.summary(period, filter),
    queryFn: () => cycleApi.resultsSummary(period, filter),
    enabled: period !== '',
  });
}

/** C-02: the calculation trace of the selected member; personal data, never kept in the cache. */
export function useTrace(period: string, resultId: number | null) {
  return useQuery({
    queryKey: cycleKeys.trace(period, resultId),
    queryFn: () => cycleApi.trace(period, resultId as number),
    enabled: resultId !== null,
    staleTime: 0,
    gcTime: 0,
  });
}

/** SRS-G-13: the export link of the filtered C-02 list. */
export function resultsExportUrl(period: string, filter: ResultFilter): string {
  return cycleApi.resultsExportUrl(period, filter);
}

/** C-02: the initiation checks P1-P5, evaluated by the server on every read (SRS-15-23). */
export function usePreRunChecks(period: string, enabled: boolean) {
  return useQuery({
    queryKey: cycleKeys.preRun(period),
    queryFn: () => cycleApi.preRunChecks(period),
    enabled: enabled && period !== '',
  });
}

/** C-01: the band, tiles, initiation checks, run, levels and fallback (SRS l.1755-1767). */
export function useCycleOverview(period: string) {
  return useQuery({
    queryKey: cycleKeys.overview(period),
    queryFn: () => cycleApi.overview(period),
    enabled: period !== '',
  });
}

/** SRS-G-32: the override approval notes of the period's instance. */
export function useCycleOrders(period: string, enabled: boolean) {
  return useQuery({
    queryKey: cycleKeys.orders(period),
    queryFn: () => cycleApi.orders(period),
    enabled: enabled && period !== '',
  });
}

/** The document types of the order card (reference required by type). */
export function useCycleDocumentTypes() {
  return useQuery({ queryKey: ['document-types'], queryFn: cycleApi.documentTypes });
}

export function useAttachCycleOrder(period: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (form: FormData) => cycleApi.attachOrder(period, form),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: cycleKeys.orders(period) }),
  });
}

/** C-01: the gates and checks, evaluated by the server on every read (SRS-15-18). */
export function useGates(period: string) {
  return useQuery({
    queryKey: cycleKeys.gates(period),
    queryFn: () => cycleApi.gates(period),
    enabled: period !== '',
  });
}

/** C-01 checker actions: accept G2 / G5, acknowledge a Warn check, review a quarantined member. */
export function useGateAction(period: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (
      input:
        | { kind: 'gate'; gate: string; reason: string; members?: string[] }
        | { kind: 'check'; checkId: number; reason: string }
        | { kind: 'member'; id: number; accepted: boolean; reason: string },
    ) => {
      switch (input.kind) {
        case 'gate':
          return cycleApi.acceptGate(period, input.gate, input.reason, input.members);
        case 'check':
          return cycleApi.acknowledgeCheck(period, input.checkId, input.reason);
        case 'member':
          return cycleApi.reviewMember(period, input.id, input.accepted, input.reason);
      }
    },
    // SRS-15-17: the Gate Override & Undo / Reversal Register lives outside the period prefix, so
    // it is refreshed explicitly after every gate, check, member, undo or return action.
    onSuccess: () => invalidateCycle(queryClient, period),
  });
}

export function useCycle(period: string) {
  return useQuery({
    queryKey: cycleKeys.cycle(period),
    queryFn: () => cycleApi.cycle(period),
    // Polls while a run is going on or another action holds the lease (SRS-15-28), so the board
    // frees itself when they end.
    refetchInterval: (query) =>
      query.state.data?.runs.some((r) => r.status === 'RUNNING') || query.state.data?.lease ? 5000 : false,
  });
}

/** Every C-02 step the board offers (each one call; the freeze and the lock with the OTP step). */
export type CycleActionName =
  'initiate' | 'freeze' | 'verify' | 'lock' | 'leaseRelease' | 'return' | 'withdraw' | 'unfreeze';

export function useCycleAction(period: string) {
  const queryClient = useQueryClient();
  const run = useGoverned();
  return useMutation({
    mutationFn: (input: {
      action: CycleActionName;
      reason?: string;
      returnInput?: ReturnInput;
      returnId?: number;
    }) => {
      const reason = input.reason ?? '';
      switch (input.action) {
        case 'initiate':
          return cycleApi.initiate(period);
        case 'freeze':
          return run((otp) => cycleApi.freeze(period, otp));
        case 'verify':
          return cycleApi.verify(period);
        case 'lock':
          return run((otp) => cycleApi.lock(period, reason, otp));
        case 'leaseRelease':
          return cycleApi.requestLeaseRelease(period, reason);
        case 'return':
          return cycleApi.returnCycle(period, input.returnInput as ReturnInput);
        case 'withdraw':
          return cycleApi.withdrawReturn(period, input.returnId ?? 0, reason);
        case 'unfreeze':
          return cycleApi.requestUnfreeze(period, reason);
      }
    },
    // SRS-15-17: the Gate Override & Undo / Reversal Register lives outside the period prefix, so
    // it is refreshed explicitly after every gate, check, member, undo or return action.
    onSuccess: () => invalidateCycle(queryClient, period),
  });
}

/** C-01 / C-02: the page fetchers of the paged lists (docs/plan/X-pagination.md). */
export const cyclePages = {
  results: (period: string, filter: ResultFilter) => (page: number, size: number) =>
    cycleApi.results(period, page, size, filter),
  pli: (period: string) => (page: number, size: number) => cycleApi.pliPayments(period, page, size),
  lines: (period: string, id: number) => (page: number, size: number) =>
    cycleApi.lines(period, id, page, size),
  quarantine: (period: string) => (page: number, size: number) => cycleApi.quarantine(period, page, size),
  register: () => (page: number, size: number) => cycleApi.register(page, size),
  periodRegister: (page: number, size: number) => cycleApi.periodRegister(page, size),
  report: (period: string) => (page: number, size: number) => cycleApi.prePayrollReport(period, page, size),
};

/** SRS-G-32: the link of a stored override note. */
export const cycleOrderUrl = cycleApi.orderFileUrl;
