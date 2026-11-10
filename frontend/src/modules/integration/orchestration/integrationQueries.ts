'use client';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { type HrmsApiFilter, integrationApi } from '../api/integrationApi';

/** Module 23 use cases for X-03: server state through TanStack Query; an action refreshes the screen. */

export const integrationKeys = {
  all: ['integration'] as const,
  summary: ['integration', 'summary'] as const,
  interfaces: ['integration', 'interfaces'] as const,
  windows: ['integration', 'windows'] as const,
  hrmsApis: ['integration', 'hrms-apis'] as const,
  fallbackUploads: ['integration', 'fallback-uploads'] as const,
  exceptions: ['integration', 'exceptions'] as const,
  hrmsApiOptions: ['integration', 'hrms-api-options'] as const,
  contract: (code: string) => ['integration', 'contract', code] as const,
};

/** FRS V0.5 Integration Audit Trail export (X-03 EXPORT). */
export const auditTrailUrl = integrationApi.auditTrailUrl;

export const integrationPages = {
  interfaces: (counterparty: string) => (page: number, size: number) =>
    integrationApi.interfaces(counterparty, page, size),
  /** X-03 as drawn: the HRMS or the enterprise interfaces with the drawn status filter and search. */
  drawn: (scope: string, status: string, search: string) => (page: number, size: number) =>
    integrationApi.drawnInterfaces(scope, status, search, page, size),
  windows: (code: string) => (page: number, size: number) => integrationApi.windows(code, page, size),
  hrmsApis: (filter: HrmsApiFilter) => (page: number, size: number) =>
    integrationApi.hrmsApis(filter, page, size),
  fallbackUploads: (page: number, size: number) => integrationApi.fallbackUploads(page, size),
  exceptions: (open: boolean, code: string) => (page: number, size: number) =>
    integrationApi.exceptions(open, code, page, size),
};

export function useIntegrationSummary() {
  return useQuery({ queryKey: integrationKeys.summary, queryFn: integrationApi.summary });
}

export function useContract(code: string | null) {
  return useQuery({
    queryKey: integrationKeys.contract(code ?? ''),
    queryFn: () => integrationApi.contract(code ?? ''),
    enabled: code !== null,
  });
}

function useRefresh() {
  const queryClient = useQueryClient();
  return () => queryClient.invalidateQueries({ queryKey: integrationKeys.all });
}

export function useReprocess() {
  const refresh = useRefresh();
  return useMutation({
    mutationFn: (input: { id: number; reason: string }) => integrationApi.reprocess(input.id, input.reason),
    onSuccess: refresh,
  });
}

/** X-03 l.5172 / SRS-INT-33: Reprocess Failed for one window. */
export function useReprocessWindow() {
  const refresh = useRefresh();
  return useMutation({
    mutationFn: (input: { runId: number; reason: string }) =>
      integrationApi.reprocessWindow(input.runId, input.reason),
    onSuccess: refresh,
  });
}

export function useWriteOff() {
  const refresh = useRefresh();
  return useMutation({
    mutationFn: (input: { id: number; reason: string }) => integrationApi.writeOff(input.id, input.reason),
    onSuccess: refresh,
  });
}

/** X-03: a test refreshes the list and summary, whatever its outcome (status, breaker, last good). */
export function useTestConnection() {
  const refresh = useRefresh();
  return useMutation({
    mutationFn: (code: string) => integrationApi.testConnection(code),
    onSettled: refresh,
  });
}

/** X-03 HRMS API console: the configured outbound APIs to choose from (size 100). */
export function useHrmsApiOptions() {
  return useQuery({ queryKey: integrationKeys.hrmsApiOptions, queryFn: integrationApi.hrmsApiOptions });
}

/** X-03 HRMS API console: one call; the API list's last-call figures are refreshed afterwards. */
export function useCallHrmsApi() {
  const refresh = useRefresh();
  return useMutation({
    mutationFn: integrationApi.callHrmsApi,
    onSettled: refresh,
  });
}
