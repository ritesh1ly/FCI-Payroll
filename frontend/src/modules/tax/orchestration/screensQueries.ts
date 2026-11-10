'use client';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { screensApi } from '../api/screensApi';

/**
 * Use cases of the drawn M-17, I-07, C-07, C-10 and C-11 (OD-121). Personal figures are never kept
 * in the cache (staleTime 0, gcTime 0), as on the older reads of these screens.
 */
const PERSONAL = { staleTime: 0, gcTime: 0 } as const;

export const screenKeys = {
  outputs: ['tax', 'screens', 'outputs'] as const,
  declarations: (finYear: string) => ['tax', 'declarations', 'summary', finYear] as const,
  declarationMembers: (finYear: string) => ['tax', 'declarations', 'members', finYear] as const,
  roundOrders: (id: number) => ['tax', 'declarations', 'orders', id] as const,
  computation: ['tax', 'screens', 'computation'] as const,
  eps: ['tax', 'screens', 'eps'] as const,
  taxLines: ['tax', 'screens', 'tax-lines'] as const,
  nps: ['tax', 'screens', 'nps'] as const,
  certificates: (finYear: string) => ['tax', 'form16', 'summary', finYear] as const,
  certificateLines: (finYear: string, status: string, office: string) =>
    ['tax', 'form16', 'lines', finYear, status, office] as const,
  returns: (finYear: string, quarter: string) => ['tax', 'returns', 'summary', finYear, quarter] as const,
  returnLines: (office: string) => ['tax', 'returns', 'lines', office] as const,
};

/** The paged reads of the drawn tables (SRS-NF-3). */
export const screenPages = {
  declarationMembers: (finYear: string) => (page: number, size: number) =>
    screensApi.declarationMembers(finYear, page, size),
  eps: (page: number, size: number) => screensApi.epsLines(page, size),
  taxLines: (page: number, size: number) => screensApi.taxLines(page, size),
  nps: (page: number, size: number) => screensApi.npsLines(page, size),
  certificateLines: (finYear: string, status: string, office: string) => (page: number, size: number) =>
    screensApi.certificateLines(finYear, status, office, page, size),
  returnLines: (office: string) => (page: number, size: number) => screensApi.returnLines(office, page, size),
};

export function useOutputSummary() {
  return useQuery({ queryKey: screenKeys.outputs, queryFn: screensApi.outputSummary });
}

export function useDeclarationSummary(finYear: string) {
  return useQuery({
    queryKey: screenKeys.declarations(finYear),
    queryFn: () => screensApi.declarationSummary(finYear),
    ...PERSONAL,
  });
}

/** I-07: the order copies of the selected round; nothing is read while none is selected. */
export function useRoundOrders(id: number | null) {
  return useQuery({
    queryKey: screenKeys.roundOrders(id ?? 0),
    queryFn: () => screensApi.roundOrders(id ?? 0),
    enabled: id !== null,
    ...PERSONAL,
  });
}

export function useAttachRoundOrder() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({
      id,
      order,
    }: {
      id: number;
      order: {
        documentType: string;
        reference: string;
        orderDate: string;
        issuingOffice: string;
        file: File;
      };
    }) => screensApi.attachRoundOrder(id, order),
    onSuccess: () => client.invalidateQueries({ queryKey: ['tax', 'declarations', 'orders'] }),
  });
}

/** The link of an order copy (the read is logged on the server). */
export const roundOrderUrl = screensApi.roundOrderUrl;

export function useComputationSummary() {
  return useQuery({
    queryKey: screenKeys.computation,
    queryFn: screensApi.computationSummary,
    ...PERSONAL,
  });
}

export function useCertificateSummary(finYear: string) {
  return useQuery({
    queryKey: screenKeys.certificates(finYear),
    queryFn: () => screensApi.certificateSummary(finYear),
    ...PERSONAL,
  });
}

export function useReturnSummary(finYear: string, quarter: string) {
  return useQuery({
    queryKey: screenKeys.returns(finYear, quarter),
    queryFn: () => screensApi.returnSummary(finYear, quarter),
    ...PERSONAL,
  });
}
