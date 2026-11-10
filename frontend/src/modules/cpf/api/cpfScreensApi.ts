import { api } from '@/shared/api';
import { type PageResult } from '@/shared/ui';

/**
 * The reads of the CPF screens as Wireframes v6.1 draws them (CpfScreenController, RuleCatalogueController; PENDING
 * API-07-13). The writes of I-09, I-22, M-19 and C-12 stay in cpfApi; I-12 and I-13 have none (07h-blocked, 07k-blocked).
 */
export interface DeputationBoard {
  members: number;
  withAdvance: number;
  recovered: number;
  organisations: number;
  contributions: number;
  pending: number;
}

/** One deputed member; null where nothing has been remitted. */
export interface DeputedRow {
  eis: string;
  name: string;
  organisation: string | null;
  from: string;
  to: string | null;
  outstanding: number | null;
  instalment: number | null;
  balanceAfter: number | null;
  contribution: number | null;
  status: string;
}

/** interestQueued stays null until FCI gives the interest method (Q-072). */
export interface CorrectionBoard {
  open: number;
  pendingAgm: number;
  pendingHq: number;
  interestQueued: number | null;
}

/** One corrected part of an I-22 request, with its evidence. */
export interface CorrectionLine {
  ref: string;
  eis: string;
  name: string;
  cpfCode: string | null;
  part: 'EMPLOYEE' | 'EMPLOYER' | 'VPF';
  current: number;
  corrected: number;
  difference: number;
  reasonCode: string;
  evidence: string | null;
  interestEffect: number | null;
  status: string;
  evidenceId: number | null;
  fileName: string | null;
  documentType: string | null;
  uploadedBy: string | null;
  uploadedAt: string | null;
}

/** A CPF supplementary payment with its member and invoice (SRS I-12 field table). */
export interface SupplementaryRow {
  entry: string;
  eis: string;
  name: string;
  designation: string | null;
  category: string | null;
  employeeType: string;
  cpfCode: string | null;
  posting: string | null;
  employmentStatus: string;
  sanctionNumber: string | null;
  employee: number;
  employer: number;
  vpf: number;
  sanctioned: number;
  orderNumber: string | null;
  orderDate: string | null;
  paymentStatus: string;
  invoiceNumber: string | null;
  invoiceStatus: string | null;
  sentToApps: string | null;
  appsCancelStatus: string | null;
}

/** A change received from HRMS (SRS I-13 field table). */
export interface ZoneChangeRow {
  reference: string;
  eis: string;
  name: string;
  reason: string;
  fromZone: string;
  toZone: string;
  order: string | null;
  oldId: string;
  newId: string | null;
  effective: string | null;
  decision: 'APPROVED' | 'REJECTED';
  status: string;
  taskNumber: string | null;
}

export interface MemberIdRow {
  id: string;
  zone: string | null;
  from: string;
  to: string | null;
  status: string;
  reason: string | null;
}

/** What the approval carried, as far as it has happened; a figure no run produced is null (Q-072). */
export interface ZoneChangeDetail {
  change: ZoneChangeRow;
  carriedEmployee: number | null;
  carriedEmployer: number | null;
  carriedVpf: number | null;
  interestTo: string | null;
  reconStatus: string;
  sspSyncedAt: string | null;
  ecrFrom: string | null;
  iocpfStatus: string | null;
  iocpfAcknowledged: string | null;
  ids: MemberIdRow[];
}

export interface IocpfRow {
  number: string;
  eis: string;
  name: string;
  reason: string;
  total: number;
  appsStatus: string;
  sentAt: string | null;
  retries: number;
}

/** The fields of a rule the M-18 and M-19 registers read. */
export interface RegisterRule {
  key: string;
  name: string;
  description: string | null;
  payrollTypeScope: string | null;
  inForce: {
    versionNo: number;
    effectiveFrom: string;
    status: string;
    value: number | null;
    bands: unknown[];
    circularNumber: string | null;
  } | null;
  versions: number;
}

export const cpfScreensApi = {
  deputationBoard: (yymm: string) =>
    api<DeputationBoard>(`/v1/cpf/deputations/board?yymm=${encodeURIComponent(yymm)}`),
  deputedMembers: (page: number, size: number) =>
    api<PageResult<DeputedRow>>(`/v1/cpf/deputations/members?page=${page}&size=${size}`),
  correctionBoard: () => api<CorrectionBoard>('/v1/cpf/opening-balance-corrections/board'),
  correctionLines: (page: number, size: number) =>
    api<PageResult<CorrectionLine>>(`/v1/cpf/opening-balance-corrections/lines?page=${page}&size=${size}`),
  supplementary: (page: number, size: number) =>
    api<PageResult<SupplementaryRow>>(`/v1/cpf/supplementary-payments?page=${page}&size=${size}`),
  zoneChanges: (page: number, size: number) =>
    api<PageResult<ZoneChangeRow>>(`/v1/cpf/parent-zone-changes?page=${page}&size=${size}`),
  zoneChange: (reference: string) =>
    api<ZoneChangeDetail>(`/v1/cpf/parent-zone-changes/${encodeURIComponent(reference)}`),
  iocpfPending: (page: number, size: number) =>
    api<PageResult<IocpfRow>>(`/v1/cpf/iocpf-pending?page=${page}&size=${size}`),
  register: (screen: 'm-18' | 'm-19') => api<RegisterRule[]>(`/v1/rules/register/${screen}`),
};
