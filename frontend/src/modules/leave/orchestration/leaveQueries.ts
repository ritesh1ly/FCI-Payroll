'use client';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { type LeaveEventFilter, leaveApi, type NewLeaveParameterVersion } from '../api/leaveApi';

/**
 * M-13 / M-20 / I-11 use cases (plan 19). The lists page on the server; the only write is an M-20
 * version, a maker-checker submission that is read only once the Payroll Checker approves it.
 */
export const leaveKeys = {
  types: ['leave-types'] as const,
  typeCounts: ['leave-types', 'counts'] as const,
  events: (filter: LeaveEventFilter) => ['leave-events', filter] as const,
  eventCounts: ['leave-events', 'counts'] as const,
  versions: (code: string | undefined) => ['leave-pay-parameters', code ?? ''] as const,
};

/** SRS M-20: the Active circulars a new version may name (only for a proposer). */
export function useLeaveCirculars(enabled: boolean) {
  return useQuery({ queryKey: ['leave', 'circulars'], queryFn: leaveApi.circulars, enabled });
}

/** M-13 and I-11 Export links (SRS-G-13). */
export const leaveTypesExportUrl = leaveApi.typesExportUrl;
export const leaveEventsExportUrl = leaveApi.eventsExportUrl;
export const leaveRecoveryReportUrl = leaveApi.recoveryReportUrl;

export const leavePages = {
  types: leaveApi.types,
  events: (filter: LeaveEventFilter) => (page: number, size: number) => leaveApi.events(filter, page, size),
  versions: (code: string | undefined) => (page: number, size: number) => leaveApi.versions(code, page, size),
};

/** M-20: every version, for the seven drawn rows and their tiles (one page: a short list). */
export function useLeaveVersions() {
  return useQuery({
    queryKey: [...leaveKeys.versions(undefined), 'all'],
    queryFn: () => leaveApi.versions(undefined, 0, 100),
  });
}

/** M-13: every received type, for the captions of two tiles (one page: a short list). */
export function useLeaveTypeList() {
  return useQuery({ queryKey: [...leaveKeys.types, 'all'], queryFn: () => leaveApi.types(0, 100) });
}

export function useLeaveTypeCounts() {
  return useQuery({ queryKey: leaveKeys.typeCounts, queryFn: leaveApi.typeCounts });
}

export function useLeaveEventCounts() {
  return useQuery({ queryKey: leaveKeys.eventCounts, queryFn: leaveApi.eventCounts });
}

export function useProposeLeaveParameter() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: NewLeaveParameterVersion) => leaveApi.propose(body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['leave-pay-parameters'] }),
  });
}

/** E-01 (SRS-LV-32): the member's leave balance (personal data: not kept after the screen). */
export function useMyLeaveBalance(enabled: boolean) {
  return useQuery({
    queryKey: ['leave-balances', 'mine'],
    queryFn: leaveApi.balancesMine,
    enabled,
    gcTime: 0,
  });
}
