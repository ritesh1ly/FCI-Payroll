'use client';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { settlementApi } from '../api/settlementApi';

/**
 * O-05 use cases (plan 11). A case opens on the approved separation; the Maker computes and submits,
 * Establishment / HR answers the no-dues, Finance initiates the release; every approval is a W-01
 * decision of the Payroll Checker (with OTP for the settlement and its release).
 */
export const settlementKeys = {
  cases: (eis: string) => ['settlement-cases', eis] as const,
  awaiting: ['settlement-cases', 'awaiting'] as const,
  summary: ['settlement-cases', 'summary'] as const,
  invoices: ['settlement-cases', 'invoices'] as const,
  orders: (id: number) => ['settlement-cases', 'orders', id] as const,
  detail: (id: number) => ['settlement-cases', 'detail', id] as const,
};

export const settlementPages = {
  cases: (eis: string) => (page: number, size: number) => settlementApi.cases(eis || undefined, page, size),
  invoices: (page: number, size: number) => settlementApi.invoices(page, size),
};

export function useAwaiting() {
  return useQuery({ queryKey: settlementKeys.awaiting, queryFn: settlementApi.awaiting });
}

/** O-05 tiles: SRS-10-6, SRS-10-11, SRS-10-22. */
export function useSummary() {
  return useQuery({ queryKey: settlementKeys.summary, queryFn: settlementApi.summary });
}

/** SRS-G-31: the order copies stored with the open case. */
export function useCaseOrders(id: number | null) {
  return useQuery({
    queryKey: settlementKeys.orders(id ?? 0),
    queryFn: () => settlementApi.orders(id as number),
    enabled: id !== null,
  });
}

export function useDocumentTypes() {
  return useQuery({ queryKey: ['document-types'], queryFn: settlementApi.documentTypes });
}

/** The download link of a stored order. */
export const orderFileUrl = settlementApi.fileUrl;

/** SRS-G-30: stores an order copy with the case; the list above follows. */
export function useOrderAttach() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, order }: { id: number; order: Parameters<typeof settlementApi.attachOrder>[1] }) =>
      settlementApi.attachOrder(id, order),
    onSuccess: (_, { id }) => queryClient.invalidateQueries({ queryKey: settlementKeys.orders(id) }),
  });
}

export function useCase(id: number | null) {
  return useQuery({
    queryKey: settlementKeys.detail(id ?? 0),
    queryFn: () => settlementApi.detail(id as number),
    enabled: id !== null,
  });
}

/** The O-05 actions the screen runs through {@link useSettlementAction}. */
export const settlementCalls = {
  noDues: settlementApi.noDues,
  compute: settlementApi.compute,
  exGratia: settlementApi.exGratia,
  exclude: settlementApi.exclude,
  source: settlementApi.source,
  submit: settlementApi.submit,
  release: settlementApi.release,
};

export function useSettlementAction() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (run: () => Promise<unknown>) => run(),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['settlement-cases'] }),
  });
}
