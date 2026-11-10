'use client';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { incrementApi, type CaseOrderCopy, type CaseRequest } from '../api/incrementApi';

/**
 * I-05 and M-03 use cases (plan 17). Establishment / HR runs the increments and raises the cases;
 * the Rule Administrator at Headquarters proposes scale versions; the Payroll Checker approves both
 * in W-01 (a correction with OTP).
 */
export const incrementKeys = {
  due: (month: string) => ['increments', 'due', month] as const,
  counts: (month: string) => ['increments', 'counts', month] as const,
  worklist: (month: string) => ['increments', 'worklist', month] as const,
  caseOrders: (approvalId: number) => ['increments', 'orders', approvalId] as const,
  mapping: ['pay-scales', 'mapping'] as const,
  history: (eis: string) => ['increments', 'history', eis] as const,
  scales: ['pay-scales'] as const,
  stages: (code: string) => ['pay-scales', 'stages', code] as const,
  versions: (code: string) => ['pay-scales', 'versions', code] as const,
  orders: ['pay-scales', 'orders'] as const,
  scaleOrders: (code: string) => ['pay-scales', 'scale-orders', code] as const,
  wageRevisions: ['pay-scales', 'wage-revisions'] as const,
};

export const incrementPages = {
  due: (month: string) => (page: number, size: number) => incrementApi.due(month, page, size),
  worklist: (month: string) => (page: number, size: number) => incrementApi.worklist(month, page, size),
  scales: (page: number, size: number) => incrementApi.scales(page, size),
  mapping: (page: number, size: number) => incrementApi.mapping(page, size),
};

export const incrementUrls = {
  tracking: incrementApi.trackingUrl,
  history: incrementApi.historyUrl,
  caseOrder: incrementApi.caseOrderUrl,
  scaleOrder: incrementApi.scaleOrderUrl,
};

/** The first page of the worklist, for the drawn cards that read its first case. */
export function useFirstWorklist(month: string) {
  return useQuery({
    queryKey: [...incrementKeys.worklist(month), 'first'],
    queryFn: () => incrementApi.worklist(month, 0, 20),
  });
}

/** The first page of the scales, for the drawn order card. */
export function useFirstScales() {
  return useQuery({
    queryKey: [...incrementKeys.scales, 'first'],
    queryFn: () => incrementApi.scales(0, 20),
  });
}

/** The drawn tiles and rule figures of the month (API-17-2). */
export function useWorklistCounts(month: string) {
  return useQuery({
    queryKey: incrementKeys.counts(month),
    queryFn: () => incrementApi.counts(month),
    enabled: month !== '',
  });
}

/** The orders stored with a case (the drawn order card, SRS-13-18). */
export function useCaseOrders(approvalId: number | null) {
  return useQuery({
    queryKey: incrementKeys.caseOrders(approvalId ?? 0),
    queryFn: () => incrementApi.caseOrders(approvalId as number),
    enabled: approvalId !== null,
  });
}

export function useHistory(eis: string) {
  return useQuery({
    queryKey: incrementKeys.history(eis),
    queryFn: () => incrementApi.history(eis),
    enabled: eis.length > 0,
    retry: false,
  });
}

/** M-03 (SRS l.3952): every version of the scale, the superseded ones end-dated. */
export function useScaleVersions(code: string | null) {
  return useQuery({
    queryKey: incrementKeys.versions(code ?? ''),
    queryFn: () => incrementApi.versions(code as string),
    enabled: code !== null,
  });
}

export function useStages(code: string | null) {
  return useQuery({
    queryKey: incrementKeys.stages(code ?? ''),
    queryFn: () => incrementApi.stages(code as string),
    enabled: code !== null,
  });
}

export function useOrders(enabled: boolean) {
  return useQuery({ queryKey: incrementKeys.orders, queryFn: incrementApi.orders, enabled });
}

/** SRS M-03 Wage Revision lookup (a choice list, not paged). */
export function useWageRevisions(enabled: boolean) {
  return useQuery({ queryKey: incrementKeys.wageRevisions, queryFn: incrementApi.wageRevisions, enabled });
}

/** Every I-05 / M-03 action, refreshing what it changes. */
/** M-03: the order copies stored with a scale (the drawn order card, SRS-G-30). */
export function useScaleOrders(code: string | null) {
  return useQuery({
    queryKey: incrementKeys.scaleOrders(code ?? ''),
    queryFn: () => incrementApi.scaleOrders(code as string),
    enabled: code !== null,
  });
}

export function useIncrementAction() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (run: () => Promise<unknown>) => run(),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['increments'] });
      void queryClient.invalidateQueries({ queryKey: ['pay-scales'] });
    },
  });
}

export const incrementCalls = {
  attachScaleOrder: incrementApi.attachScaleOrder,
  submitRun: incrementApi.submitRun,
  preview: incrementApi.preview,
  submitCase: incrementApi.submitCase,
  /** SRS-13-18: the order copy is stored, then the case names it. */
  submitCaseWithOrder: async (c: CaseRequest, copy: CaseOrderCopy) => {
    const stored = await incrementApi.storeOrder(copy);
    return incrementApi.submitCase({
      ...c,
      orderAttachmentId: stored.attachmentId,
      orderProvisionalKey: stored.provisionalKey,
    });
  },
  propose: incrementApi.propose,
  define: incrementApi.define,
};
