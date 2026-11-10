import type { StoredOrder } from '@/shared/ui/orderCopy';
import { PageResult } from '@/shared/ui';
import { api, API_BASE } from '@/shared/api';

/** The drawn columns of M-17 the catalogue read does not carry (backend TaxScreenViews). */
export interface OutputLine {
  key: string;
  name: string;
  family: string;
  periodicity: string | null;
  lockRequirement: string;
  statutoryReference: string | null;
  carriedForward: boolean;
  catalogueReport: boolean;
}

export interface OutputSummary {
  carriedForward: number;
  superseded: number;
  families: number;
  outputs: OutputLine[];
}

export interface SectionLine {
  code: string;
  regime: string | null;
  limit: number | null;
  effectiveFrom: string;
  rule: boolean;
}

export interface DeclarationSummary {
  finYear: string;
  members: number;
  newRegime: number;
  oldRegime: number;
  notSelected: number;
  received: number;
  pending: number;
  capped: number;
  unproved: number;
  sections: SectionLine[];
  /** The latest round with a stored order at the user's offices; the card opens on it (SRS-G-30). */
  orderRound?: number | null;
}

/** A member's line of the I-07 register; no regime and no round are lines too. */
export interface DeclarationLine {
  employeeId: number;
  eis: string;
  name: string;
  regime: 'O' | 'N' | null;
  frozen: boolean;
  frozenAt: string | null;
  roundId: number | null;
  roundStatus: string | null;
  declared80c: number | null;
  limit80c: number | null;
  declared80d: number | null;
  declared24b: number | null;
  allowed: number | null;
  capped: boolean;
  unproved: boolean;
}

export interface TypeLine {
  payrollType: string;
  members: number;
  employee: number | null;
  vpf: number | null;
  tds: number | null;
}

export interface ComputationSummary {
  period: string | null;
  cpfRate: number | null;
  types: TypeLine[];
  noCpfCode: number;
  npsMismatch: number;
  regimeMissing: number;
}

export interface EpsLine {
  employeeId: number;
  eis: string;
  name: string;
  wages: number | null;
}

export interface TaxLine {
  employeeId: number;
  eis: string;
  name: string;
  regime: 'OLD' | 'NEW' | null;
  taxable: number | null;
  annualTax: number | null;
  monthlyTds: number | null;
  professionalTax: number | null;
  higherRate: boolean;
  reason: string | null;
}

/** A member's NPS record exactly as HRMS sent it (SRS-9-1): never keyed or derived here. */
export interface NpsLine {
  id: number;
  eis: string;
  name: string;
  pattern: string | null;
  pfType: string | null;
  npsMember: boolean;
  pran: string | null;
  employeeAmount: number | null;
  employerAmount: number | null;
  mismatch: string;
  applyStatus: string;
}

export interface Unit {
  id: number;
  code: string;
  name: string;
}

export interface CertificateSummary {
  finYear: string;
  due: number;
  partB: number;
  partA: number;
  merged: number;
  issued: number;
  form10e: number;
  units: Unit[];
}

export interface CertificateLine {
  id: number;
  eis: string;
  name: string;
  panMasked: string | null;
  panStatus: 'VALID' | 'INVALID' | 'MISSING';
  regime: 'O' | 'N' | null;
  taxable: number | null;
  tds: number;
  partB: boolean;
  partA: boolean;
  signed: boolean;
  status: string;
  /** OD-153: the copy frozen at the release is on file (a released line without one has no PDF). */
  issuedCopy?: boolean;
}

export interface ReturnSummary {
  finYear: string;
  quarter: string;
  status: string | null;
  validated: boolean;
  deductees: number;
  staff: number;
  labour: number;
  tds: number | null;
  challans: number;
  challansMapped: number;
  panExceptions: number;
  definitionVersion: number;
  definitionFrom: string | null;
  units: Unit[];
}

export interface ReturnLine {
  id: number;
  type: string;
  finYear: string;
  quarter: string;
  deductees: number;
  tds: number | null;
  validated: boolean;
  filed: string | null;
  token: string | null;
  status: string;
  correction: boolean;
}

const year = (finYear: string) => `finYear=${encodeURIComponent(finYear)}`;
const paging = (page: number, size: number) => `page=${page}&size=${size}`;

/** The reads the drawn screens add (backend TaxScreenController, API-08-8). */
export const screensApi = {
  outputSummary: () => api<OutputSummary>('/v1/tax/output-definitions/summary'),
  declarationSummary: (finYear: string) =>
    api<DeclarationSummary>(`/v1/tax/declarations/summary?${year(finYear)}`),
  declarationMembers: (finYear: string, page: number, size: number) =>
    api<PageResult<DeclarationLine>>(`/v1/tax/declarations/members?${year(finYear)}&${paging(page, size)}`),
  roundOrders: (id: number) => api<StoredOrder[]>(`/v1/tax/declarations/${id}/orders`),
  attachRoundOrder: (
    id: number,
    order: { documentType: string; reference: string; orderDate: string; issuingOffice: string; file: File },
  ) => {
    const form = new FormData();
    form.append('documentType', order.documentType);
    form.append('reference', order.reference);
    form.append('orderDate', order.orderDate);
    form.append('issuingAuthority', order.issuingOffice);
    form.append('file', order.file);
    return api<StoredOrder>(`/v1/tax/declarations/${id}/orders`, { method: 'POST', body: form });
  },
  roundOrderUrl: (id: number, fileId: number) => `${API_BASE}/v1/tax/declarations/${id}/files/${fileId}`,
  computationSummary: () => api<ComputationSummary>('/v1/tax/statutory-computation/summary'),
  epsLines: (page: number, size: number) =>
    api<PageResult<EpsLine>>(`/v1/tax/statutory-computation/eps?${paging(page, size)}`),
  taxLines: (page: number, size: number) =>
    api<PageResult<TaxLine>>(`/v1/tax/statutory-computation/members?${paging(page, size)}`),
  npsLines: (page: number, size: number) =>
    api<PageResult<NpsLine>>(`/v1/tax/statutory-computation/nps?${paging(page, size)}`),
  certificateSummary: (finYear: string) =>
    api<CertificateSummary>(`/v1/tax/form16-certificates/summary?${year(finYear)}`),
  certificateLines: (finYear: string, status: string, office: string, page: number, size: number) =>
    api<PageResult<CertificateLine>>(
      `/v1/tax/form16-certificates/lines?${year(finYear)}${status ? `&status=${status}` : ''}${office ? `&office=${office}` : ''}&${paging(page, size)}`,
    ),
  returnSummary: (finYear: string, quarter: string) =>
    api<ReturnSummary>(`/v1/tax/returns/summary?${year(finYear)}&quarter=${quarter}`),
  returnLines: (office: string, page: number, size: number) =>
    api<PageResult<ReturnLine>>(
      `/v1/tax/returns/lines?${office ? `office=${office}&` : ''}${paging(page, size)}`,
    ),
};
