import { API_BASE, api } from '@/shared/api';
import type { PageResult } from '@/shared/ui';
import type { GangRole, PayrollType } from './labourApi';

/** One labour member as the drawn M-02 table lists it (identity numbers masked by the server). */
export interface MemberLine {
  employeeId: number;
  labourCode: string;
  name: string;
  payrollType: PayrollType;
  designation: string | null;
  gangRole: GangRole | null;
  gang: string | null;
  depot: string | null;
  aadhaarMasked: string | null;
  basicWage: number | null;
  cpfCode: string | null;
  status: string;
  transferPending: boolean;
}

export interface AttachmentLine {
  id: number;
  reference: string;
  labourCode: string;
  labour: string;
  category: string;
  gang: string;
  from: string;
  to: string | null;
  status: string;
}

export interface LightDutyLine {
  id: number;
  labourCode: string;
  labour: string;
  fromDesignation: string;
  toDesignation: string;
  reasonCode: string;
  effectiveDate: string;
  orderReference: string;
  issuingAuthority: string;
  payOption: string;
  protectedBasic: number | null;
  status: string;
}

/** One gang as the drawn M-09 register lists it. */
export interface GangLine {
  gangId: number;
  gangCode: string;
  gangName: string;
  payrollType: PayrollType | null;
  gangType: 'HANDLING' | 'ANCILLARY_POOL' | 'LEGACY_DUMMY_ANCILLARY';
  status: string;
  depot: string;
  shed: string | null;
  sardar: string | null;
  mandal: string | null;
  labourCount: number;
  effectiveFrom: string;
  effectiveTo: string | null;
  endBlocked: boolean;
  pending: boolean;
}

export interface GangSummary {
  leaderBreaches: number;
  endBlocked: number;
}

export interface StrengthLine {
  gangId: number;
  gangCode: string;
  gangName: string;
  gangType: GangLine['gangType'];
  sanctioned: number | null;
  present: number;
  shortBy: number;
  alertOpen: boolean;
  filledBy: string[];
}

export interface NormLine {
  id: number;
  depotCode: string;
  depot: string;
  mouReference: string | null;
  minLabourNumber: number;
  minSlabWeight: number;
  heightNorm: number | null;
  leadNorm: number | null;
  effectiveFrom: string;
  effectiveTo: string | null;
  approvalState: string;
}

export interface NormSummary {
  depotsWithSet: number;
  slabTables: number;
}

export interface SlabLine {
  table: string;
  order: number;
  from: number;
  to: number | null;
  ratePercent: number;
  stepSize: number | null;
}

export interface AsorLine {
  id: number;
  code: string;
  depot: string;
  effectiveFrom: string;
  effectiveTo: string | null;
  approvalState: string;
  pieceOfRate: number | null;
  mgw: Partial<Record<GangRole, number>>;
}

export interface SorLine {
  id: number;
  operationCode: string;
  operation: string;
  unit: string | null;
  rate: number;
  effectiveFrom: string;
  effectiveTo: string | null;
  approvalState: string;
}

export interface MinWageLine {
  id: number;
  locationType: string;
  basicWage: number | null;
  vda: number | null;
  mgw: number | null;
  effectiveFrom: string;
  effectiveTo: string | null;
  approvalState: string;
}

/** The filters of the drawn lists; an absent one is not sent. */
export interface ScreenFilter {
  type?: string;
  depot?: number | null;
  gang?: string;
  normType?: string;
  q?: string;
}

/** A stored order of a member, as the document module lists it. */
export interface MemberOrder {
  id: number;
  fileName: string;
  documentTypeCode: string | null;
  documentTypeName: string | null;
  referenceNumber: string | null;
  uploadedBy: string;
  uploadedAt: string;
}

export interface MemberOrderInput {
  documentType: string;
  reference: string;
  orderDate: string;
  issuingAuthority: string;
  file: File;
}

