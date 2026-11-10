'use client';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useGoverned } from '@/modules/workflow';
import {
  mdmApi,
  type CircularQuery,
  type OperationQuery,
  type LabelQuery,
  type NewCircular,
  type ProposedFloor,
  type ProposedLabel,
  type ProposedNorm,
  type ProposedOperation,
} from '../api/mdmApi';

/**
 * Use cases of the Module 04 master screens (M-14, M-15, M-25, M-26, M-27): server state through
 * TanStack Query; every change is a maker-checker submission and changes nothing in force until it is
 * approved.
 */
/** Download links (plain links: the browser streams the file with the session cookie). */
export const mdmLinks = {
  circularDocument: (id: number) => mdmApi.documentHref(id),
  labelsExport: (language?: string, scope?: string) => mdmApi.labelsExportHref(language, scope),
  circularsExport: (q: CircularQuery) => mdmApi.circularsExportHref(q),
  operationsExport: (q: OperationQuery) => mdmApi.operationsExportHref(q),
};

export const mdmKeys = {
  floors: ['mdm', 'floors'] as const,
  floorHistory: (type: string) => ['mdm', 'floors', type] as const,
  exclusions: ['mdm', 'exclusions'] as const,
  norms: ['mdm', 'norms'] as const,
  normHistory: (claimType: string) => ['mdm', 'norms', claimType] as const,
  circulars: (q: CircularQuery) => ['mdm', 'circulars', q] as const,
  whereUsed: (id: number) => ['mdm', 'circulars', 'used', id] as const,
  labels: (q: LabelQuery) => ['mdm', 'labels', q] as const,
  missingHindi: ['mdm', 'labels', 'missing'] as const,
  operations: ['mdm', 'operations'] as const,
  operationList: (q: OperationQuery) => ['mdm', 'operations', 'list', q] as const,
  operationHistory: (code: string) => ['mdm', 'operations', code] as const,
};

function useInvalidate(prefix: readonly string[]) {
  const client = useQueryClient();
  return () => client.invalidateQueries({ queryKey: prefix });
}

/** M-15 tiles. */
export function useNormCounts() {
  return useQuery({ queryKey: ['mdm', 'norms', 'counts'], queryFn: mdmApi.normCounts });
}

/** The M-15 tile of capped claims (read from I-03's records under the M-15 right). */
export function useCeilingBreaches() {
  return useQuery({ queryKey: ['mdm', 'norms', 'breaches'], queryFn: mdmApi.ceilingBreaches, retry: false });
}

// ---------------------------------------------------------------- M-14

export function useProposeFloor() {
  const run = useGoverned();
  const done = useInvalidate(['mdm', 'floors']);
  return useMutation({
    mutationFn: (input: { type: string; body: ProposedFloor }) =>
      run((otp) => mdmApi.proposeFloor(input.type, input.body, otp)),
    onSuccess: done,
  });
}

/** The drawn tile Min-Net Guard Triggered (I-06) (API-04-14). */
export function useFloorGuardCount() {
  return useQuery({ queryKey: ['mdm', 'floors', 'guard-count'], queryFn: mdmApi.floorGuardCount });
}

export function useExclusions() {
  return useQuery({ queryKey: mdmKeys.exclusions, queryFn: mdmApi.exclusions });
}

export function useProposeExclusions() {
  const run = useGoverned();
  const done = useInvalidate(mdmKeys.exclusions);
  return useMutation({
    mutationFn: (input: { payCodes: number[]; reason: string }) =>
      run((otp) => mdmApi.proposeExclusions(input.payCodes, input.reason, otp)),
    onSuccess: done,
  });
}

// ---------------------------------------------------------------- M-15

export function useProposeNorm() {
  const run = useGoverned();
  const done = useInvalidate(['mdm', 'norms']);
  return useMutation({
    mutationFn: (input: { claimType: string; body: ProposedNorm }) =>
      run((otp) => mdmApi.proposeNorm(input.claimType, input.body, otp)),
    onSuccess: done,
  });
}

