'use client';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  statutoryApi,
  type DocumentParticulars,
  type NewChallan,
  type NewFiling,
  type Quarter,
  type VersionProposal,
  type WhatIfRequest,
} from '../api/statutoryApi';

/**
 * Use cases of C-07, M-17, C-10 and C-11 (plan 08 phases 08e-08h): server state through TanStack
 * Query. Personal figures (a member's computation, certificates) are never kept in the cache.
 */
export const statutoryKeys = {
  forecast: (eis: string, finYear: string) => ['tax', 'forecast', eis, finYear] as const,
  outputs: ['tax', 'outputs'] as const,
  certificates: (finYear: string, status: string) => ['tax', 'form16', 'list', finYear, status] as const,
  merged: (finYear: string) => ['tax', 'form16', 'list', finYear, 'MERGED', 'count'] as const,
  mine: ['tax', 'form16', 'mine'] as const,
  returns: (finYear: string) => ['tax', 'returns', 'list', finYear] as const,
  returnsOfYear: (finYear: string) => ['tax', 'returns', 'year', finYear] as const,
  deductees: (id: number) => ['tax', 'returns', 'deductees', id] as const,
  challans: (finYear: string) => ['tax', 'challans', finYear] as const,
};

// ---------------------------------------------------------------- C-07

/** C-07: the member's year; nothing is read while no member is chosen. */
export function useForecast(eis: string | null, finYear: string) {
  return useQuery({
    queryKey: statutoryKeys.forecast(String(eis), finYear),
    // Never run while no member is chosen (enabled), so the EIS is a string here.
    queryFn: () => statutoryApi.forecast(String(eis), finYear),
    enabled: eis !== null,
    staleTime: 0,
    gcTime: 0,
  });
}

/** SRS-7-27: the Income Tax Forecast download (CSV or XLSX; the read is logged on the server). */
export const forecastExportUrl = statutoryApi.forecastExportUrl;

// ---------------------------------------------------------------- M-17

/** M-17: the statutory outputs (a bounded catalogue, returned whole). */
export function useOutputs() {
  return useQuery({ queryKey: statutoryKeys.outputs, queryFn: statutoryApi.outputs });
}

/** M-17: a new version of an output, for the Rule Approver (route M17_VERSION). */
export function useProposeVersion() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ key, body }: { key: string; body: VersionProposal }) =>
      statutoryApi.proposeVersion(key, body),
    onSuccess: () => client.invalidateQueries({ queryKey: statutoryKeys.outputs }),
  });
}

// ---------------------------------------------------------------- C-10

/** C-10: the certificates of a year a page at a time (SRS-NF-3). */
export const certificatePages = {
  list: (finYear: string, status: string) => (page: number, size: number) =>
    statutoryApi.certificates(finYear, status, page, size),
};

/**
 * BR-TAX-012 (UI audit S13): how many certificates of the year are MERGED - the release goes with
 * them only, so "Request release" waits for one. The smallest page the server offers (10) is read; its total counts.
 */
export function useMergedCount(finYear: string, enabled: boolean) {
  return useQuery({
    queryKey: statutoryKeys.merged(finYear),
    queryFn: async () => (await statutoryApi.certificates(finYear, 'MERGED', 0, 10)).total,
    enabled,
    staleTime: 0,
    gcTime: 0,
  });
}

/** The Part A of a certificate (the read is logged on the server). */
export const partAUrl = statutoryApi.partAUrl;

/** E-01: the member's released certificates; personal data, never cached. */
export function useMyCertificates() {
  return useQuery({
    queryKey: statutoryKeys.mine,
    queryFn: statutoryApi.myCertificates,
    staleTime: 0,
    gcTime: 0,
  });
}

/** C-10: generate Part B, merge Part A, request the release, reissue. */
export function useCertificateActions() {
  const client = useQueryClient();
  const refresh = () => client.invalidateQueries({ queryKey: ['tax', 'form16'] });
  return {
    generate: useMutation({
      mutationFn: ({ finYear, eis }: { finYear: string; eis: string[] }) =>
        statutoryApi.generateCertificates(finYear, eis),
      onSuccess: refresh,
    }),
    partA: useMutation({
      mutationFn: ({ id, particulars, file }: { id: number; particulars: DocumentParticulars; file: File }) =>
        statutoryApi.uploadPartA(id, particulars, file),
      onSuccess: refresh,
    }),
    release: useMutation({
      mutationFn: (finYear: string) => statutoryApi.releaseCertificates(finYear),
      onSuccess: refresh,
    }),
    reissue: useMutation({
      mutationFn: ({ id, reason }: { id: number; reason: string }) => statutoryApi.reissue(id, reason),
      onSuccess: refresh,
    }),
  };
}

// ---------------------------------------------------------------- C-11

/** C-11: the returns of a year a page at a time (SRS-NF-3). */
export const returnPages = {
  list: (finYear: string) => (page: number, size: number) => statutoryApi.returns(finYear, page, size),
  deductees: (id: number) => (page: number, size: number) => statutoryApi.deductees(id, page, size),
};

/**
 * C-11: the returns of a year for the generation form (at most four originals and their
 * corrections: one bounded page).
 */
export function useReturnsOfYear(finYear: string, enabled: boolean) {
  return useQuery({
    queryKey: statutoryKeys.returnsOfYear(finYear),
    queryFn: () => statutoryApi.returns(finYear, 0, 100),
    enabled,
  });
}

/** C-11: the imported challans of a year. */
export function useChallans(finYear: string, enabled: boolean) {
  return useQuery({
    queryKey: statutoryKeys.challans(finYear),
    queryFn: () => statutoryApi.challans(finYear),
    enabled,
  });
}

/** The filing evidence of a return (the read is logged on the server). */
export const evidenceUrl = statutoryApi.evidenceUrl;

/** C-11: import challans, generate, validate, request the release, record the filing. */
export function useReturnActions() {
  const client = useQueryClient();
  const refresh = () => client.invalidateQueries({ queryKey: ['tax', 'returns'] });
  return {
    enterChallan: useMutation({
      mutationFn: ({ finYear, challan }: { finYear: string; challan: NewChallan }) =>
        statutoryApi.enterChallan(finYear, challan),
      onSuccess: () => client.invalidateQueries({ queryKey: ['tax', 'challans'] }),
    }),
    importChallans: useMutation({
      mutationFn: ({ finYear, file }: { finYear: string; file: File }) =>
        statutoryApi.importChallans(finYear, file),
      onSuccess: () => client.invalidateQueries({ queryKey: ['tax', 'challans'] }),
    }),
    generate: useMutation({
      mutationFn: ({
        finYear,
        quarter,
        corrects,
      }: {
        finYear: string;
        quarter: Quarter;
        corrects: number | null;
      }) => statutoryApi.generateReturn(finYear, quarter, corrects),
      onSuccess: refresh,
    }),
    validate: useMutation({
      mutationFn: (id: number) => statutoryApi.validateReturn(id),
      onSuccess: refresh,
    }),
    release: useMutation({ mutationFn: (id: number) => statutoryApi.releaseReturn(id), onSuccess: refresh }),
    filing: useMutation({
      mutationFn: ({ id, filing }: { id: number; filing: NewFiling }) =>
        statutoryApi.recordFiling(id, filing),
      onSuccess: refresh,
    }),
  };
}

/** SRS-7-28: a what-if run (a member's, or the employee's own when {@code eis} is null); nothing cached. */
export function useWhatIf(eis: string | null, finYear: string) {
  return useMutation({ mutationFn: (body: WhatIfRequest) => statutoryApi.whatIf(eis, finYear, body) });
}
