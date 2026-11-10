import { type PageResult } from '@/shared/ui';
import { API_BASE, api } from '@/shared/api';

/** One claim of the I-03 Claim Register (SRS I-03 field table). */
export interface ClaimRow {
  id: number;
  eis: string;
  name: string;
  memberType: 'EMPLOYEE' | 'LABOUR';
  claimType: string;
  claimTypeLabel: string | null;
  periodFrom: string;
  periodTo: string;
  claimed: number;
  entitlement: number | null;
  payable: number | null;
  capped: boolean;
  status: string;
  /** HRMS (employee), LDH or ESEVA (labour). */
  source: string;
  hrmsRequestId: string | null;
  taxable: boolean | null;
  taxableConfirmedAt: string | null;
  invoiceStatus: string;
  iogaNumber: string | null;
  iogaDate: string | null;
  iogaAmount: number | null;
  approvalId: number | null;
  decisionReason: string | null;
  /** US-GAP-209: Y, N or PARTLY (M-07 Taxable of the pay code, or the confirmed flag); null = not configured. */
  taxTreatment: 'Y' | 'N' | 'PARTLY' | null;
  /** US-GAP-209: what the payable adds to the taxable earnings; null when not known. */
  taxableAmount: number | null;
  /** SRS l.2805 (SRS-G-37): when the invoice went to APPS; null until sent. */
  invoiceSentAt?: string | null;
}

export interface ClaimDocument {
  id: number;
  documentTypeCode?: string | null;
  documentTypeName: string;
  referenceNumber: string | null;
  fileName: string;
  uploadedBy: string;
  uploadedAt: string;
}

export interface ClaimDetail {
  claim: ClaimRow;
  documents: ClaimDocument[];
}

export interface ClaimType {
  code: string;
  label: string;
  payCode: number | null;
}

export interface PendingHrmsClaim {
  taskId: number;
  taskNo: string;
  eis: string;
  name: string;
  value: string;
  status: string;
}

export interface ClaimFilter {
  memberType?: string;
  status?: string;
  claimType?: string;
  eis?: string;
  /** The drawn register's month (YYYYMM): unsettled claims and those whose period overlaps it. */
  yymm?: string;
}

/** The four tiles of I-03 (API-10-3). */
export interface ClaimCounts {
  claims: number;
  payable: number;
  pendingApproval: number;
  capped: number;
  leases: number;
}

/** One accommodation record in force (SRS-11-17; the 7 % figure stays null until SRS-11-3 is built). */
export interface LeaseRow {
  id: number;
  eis: string;
  name: string;
  memberType: 'EMPLOYEE' | 'LABOUR';
  accommodationType: string;
  leaseAmount: number | null;
  sevenPercent: number | null;
  hraSuppressed: boolean;
  conflict: boolean;
  source: string;
  hrmsRequestId: string | null;
  effectiveFrom: string;
}

/** The particulars of one supporting document attached on the order-copy card. */
export interface ClaimOrderInput {
  documentType: string;
  reference: string;
  orderDate: string;
  issuingOffice: string;
  file: File;
}

/** A supporting document as the entry form sends it. */
export interface ClaimDocumentInput {
  documentType: string;
  reference: string;
  orderDate: string;
  issuingAuthority: string;
  file: File;
}

export interface ClaimInput {
  memberNumber: string;
  claimType: string;
  periodFrom: string;
  periodTo: string;
  claimedAmount: string;
}

export interface IogaInput {
  number: string;
  date: string;
  amount: string;
  originatingOffice: string;
  reason: string;
}

/** One cafeteria selection of I-16 (SRS-11-16). */
export interface CafeteriaSelection {
  /** Null on a NO_SELECTION row (a member with no selection for the year). */
  id: number | null;
  eis: string;
  name: string;
  category: string | null;
  finYear: string | null;
  version: number;
  startDate: string | null;
  endDate: string | null;
  hrmsReference: string | null;
  status: string;
  components: { payCode: number; name: string; monthlyAmount: number }[];
  payEffect: number;
  /** SRS-11-16: the locked months of the arrear (YYYYMM) and the month it was raised in. */
  arrearFrom?: string | null;
  arrearTo?: string | null;
  arrearIn?: string | null;
}

