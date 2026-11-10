'use client';

import { PERSONAL_DATA } from '@/shared/api';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { TaskOrderCopy } from '../api/hrmsChangeApi';
import { registerApi } from '../api/registerApi';

/** The Employee screens' reads as drawn (OD-121): lists page on the server, within the data scope. */
export const registerKeys = {
  offices: ['employee-offices'] as const,
  memberIds: (eis: string) => ['employee', eis, 'member-ids'] as const,
  staffNumbers: (eis: string) => ['employee', eis, 'staff-numbers'] as const,
  extras: (eis: string) => ['employee', eis, 'hrms-record'] as const,
  orders: (eis: string) => ['employee', eis, 'documents'] as const,
  events: ['received-events'] as const,
  transfers: ['transfers', 'register'] as const,
  transferSummary: ['transfers', 'summary'] as const,
  taskSummary: ['hrms-changes', 'summary'] as const,
  register: (q: string) => ['hrms-changes', 'register', q] as const,
  fieldGroups: ['hrms-changes', 'field-groups'] as const,
  dobTasks: ['hrms-changes', 'dob-tasks'] as const,
  orderList: ['hrms-changes', 'orders'] as const,
  orderTasks: (id: number) => ['hrms-changes', 'orders', id] as const,
};

export const registerPages = {
  memberIds: (eis: string) => (page: number, size: number) => registerApi.memberIds(eis, page, size),
  staffNumbers: (eis: string) => (page: number, size: number) => registerApi.staffNumbers(eis, page, size),
  events: registerApi.events,
  transfers: registerApi.transfers,
  register: (q: string) => (page: number, size: number) => registerApi.register(q, page, size),
  dobTasks: registerApi.dobTasks,
  orderTasks: (id: number) => (page: number, size: number) => registerApi.orderTasks(id, page, size),
};

export const registerUrls = {
  masterExport: registerApi.masterExportUrl,
  order: registerApi.orderUrl,
  eventsExport: registerApi.eventsExportUrl,
  transfersExport: registerApi.transfersExportUrl,
};

export function useOffices() {
  return useQuery({ queryKey: registerKeys.offices, queryFn: registerApi.offices, staleTime: 300_000 });
}

/** Record Detail: the further fields and what HRMS holds now (a real call through the gateway). */
export function useRecordExtras(eis: string) {
  return useQuery({
    ...PERSONAL_DATA,
    queryKey: registerKeys.extras(eis),
    queryFn: () => registerApi.extras(eis),
    retry: false,
  });
}

export function useEmployeeOrders(eis: string | null) {
  return useQuery({
    ...PERSONAL_DATA,
    queryKey: registerKeys.orders(eis ?? ''),
    queryFn: () => registerApi.orders(eis ?? ''),
    enabled: eis !== null,
  });
}

/** SRS-G-30: Establishment / HR stores an order with the record. */
export function useAttachEmployeeOrder(eis: string | null) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (copy: TaskOrderCopy) => registerApi.attachOrder(eis ?? '', copy),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: registerKeys.orders(eis ?? '') }),
  });
}

export function useTransferSummary() {
  return useQuery({ queryKey: registerKeys.transferSummary, queryFn: registerApi.transferSummary });
}

export function useTaskSummary() {
  return useQuery({ queryKey: registerKeys.taskSummary, queryFn: registerApi.taskSummary });
}

export function useFieldGroups(enabled: boolean) {
  return useQuery({ queryKey: registerKeys.fieldGroups, queryFn: registerApi.fieldGroups, enabled });
}

/** The newest DOB task (the DOB tab's card). */
export function useFirstDobTask(enabled: boolean) {
  return useQuery({
    queryKey: [...registerKeys.dobTasks, 'first'],
    queryFn: () => registerApi.dobTasks(0, 1),
    enabled,
  });
}

/** The newest bulk order (the bulk tab's card and the Checker's Approve bulk order). */
export function useLatestOrder() {
  return useQuery({
    queryKey: [...registerKeys.orderList, 'latest'],
    queryFn: () => registerApi.orderList(0),
  });
}
