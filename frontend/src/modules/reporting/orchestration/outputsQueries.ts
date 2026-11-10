'use client';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { outputsApi, type PayslipLevel } from '../api/outputsApi';

/** O-01 and O-11 use cases: every list pages on the server; a generation re-reads the tiles and the register. */
export const outputKeys = {
  payslips: ['o01'] as const,
  register: (yymm: string, level: PayslipLevel, text: string) =>
    [...outputKeys.payslips, 'register', yymm, level, text] as const,
  runs: ['o11-runs'] as const,
};

export const outputPages = {
  register: (yymm: string, level: PayslipLevel, text: string) => (page: number, size: number) =>
    outputsApi.register(yymm, level, text, page, size),
  runs: outputsApi.remittanceRuns,
};

/** OD-123: the Period dropdown, newest first. */
export function usePayslipPeriods() {
  return useQuery({ queryKey: [...outputKeys.payslips, 'periods'], queryFn: outputsApi.periods });
}

export function usePayslipSummary(yymm: string, level: PayslipLevel) {
  return useQuery({
    queryKey: [...outputKeys.payslips, 'summary', yymm, level],
    queryFn: () => outputsApi.summary(yymm, level),
    enabled: yymm !== '',
  });
}

/** One member's month: personal data, never kept in the browser's query cache. */
export function useMemberPayslip(eis: string, yymm: string) {
  return useQuery({
    queryKey: [...outputKeys.payslips, 'member', eis, yymm],
    queryFn: () => outputsApi.member(eis, yymm),
    enabled: eis !== '' && yymm !== '',
    staleTime: 0,
    gcTime: 0,
    retry: false,
  });
}

/** SRS l.3381: the Payroll Maker's bulk generation of a locked month. */
export function useGeneratePayslips() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ yymm, level }: { yymm: string; level: PayslipLevel }) => outputsApi.generate(yymm, level),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: outputKeys.payslips }),
  });
}

/** O-11: the newest month of the session's offices. */
export function useRemittanceOverview() {
  return useQuery({ queryKey: ['o11-overview'], queryFn: outputsApi.remittance });
}
