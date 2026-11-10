'use client';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { leaveArrearApi, type LeArrearFilter, type NewLeOrder, type RunRequest } from '../api/leaveArrearApi';

/**
 * I-24 use cases (plan 13 phase 13d). The Payroll Maker runs the arrear against a revision order,
 * selects records and submits them together; the Payroll Checker approves in W-01.
 */
export const leaveArrearKeys = {
  records: (f: LeArrearFilter) => ['le-arrears', f.status ?? '', f.eis ?? ''] as const,
  orders: ['le-arrears', 'orders'] as const,
  summary: ['le-arrears', 'summary'] as const,
  documents: (id: number | null) => ['le-arrears', 'documents', id] as const,
};

export const leaveArrearPages = {
  records: (f: LeArrearFilter) => (page: number, size: number) => leaveArrearApi.records(f, page, size),
};

export const leaveArrearReportUrl = leaveArrearApi.reportUrl;
export const leaveArrearDocumentUrl = leaveArrearApi.documentUrl;

export function useArrearSummary() {
  return useQuery({ queryKey: leaveArrearKeys.summary, queryFn: leaveArrearApi.summary });
}

export function useRecordOrders(id: number | null) {
  return useQuery({
    queryKey: leaveArrearKeys.documents(id),
    queryFn: () => leaveArrearApi.documents(id as number),
    enabled: id !== null,
    staleTime: 0,
    gcTime: 0,
  });
}

export function useOrderDocumentTypes() {
  return useQuery({ queryKey: ['document-types'], queryFn: leaveArrearApi.documentTypes });
}

/** Attaches the picked files one by one with the same particulars; the stored list refreshes. */
export function useAttachRecordOrder() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: { id: number; order: Omit<NewLeOrder, 'file'>; files: File[] }) => {
      for (const file of input.files) await leaveArrearApi.attachDocument(input.id, { ...input.order, file });
    },
    onSuccess: (_, input) => queryClient.invalidateQueries({ queryKey: leaveArrearKeys.documents(input.id) }),
  });
}

export function useCancelArrear() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, reason }: { id: number; reason: string }) => leaveArrearApi.cancel(id, reason),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['le-arrears'] }),
  });
}

export function useRevisionOrders(enabled: boolean) {
  return useQuery({ queryKey: leaveArrearKeys.orders, queryFn: leaveArrearApi.orders, enabled });
}

export function useRunArrear() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (r: RunRequest) => leaveArrearApi.run(r),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['le-arrears'] }),
  });
}

export function useSubmitArrears() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ ids, remark }: { ids: number[]; remark: string }) => leaveArrearApi.submit(ids, remark),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['le-arrears'] }),
  });
}
