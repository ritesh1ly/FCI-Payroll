'use client';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useGoverned } from '@/modules/workflow';
import {
  mastersApi,
  type OrderParticulars,
  type ProposedParameter,
  type RateFilter,
  type RateLine,
  type RateScreen,
} from '../api/mastersApi';

/**
 * Use cases of the dynamic master screens (M-04, M-22, M-23, M-24): server state through TanStack
 * Query; every proposal is a maker-checker submission (the OTP step if the action requires one) and
 * changes nothing in force until it is approved.
 */
export const masterKeys = {
  rates: (screen: RateScreen) => ['masters', 'rates', screen] as const,
  rateHistory: (screen: RateScreen, code: string) => ['masters', 'rates', screen, code] as const,
  dearness: ['masters', 'rates', 'M-04', '#dearness'] as const,
  cityClasses: ['masters', 'rates', 'M-04', '#cities'] as const,
  staffRateOrders: (id: number) => ['masters', 'staff-rate-orders', id] as const,
  specialRates: (filter: RateFilter) => ['masters', 'rates', 'M-22', '#filtered', filter] as const,
  accounting: ['masters', 'mappings', '#masters'] as const,
  accountingRecords: (code: string) => ['masters', 'mappings', '#records', code] as const,
  parameters: ['masters', 'parameters'] as const,
  parameterHistory: (code: string) => ['masters', 'parameters', code] as const,
  mappings: ['masters', 'mappings'] as const,
  mappingHistory: (payCode: number) => ['masters', 'mappings', payCode] as const,
};

export function useProposeRate(screen: RateScreen) {
  const queryClient = useQueryClient();
  const run = useGoverned();
  return useMutation({
    mutationFn: (input: {
      code: string;
      effectiveFrom: string;
      lines: RateLine[];
      reason: string;
      circularOrderId?: number | null;
      computedOn?: string | null;
    }) =>
      run((otp) =>
        mastersApi.proposeRate(
          screen,
          input.code,
          {
            effectiveFrom: input.effectiveFrom,
            lines: input.lines,
            reason: input.reason,
            circularOrderId: input.circularOrderId ?? null,
            ...(input.computedOn ? { computedOn: input.computedOn } : {}),
          },
          otp,
        ),
      ),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['masters', 'rates', screen] }),
  });
}

export function useProposeOtherParameter() {
  const queryClient = useQueryClient();
  const run = useGoverned();
  return useMutation({
    mutationFn: (input: { code: string; body: ProposedParameter }) =>
      run((otp) => mastersApi.proposeParameter(input.code, input.body, otp)),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['masters', 'parameters'] }),
  });
}

export function useProposeMapping() {
  const queryClient = useQueryClient();
  const run = useGoverned();
  return useMutation({
    mutationFn: (input: { payCode: number; effectiveFrom: string; naturalAccount: string; reason: string }) =>
      run((otp) =>
        mastersApi.proposeMapping(
          input.payCode,
          { effectiveFrom: input.effectiveFrom, naturalAccount: input.naturalAccount, reason: input.reason },
          otp,
        ),
      ),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['masters', 'mappings'] }),
  });
}

/** The order copies of an M-04 version (order card). */
export function useStaffRateOrders(id: number | null) {
  return useQuery({
    queryKey: masterKeys.staffRateOrders(id ?? 0),
    queryFn: () => mastersApi.staffRateOrders(id ?? 0),
    enabled: id !== null,
  });
}

export function useAttachStaffRateOrder() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: { id: number; order: OrderParticulars }) =>
      mastersApi.attachStaffRateOrder(input.id, input.order),
    onSuccess: (_d, input) =>
      queryClient.invalidateQueries({ queryKey: masterKeys.staffRateOrders(input.id) }),
  });
}

export function useRateDocumentTypes() {
  return useQuery({ queryKey: ['document-types'], queryFn: mastersApi.documentTypes });
}

export const specialRatesExportUrl = mastersApi.specialRatesExportUrl;
export const staffRateOrderUrl = mastersApi.staffRateOrderUrl;

/** M-04 / M-22 / M-23 / M-24: the page fetchers of the paged lists (docs/plan/X-pagination.md). */
export const masterPages = {
  dearness: mastersApi.dearness,
  cityClasses: mastersApi.cityClasses,
  specialRates: (filter: RateFilter) => (page: number, size: number) =>
    mastersApi.specialRates(filter, page, size),
  accounting: mastersApi.accounting,
  accountingRecords: (code: string) => (page: number, size: number) =>
    mastersApi.accountingRecords(code, page, size),
  rates: (screen: RateScreen) => (page: number, size: number) => mastersApi.rates(screen, page, size),
  rateHistory: (screen: RateScreen, code: string) => (page: number, size: number) =>
    mastersApi.rateHistory(screen, code, page, size),
  parameters: mastersApi.parameters,
  parameterHistory: (code: string) => (page: number, size: number) =>
    mastersApi.parameterHistory(code, page, size),
  mappings: mastersApi.mappings,
  mappingHistory: (payCode: number) => (page: number, size: number) =>
    mastersApi.mappingHistory(payCode, page, size),
};
