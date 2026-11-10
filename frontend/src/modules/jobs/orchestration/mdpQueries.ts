'use client';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { mdpApi, type MdpCapture, type MdpFilter, type NewMdpOrder } from '../api/mdpApi';

/**
 * I-19 use cases (plan 05 phase 05g): the register pages on the server with its filters; every change
 * is a submission the Payroll Checker decides, so the register is read again to show it waiting.
 */
export const mdpKeys = {
  heads: () => ['mdp', 'heads'] as const,
  periods: () => ['mdp', 'periods'] as const,
  summary: () => ['mdp', 'register', 'summary'] as const,
};

/** The tiles: read again with the register after every change (same key prefix). */
export function useMdpSummary() {
  return useQuery({ queryKey: mdpKeys.summary(), queryFn: mdpApi.summary });
}

export function useMdpHeads() {
  return useQuery({ queryKey: mdpKeys.heads(), queryFn: mdpApi.heads });
}

export function useMdpPeriods() {
  return useQuery({ queryKey: mdpKeys.periods(), queryFn: mdpApi.openPeriods });
}

/** The orders of the selected entry (personal data: never kept in the cache). */
export function useMdpOrders(id: number | null) {
  return useQuery({
    queryKey: ['mdp', 'orders', id],
    queryFn: () => mdpApi.orders(id as number),
    enabled: id !== null,
    staleTime: 0,
    gcTime: 0,
  });
}

export function useMdpDocumentTypes() {
  return useQuery({ queryKey: ['document-types'], queryFn: mdpApi.documentTypes });
}

/** Attaches the picked files one by one with the same particulars; the stored list refreshes. */
export function useAttachMdpOrder() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: { id: number; order: Omit<NewMdpOrder, 'file'>; files: File[] }) => {
      for (const file of input.files) await mdpApi.attachOrder(input.id, { ...input.order, file });
    },
    onSuccess: (_, input) => queryClient.invalidateQueries({ queryKey: ['mdp', 'orders', input.id] }),
  });
}

export function mdpOrderUrl(id: number, fileId: number): string {
  return mdpApi.orderUrl(id, fileId);
}

export function mdpPage(filter: MdpFilter) {
  return (page: number, size: number) => mdpApi.register(filter, page, size);
}

export function mdpExportUrl(filter: MdpFilter): string {
  return mdpApi.exportUrl(filter);
}

export function useMdpAction() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (
      input:
        | { kind: 'capture'; body: MdpCapture }
        | { kind: 'amend'; id: number; amount: number; reason: string }
        | { kind: 'cancel'; id: number; reason: string },
    ) => {
      switch (input.kind) {
        case 'capture':
          return mdpApi.capture(input.body);
        case 'amend':
          return mdpApi.amend(input.id, input.amount, input.reason);
        case 'cancel':
          return mdpApi.cancel(input.id, input.reason);
      }
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['mdp', 'register'] }),
  });
}
