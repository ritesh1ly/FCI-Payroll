'use client';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useGoverned } from '@/modules/workflow';
import {
  taxApi,
  type NewDeclaration,
  type NewSavingsEntry,
  type SectionProposal,
  type StateProposal,
} from '../api/taxApi';

/**
 * Use cases of the income-tax screens (Module 08): server state through TanStack Query; every
 * change is a maker-checker submission and changes nothing until the Rule Approver approves it.
 */
export const taxKeys = {
  sections: ['tax', 'sections'] as const,
  sectionsPending: ['tax', 'sections', 'pending'] as const,
  states: ['tax', 'pt-states'] as const,
  statesPending: ['tax', 'pt-states', 'pending'] as const,
  mine: ['tax', 'declarations', 'mine'] as const,
  declarations: (finYear: string, status: string) =>
    ['tax', 'declarations', 'list', finYear, status] as const,
  declaration: (id: number) => ['tax', 'declarations', 'one', id] as const,
  savings: (finYear: string, status: string) => ['tax', 'savings', 'list', finYear, status] as const,
  memberSavings: (eis: string, finYear: string) => ['tax', 'savings', 'member', eis, finYear] as const,
};

/** C-01: the TDS cases flagged for the Checker's review of the cycle, a page at a time. */
export function flaggedTdsPages(period: string) {
  return (page: number, size: number) => taxApi.flaggedTdsCases(period, page, size);
}

/** M-18: the section versions and the PT states a page at a time (SRS-NF-3). */
export const taxPages = {
  sections: (page: number, size: number) => taxApi.sections(page, size),
  states: (page: number, size: number) => taxApi.states(page, size),
};

export function usePendingSections() {
  return useQuery({ queryKey: taxKeys.sectionsPending, queryFn: taxApi.pendingSections });
}

export function usePendingStates() {
  return useQuery({ queryKey: taxKeys.statesPending, queryFn: taxApi.pendingStates });
}

// ---------------------------------------------------------------- I-07 / E-01 declarations (08c)

/** E-01: the member's year; personal data, never kept in the cache (staleTime 0). */
export function useMyDeclarations() {
  return useQuery({ queryKey: taxKeys.mine, queryFn: taxApi.myDeclarations, staleTime: 0, gcTime: 0 });
}

/** E-01: a new round of the member's declarations. */
export function useSubmitDeclarations() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (body: NewDeclaration) => taxApi.submitDeclarations(body),
    onSuccess: () => client.invalidateQueries({ queryKey: taxKeys.mine }),
  });
}

/** I-07: the rounds of a year a page at a time (SRS-NF-3). */
export const declarationPages = {
  list: (finYear: string, status: string) => (page: number, size: number) =>
    taxApi.declarations(finYear, status, page, size),
};

/** The link of a proof of a round (the read is logged on the server). */
export const proofUrl = taxApi.proofUrl;

/** I-07: one round; nothing is read while none is chosen. */
export function useDeclaration(id: number | null) {
  return useQuery({
    queryKey: taxKeys.declaration(id ?? 0),
    queryFn: () => taxApi.declaration(id ?? 0),
    enabled: id !== null,
    staleTime: 0,
    gcTime: 0,
  });
}

/** I-07: verify (to the Checker) or return (to the member) a round, or propose a regime reset. */
export function useDeclarationActions() {
  const client = useQueryClient();
  const refresh = () => client.invalidateQueries({ queryKey: ['tax', 'declarations'] });
  return {
    verify: useMutation({
      mutationFn: ({ id, lines }: { id: number; lines: { lineId: number; verified: string }[] }) =>
        taxApi.verifyDeclaration(id, lines),
      onSuccess: refresh,
    }),
    returnToMember: useMutation({
      mutationFn: ({ id, reason }: { id: number; reason: string }) => taxApi.returnDeclaration(id, reason),
      onSuccess: refresh,
    }),
    reset: useMutation({
      mutationFn: ({
        employeeId,
        finYear,
        reason,
      }: {
        employeeId: number;
        finYear: string;
        reason: string;
      }) => taxApi.resetRegime(employeeId, finYear, reason),
      onSuccess: refresh,
    }),
  };
}

// ---------------------------------------------------------------- I-18 savings and income (08d)

/** I-18: the entries of a year a page at a time (SRS-NF-3). */
export const savingsPages = {
  list: (finYear: string, status: string) => (page: number, size: number) =>
    taxApi.savingsEntries(finYear, status, page, size),
};

/** I-18: one member's year; nothing is read while no member is chosen. Personal data: not cached. */
export function useMemberSavings(eis: string | null, finYear: string) {
  return useQuery({
    queryKey: taxKeys.memberSavings(eis ?? '', finYear),
    queryFn: () => taxApi.memberSavings(eis ?? '', finYear),
    enabled: eis !== null && /^\d{4}-\d{2}$/.test(finYear),
    staleTime: 0,
    gcTime: 0,
  });
}

/** The link of an entry's supporting document (the read is logged on the server). */
export const entryDocumentUrl = taxApi.entryDocumentUrl;

/** I-18: save, submit or cancel an entry, or propose the transport rebate months. */
export function useSavingsActions() {
  const client = useQueryClient();
  const refresh = () => client.invalidateQueries({ queryKey: ['tax', 'savings'] });
  return {
    save: useMutation({
      mutationFn: ({ eis, body }: { eis: string; body: NewSavingsEntry }) => taxApi.saveEntry(eis, body),
      onSuccess: refresh,
    }),
    submit: useMutation({ mutationFn: (id: number) => taxApi.submitEntry(id), onSuccess: refresh }),
    cancel: useMutation({
      mutationFn: ({ id, reason }: { id: number; reason: string }) => taxApi.cancelEntry(id, reason),
      onSuccess: refresh,
    }),
    rebate: useMutation({
      mutationFn: ({
        eis,
        months,
        reason,
      }: {
        eis: string;
        months: Record<string, string>;
        reason: string;
      }) => taxApi.proposeRebate(eis, months, reason),
      onSuccess: refresh,
    }),
  };
}

/** M-18: a new version of a section, for the Rule Approver. */
export function useProposeSection() {
  const run = useGoverned();
  const client = useQueryClient();
  return useMutation({
    mutationFn: (body: SectionProposal & { reason: string }) =>
      run((otp) => taxApi.proposeSection(body, otp)),
    onSuccess: () => client.invalidateQueries({ queryKey: taxKeys.sections }),
  });
}

/** M-18: a new PT state ({@code edit} false) or new values of one, for the Rule Approver. */
export function useProposeState() {
  const run = useGoverned();
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ edit, ...body }: StateProposal & { reason: string; edit: boolean }) =>
      run((otp) => (edit ? taxApi.changeState(body, otp) : taxApi.createState(body, otp))),
    onSuccess: () => client.invalidateQueries({ queryKey: taxKeys.states }),
  });
}
