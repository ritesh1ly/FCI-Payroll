'use client';

import { PERSONAL_DATA } from '@/shared/api';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { type Decision, workflowApi } from '../api/workflowApi';
import { type ProposedRoute, workflowMasterApi } from '../api/workflowMasterApi';
import { useGoverned } from './governed';

export const workflowKeys = {
  inbox: ['inbox'] as const,
  inboxSummary: ['inbox', 'summary'] as const,
  submissions: ['submissions'] as const,
  actions: ['actions'] as const,
};

/** W-01 tiles and module filter (OD-121); refreshed with every decision like the inbox itself. */
export function useInboxSummary() {
  return useQuery({ queryKey: workflowKeys.inboxSummary, queryFn: workflowApi.inboxSummary });
}

export const inboxExportUrl = workflowApi.inboxExportUrl;

/**
 * The checker's decision, with the OTP step when the rule asks for one. A decision changes data of
 * the item's own module (job queue, access, parameters …), so every cached query is refreshed.
 */
export function useDecision() {
  const queryClient = useQueryClient();
  const run = useGoverned();
  return useMutation({
    mutationFn: (input: { approvalId: number; decision: Decision; reason: string }) =>
      run((otp) => workflowApi.decide(input.approvalId, input.decision, input.reason, otp)),
    onSuccess: () => queryClient.invalidateQueries(),
  });
}

/** SRS-G-13 / UC-07 A2 (US-GAP-21): the selected items approved together, each on its own. */
export function useBulkApprove() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: { approvalIds: number[]; reason: string }) =>
      workflowApi.bulkApprove(input.approvalIds, input.reason),
    onSuccess: () => queryClient.invalidateQueries(),
  });
}

/** Every governed button with whether this session holds its permission. */
export function useScreenActions() {
  return useQuery({ queryKey: workflowKeys.actions, queryFn: workflowApi.myActions });
}

/** Performs a governed button (with the OTP step if the rule asks for one). */
export function usePerformAction() {
  const run = useGoverned();
  return useMutation({
    mutationFn: (input: { screen: string; action: string; reason: string | null }) =>
      run((otp) => workflowApi.performAction(input.screen, input.action, input.reason, otp)),
  });
}

/** A-08: every approval action with its versions (configuration, not personal data). */
export const routeActionsKey = ['workflow-actions'] as const;

/** A-08: items no one in scope can decide, flagged to the HQ Administrator (SRS-G-28). */
export const unstaffedKey = ['workflow-actions', 'unstaffed'] as const;

/** A-08: how many items no one in scope can decide (the tab's count; the list pages itself). */
export function useUnstaffedCount(enabled: boolean) {
  return useQuery({
    ...PERSONAL_DATA,
    queryKey: [...unstaffedKey, 'count'],
    queryFn: () => workflowMasterApi.unstaffed(0, 10),
    select: (page) => page.total,
    enabled,
  });
}

/** A-08: proposes a new action or version; the list refreshes (it shows the pending version). */
export function useProposeRoute() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (route: ProposedRoute) => workflowMasterApi.propose(route),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['workflow-actions'] }),
  });
}

/** W-01 / W-02: the page fetchers of the paged lists (ServerPagination, docs/plan/X-pagination.md). */
export const workflowPages = {
  inbox: workflowApi.inbox,
  submissions: workflowApi.submissions,
  routeActions: workflowMasterApi.actions,
  unstaffed: workflowMasterApi.unstaffed,
  accessMatrix: workflowMasterApi.accessMatrix,
  activeUsers: workflowMasterApi.activeUsers,
};

/** X-01: the Screen-Level Access Matrix and the Active User Report (each pages itself). */
export const accessMatrixKey = ['workflow-actions', 'access-matrix'] as const;
export const activeUsersKey = ['workflow-actions', 'active-users'] as const;
export const activeUsersExportUrl = workflowMasterApi.activeUsersExportUrl;

/** SRS-G-31: the documents of an item's record, read when the reviewer opens them. */
export function useItemDocuments(approvalId: number, enabled: boolean) {
  return useQuery({
    queryKey: ['inbox', 'documents', approvalId],
    queryFn: () => workflowApi.documents(approvalId),
    enabled,
    ...PERSONAL_DATA,
  });
}

/** The link of one document of an item (served as an attachment). */
export function itemDocumentUrl(approvalId: number, fileId: number): string {
  return workflowApi.documentUrl(approvalId, fileId);
}
