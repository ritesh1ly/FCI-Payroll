'use client';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { type ComponentFilter, type ComponentInput, componentsApi } from '../api/componentsApi';

/** M-07 use cases (plan 06 phase 06d): the catalogue pages on the server; a change re-reads it. */
export const componentKeys = {
  list: (f: ComponentFilter) => ['pay-components', f] as const,
  usages: (code: number) => ['pay-components', 'usages', code] as const,
};

export const componentExportUrl = componentsApi.exportUrl;

export function componentPage(f: ComponentFilter) {
  return (page: number, size: number) => componentsApi.list(f, page, size);
}

export function useComponentUsages(code: number | null) {
  return useQuery({
    queryKey: componentKeys.usages(code ?? 0),
    queryFn: () => componentsApi.usages(code ?? 0),
    enabled: code !== null,
  });
}

export function useComponentActions() {
  const client = useQueryClient();
  const refresh = () => client.invalidateQueries({ queryKey: ['pay-components'] });
  return {
    define: useMutation({ mutationFn: (b: ComponentInput) => componentsApi.define(b), onSuccess: refresh }),
    revise: useMutation({ mutationFn: (b: ComponentInput) => componentsApi.revise(b), onSuccess: refresh }),
    retire: useMutation({
      mutationFn: (v: { code: number; lastDay: string; reason: string }) =>
        componentsApi.retire(v.code, v.lastDay, v.reason),
      onSuccess: refresh,
    }),
  };
}
