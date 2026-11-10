'use client';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  type BulkOrderInput,
  hrmsChangeApi,
  type HrmsTaskFilter,
  type OrderInput,
  type TaskOrderCopy,
} from '../api/hrmsChangeApi';

/** I-21 use cases (plan 16 phases 16a, 16b): the register pages on the server; a submission re-reads it. */
export const hrmsChangeKeys = {
  list: (filter: HrmsTaskFilter) => ['hrms-changes', filter] as const,
  detail: (id: number) => ['hrms-changes', 'detail', id] as const,
  counts: (filter: HrmsTaskFilter) => ['hrms-changes', 'counts', filter] as const,
};

/** SRS-1-71 / SRS-1-30: the export link of the register the filter selects. */
export function hrmsRegisterExportUrl(filter: HrmsTaskFilter): string {
  return hrmsChangeApi.exportUrl(filter);
}

export function hrmsChangePage(filter: HrmsTaskFilter) {
  return (page: number, size: number) => hrmsChangeApi.list(filter, page, size);
}

export function useHrmsTaskDetail(id: number | null) {
  return useQuery({
    queryKey: hrmsChangeKeys.detail(id ?? 0),
    queryFn: () => hrmsChangeApi.detail(id ?? 0),
    enabled: id !== null,
  });
}

/**
 * SRS-1-28 / SRS-G-31: the Maker sends the task; with an individual order its copy is attached
 * first (the server refuses an individual order without one).
 */
export function useSubmitHrmsTask() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: { id: number; body: OrderInput; copy?: TaskOrderCopy }) => {
      if (input.copy) await hrmsChangeApi.attach(input.id, input.copy);
      return hrmsChangeApi.submit(input.id, input.body);
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['hrms-changes'] }),
  });
}

/** The order copies of a task (the Checker compares the value with the order). */
export function useTaskDocuments(id: number, enabled = true) {
  return useQuery({
    queryKey: ['hrms-changes', 'documents', id],
    queryFn: () => hrmsChangeApi.documents(id),
    enabled,
  });
}

/** SRS-G-31: the Maker attaches an order copy to a task from the order card. */
export function useAttachTaskOrder() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: { id: number; copy: TaskOrderCopy }) => hrmsChangeApi.attach(input.id, input.copy),
    onSuccess: (_, input) =>
      queryClient.invalidateQueries({ queryKey: ['hrms-changes', 'documents', input.id] }),
  });
}

export function useRegisterCounts(filter: HrmsTaskFilter) {
  return useQuery({ queryKey: hrmsChangeKeys.counts(filter), queryFn: () => hrmsChangeApi.counts(filter) });
}

export function useCreateBulkOrder() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: { body: BulkOrderInput; copy: TaskOrderCopy }) => {
      const stored = await hrmsChangeApi.storeOrderCopy(input.copy);
      return hrmsChangeApi.createOrder({
        ...input.body,
        copyAttachmentId: stored.attachmentId,
        copyProvisionalKey: stored.provisionalKey,
      });
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['hrms-changes'] }),
  });
}

/** The link of one order copy of a task (View order; served as an attachment). */
export function taskDocumentUrl(id: number, fileId: number): string {
  return hrmsChangeApi.documentUrl(id, fileId);
}
