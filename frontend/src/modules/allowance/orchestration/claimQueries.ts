'use client';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  type ClaimDocumentInput,
  type ClaimFilter,
  type ClaimInput,
  type ClaimOrderInput,
  claimsApi,
  type IogaInput,
} from '../api/claimsApi';

/** I-03 / I-16 use cases (plan 10): the registers page on the server; a change re-reads them. */
export const claimKeys = {
  register: (f: ClaimFilter) => ['claims', 'register', f] as const,
  detail: (id: number) => ['claims', 'detail', id] as const,
  pending: () => ['claims', 'pending-hrms'] as const,
  medical: (eis: string) => ['claims', 'medical', eis] as const,
  cafeteria: (eis: string, finYear = '') => ['cafeteria', eis, finYear] as const,
  counts: () => ['claims', 'counts'] as const,
  leases: () => ['claims', 'leases'] as const,
};

export const claimPage = (f: ClaimFilter) => (page: number, size: number) =>
  claimsApi.register(f, page, size);
export const pendingPage = () => (page: number, size: number) => claimsApi.pendingHrms(page, size);
export const cafeteriaPage =
  (eis: string, finYear = '') =>
  (page: number, size: number) =>
    claimsApi.cafeteria(eis || undefined, page, size, finYear || undefined);

export const leasePage = claimsApi.leases;
export const cafeteriaExportUrl = claimsApi.cafeteriaExportUrl;

export const claimDocumentUrl = claimsApi.documentUrl;
export const claimRegisterUrl = claimsApi.registerUrl;
export const medicalRegisterUrl = claimsApi.medicalRegisterUrl;

export function useLabourClaimTypes(enabled: boolean) {
  return useQuery({
    queryKey: ['claims', 'types', 'LABOUR'],
    queryFn: () => claimsApi.types('LABOUR'),
    enabled,
  });
}

export function useClaimCounts() {
  return useQuery({ queryKey: claimKeys.counts(), queryFn: claimsApi.counts });
}

/** The newest claims with a payment, for the invoice card (SRS-G-37). */
export function useInvoicedClaims() {
  return useQuery({
    queryKey: ['claims', 'invoiced'],
    queryFn: () => claimsApi.register({ status: 'PAID' }, 0, 20),
  });
}

/** The first page of the register, for the drawn order card. */
export function useFirstClaims(filter: ClaimFilter, enabled: boolean) {
  return useQuery({
    queryKey: [...claimKeys.register(filter), 'first'],
    queryFn: () => claimsApi.register(filter, 0, 20),
    enabled,
  });
}

export function useClaimDetail(id: number | null) {
  return useQuery({
    queryKey: claimKeys.detail(id ?? 0),
    queryFn: () => claimsApi.detail(id ?? 0),
    enabled: id !== null,
  });
}

export function useMedicalClaims(eis: string) {
  return useQuery({
    queryKey: claimKeys.medical(eis),
    queryFn: () => claimsApi.medical(eis),
    enabled: eis !== '',
  });
}

export function useClaimActions() {
  const client = useQueryClient();
  const refresh = () => client.invalidateQueries({ queryKey: ['claims'] });
  return {
    enter: useMutation({
      mutationFn: (v: { body: ClaimInput; documents: ClaimDocumentInput[] }) =>
        claimsApi.enter(v.body, v.documents),
      onSuccess: refresh,
    }),
    validate: useMutation({
      mutationFn: (v: { id: number; reason: string }) => claimsApi.validate(v.id, v.reason),
      onSuccess: refresh,
    }),
    confirmTaxable: useMutation({
      mutationFn: (v: { id: number; taxable: boolean }) => claimsApi.confirmTaxable(v.id, v.taxable),
      onSuccess: refresh,
    }),
    attach: useMutation({
      mutationFn: async (v: { id: number; order: Omit<ClaimOrderInput, 'file'>; files: File[] }) => {
        for (const file of v.files) await claimsApi.attach(v.id, { ...v.order, file });
      },
      onSuccess: refresh,
    }),
    ioga: useMutation({
      mutationFn: (v: { id: number; body: IogaInput }) => claimsApi.ioga(v.id, v.body),
      onSuccess: refresh,
    }),
  };
}