// ---------------------------------------------------------------- M-25

/** The circulars in force today, as a choice list (M-27 link; not paged). */
export function useCurrentCirculars() {
  return useQuery({ queryKey: ['mdm', 'circulars', 'current'], queryFn: mdmApi.currentCirculars });
}

export function useCircularOffices(enabled: boolean) {
  return useQuery({ queryKey: ['mdm', 'circulars', 'offices'], queryFn: mdmApi.circularOffices, enabled });
}

export function useRecordCircular() {
  const run = useGoverned();
  const done = useInvalidate(['mdm', 'circulars']);
  return useMutation({
    mutationFn: (input: { body: NewCircular; file: File }) =>
      run((otp) => mdmApi.recordCircular(input.body, input.file, otp)),
    onSuccess: done,
  });
}

export function useWithdrawCircular() {
  const run = useGoverned();
  const done = useInvalidate(['mdm', 'circulars']);
  return useMutation({
    mutationFn: (input: { id: number; reason: string }) =>
      run((otp) => mdmApi.withdrawCircular(input.id, input.reason, otp)),
    onSuccess: done,
  });
}

// ---------------------------------------------------------------- M-26

export function useProposeLabel() {
  const run = useGoverned();
  const done = useInvalidate(['mdm', 'labels']);
  return useMutation({
    mutationFn: (body: ProposedLabel) => run((otp) => mdmApi.proposeLabel(body, otp)),
    onSuccess: done,
  });
}

export function useWithdrawLabel() {
  const run = useGoverned();
  const done = useInvalidate(['mdm', 'labels']);
  return useMutation({
    mutationFn: (input: { id: number; reason: string }) =>
      run((otp) => mdmApi.withdrawLabel(input.id, input.reason, otp)),
    onSuccess: done,
  });
}

export function useUploadLabels() {
  const done = useInvalidate(['mdm', 'labels']);
  return useMutation({
    mutationFn: (input: { file: File; reason: string }) => mdmApi.uploadLabels(input.file, input.reason),
    onSuccess: done,
  });
}

// ---------------------------------------------------------------- M-27

export function useProposeOperation() {
  const run = useGoverned();
  const done = useInvalidate(['mdm', 'operations']);
  return useMutation({
    mutationFn: (input: { code: string; body: ProposedOperation }) =>
      run((otp) => mdmApi.proposeOperation(input.code, input.body, otp)),
    onSuccess: done,
  });
}

export function useEndDateOperation() {
  const run = useGoverned();
  const done = useInvalidate(['mdm', 'operations']);
  return useMutation({
    mutationFn: (input: { code: string; lastDay: string; reason: string }) =>
      run((otp) => mdmApi.endDateOperation(input.code, input.lastDay, input.reason, otp)),
    onSuccess: done,
  });
}

/** M-14 / M-15 / M-25 / M-26 / M-27: the page fetchers of the paged lists (docs/plan/X-pagination.md). */
export const mdmPages = {
  floors: mdmApi.floors,
  floorHistory: (type: string) => (page: number, size: number) => mdmApi.floorHistory(type, page, size),
  norms: mdmApi.norms,
  normHistory: (claimType: string) => (page: number, size: number) =>
    mdmApi.normHistory(claimType, page, size),
  circulars: (q: CircularQuery) => (page: number, size: number) => mdmApi.circulars(q, page, size),
  whereUsed: (id: number) => (page: number, size: number) => mdmApi.whereUsed(id, page, size),
  labels: (q: LabelQuery) => (page: number, size: number) => mdmApi.labels(q, page, size),
  missingHindi: mdmApi.missingHindi,
  operations: (q: OperationQuery) => (page: number, size: number) => mdmApi.operations(q, page, size),
  operationHistory: (code: string) => (page: number, size: number) =>
    mdmApi.operationHistory(code, page, size),
};
