'use client';

import { PERSONAL_DATA } from '@/shared/api';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { type SubmissionFilter, esevaApi } from '../api/esevaApi';

/** eSeva use cases (plan 14 phase 14a / 14b). Personal data: never kept in a shared cache. */
export const esevaKeys = {
  submissions: (filter: SubmissionFilter) => ['eseva-submissions', filter] as const,
  notice: ['eseva-privacy-notice'] as const,
  notices: ['eseva-notices'] as const,
};

export function submissionsPage(filter: SubmissionFilter) {
  return (page: number, size: number) => esevaApi.submissions(filter, page, size);
}

/** SRS-17-8: one recorded support preview. */
export function usePreview() {
  return useMutation({
    mutationFn: ({ eis, reason }: { eis: string; reason: string }) => esevaApi.preview(eis, reason),
  });
}

/** FRS V0.5 l.6958 (US-GAP-261): the support preview closed by its user. */
export function useClosePreview() {
  return useMutation({ mutationFn: (sessionId: number) => esevaApi.closePreview(sessionId) });
}

/** E-01 as drawn: the signed-in member's overview (E-01 SELF only: a refused call ends the session, OD-130). */
export function useMemberOverview(enabled: boolean) {
  return useQuery({
    ...PERSONAL_DATA,
    queryKey: ['eseva-overview', 'mine'],
    queryFn: esevaApi.overviewMine,
    enabled,
  });
}

/** E-01 support preview: the overview of the member of the own open session (SRS-17-6, l.496). */
export function usePreviewOverview(sessionId: number | null) {
  return useQuery({
    ...PERSONAL_DATA,
    queryKey: ['eseva-overview', 'preview', sessionId],
    queryFn: () => esevaApi.overviewOfPreview(sessionId ?? 0),
    enabled: sessionId !== null,
  });
}

/** The identity band of the member's own session (never for a staff user). */
export function useOwnBand(enabled: boolean) {
  return useQuery({ ...PERSONAL_DATA, queryKey: ['eseva-band'], queryFn: esevaApi.ownBand, enabled });
}

/** One locked month's payslip: the member's own, or through the open support session. */
export function useMemberSlip(sessionId: number | null, yymm: string | null, preview: boolean) {
  return useQuery({
    ...PERSONAL_DATA,
    queryKey: ['eseva-slip', sessionId, yymm],
    queryFn: () =>
      preview ? esevaApi.payslipOfPreview(sessionId ?? 0, yymm ?? '') : esevaApi.payslipMine(yymm ?? ''),
    enabled: yymm !== null && (!preview || sessionId !== null),
  });
}

/** SRS-SEC-9: the member's privacy notice (only for a member). */
export function useNotice(enabled: boolean) {
  return useQuery({ queryKey: esevaKeys.notice, queryFn: esevaApi.notice, enabled, gcTime: 0 });
}

export function useAcknowledge() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => esevaApi.acknowledge(),
    onSuccess: (notice) => queryClient.setQueryData(esevaKeys.notice, notice),
  });
}

/** SRS-17-12: the member's in-app notices, paged on the server. */
export function noticesPage(page: number, size: number) {
  return esevaApi.notices(page, size);
}

export function useMarkRead() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => esevaApi.markRead(id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: esevaKeys.notices }),
  });
}

/** The signed-in staff user's own notices (returned cycle, overdue I-21 task). */
export const staffNoticesKey = ['staff-notices'] as const;

export function staffNoticesPage(page: number, size: number) {
  return esevaApi.staffNotices(page, size);
}

export function useMarkStaffRead() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => esevaApi.markStaffRead(id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: staffNoticesKey }),
  });
}