function query(filter: ScreenFilter, page?: number, size?: number): string {
  const p = new URLSearchParams();
  if (filter.type) p.set('type', filter.type);
  if (filter.depot) p.set('depot', String(filter.depot));
  if (filter.gang) p.set('gang', filter.gang);
  if (filter.normType) p.set('normType', filter.normType);
  if (filter.q?.trim()) p.set('q', filter.q.trim());
  if (page !== undefined) p.set('page', String(page));
  if (size !== undefined) p.set('size', String(size));
  const text = p.toString();
  return text ? `?${text}` : '';
}

/** The reads of M-02, M-05, M-06 and M-09 as drawn (PENDING API-20-10). */
export const labourScreenApi = {
  /** No size asks for the configured default: a size not offered is refused (COM-400-PAGE, SRS-NF-3). */
  members: (filter: ScreenFilter, page: number, size?: number) =>
    api<PageResult<MemberLine>>(`/v1/labour/registers/members${query(filter, page, size)}`),
  membersExportUrl: (filter: ScreenFilter) =>
    `${API_BASE}/v1/labour/registers/members/export${query(filter)}`,
  attachments: (type: string, page: number, size: number) =>
    api<PageResult<AttachmentLine>>(`/v1/labour/registers/attachments${query({ type }, page, size)}`),
  lightDuty: (type: string) =>
    api<PageResult<LightDutyLine>>(`/v1/labour/registers/light-duty-orders${query({ type }, 0, 10)}`),
  documentTypes: () => api<{ code: string; referenceRequired: boolean }[]>('/v1/document-types'),
  orders: (employeeId: number) => api<MemberOrder[]>(`/v1/labour/members/${employeeId}/documents`),
  orderUrl: (employeeId: number, fileId: number) =>
    `${API_BASE}/v1/labour/members/${employeeId}/documents/${fileId}`,
  attachOrder: (employeeId: number, o: MemberOrderInput) => {
    const form = new FormData();
    form.append('documentType', o.documentType);
    if (o.reference.trim()) form.append('reference', o.reference.trim());
    form.append('orderDate', o.orderDate);
    if (o.issuingAuthority.trim()) form.append('issuingAuthority', o.issuingAuthority.trim());
    form.append('file', o.file);
    return api<MemberOrder>(`/v1/labour/members/${employeeId}/documents`, { method: 'POST', body: form });
  },
  gangs: (filter: ScreenFilter, page: number, size: number) =>
    api<PageResult<GangLine>>(`/v1/labour/registers/gangs${query(filter, page, size)}`),
  gangSummary: (filter: ScreenFilter) =>
    api<GangSummary>(`/v1/labour/registers/gangs/summary${query(filter)}`),
  gangsExportUrl: (filter: ScreenFilter) => `${API_BASE}/v1/labour/registers/gangs/export${query(filter)}`,
  strengths: (filter: ScreenFilter, page: number, size: number) =>
    api<PageResult<StrengthLine>>(`/v1/labour/registers/gang-strengths${query(filter, page, size)}`),
  norms: (filter: ScreenFilter, page: number, size?: number) =>
    api<PageResult<NormLine>>(`/v1/labour/registers/norms${query(filter, page, size)}`),
  normSummary: () => api<NormSummary>('/v1/labour/registers/norms/summary'),
  normsExportUrl: (filter: ScreenFilter) => `${API_BASE}/v1/labour/registers/norms/export${query(filter)}`,
  slabs: (id: number) => api<SlabLine[]>(`/v1/labour/registers/norms/${id}/slabs`),
  asor: (page: number, size: number) =>
    api<PageResult<AsorLine>>(`/v1/labour/registers/asor-rates?page=${page}&size=${size}`),
  sor: (page: number, size: number) =>
    api<PageResult<SorLine>>(`/v1/labour/registers/sor-rates?page=${page}&size=${size}`),
  minWages: () => api<MinWageLine[]>('/v1/labour/registers/min-wages'),
  ratesExportUrl: (type: string) => `${API_BASE}/v1/labour/registers/rate-tables/export${query({ type })}`,
};
