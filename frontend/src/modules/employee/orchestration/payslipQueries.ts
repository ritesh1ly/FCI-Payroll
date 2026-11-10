'use client';

import { useQuery } from '@tanstack/react-query';
import { payslipApi } from '../api/payslipApi';

/** The member's payslips (plan 16 phase 16e-3; SRS-17-1): locked months only, paged on the server. */
export const payslipKeys = {
  months: ['payslips', 'mine'] as const,
  payslip: (yymm: string) => ['payslips', 'mine', yymm] as const,
  bulkSettings: ['payslips', 'mine', 'bulk-settings'] as const,
};

/** OD-149: the most months one bulk download carries (the setting, never assumed). */
export function usePayslipBulkSettings(enabled: boolean) {
  return useQuery({ queryKey: payslipKeys.bulkSettings, queryFn: payslipApi.bulkSettings, enabled });
}

export const payslipPage = (page: number, size: number) => payslipApi.months(page, size);

export function usePayslip(yymm: string | null) {
  return useQuery({
    queryKey: payslipKeys.payslip(yymm ?? ''),
    queryFn: () => payslipApi.payslip(yymm ?? ''),
    enabled: yymm !== null,
  });
}
