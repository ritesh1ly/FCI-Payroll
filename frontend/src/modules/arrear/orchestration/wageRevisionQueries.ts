'use client';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { type DefinitionRequest, wageRevisionApi } from '../api/wageRevisionApi';

/**
 * I-25 use cases (plan 13 phase 13e). The Rule Administrator at Headquarters defines, simulates and
 * submits; the Payroll Checker at Headquarters approves in W-01 with the OTP.
 */
export const wageRevisionKeys = {
  list: ['wage-revisions'] as const,
  detail: (id: number) => ['wage-revisions', 'detail', id] as const,
  members: (id: number) => ['wage-revisions', 'members', id] as const,
  orders: ['wage-revisions', 'orders'] as const,
  board: ['wage-revisions', 'board'] as const,
  offices: (id: number) => ['wage-revisions', 'offices', id] as const,
};

export const wageRevisionPages = {
  list: (page: number, size: number) => wageRevisionApi.list(page, size),
  members: (id: number) => (page: number, size: number) => wageRevisionApi.members(id, page, size),
  offices: (id: number) => (page: number, size: number) => wageRevisionApi.offices(id, page, size),
};

export const wageRevisionMembersUrl = wageRevisionApi.membersUrl;
export const wageRevisionStatusUrl = wageRevisionApi.statusUrl;

export function useRevision(id: number | null) {
  return useQuery({
    queryKey: wageRevisionKeys.detail(id ?? 0),
    queryFn: () => wageRevisionApi.detail(id as number),
    enabled: id !== null,
  });
}

export function useRevisionBoard() {
  return useQuery({ queryKey: wageRevisionKeys.board, queryFn: wageRevisionApi.board });
}

export function useRevisionOrders(enabled: boolean) {
  return useQuery({ queryKey: wageRevisionKeys.orders, queryFn: wageRevisionApi.orders, enabled });
}

/** The I-25 actions, each refreshing the revisions. */
export function useRevisionAction() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (run: () => Promise<unknown>) => run(),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['wage-revisions'] }),
  });
}

export const wageRevisionCalls = {
  define: (d: DefinitionRequest) => wageRevisionApi.define(d),
  simulate: (id: number) => wageRevisionApi.simulate(id),
  submit: (id: number, remark: string) => wageRevisionApi.submit(id, remark),
  fix: (id: number) => wageRevisionApi.fix(id),
};
