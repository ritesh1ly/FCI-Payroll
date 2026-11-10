'use client';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { type MemberOrderInput, type ScreenFilter, labourScreenApi } from '../api/labourScreenApi';
import { labourApi } from '../api/labourApi';

/** The reads of M-02, M-05, M-06 and M-09 as Wireframes v6.1 draws them (OD-121). */
export const screenKeys = {
  of: (name: string, ...rest: unknown[]) => ['labour-screen', name, ...rest] as const,
};

export const memberLinesPage = (filter: ScreenFilter) => (page: number, size: number) =>
  labourScreenApi.members(filter, page, size);
export const attachmentsPage = (type: string) => (page: number, size: number) =>
  labourScreenApi.attachments(type, page, size);
export const gangLinesPage = (filter: ScreenFilter) => (page: number, size: number) =>
  labourScreenApi.gangs(filter, page, size);
export const strengthsPage = (filter: ScreenFilter) => (page: number, size: number) =>
  labourScreenApi.strengths(filter, page, size);
export const normLinesPage = (filter: ScreenFilter) => (page: number, size: number) =>
  labourScreenApi.norms(filter, page, size);
export const asorPage = (page: number, size: number) => labourScreenApi.asor(page, size);
export const sorPage = (page: number, size: number) => labourScreenApi.sor(page, size);

export const { membersExportUrl, gangsExportUrl, normsExportUrl, ratesExportUrl, orderUrl } = labourScreenApi;

/** The record the order card and History open on: the first of the list until a row is viewed. */
export function useFirstMember(filter: ScreenFilter) {
  return useQuery({
    queryKey: screenKeys.of('first-member', filter),
    queryFn: () => labourScreenApi.members(filter, 0),
  });
}

export function useDocumentTypes() {
  return useQuery({ queryKey: ['document-types'], queryFn: labourScreenApi.documentTypes });
}

export function useLightDuty(type: string) {
  return useQuery({
    queryKey: screenKeys.of('light-duty', type),
    queryFn: () => labourScreenApi.lightDuty(type),
  });
}

export function useMemberOrders(employeeId: number | null) {
  return useQuery({
    queryKey: screenKeys.of('member-orders', employeeId),
    queryFn: () => labourScreenApi.orders(employeeId ?? 0),
    enabled: employeeId !== null,
  });
}

export function useAttachMemberOrder(employeeId: number | null) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (order: MemberOrderInput) => labourScreenApi.attachOrder(employeeId ?? 0, order),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: screenKeys.of('member-orders', employeeId) }),
  });
}

/** The gangs the composition card, History and Membership open on (the first page of the register). */
export function useFirstGangs(filter: ScreenFilter) {
  return useQuery({
    queryKey: screenKeys.of('first-gangs', filter),
    queryFn: () => labourScreenApi.gangs(filter, 0, 20),
  });
}

export function useGangSummary(filter: ScreenFilter) {
  return useQuery({
    queryKey: screenKeys.of('gang-summary', filter),
    queryFn: () => labourScreenApi.gangSummary(filter),
  });
}

export function useNormSummary() {
  return useQuery({ queryKey: screenKeys.of('norm-summary'), queryFn: labourScreenApi.normSummary });
}

/** The set whose bands the slab table shows: the first of the filtered register. */
export function useFirstNorm(filter: ScreenFilter) {
  return useQuery({
    queryKey: screenKeys.of('first-norm', filter),
    queryFn: () => labourScreenApi.norms(filter, 0),
  });
}

export function useSlabs(normId: number | null) {
  return useQuery({
    queryKey: screenKeys.of('slabs', normId),
    queryFn: () => labourScreenApi.slabs(normId ?? 0),
    enabled: normId !== null,
  });
}

export function useMinWages(enabled: boolean) {
  return useQuery({
    queryKey: screenKeys.of('min-wages'),
    queryFn: labourScreenApi.minWages,
    enabled,
  });
}

/** The newest rate sets of an engine, enough to find the one in force. */
export function useRateSets(payrollType: string, enabled: boolean) {
  return useQuery({
    queryKey: screenKeys.of('rate-sets', payrollType),
    queryFn: () => labourApi.rateSets(payrollType, 0, 20),
    enabled,
  });
}
