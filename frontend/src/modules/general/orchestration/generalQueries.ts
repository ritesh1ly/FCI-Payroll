'use client';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { PERSONAL_DATA } from '@/shared/api';
import {
  generalApi,
  type ExceptionFilter,
  type NewEnablement,
  type NewOffCycle,
  type NewSupplementary,
  type OrderInput,
  type QueryStatus,
  type ReviewFilter,
} from '../api/generalApi';

/**
 * Module 01 use cases for the components: server state through TanStack Query. Every list holds
 * member data, so it is never kept in the cache (PERSONAL_DATA); a change refreshes its screen.
 */

export const generalKeys = {
  documentTypes: ['document-types'] as const,
  queries: ['queries'] as const,
  exceptions: ['exceptions'] as const,
  enablements: ['fallback-enablements'] as const,
  uploads: ['fallback-uploads'] as const,
  lines: (id: number) => ['fallback-lines', id] as const,
  reconciliation: ['fallback-uploads', 'reconciliation'] as const,
  offCycle: ['off-cycle'] as const,
  supplementary: ['supplementary'] as const,
};

function useRefresh(...keys: (readonly unknown[])[]) {
  const queryClient = useQueryClient();
  // Attaching or cancelling also changes the stored-order lists.
  const all = [...keys, ['stored-orders']];
  return () => Promise.all(all.map((queryKey) => queryClient.invalidateQueries({ queryKey })));
}

export function useDocumentTypes(enabled = true) {
  return useQuery({ queryKey: generalKeys.documentTypes, queryFn: generalApi.documentTypes, enabled });
}

/** The stored orders of a record (order-copy card), by screen. */
export function useStoredOrders(kind: 'off-cycle' | 'supplementary' | 'upload', id: number | null) {
  return useQuery({
    ...PERSONAL_DATA,
    queryKey: ['stored-orders', kind, id ?? 0],
    queryFn: () =>
      kind === 'off-cycle'
        ? generalApi.offCycleOrders(id ?? 0)
        : kind === 'supplementary'
          ? generalApi.supplementaryOrders(id ?? 0)
          : generalApi.uploadOrders(id ?? 0),
    enabled: id !== null,
  });
}

// ---------------------------------------------------------------- X-06

export function useRaiseQuery() {
  const refresh = useRefresh(generalKeys.queries);
  return useMutation({
    mutationFn: (input: { payrollId: number; queryText: string; computed: boolean }) =>
      generalApi.raiseQuery(input.payrollId, input.queryText, input.computed),
    onSuccess: refresh,
  });
}

export function useRespondQuery() {
  const refresh = useRefresh(generalKeys.queries);
  return useMutation({
    mutationFn: (input: { id: number; response: string; computed: boolean }) =>
      generalApi.respondQuery(input.id, input.response, input.computed),
    onSuccess: refresh,
  });
}

export function useResolveQuery() {
  const refresh = useRefresh(generalKeys.queries);
  return useMutation({
    mutationFn: (input: { id: number; resolution: string }) =>
      generalApi.resolveQuery(input.id, input.resolution),
    onSuccess: refresh,
  });
}

/** X-06: the queries in scope counted by status (the tiles; the whole scope, not one page). */
export function useQueryCounts() {
  return useQuery({
    ...PERSONAL_DATA,
    queryKey: [...generalKeys.queries, 'counts'],
    queryFn: generalApi.queryCounts,
  });
}

// ---------------------------------------------------------------- X-05

/**
 * X-05: the tiles and the exception classes in scope (the first page of conditions carries them).
 * SRS l.627-630 (UI_BMAD_g1 #5): the tiles count "All modules, all engines", so the status,
 * severity and module filters of the rows never move them; the period and office narrow the scope.
 */
export function useExceptionSummary(filter: ExceptionFilter) {
  const scope: ExceptionFilter = { period: filter.period, office: filter.office };
  return useQuery({
    ...PERSONAL_DATA,
    queryKey: [...generalKeys.exceptions, scope, 'tiles'],
    queryFn: () => generalApi.exceptionGroups(scope, 0, 10),
    select: (w) => ({ tiles: w.tiles, modules: w.modules }),
  });
}