export interface Submitted {
  id: number;
  status: string;
}

function query(params: Record<string, string | number | undefined>): string {
  const p = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) if (v !== undefined && v !== '') p.set(k, String(v));
  return p.toString();
}

/** The only place the allowance module talks to /api/v1/claims and /api/v1/cafeteria-selections. */
export const claimsApi = {
  register: (f: ClaimFilter, page: number, size: number) =>
    api<PageResult<ClaimRow>>(`/v1/claims?${query({ ...f, page, size })}`),
  types: (memberType: 'LABOUR' | 'EMPLOYEE') => api<ClaimType[]>(`/v1/claims/types?memberType=${memberType}`),
  pendingHrms: (page: number, size: number) =>
    api<PageResult<PendingHrmsClaim>>(`/v1/claims/pending-hrms?${query({ page, size })}`),
  medical: (eis: string) => api<ClaimRow[]>(`/v1/claims/medical?eis=${encodeURIComponent(eis)}`),
  detail: (id: number) => api<ClaimDetail>(`/v1/claims/${id}`),
  enter: (body: ClaimInput, documents: ClaimDocumentInput[]) => {
    const form = new FormData();
    const claim = {
      ...body,
      documents: documents.map((d) => ({
        documentType: d.documentType,
        reference: d.reference || null,
        orderDate: d.orderDate,
        issuingAuthority: d.issuingAuthority,
      })),
    };
    form.append('claim', new Blob([JSON.stringify(claim)], { type: 'application/json' }));
    for (const d of documents) form.append('files', d.file);
    return api<ClaimRow>('/v1/claims', { method: 'POST', body: form });
  },
  validate: (id: number, reason: string) =>
    api<Submitted>(`/v1/claims/${id}/validations`, { method: 'POST', body: { reason: reason || null } }),
  confirmTaxable: (id: number, taxable: boolean) =>
    api<ClaimRow>(`/v1/claims/${id}/taxable-confirmations`, { method: 'POST', body: { taxable } }),
  ioga: (id: number, body: IogaInput) =>
    api<Submitted>(`/v1/claims/${id}/iogas`, {
      method: 'POST',
      body: { ...body, reason: body.reason || null },
    }),
  documentUrl: (id: number, fileId: number) => `${API_BASE}/v1/claims/${id}/documents/${fileId}`,
  registerUrl: (f: ClaimFilter) => {
    const q = query({ ...f });
    return `${API_BASE}/v1/claims/register${q ? `?${q}` : ''}`;
  },
  medicalRegisterUrl: (yymm: string) =>
    `${API_BASE}/v1/claims/medical-register?yymm=${encodeURIComponent(yymm)}`,
  counts: () => api<ClaimCounts>('/v1/claims/counts'),
  leases: (page: number, size: number) =>
    api<PageResult<LeaseRow>>(`/v1/claims/leases?${query({ page, size })}`),
  attach: (id: number, o: ClaimOrderInput) => {
    const form = new FormData();
    form.append('documentType', o.documentType);
    if (o.reference) form.append('reference', o.reference);
    form.append('orderDate', o.orderDate);
    form.append('issuingOffice', o.issuingOffice);
    form.append('file', o.file);
    return api<ClaimDocument>(`/v1/claims/${id}/documents`, { method: 'POST', body: form });
  },
  cafeteriaExportUrl: (eis: string, finYear: string) => {
    const q = query({ eis, finYear });
    return `${API_BASE}/v1/cafeteria-selections/export${q ? `?${q}` : ''}`;
  },
  cafeteria: (eis: string | undefined, page: number, size: number, finYear?: string) =>
    api<PageResult<CafeteriaSelection>>(`/v1/cafeteria-selections?${query({ eis, finYear, page, size })}`),
};
