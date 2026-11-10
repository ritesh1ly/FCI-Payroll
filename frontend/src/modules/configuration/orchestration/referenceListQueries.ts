'use client';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useGoverned } from '@/modules/workflow';
import { referenceListsApi, type NewValue, type ValueChange } from '../api/referenceListsApi';

/**
 * M-16 use cases: every proposal is a maker-checker submission and changes nothing until the checker
 * approves it in W-01; then every server instance serves the new values within seconds and every
 * browser gets them on its next revalidation of the reference lists.
 */
export const referenceListKeys = { lists: ['masters', 'reference-lists'] as const };

export function useReferenceListAdmin() {
  return useQuery({ queryKey: referenceListKeys.lists, queryFn: referenceListsApi.lists });
}

export function useProposeReferenceValue() {
  const queryClient = useQueryClient();
  const run = useGoverned();
  return useMutation({
    mutationFn: (input: { list: string; code: string | null; created?: NewValue; changed?: ValueChange }) =>
      run((otp) =>
        input.created
          ? referenceListsApi.proposeNew(input.list, input.created, otp)
          : referenceListsApi.proposeChange(input.list, input.code ?? '', input.changed as ValueChange, otp),
      ),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: referenceListKeys.lists }),
  });
}
