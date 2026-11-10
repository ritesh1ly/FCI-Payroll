import { PageResult } from '@/shared/ui';
import { api, API_BASE } from '@/shared/api';
import type { TaxRegime } from './taxApi';

// ---------------------------------------------------------------- C-07 computation (08e)

export type PanStatus = 'VALID' | 'INVALID' | 'MISSING';

/** One deduction of the year: what was claimed, the section cap, what counts (SRS-7-20). */
export interface Deduction {
  sectionCode: string;
  claimed: number;
  limit: number | null;
  allowed: number;
}

export interface OtherIncome {
  head: string;
  amount: number;
  tdsOtherSource: number;
}

export interface EarningLine {
  description: string;
  amount: number;
}

/** One payroll month: actual (locked) or projected (SRS-7-12). */
export interface EarningMonth {
  yymm: string;
  actual: boolean;
  taxableGross: number;
  tdsDeducted: number;
  employerNps: number;
  lines: EarningLine[];
}

export interface TaxResult {
  taxableIncome: number;
  slabTax: number;
  surcharge: number;
  cess: number;
  annualTax: number;
  higherRateApplied: boolean;
  monthlyTds: number;
}

/** C-07: a member's year; {@code reasons} name what is missing (never assumed, SRS-7-26). */
export interface Forecast {
  employeeId: number;
  eis: string;
  name: string;
  /** SRS-7-27: IN_SERVICE, SEPARATED or RETIRED: a separated or retired member's year is shown as such. */
  employmentStatus: string | null;
  finYear: string;
  view: 'PROJECTED' | 'ACTUAL';
  inputs: {
    regime: TaxRegime | null;
    pan: PanStatus;
    deductions: Deduction[];
    transportRebate: number;
    otherIncome: OtherIncome[];
  };
  earnings: { months: EarningMonth[] } | null;
  result: TaxResult | null;
  yearEndDifference: number | null;
  reasons: string[];
}

/** SRS-7-28: the what-if change (null: as approved). */
export interface WhatIfRequest {
  regime: TaxRegime | null;
  claims: { sectionCode: string; amount: number }[];
  salaryChange: number | null;
  otherIncome: number | null;
}

/** A what-if run: its record id, the actual forecast and the varied one (never stored). */
export interface WhatIfResult {
  runId: number;
  actual: Forecast;
  whatIf: Forecast;
}

// ---------------------------------------------------------------- M-17 output definitions (08f)

export type Binding = 'COMPONENT' | 'FIELD_SOURCE' | 'AGGREGATE' | 'DERIVED' | 'LITERAL';
export type LayoutFormat = 'PDF' | 'EXCEL' | 'CSV' | 'PRESCRIBED_FILE' | 'HTML';
export type ReconcilesAgainst = 'POSTED_PAYROLL' | 'GL_POSTING_STATEMENT' | 'BANK_ADVICE' | 'CHALLAN';

export interface OutputField {
  label: string;
  binding: Binding;
  reference: string;
  formatMask: string | null;
}

export interface ControlTotal {
  label: string;
  reconcilesAgainst: ReconcilesAgainst;
  tolerance: number | string;
  releaseGate: boolean;
}

export interface OutputVersion {
  id: number;
  number: number;
  effectiveFrom: string;
  effectiveTo: string | null;
  layoutFormat: LayoutFormat;
  periodLockRequired: boolean;
  inForce: boolean;
  fields: OutputField[];
  controls: ControlTotal[];
}

/** One statutory output: BLOCKED while no Active version governs today (SRS M-17). */
export interface StatutoryOutput {
  key: string;
  name: string;
  producingScreen: string | null;
  statutoryReference: string | null;
  state: 'ACTIVE' | 'BLOCKED';
  versions: OutputVersion[];
  pendingApprovals: number[];
}

export interface VersionProposal {
  effectiveFrom: string;
  layoutFormat: LayoutFormat;
  layoutNote: string;
  roundingRuleKey: string;
  periodLockRequired: boolean;
  fields: OutputField[];
  controls: ControlTotal[];
  reason: string;
}

// ---------------------------------------------------------------- C-10 Form 16 (08g)

export type CertificateStatus = 'DRAFT' | 'PENDING_PART_A' | 'MERGED' | 'RELEASED';

