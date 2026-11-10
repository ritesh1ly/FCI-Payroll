'use client';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { type LoanDocument, loansApi, type RescheduleInput, type SanctionInput } from '../api/loansApi';

/** I-04 loans use cases (plan 09 phase 09f): registers page on the server; a submission re-reads them. */
export const loanKeys = {
  list: (filter: { eis?: string; status?: string }) => ['loans', filter] as const,
  detail: (id: number) => ['loans', 'detail', id] as const,
  requests: () => ['loans', 'requests'] as const,
  types: () => ['loans', 'types'] as const,
  summary: (yymm: string) => ['loans', 'summary', yymm] as const,
  disbursements: () => ['loans', 'disbursements'] as const,
  orders: (id: number) => ['loans', 'orders', id] as const,
};

export function ledgerPage(page: number, size: number) {
  return loansApi.list({}, page, size);
}

export function disbursementPage(page: number, size: number) {
  return loansApi.disbursements(page, size);
}

/** The link of a stored order copy. */
export const loanOrderUrl = loansApi.orderFileUrl;

/** The loan tiles of the month (a month without instalments shows the latest before it). */
export function useLoanSummary(yymm: string) {
  return useQuery({ queryKey: loanKeys.summary(yymm), queryFn: () => loansApi.summary(yymm) });
}

/** The order copies of the loan chosen on the ledger. */
export function useLoanOrders(id: number | null) {
  return useQuery({
    queryKey: loanKeys.orders(id ?? 0),
    queryFn: () => loansApi.orders(id ?? 0),
    enabled: id !== null,
  });
}

export function useAttachLoanOrder() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (v: { id: number; order: LoanDocument }) => loansApi.attachOrder(v.id, v.order),
    onSuccess: (_stored, v) => queryClient.invalidateQueries({ queryKey: loanKeys.orders(v.id) }),
  });
}

export function loanPage(filter: { eis?: string; status?: string }) {
  return (page: number, size: number) => loansApi.list(filter, page, size);
}

export function requestPage() {
  return (page: number, size: number) => loansApi.requests(page, size);
}

/** SRS-12-26: the report's link. */
export function loanReportUrl(from: string, to: string): string {
  return loansApi.reportUrl(from, to);
}

export function useLoanDetail(id: number | null) {
  return useQuery({
    queryKey: loanKeys.detail(id ?? 0),
    queryFn: () => loansApi.detail(id ?? 0),
    enabled: id !== null,
  });
}

export function useLoanTypes() {
  return useQuery({ queryKey: loanKeys.types(), queryFn: loansApi.types });
}

export function useLoanActions() {
  const queryClient = useQueryClient();
  const refresh = () => queryClient.invalidateQueries({ queryKey: ['loans'] });
  return {
    preview: useMutation({ mutationFn: (body: SanctionInput) => loansApi.preview(body) }),
    submit: useMutation({
      mutationFn: (v: { body: SanctionInput; order: LoanDocument }) => loansApi.submit(v.body, v.order),
      onSuccess: refresh,
    }),
    foreclose: useMutation({
      mutationFn: (v: { id: number; order: LoanDocument; receipt: LoanDocument }) =>
        loansApi.foreclose(v.id, v.order, v.receipt),
      onSuccess: refresh,
    }),
    reschedule: useMutation({
      mutationFn: (v: { id: number; body: RescheduleInput; order: LoanDocument; signed: LoanDocument }) =>
        loansApi.reschedule(v.id, v.body, v.order, v.signed),
      onSuccess: refresh,
    }),
  };
}
