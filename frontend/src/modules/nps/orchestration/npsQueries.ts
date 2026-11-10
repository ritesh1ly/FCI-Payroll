'use client';

import { useQuery } from '@tanstack/react-query';
import { type NpsFilter, npsApi } from '../api/npsApi';

/** I-14 use cases (plan 18 phase 18c): the register pages on the server; nothing is written here. */
export const npsKeys = {
  list: (filter: NpsFilter) => ['nps-deductions', filter] as const,
  counts: (yymm: string) => ['nps-deductions', 'counts', yymm] as const,
  summary: (yymm: string) => ['nps-deductions', 'summary', yymm] as const,
};

/** The I-14 tiles and the month they show. */
export function useNpsSummary(yymm: string) {
  return useQuery({ queryKey: npsKeys.summary(yymm), queryFn: () => npsApi.summary(yymm) });
}

export function npsPage(filter: NpsFilter) {
  return (page: number, size: number) => npsApi.list(filter, page, size);
}

export function useNpsCounts(yymm: string, enabled: boolean) {
  return useQuery({ queryKey: npsKeys.counts(yymm), queryFn: () => npsApi.counts(yymm), enabled });
}

/** I-14 Export: the register's link for the filter. */
export function npsExportUrl(filter: NpsFilter): string {
  return npsApi.exportUrl(filter);
}

/** SRS-9-6: the NPS Deduction Report's link. */
export function npsReportUrl(from: string, to: string): string {
  return npsApi.reportUrl(from, to);
}