/** One certificate as C-10 and E-01 show it; never the PAN (SRS-7-23). */
export interface Certificate {
  id: number;
  employeeId: number;
  eis: string | null;
  name: string | null;
  finYear: string;
  grossSalary: number;
  taxableIncome: number;
  taxDeducted: number;
  partAStatus: 'PENDING' | 'RECEIVED';
  status: CertificateStatus;
  partBGeneratedAt: string | null;
  releasedAt: string | null;
  partADocumentId: number | null;
  /** SRS l.2589 / SRS-7-23: the PAN, HRMS, masked; on the register only. */
  panMasked?: string | null;
  /** SRS-7-13: VALID, or MISSING / INVALID shown as the exception before release. */
  panStatus?: 'VALID' | 'INVALID' | 'MISSING' | null;
}

export interface Generation {
  generated: number;
  skipped: { eis: string; reason: string }[];
}

export interface DocumentParticulars {
  documentType: string;
  reference: string;
  documentDate: string;
  issuingAuthority: string;
}

// ---------------------------------------------------------------- C-11 Form 24Q (08h)

export type ReturnStatus = 'DRAFT' | 'GENERATED' | 'VALIDATED' | 'RELEASED' | 'FILED' | 'REJECTED';
export type Quarter = 'Q1' | 'Q2' | 'Q3' | 'Q4';

export interface Filing {
  filingDate: string;
  signatoryName: string;
  signatoryDesignation: string;
  acknowledgementToken: string | null;
  evidenceId: number | null;
}

export interface StatutoryReturn {
  id: number;
  type: 'FORM_24Q' | 'FORM_24Q_CORRECTION';
  finYear: string;
  quarter: Quarter;
  deductees: number;
  totalTds: number | null;
  status: ReturnStatus;
  validated: boolean;
  validationUtility: string | null;
  validationResult: string[];
  correction: boolean;
  correctsReturnId: number | null;
  generatedAt: string | null;
  releasedBy: string | null;
  filings: Filing[];
}

/** One deductee: the PAN masked (SRS-7-23). */
export interface Deductee {
  employeeId: number;
  eis: string | null;
  name: string | null;
  panMasked: string | null;
  panValid: boolean;
  higherRateApplied: boolean;
  amountPaid: number;
  taxDeducted: number;
  challanId: number | null;
  /** SRS l.2625: the mapped challan as CIN / BSR / date. */
  challanReference?: string | null;
}

/** SRS C-11 l.2625 "User Input": a challan the Maker keys (the columns of a challan-file line). */
export interface NewChallan {
  cin: string;
  bsr: string;
  date: string;
  amount: string;
  reference: string;
  period: string;
}

export interface Challan {
  id: number;
  cin: string;
  bsr: string;
  date: string;
  amount: number;
  reference: string | null;
  period: string;
}

export interface NewFiling {
  filingDate: string;
  signatoryName: string;
  signatoryDesignation: string;
  acknowledgementToken: string;
  evidence: DocumentParticulars;
  file: File | null;
}

/** A JSON part named {@code request} beside an optional file part. */
function multipart(request: object, file: File | null): FormData {
  const form = new FormData();
  form.append('request', new Blob([JSON.stringify(request)], { type: 'application/json' }));
  if (file) form.append('file', file);
  return form;
}

const year = (finYear: string) => `finYear=${encodeURIComponent(finYear)}`;