/** SRS l.604: the offices of the session's scope for the office filter (reference data, cached). */
export function useExceptionOffices() {
  return useQuery({
    queryKey: [...generalKeys.exceptions, 'offices'],
    queryFn: generalApi.exceptionOffices,
    staleTime: 300_000,
  });
}

export function useAssignException() {
  const refresh = useRefresh(generalKeys.exceptions);
  return useMutation({
    mutationFn: (input: { id: number; owner: string }) => generalApi.assignException(input.id, input.owner),
    onSuccess: refresh,
  });
}

export function useAcceptException() {
  const refresh = useRefresh(generalKeys.exceptions);
  return useMutation({
    mutationFn: (input: { id: number; reason: string }) => generalApi.acceptException(input.id, input.reason),
    onSuccess: refresh,
  });
}

// ---------------------------------------------------------------- X-07

/** X-07: the enablements still open for an upload (the form's choice and the tile; not paged). */
export function useLiveEnablements() {
  return useQuery({
    ...PERSONAL_DATA,
    queryKey: [...generalKeys.enablements, 'live'],
    queryFn: generalApi.liveEnablements,
  });
}

/** X-07: the tiles over the whole scope. */
export function useFallbackSummary() {
  return useQuery({
    ...PERSONAL_DATA,
    queryKey: [...generalKeys.uploads, 'summary'],
    queryFn: generalApi.fallbackSummary,
  });
}

/** X-07: the tile "HRMS interface". */
export function useHrmsInterfaceStatus() {
  return useQuery({
    ...PERSONAL_DATA,
    queryKey: [...generalKeys.enablements, 'interface'],
    queryFn: generalApi.hrmsInterfaceStatus,
  });
}

/** X-07: the newest uploads, of one enablement when picked (the "Upload" card reads one of them). */
export function useFallbackUploads(enablementId: number | null) {
  return useQuery({
    ...PERSONAL_DATA,
    queryKey: [...generalKeys.uploads, 'latest', enablementId],
    queryFn: () => generalApi.uploads(0, 20, enablementId ?? undefined),
  });
}

/** X-07: the rejected records of an upload, reason by reason. */
export function useUploadRejections(id: number | null) {
  return useQuery({
    ...PERSONAL_DATA,
    queryKey: [...generalKeys.uploads, 'rejections', id],
    queryFn: () => generalApi.uploadRejections(id ?? 0),
    enabled: id !== null,
  });
}

export function useProposeEnablement() {
  const refresh = useRefresh(generalKeys.enablements);
  return useMutation({
    mutationFn: (body: NewEnablement) => generalApi.proposeEnablement(body),
    onSuccess: refresh,
  });
}

export function useWithdrawEnablement() {
  const refresh = useRefresh(generalKeys.enablements);
  return useMutation({
    mutationFn: (input: { id: number; reason: string }) =>
      generalApi.withdrawEnablement(input.id, input.reason),
    onSuccess: refresh,
  });
}

export function useUploadFile() {
  const refresh = useRefresh(generalKeys.uploads, generalKeys.enablements);
  return useMutation({
    mutationFn: (input: { enablementId: number; file: File }) =>
      generalApi.upload(input.enablementId, input.file),
    onSuccess: refresh,
  });
}

export function useUploadOrder() {
  const refresh = useRefresh(generalKeys.uploads);
  return useMutation({
    mutationFn: (input: { id: number; order: OrderInput }) => generalApi.uploadOrder(input.id, input.order),
    onSuccess: refresh,
  });
}

export function useSubmitUpload() {
  const refresh = useRefresh(generalKeys.uploads);
  return useMutation({ mutationFn: (id: number) => generalApi.submitUpload(id), onSuccess: refresh });
}

export function useCancelUpload() {
  const refresh = useRefresh(generalKeys.uploads);
  return useMutation({
    mutationFn: (input: { id: number; reason: string }) => generalApi.cancelUpload(input.id, input.reason),
    onSuccess: refresh,
  });
}

// ---------------------------------------------------------------- I-20

export function useCaptureOffCycle() {
  const refresh = useRefresh(generalKeys.offCycle);
  return useMutation({
    mutationFn: (body: NewOffCycle) => generalApi.captureOffCycle(body),
    onSuccess: refresh,
  });
}

export function useOffCycleOrder() {
  const refresh = useRefresh(generalKeys.offCycle);
  return useMutation({
    mutationFn: (input: { id: number; order: OrderInput }) => generalApi.offCycleOrder(input.id, input.order),
    onSuccess: refresh,
  });
}

