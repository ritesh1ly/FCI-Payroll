'use client';

import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { statementApi } from '../api/statementApi';
import { type VpfStatus, vpfApi } from '../api/vpfApi';

/** VPF change use cases (plan 14 phase 14c). Personal data: not kept after the screen closes. */
export const vpfKeys = {
  mine: ['vpf-changes', 'mine'] as const,
  list: (status: VpfStatus | undefined) => ['vpf-changes', 'list', status ?? ''] as const,
};

export function useMyVpf(enabled: boolean) {
  return useQuery({ queryKey: vpfKeys.mine, queryFn: vpfApi.mine, enabled, gcTime: 0 });
}

export function useRequestVpf() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (amount: number) => vpfApi.request(amount),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: vpfKeys.mine }),
  });
}

export function vpfPage(status: VpfStatus | undefined) {
  return (page: number, size: number) => vpfApi.list(status, page, size);
}

export function useVerifyVpf() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => vpfApi.verify(id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['vpf-changes'] }),
  });
}

export function useReturnVpf() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, reason }: { id: number; reason: string }) => vpfApi.returnToMember(id, reason),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['vpf-changes'] }),
  });
}

/** E-01 (SRS-17-17): the member's CPF statement of a year (personal data: not kept). */
export function useMyStatement(enabled: boolean, finYear?: string) {
  return useQuery({
    queryKey: ['cpf-statements', 'mine', finYear ?? ''],
    queryFn: () => statementApi.mine(finYear),
    enabled,
    gcTime: 0,
    // E-01: the card keeps the last year shown while another loads (the year selector stays).
    placeholderData: keepPreviousData,
  });
}
