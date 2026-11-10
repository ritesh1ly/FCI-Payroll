'use client';

import { useMutation, useQueryClient } from '@tanstack/react-query';
import { suspensionApi, type SuspendInput } from '../api/suspensionApi';
import { bankAdviceKeys } from './bankAdviceQueries';

/** O-02 use cases (plan 05 phase 05h): the list pages on the server; a suspension or a resumption request re-reads it. */
export const suspensionKeys = { list: () => ['suspensions'] as const };

export function suspensionPage(page: number, size: number) {
  return suspensionApi.list(page, size);
}

export function useSuspensionAction() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (
      input: { kind: 'suspend'; body: SuspendInput } | { kind: 'resume'; id: number; reason: string },
    ) =>
      input.kind === 'suspend'
        ? suspensionApi.suspend(input.body)
        : suspensionApi.resume(input.id, input.reason),
    onSuccess: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: suspensionKeys.list() }),
        // The Emergency stop banner of O-02 reads the same state.
        queryClient.invalidateQueries({ queryKey: bankAdviceKeys.overview }),
      ]),
  });
}
