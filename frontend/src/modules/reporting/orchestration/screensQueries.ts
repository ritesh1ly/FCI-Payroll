'use client';

import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { api } from '@/shared/api';
import { type PageResult } from '@/shared/ui';
import { type LabourEngine, screensApi } from '../api/screensApi';

interface LabourChoices {
  depots: { id: number; label: string }[];
  gangs: { id: number; code: string }[];
}

/**
 * The O-03 filters: the depots of the session and the gangs of the chosen depot. O-03 has no read of its own for them
 * (M-02 / M-09 VIEW guard the labour masters): a role without those reads sees only the "All" choices.
 */
export function useOptionsOfLabour(depot: string) {
  return useQuery({
    queryKey: ['o03-options', depot],
    queryFn: async (): Promise<LabourChoices> => {
      const options = await api<{ depots?: { id: number; code: string; name: string }[] }>(
        '/v1/labour/options',
      ).catch(() => null);
      const gangs = await api<PageResult<{ gangId: number; gangCode: string }>>(
        `/v1/labour/gangs?${depot ? `depot=${depot}&` : ''}size=100`,
      ).catch(() => null);
      return {
        depots: (options?.depots ?? []).map((d) => ({ id: d.id, label: `${d.code} - ${d.name}` })),
        gangs: (gangs?.items ?? []).map((g) => ({ id: g.gangId, code: g.gangCode })),
      };
    },
  });
}

/** The pages of the O-03 and O-06 tables. */
export const screenPages = {
  cpfEseva: screensApi.cpfEseva,
  cpfReconciliation: screensApi.cpfReconciliation,
  labourCatalogue: (annexure: boolean, engine: LabourEngine) => (page: number, size: number) =>
    screensApi.labourCatalogue(annexure, engine, page, size),
  invoiceRegister: (yymm: string, unit: number | null, type: string) => (page: number, size: number) =>
    screensApi.invoiceRegister(yymm, unit, type, page, size),
  invoiceLines: (yymm: string, unit: number | null) => (page: number, size: number) =>
    screensApi.invoiceLines(yymm, unit, page, size),
};

/** O-07: whether the month and the month before are Provisional or Final (BR-CPF-036). */
export function useCpfMonths(yymm: string) {
  return useQuery({
    queryKey: ['cpf-months', yymm],
    queryFn: () => screensApi.cpfMonths(yymm),
    enabled: yymm !== '',
  });
}

/** O-06: the overview of the period chosen, or of the latest with invoices. */
export function useInvoiceOverview(yymm: string, unit: number | null) {
  return useQuery({
    queryKey: ['invoice-overview', yymm, unit],
    queryFn: () => screensApi.invoiceOverview(yymm, unit),
    // The filters stay on screen while another period or unit loads.
    placeholderData: keepPreviousData,
  });
}
