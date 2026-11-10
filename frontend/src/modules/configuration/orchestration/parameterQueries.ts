'use client';

import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useGoverned } from '@/modules/workflow';
import { parametersApi } from '../api/parametersApi';

export const parameterKeys = { all: ['parameters'] as const };

/** A-07: the page fetcher of the settings (docs/plan/X-pagination.md). */
export const parameterPages = { list: parametersApi.list };

/** Proposes a new value (maker-checker with OTP); it takes effect when an HQ checker approves. */
export function useProposeParameter() {
  const queryClient = useQueryClient();
  const run = useGoverned();
  return useMutation({
    mutationFn: (input: { code: string; value: number; reason: string }) =>
      run((otp) => parametersApi.propose(input.code, input.value, input.reason, otp)),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: parameterKeys.all }),
  });
}
