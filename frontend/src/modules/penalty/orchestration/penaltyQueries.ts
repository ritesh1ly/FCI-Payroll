'use client';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  type OrderCopy,
  type OrderInput,
  type PaymentInput,
  penaltyApi,
  type RevokeInput,
} from '../api/penaltyApi';

/** I-06 use cases (plan 12 phase 12g): registers page on the server; a submission re-reads them. */
export const penaltyKeys = {
  list: (filter: { eis?: string; status?: string }) => ['penalties', filter] as const,
  detail: (id: number) => ['penalties', 'detail', id] as const,
  requests: () => ['penalties', 'requests'] as const,
  payments: () => ['penalties', 'payments'] as const,
  counts: ['penalties', 'counts'] as const,
  register: ['penalties', 'register'] as const,
  minNet: ['penalties', 'min-net'] as const,
  copies: (id: number) => ['penalties', 'copies', id] as const,
};

/** The drawn tiles and panel figures of the unit (API-12-3). */
export type { OrderRow, PaymentRow, RegisterRow } from '../api/penaltyApi';

/** The first page of orders: I-06 opens the newest order's card until one is picked. */
export function useFirstOrder() {
  return useQuery({
    queryKey: [...penaltyKeys.list({}), 'first'],
    queryFn: () => penaltyApi.list({}, 0, 20),
  });
}

export function usePenaltyCounts() {
  return useQuery({ queryKey: penaltyKeys.counts, queryFn: penaltyApi.counts });
}

/** The latest request waiting on the floor route (SRS-14-10). */
export function useMinNetGuard() {
  return useQuery({ queryKey: penaltyKeys.minNet, queryFn: penaltyApi.minNet });
}

/** The order copies of the selected order (SRS-14-12). */
export function useOrderCopies(id: number | null) {
  return useQuery({
    queryKey: penaltyKeys.copies(id ?? 0),
    queryFn: () => penaltyApi.orderCopies(id as number),
    enabled: id !== null,
  });
}

export function registerPage() {
  return (page: number, size: number) => penaltyApi.register(page, size);
}

export const penaltyCopyUrl = penaltyApi.orderCopyUrl;

export function orderPage(filter: { eis?: string; status?: string }) {
  return (page: number, size: number) => penaltyApi.list(filter, page, size);
}

export function requestPage() {
  return (page: number, size: number) => penaltyApi.requests(page, size);
}

export function paymentPage() {
  return (page: number, size: number) => penaltyApi.payments(page, size);
}

/** SRS-14-19: the Penalty Report link. */
export function penaltyReportUrl(from: string, to: string): string {
  return penaltyApi.reportUrl(from, to);
}

export function useOrderDetail(id: number | null) {
  return useQuery({
    queryKey: penaltyKeys.detail(id ?? 0),
    queryFn: () => penaltyApi.detail(id ?? 0),
    enabled: id !== null,
  });
}

export function usePenaltyActions() {
  const queryClient = useQueryClient();
  const refresh = () => queryClient.invalidateQueries({ queryKey: ['penalties'] });
  return {
    preview: useMutation({ mutationFn: (body: OrderInput) => penaltyApi.preview(body) }),
    submit: useMutation({
      mutationFn: (v: { body: OrderInput; copy: OrderCopy }) => penaltyApi.submit(v.body, v.copy),
      onSuccess: refresh,
    }),
    revoke: useMutation({
      mutationFn: (v: { id: number; body: RevokeInput; copy: OrderCopy }) =>
        penaltyApi.revoke(v.id, v.body, v.copy),
      onSuccess: refresh,
    }),
    cancel: useMutation({
      mutationFn: (v: { id: number; reason: string; copy: OrderCopy }) =>
        penaltyApi.cancel(v.id, v.reason, v.copy),
      onSuccess: refresh,
    }),
    pay: useMutation({ mutationFn: (body: PaymentInput) => penaltyApi.pay(body), onSuccess: refresh }),
    cancelPayment: useMutation({
      mutationFn: (v: { id: number; reason: string }) => penaltyApi.cancelPayment(v.id, v.reason),
      onSuccess: refresh,
    }),
  };
}
