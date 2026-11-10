'use client';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { hrmsUpdateApi, type OrderUpload, type UpdateInput } from '../api/hrmsUpdateApi';

/** HRMS Update Request use cases (plan 16 phase 16c; SRS-1-70): the lists page on the server. */
export const hrmsUpdateKeys = {
  fields: ['hrms-update-requests', 'fields'] as const,
  list: ['hrms-update-requests', 'unit'] as const,
  mine: ['hrms-update-requests', 'mine'] as const,
};

export const hrmsUpdatePage = (page: number, size: number) => hrmsUpdateApi.list(page, size);
export const myHrmsUpdatePage = (page: number, size: number) => hrmsUpdateApi.mine(page, size);

export function useUpdateFields(enabled = true) {
  return useQuery({ queryKey: hrmsUpdateKeys.fields, queryFn: hrmsUpdateApi.fields, enabled });
}

/** The Maker's request (I-21) or the employee's own (eSeva); either re-reads the lists and the I-21 tasks. */
export function useRaiseUpdate(mine: boolean) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: UpdateInput) => (mine ? hrmsUpdateApi.raiseMine(body) : hrmsUpdateApi.raise(body)),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['hrms-update-requests'] });
      void queryClient.invalidateQueries({ queryKey: ['hrms-changes'] });
    },
  });
}

export function useRefetchUpdate() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => hrmsUpdateApi.refetch(id),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['hrms-update-requests'] });
      void queryClient.invalidateQueries({ queryKey: ['hrms-changes'] });
    },
  });
}

/** The download address of an order copy. */
export const orderCopyUrl = (id: number, mine: boolean, fileId: number) =>
  hrmsUpdateApi.documentUrl(id, mine, fileId);

/** SRS-1-70: the order copies of one request (the member's own, or one of the desk's unit). */
export function useOrderCopies(id: number, mine: boolean) {
  return useQuery({
    queryKey: ['hrms-update-requests', 'documents', mine, id],
    queryFn: () => hrmsUpdateApi.documents(id, mine),
  });
}

export function useAttachOrder(id: number, mine: boolean) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (order: OrderUpload) => hrmsUpdateApi.attach(id, mine, order),
    onSuccess: () =>
      queryClient.invalidateQueries({ queryKey: ['hrms-update-requests', 'documents', mine, id] }),
  });
}
