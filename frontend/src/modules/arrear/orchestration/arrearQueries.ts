'use client';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { arrearApi, type EventFilter, type NewEventOrder, type NewRetroEvent } from '../api/arrearApi';

/**
 * I-08 use cases (plan 13 phase 13a). An event is raised by the arrear job or a back-dated event;
 * the Payroll Maker reviews its differentials and submits it with a remark; the Payroll Checker
 * approves it in W-01 and it merges into the next open cycle.
 */
export const arrearKeys = {
  all: ['arrear-events'] as const,
  events: (f: EventFilter) => ['arrear-events', f.status ?? '', f.eventCode ?? ''] as const,
  details: (id: number) => ['arrear-events', 'details', id] as const,
};

export const arrearPages = {
  events: (f: EventFilter) => (page: number, size: number) => arrearApi.events(f, page, size),
  details: (id: number) => (page: number, size: number) => arrearApi.details(id, page, size),
};

export const arrearRegisterUrl = arrearApi.registerUrl;

export function useRecomputeEvent() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => arrearApi.recompute(id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['arrear-events'] }),
  });
}

export function useRequestException() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, reason, circularId }: { id: number; reason: string; circularId: number }) =>
      arrearApi.requestException(id, reason, circularId),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['arrear-events'] }),
  });
}

/** SRS-21-6: the Payroll Maker asks for the controlled undo of an approved event. */
export function useRequestUndo() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, period, reason }: { id: number; period: string; reason: string }) =>
      arrearApi.requestUndo(id, period, reason),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['arrear-events'] }),
  });
}

export function useSubmitEvent() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, remark }: { id: number; remark: string }) => arrearApi.submit(id, remark),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['arrear-events'] }),
  });
}

/** I-08 as drawn (OD-121): the tiles, the retro controls, the event types and one event's periods. */
export function useArrearSummary() {
  return useQuery({ queryKey: [...arrearKeys.all, 'summary'], queryFn: arrearApi.summary });
}

export function useRetroControls() {
  return useQuery({ queryKey: [...arrearKeys.all, 'controls'], queryFn: arrearApi.controls });
}

export function useArrearEventTypes(enabled: boolean) {
  return useQuery({ queryKey: [...arrearKeys.all, 'types'], queryFn: arrearApi.types, enabled });
}

export function useEventPeriods(id: number | null) {
  return useQuery({
    queryKey: [...arrearKeys.all, 'periods', id],
    queryFn: () => arrearApi.periods(id as number),
    enabled: id !== null,
  });
}

/** The Maker's new retro event: the list and the tiles refresh once it is raised. */
export function useCreateRetroEvent() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (event: NewRetroEvent) => arrearApi.create(event),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: arrearKeys.all }),
  });
}

/** The orders stored with the selected event (personal data: never kept in the cache). */
export function useEventOrders(id: number | null) {
  return useQuery({
    queryKey: [...arrearKeys.all, 'orders', id],
    queryFn: () => arrearApi.orders(id as number),
    enabled: id !== null,
    staleTime: 0,
    gcTime: 0,
  });
}

export function useOrderDocumentTypes() {
  return useQuery({ queryKey: ['document-types'], queryFn: arrearApi.documentTypes });
}

/** Attaches the picked files one by one with the same particulars; the stored list refreshes. */
export function useAttachEventOrder() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: { id: number; order: Omit<NewEventOrder, 'file'>; files: File[] }) => {
      for (const file of input.files) await arrearApi.attachOrder(input.id, { ...input.order, file });
    },
    onSuccess: (_, input) =>
      queryClient.invalidateQueries({ queryKey: [...arrearKeys.all, 'orders', input.id] }),
  });
}

/** The address of one stored order of an event (a download, never rendered inline). */
export function eventOrderUrl(id: number, fileId: number): string {
  return arrearApi.orderUrl(id, fileId);
}
