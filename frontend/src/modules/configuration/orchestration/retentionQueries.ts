'use client';

import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useGoverned } from '@/modules/workflow';
import { type RetentionProposal, retentionApi } from '../api/retentionApi';

export const retentionKeys = { all: ['retention-classes'] as const };

/** SRS-20-5: the audit stores' retention periods. */
export const retentionPages = { list: retentionApi.list };

/** Proposes a period (maker-checker); it takes effect when a Headquarters checker approves it. */
export function useProposeRetention() {
  const queryClient = useQueryClient();
  const run = useGoverned();
  return useMutation({
    mutationFn: (input: RetentionProposal) => run((otp) => retentionApi.propose(input, otp)),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: retentionKeys.all }),
  });
}