export function useSubmitOffCycle() {
  const refresh = useRefresh(generalKeys.offCycle);
  return useMutation({ mutationFn: (id: number) => generalApi.submitOffCycle(id), onSuccess: refresh });
}

export function useCancelOffCycle() {
  const refresh = useRefresh(generalKeys.offCycle);
  return useMutation({
    mutationFn: (input: { id: number; reason: string }) => generalApi.cancelOffCycle(input.id, input.reason),
    onSuccess: refresh,
  });
}

// ---------------------------------------------------------------- I-23

export function useSupplementaryTiles() {
  return useQuery({
    ...PERSONAL_DATA,
    queryKey: [...generalKeys.supplementary, 'tiles'],
    queryFn: generalApi.supplementaryTiles,
  });
}

export function useCaptureSupplementary() {
  const refresh = useRefresh(generalKeys.supplementary);
  return useMutation({
    mutationFn: (input: NewSupplementary) => generalApi.captureSupplementary(input),
    onSuccess: refresh,
  });
}

export function useSubmitSupplementary() {
  const refresh = useRefresh(generalKeys.supplementary);
  return useMutation({ mutationFn: (id: number) => generalApi.submitSupplementary(id), onSuccess: refresh });
}

export function useCancelSupplementary() {
  const refresh = useRefresh(generalKeys.supplementary);
  return useMutation({
    mutationFn: (input: { id: number; reason: string }) =>
      generalApi.cancelSupplementary(input.id, input.reason),
    onSuccess: refresh,
  });
}

/** Builds a download link for a kept file (rendered as an anchor by the screens). */
export const fileUrl = generalApi.fileUrl;
export const exceptionsExportUrl = generalApi.exceptionsExportUrl;
export const computationExportUrl = generalApi.computationExportUrl;

/** C-08: the drawn tiles and the run's month, over the same filters as the list. */
export function useComputationSummary(filter: ReviewFilter) {
  return useQuery({
    queryKey: [...generalKeys.exceptions, 'run-summary', filter],
    queryFn: () => generalApi.computationExceptions(filter, 0, 10),
    select: (r) => ({ tiles: r.tiles, period: r.period }),
    enabled: filter.period !== '',
  });
}
export const queriesExportUrl = generalApi.queriesExportUrl;
export const offCycleExportUrl = generalApi.offCycleExportUrl;
export const supplementaryExportUrl = generalApi.supplementaryExportUrl;
export const uploadsExportUrl = generalApi.uploadsExportUrl;
export const reconciliationExportUrl = generalApi.reconciliationExportUrl;

/**
 * The page fetchers of the Module 01 lists (ServerPagination, docs/plan/X-pagination.md): every list
 * is paged on the server; a change refreshes it through its key.
 */
export const generalPages = {
  queries: (status?: QueryStatus) => (page: number, size: number) => generalApi.queries(status, page, size),
  exceptions: (filter: ExceptionFilter) => (page: number, size: number) =>
    generalApi.exceptions(filter, page, size),
  exceptionGroups: (filter: ExceptionFilter) => (page: number, size: number) =>
    generalApi.exceptionGroups(filter, page, size),
  computationExceptions: (filter: ReviewFilter) => (page: number, size: number) =>
    generalApi.computationExceptions(filter, page, size),
  computationItems:
    (filter: ReviewFilter, module: string | null, code: string) => (page: number, size: number) =>
      generalApi.computationItems(filter, module, code, page, size),
  enablements: generalApi.enablements,
  uploads: generalApi.uploads,
  lines: (id: number) => (page: number, size: number) => generalApi.uploadLines(id, page, size),
  reconciliation: generalApi.reconciliation,
  offCycle: (page: number, size: number) => generalApi.offCycleCases(page, size),
  /** I-20 register of one case type (the drawn Filter); every type when undefined. */
  offCycleOf: (caseType?: string) => (page: number, size: number) =>
    generalApi.offCycleCases(page, size, caseType),
  supplementary: (page: number, size: number) => generalApi.supplementary(page, size),
  /** I-23 requests of one payment type (the drawn Filter); every type when undefined. */
  supplementaryOf: (paymentType?: string) => (page: number, size: number) =>
    generalApi.supplementary(page, size, paymentType),
};
