'use client';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useGoverned } from '@/modules/workflow';
import { rulesApi, type ProposedEnd, type ProposedRuleVersion, type SimulationScope } from '../api/rulesApi';

/**
 * Use cases of M-08 (SRS-19-4, SRS-19-21): server state through TanStack Query. A proposal is a
 * maker-checker submission: nothing changes in force until a Rule Approver approves it in the inbox.
 */
export const ruleKeys = {
  all: ['rules'] as const,
  catalogue: ['rules', 'catalogue'] as const,
  pending: ['rules', 'pending'] as const,
  vocabulary: ['rules', 'vocabulary'] as const,
  versions: (key: string) => ['rules', 'versions', key] as const,
  simulations: (key: string) => ['rules', 'simulations', key] as const,
};

export function useRuleCatalogue() {
  return useQuery({ queryKey: ruleKeys.catalogue, queryFn: rulesApi.catalogue });
}

/** SRS-19-21: the domains entered on M-14, M-15 and M-20, on M-08 only. */
export function useRuleReadThrough(enabled: boolean) {
  return useQuery({ queryKey: ['rules', 'read-through'], queryFn: rulesApi.readThrough, enabled });
}

/** The latest runs of the version waiting on {@code key}; nothing is read without one. */
export function useRuleSimulations(key: string | null) {
  return useQuery({
    queryKey: key ? ruleKeys.simulations(key) : ['rules', 'simulations', 'none'],
    queryFn: () => rulesApi.simulations(key ?? '', 0, 10),
    enabled: key !== null,
  });
}

/** Read only while a formula is being written. */
export function useRuleVocabulary(enabled: boolean) {
  return useQuery({ queryKey: ruleKeys.vocabulary, queryFn: rulesApi.vocabulary, enabled });
}

/** M-08: the page fetchers of the pending changes and of one rule's versions (docs/plan/X-pagination.md). */
export const rulePages = {
  pending: rulesApi.pending,
  versions: (key: string) => (page: number, size: number) => rulesApi.versions(key, page, size),
  simulations: (key: string) => (page: number, size: number) => rulesApi.simulations(key, page, size),
};

/** SRS-19-21: the two on-demand reports of M-08. */
export const catalogueSummaryUrl = rulesApi.catalogueSummaryUrl;
export const pendingReportUrl = rulesApi.pendingReportUrl;

/** The offices a simulation sample can be narrowed to (not paged: a choice list). */
export function useSimulationOffices(enabled: boolean) {
  return useQuery({
    queryKey: ['rules', 'simulation-offices'],
    queryFn: rulesApi.simulationOffices,
    enabled,
  });
}

/** SRS-19-4 (06e): simulates the version awaiting approval; the list of runs is read again. */
export function useSimulateRule() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: { key: string; scope: SimulationScope }) => rulesApi.simulate(input.key, input.scope),
    onSuccess: (_r, input) => client.invalidateQueries({ queryKey: ruleKeys.simulations(input.key) }),
  });
}

function useInvalidateRules() {
  const client = useQueryClient();
  return () => client.invalidateQueries({ queryKey: ruleKeys.all });
}

export function useProposeRuleVersion() {
  const run = useGoverned();
  const done = useInvalidateRules();
  return useMutation({
    mutationFn: (input: { key: string; body: ProposedRuleVersion }) =>
      run((otp) => rulesApi.propose(input.key, input.body, otp)),
    onSuccess: done,
  });
}

export function useProposeRuleEnd() {
  const run = useGoverned();
  const done = useInvalidateRules();
  return useMutation({
    mutationFn: (input: { key: string; body: ProposedEnd }) =>
      run((otp) => rulesApi.end(input.key, input.body, otp)),
    onSuccess: done,
  });
}
