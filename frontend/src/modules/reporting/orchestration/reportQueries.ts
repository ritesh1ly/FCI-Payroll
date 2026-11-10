'use client';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  type DefinitionFilter,
  type Level,
  type SummaryFilters,
  type VersionProposal,
  reportApi,
} from '../api/reportApi';

const ALL_DEFINITIONS: DefinitionFilter = { family: '', payrollType: '', text: '' };

/** Report use cases (plan 15 phases 15b / 15c). */
export const reportKeys = {
  catalogue: (family: string, text: string) => ['report-catalogue', family, text] as const,
  definitions: (filter: DefinitionFilter = ALL_DEFINITIONS) => ['report-catalogue', 'M-12', filter] as const,
  definitionSummary: () => ['report-catalogue', 'M-12', 'summary'] as const,
  definitionSet: () => ['report-catalogue', 'M-12', 'set'] as const,
  summary: (yymm: string, level: Level, filters: SummaryFilters) =>
    ['component-summary', yymm, level, filters] as const,
  summaryOptions: (yymm: string) => ['component-summary', 'options', yymm] as const,
  versions: (key: string) => ['report-versions', key] as const,
};

/** O-04 Report select (l.3500): every catalogue entry to choose from, read page by page. */
export function useCatalogueChoices() {
  return useQuery({
    queryKey: ['report-catalogue', 'choices'] as const,
    queryFn: async () => {
      const first = await reportApi.catalogue(undefined, undefined, 0, 100);
      const items = [...first.items];
      for (let page = 1; items.length < first.total; page++) {
        const next = await reportApi.catalogue(undefined, undefined, page, 100);
        if (next.items.length === 0) break;
        items.push(...next.items);
      }
      return { ...first, items };
    },
  });
}

export function cataloguePage(family: string, text: string) {
  return (page: number, size: number) =>
    reportApi.catalogue(family || undefined, text || undefined, page, size);
}

/** M-12: the definitions, one page (UI audit S8), narrowed by the drawn filters. */
export function definitionsPage(filter: DefinitionFilter = ALL_DEFINITIONS) {
  return (page: number, size: number) => reportApi.definitions(filter, page, size);
}

/** M-12 (OD-123): the tiles, the catalogue by family and the choices of the two filters. */
export function useDefinitionSummary() {
  return useQuery({
    queryKey: reportKeys.definitionSummary(),
    queryFn: reportApi.definitionSummary,
  });
}

/** M-12: every definition, read page by page, for the rows that name a report by its key. */
export function useDefinitionSet() {
  return useQuery({
    queryKey: reportKeys.definitionSet(),
    queryFn: async () => {
      const first = await reportApi.definitions(ALL_DEFINITIONS, 0, 100);
      const items = [...first.items];
      for (let page = 1; items.length < first.total; page++) {
        const next = await reportApi.definitions(ALL_DEFINITIONS, page, 100);
        if (next.items.length === 0) break;
        items.push(...next.items);
      }
      return items;
    },
  });
}

/**
 * O-04 Component-wise Summary (FRS §23.9, G59): reading it writes report_output, so it never runs by
 * itself or on a filter change: only Generate or Recheck runs it, through refetch(). The key follows the
 * filters, so what is shown (and exported) is always the result of the parameters on screen.
 */
export function useSummary(yymm: string, level: Level, filters: SummaryFilters) {
  return useQuery({
    queryKey: reportKeys.summary(yymm, level, filters),
    queryFn: () => reportApi.summary(yymm, level, filters),
    enabled: false,
  });
}

/** SRS-18-12 (a): the offices, payroll types and categories O-04 offers for the month. */
/** O-04 (SRS l.3501): the latest locked month, the screen's default period. */
export function useLatestLocked() {
  return useQuery({
    queryKey: ['component-summary', 'latest-locked'] as const,
    queryFn: () => reportApi.latestLocked(),
  });
}

export function useSummaryOptions(yymm: string, enabled: boolean) {
  return useQuery({
    queryKey: reportKeys.summaryOptions(yymm),
    queryFn: () => reportApi.summaryOptions(yymm),
    enabled,
    staleTime: 300_000,
  });
}

export function summaryExportUrl(
  yymm: string,
  level: Level,
  filters: SummaryFilters,
  lang: 'en' | 'hi',
): string {
  return reportApi.summaryExportUrl(yymm, level, filters, lang);
}

export function useVersions(key: string | null) {
  return useQuery({
    queryKey: reportKeys.versions(key ?? ''),
    queryFn: () => reportApi.versions(key ?? ''),
    enabled: key !== null,
  });
}

export function usePropose() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ key, body }: { key: string; body: VersionProposal }) => reportApi.propose(key, body),
    onSuccess: (_, { key }) => queryClient.invalidateQueries({ queryKey: reportKeys.versions(key) }),
  });
}
