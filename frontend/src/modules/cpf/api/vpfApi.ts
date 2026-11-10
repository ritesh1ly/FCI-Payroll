import { type PageResult } from '@/shared/ui';
import { api } from '@/shared/api';

/** DDL vpf_change_request.request_status. */
export type VpfStatus = 'SUBMITTED' | 'APPROVED' | 'REJECTED' | 'RETURNED' | 'APPLIED' | 'CANCELLED';

/** One VPF change request (plan 14 phase 14c; BR-ESEVA-006). */
export interface VpfRequest {
  id: number;
  eis: string;
  member: string;
  office: string | null;
  currentAmount: number | null;
  requestedAmount: number;
  effectiveFromYymm: string;
  status: VpfStatus;
  /** Sent by the Maker, waiting for the Payroll Checker. */
  awaitingChecker: boolean;
  decisionReason: string | null;
  submittedAt: string;
}

/** E-01: the member's VPF of the year and the requests. */
export interface MyVpf {
  finYear: string;
  currentAmount: number;
  /** The member has a CPF ledger this year (VPF is a CPF subscription). */
  cpfMember: boolean;
  requests: VpfRequest[];
}

/** The only place the VPF change talks to /api/v1/cpf/vpf-changes. */
export const vpfApi = {
  mine: () => api<MyVpf>('/v1/cpf/vpf-changes/mine'),
  request: (amount: number) =>
    api<VpfRequest>('/v1/cpf/vpf-changes/mine', { method: 'POST', body: { amount } }),
  list: (status: VpfStatus | undefined, page: number, size: number) => {
    const p = new URLSearchParams();
    if (status) p.set('status', status);
    p.set('page', String(page));
    p.set('size', String(size));
    return api<PageResult<VpfRequest>>(`/v1/cpf/vpf-changes?${p.toString()}`);
  },
  verify: (id: number) =>
    api<VpfRequest>(`/v1/cpf/vpf-changes/${id}/verifications`, { method: 'POST', body: {} }),
  returnToMember: (id: number, reason: string) =>
    api<VpfRequest>(`/v1/cpf/vpf-changes/${id}/returns`, { method: 'POST', body: { reason } }),
};