/** The endpoints of C-07, M-17, C-10 and C-11 (backend TaxComputationController, OutputDefinitionController, StatutoryOutputController). */
export const statutoryApi = {
  forecast: (eis: string, finYear: string) =>
    api<Forecast>(`/v1/tax/forecasts/${encodeURIComponent(eis)}?${year(finYear)}`),
  forecastExportUrl: (eis: string, finYear: string, format: 'CSV' | 'XLSX') =>
    `${API_BASE}/v1/tax/forecasts/${encodeURIComponent(eis)}/export?${year(finYear)}&format=${format}`,
  /** SRS-7-28: a member's what-if (C-09 RUN), or the employee's own ({@code eis} null, E-01). */
  whatIf: (eis: string | null, finYear: string, body: WhatIfRequest) =>
    api<WhatIfResult>(
      `/v1/tax/forecasts/${eis === null ? 'mine' : encodeURIComponent(eis)}/what-ifs?${year(finYear)}`,
      { method: 'POST', body },
    ),

  outputs: () => api<StatutoryOutput[]>('/v1/tax/output-definitions'),
  proposeVersion: (key: string, body: VersionProposal) =>
    api<{ approvalId: number }>(`/v1/tax/output-definitions/${encodeURIComponent(key)}/versions`, {
      method: 'POST',
      body: {
        ...body,
        fields: body.fields.map((f) => ({ ...f, formatMask: f.formatMask || null })),
        roundingRuleKey: body.roundingRuleKey || null,
        layoutNote: body.layoutNote || null,
      },
    }),

  certificates: (finYear: string, status: string, page: number, size: number) =>
    api<PageResult<Certificate>>(
      `/v1/tax/form16-certificates?${year(finYear)}${status ? `&status=${status}` : ''}&page=${page}&size=${size}`,
    ),
  /** {@code eis}: the member population (empty: every member of the paying unit). */
  generateCertificates: (finYear: string, eis: string[] = []) =>
    api<Generation>('/v1/tax/form16-certificates/generate', {
      method: 'POST',
      body: { finYear, eis: eis.length > 0 ? eis : null },
    }),
  uploadPartA: (id: number, particulars: DocumentParticulars, file: File) =>
    api<Certificate>(`/v1/tax/form16-certificates/${id}/part-a`, {
      method: 'POST',
      body: multipart({ ...particulars, reference: particulars.reference || null }, file),
    }),
  releaseCertificates: (finYear: string) =>
    api<{ approvalId: number }>('/v1/tax/form16-certificates/release', { method: 'POST', body: { finYear } }),
  reissue: (id: number, reason: string) =>
    api<Certificate>(`/v1/tax/form16-certificates/${id}/reissue`, { method: 'POST', body: { reason } }),
  partAUrl: (id: number) => `${API_BASE}/v1/tax/form16-certificates/${id}/part-a`,
  myCertificates: () => api<Certificate[]>('/v1/tax/form16-certificates/mine'),

  returns: (finYear: string, page: number, size: number) =>
    api<PageResult<StatutoryReturn>>(`/v1/tax/returns?${year(finYear)}&page=${page}&size=${size}`),
  deductees: (id: number, page: number, size: number) =>
    api<PageResult<Deductee>>(`/v1/tax/returns/${id}/deductees?page=${page}&size=${size}`),
  challans: (finYear: string) => api<Challan[]>(`/v1/tax/challans?${year(finYear)}`),
  enterChallan: (finYear: string, c: NewChallan) =>
    api<Challan>(`/v1/tax/challans?${year(finYear)}`, {
      method: 'POST',
      body: { ...c, reference: c.reference.trim() === '' ? null : c.reference.trim() },
    }),
  importChallans: (finYear: string, file: File) => {
    const form = new FormData();
    form.append('file', file);
    return api<{ imported: number; duplicates: string[] }>(`/v1/tax/challans/import?${year(finYear)}`, {
      method: 'POST',
      body: form,
    });
  },
  generateReturn: (finYear: string, quarter: Quarter, correctsReturnId: number | null) =>
    api<StatutoryReturn>('/v1/tax/returns/generate', {
      method: 'POST',
      body: { finYear, quarter, correctsReturnId },
    }),
  validateReturn: (id: number) =>
    api<StatutoryReturn>(`/v1/tax/returns/${id}/validate`, { method: 'POST', body: {} }),
  releaseReturn: (id: number) =>
    api<{ approvalId: number }>(`/v1/tax/returns/${id}/release`, { method: 'POST', body: {} }),
  recordFiling: (id: number, filing: NewFiling) => {
    const { file, ...request } = filing;
    return api<StatutoryReturn>(`/v1/tax/returns/${id}/filings`, {
      method: 'POST',
      body: multipart(
        {
          ...request,
          acknowledgementToken: request.acknowledgementToken || null,
          evidence: file ? { ...request.evidence, reference: request.evidence.reference || null } : null,
        },
        file,
      ),
    });
  },
  evidenceUrl: (id: number, evidenceId: number) => `${API_BASE}/v1/tax/returns/${id}/documents/${evidenceId}`,
};
