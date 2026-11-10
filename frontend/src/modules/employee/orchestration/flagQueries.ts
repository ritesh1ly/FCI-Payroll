'use client';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { controlFlagApi, type ControlFlagProposal } from '../api/controlFlagApi';
import { flagApi, type FlagDocument, type FlagProposal } from '../api/flagApi';

/** M-01 allowance flag use cases (plan 16 phase 16d-2; SRS-1-4, SRS-1-11). */
export const flagKeys = {
  flags: (eis: string) => ['allowance-flags', eis] as const,
  history: (eis: string) => ['allowance-flags', eis, 'history'] as const,
};

export function useEmployeeFlags(eis: string) {
  return useQuery({ queryKey: flagKeys.flags(eis), queryFn: () => flagApi.flags(eis) });
}

export function flagHistoryPage(eis: string) {
  return (page: number, size: number) => flagApi.history(eis, page, size);
}

/**
 * Establishment/HR's proposal with its supporting document (SRS-1-26); the flags re-read (the flag
 * shows as waiting for the Checker).
 */
export function useProposeFlag(eis: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ body, doc }: { body: FlagProposal; doc: FlagDocument }) => flagApi.propose(eis, body, doc),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['allowance-flags', eis] }),
  });
}

/** M-01 Control flags: Hold, Stop-Payment (SRS-1-7, FRS §7.8.1 Scenario 4). */
export const controlFlagKeys = { flags: (eis: string) => ['control-flags', eis] as const };

export function useControlFlags(eis: string) {
  return useQuery({ queryKey: controlFlagKeys.flags(eis), queryFn: () => controlFlagApi.flags(eis) });
}

/** Establishment/HR sets or clears a flag with the order; the flags re-read (waiting for the Checker). */
export function useProposeControlFlag(eis: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ body, doc }: { body: ControlFlagProposal; doc: FlagDocument }) =>
      controlFlagApi.propose(eis, body, doc),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: controlFlagKeys.flags(eis) }),
  });
}
