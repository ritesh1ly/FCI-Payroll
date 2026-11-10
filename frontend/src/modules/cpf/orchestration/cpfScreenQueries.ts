'use client';

import { useQuery } from '@tanstack/react-query';
import { useI18n } from '@/shared/i18n';
import { can, useMe } from '@/shared/session';
import { useToast, withBase } from '@/shared/ui';
import { useApproveRow } from '@/shared/ui/masterParts';
import { cpfScreensApi } from '../api/cpfScreensApi';

/** The server state of the drawn CPF screens; the pages of their tables are functions, for ServerPagination. */
export const cpfScreenKeys = {
  deputationBoard: (yymm: string) => ['cpf', 'deputation', 'board', yymm] as const,
  deputedMembers: ['cpf', 'deputation', 'members'] as const,
  correctionBoard: ['cpf', 'opening-balance-corrections', 'board'] as const,
  correctionLines: ['cpf', 'opening-balance-corrections', 'lines'] as const,
  supplementary: ['cpf', 'supplementary'] as const,
  zoneChanges: ['cpf', 'zone-changes'] as const,
  zoneChange: (reference: string) => ['cpf', 'zone-changes', reference] as const,
  iocpfPending: ['cpf', 'iocpf-pending'] as const,
  register: (screen: string) => ['cpf', 'register', screen] as const,
};

export const cpfScreenPages = {
  deputedMembers: cpfScreensApi.deputedMembers,
  correctionLines: cpfScreensApi.correctionLines,
  supplementary: cpfScreensApi.supplementary,
  zoneChanges: cpfScreensApi.zoneChanges,
  iocpfPending: cpfScreensApi.iocpfPending,
};

export function useDeputationBoard(yymm: string) {
  return useQuery({
    queryKey: cpfScreenKeys.deputationBoard(yymm),
    queryFn: () => cpfScreensApi.deputationBoard(yymm),
    enabled: yymm !== '',
  });
}

export function useCorrectionBoard() {
  return useQuery({ queryKey: cpfScreenKeys.correctionBoard, queryFn: cpfScreensApi.correctionBoard });
}

export function useZoneChange(reference: string) {
  return useQuery({
    queryKey: cpfScreenKeys.zoneChange(reference),
    queryFn: () => cpfScreensApi.zoneChange(reference),
    enabled: reference !== '',
  });
}

export function useRegister(screen: 'm-18' | 'm-19') {
  return useQuery({
    queryKey: cpfScreenKeys.register(screen),
    queryFn: () => cpfScreensApi.register(screen),
  });
}

/**
 * ✔ of a CPF row. The pack draws it for the Payroll Checker; the SRS route gives the decision to the AGM (CPF) or the
 * Rule Approver (WF-271): the approver goes to W-01, the Payroll Checker is told who decides. Undefined for any other
 * role: no ✔ is drawn.
 */
export function useCpfApprove(screen: string, decider: string): ((pending: boolean) => void) | undefined {
  const { t } = useI18n();
  const toast = useToast();
  const me = useMe();
  const approve = useApproveRow();
  if (can(me.data, screen, 'APPROVE')) return approve;
  return me.data?.roles?.includes('PAYROLL_CHECKER') ? () => toast(t(decider)) : undefined;
}

/** A drawn link to another screen. */
export function goTo(screen: string) {
  window.location.assign(withBase(`/payroll/?s=${screen}`));
}
