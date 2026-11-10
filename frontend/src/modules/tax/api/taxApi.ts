import { PageResult } from '@/shared/ui';
import { api, API_BASE } from '@/shared/api';
import type { OtpProof } from '@/modules/workflow';

/** it_section_master.regime_applicability (DDL CHECK). */
export type Regime = 'OLD' | 'NEW' | 'BOTH';

// ---------------------------------------------------------------- M-18 income-tax sections

/** One version of an income-tax section (SRS-7-2, SRS-7-20; BR-TAX-002). */
export interface SectionVersion {
  id: number;
  code: string;
  description: string;
  /** The limit in rupees; null when the section has no fixed limit. */
  limit: number | null;
  regime: Regime;
  effectiveFrom: string;
  effectiveTo: string | null;
  inForce: boolean;
}

/** A section version as proposed (and as it waits for the Rule Approver). */
export interface SectionProposal {
  code: string;
  description: string;
  limit: string | null;
  regime: Regime;
  effectiveFrom: string;
}

export interface PendingSection {
  approvalId: number;
  proposal: SectionProposal & { proposedBy: string; proposedAt: string };
}

// ---------------------------------------------------------------- M-18 Professional Tax states

/** One PT state (SRS-7-4, BR-TAX-006). {@code slabRuleKey} is set by the system. */
export interface PtState {
  id: number;
  code: string;
  name: string;
  ptApplicable: boolean;
  registrationNumber: string | null;
  slabRuleKey: string | null;
  returnPeriodicity: string | null;
  effectiveFrom: string;
  effectiveTo: string | null;
  inForce: boolean;
}

/** A state as proposed: new, or new values of an existing state. */
export interface StateProposal {
  code: string;
  name: string;
  ptApplicable: boolean;
  registrationNumber: string | null;
  returnPeriodicity: string | null;
  effectiveFrom: string;
  effectiveTo: string | null;
}

export interface PendingState {
  approvalId: number;
  proposal: StateProposal & { proposedBy: string; proposedAt: string };
}

// ---------------------------------------------------------------- I-07 / E-01 declarations (08c)

/** it_declaration.declaration_kind. */
export type DeclarationKind = 'INVESTMENT' | 'HRA_RENT';
export type RoundStatus = 'SUBMITTED' | 'VERIFIED' | 'APPROVED' | 'RETURNED' | 'SUPERSEDED';
export type TaxRegime = 'OLD' | 'NEW';

/** One line of a round; {@code capped}: above its section limit, so the member is told (SRS-7-16). */
export interface DeclarationLine {
  id: number;
  kind: DeclarationKind;
  sectionCode: string;
  declared: number;
  allowed: number;
  capped: boolean;
  proofStatus: string;
  verified: number | null;
  frozen: boolean;
  proofId: number | null;
}

/** One round of a member's declarations (never PAN). */
export interface DeclarationRound {
  id: number;
  employeeId: number;
  eis: string | null;
  name: string | null;
  finYear: string;
  number: number;
  status: RoundStatus;
  submittedAt: string;
  verifiedBy: string | null;
  returnReason: string | null;
  proofDeadlineReversed: boolean;
  regime: TaxRegime | null;
  regimeFrozen: boolean;
  lines: DeclarationLine[];
}

/** A section the member may declare under. */
export interface SectionOption {
  code: string;
  description: string;
  limit: number | null;
  regime: Regime;
}

/** E-01: the member's year. */
export interface MyDeclarations {
  finYear: string;
  windowOpen: boolean;
  proofsUntil: string | null;
  regime: TaxRegime | null;
  regimeFrozen: boolean;
  sections: SectionOption[];
  rounds: DeclarationRound[];
}

/** A proof's particulars (SRS-G-30) and its file. */
export interface ProofFile {
  documentType: string;
  reference: string;
  documentDate: string;
  issuingAuthority: string;
  file: File;
}

export interface NewDeclarationLine {
  kind: DeclarationKind;
  sectionCode: string;
  declared: string;
  proof: ProofFile | null;
}

export interface NewDeclaration {
  regime: TaxRegime | null;
  lines: NewDeclarationLine[];
}

/** The lines as one JSON part and the files in order: the n-th file with the n-th line naming a proof. */
function declarationForm(body: NewDeclaration): FormData {
  const request = {
    regime: body.regime,
    lines: body.lines.map((l) => ({
      kind: l.kind,
      sectionCode: l.sectionCode,
      declared: l.declared,
      proof: l.proof
        ? {
            documentType: l.proof.documentType,
            reference: l.proof.reference,
            documentDate: l.proof.documentDate,
            issuingAuthority: l.proof.issuingAuthority,
          }
        : null,
    })),
  };
  const form = new FormData();
  form.append('request', new Blob([JSON.stringify(request)], { type: 'application/json' }));
  for (const l of body.lines) if (l.proof) form.append('file', l.proof.file);
  return form;
}

// ---------------------------------------------------------------- I-18 savings and income (08d)

export type EntryType = 'EXTERNAL_SAVINGS' | 'OTHER_INCOME';
export type EntryStatus = 'MAKER_SAVED' | 'PENDING_CHECKER' | 'APPROVED' | 'REJECTED' | 'CANCELLED';

/** One external savings or other income entry (SRS-7-25); never PAN. */
export interface SavingsEntry {
  id: number;
  employeeId: number;
  eis: string | null;
  name: string | null;
  finYear: string;
  type: EntryType;
  sectionCode: string | null;
  incomeHead: string | null;
  description: string | null;
  amount: number;
  tdsOtherSource: number;
  status: EntryStatus;
  documentId: number;
  enteredBy: string;
  enteredAt: string;
}

/** One savings or rebate row of a member's year. */
export interface SavingsRow {
  id: number;
  type: 'INTERNAL' | 'EXTERNAL' | 'TRANSPORT_REBATE';
  sectionCode: string | null;
  annualAmount: number | null;
  payrollYymm: string | null;
  monthlyAmount: number | null;
  componentId: number | null;
}

export interface RebateProposal {
  employeeId: number;
  finYear: string;
  months: Record<string, number>;
  proposedBy: string;
  proposedAt: string;
}

/** I-18 for one member and year. */
export interface MemberSavings {
  employeeId: number;
  eis: string;
  name: string;
  finYear: string;
  entries: SavingsEntry[];
  savings: SavingsRow[];
  pendingRebates: RebateProposal[];
  sections: SectionOption[];
}

export interface NewSavingsEntry {
  type: EntryType;
  sectionCode: string;
  incomeHead: string;
  description: string;
  amount: string;
  tdsOtherSource: string;
  document: { documentType: string; reference: string; documentDate: string; issuingAuthority: string };
  file: File;
}

/** The entry as one JSON part beside its one file. */
function entryForm(body: NewSavingsEntry): FormData {
  const { file, ...request } = body;
  const form = new FormData();
  form.append(
    'request',
    new Blob(
      [
        JSON.stringify({
          ...request,
          sectionCode: request.type === 'EXTERNAL_SAVINGS' ? request.sectionCode : null,
          incomeHead: request.type === 'OTHER_INCOME' ? request.incomeHead : null,
          tdsOtherSource: request.tdsOtherSource === '' ? '0' : request.tdsOtherSource,
        }),
      ],
      { type: 'application/json' },
    ),
  );
  form.append('file', file);
  return form;
}

/** A TDS case flagged for the Payroll Checker's review of the cycle (FRS V0.5 §14.4 Step 4). */
export interface FlaggedTdsCase {
  employeeId: number;
  eis: string;
  name: string;
  office: string | null;
  flag: 'PROOF_DEADLINE_REVERSAL' | 'REGIME_RESET';
  /** The declaration round (reversal) or the reset's approval item. */
  reference: number | null;
  reason: string | null;
  at: string | null;
}

const withOtp = <T extends object>(body: T, otp?: OtpProof) => ({ ...body, ...(otp ? { otp } : {}) });

/** The endpoints of the income-tax module (Module 08; backend TaxConfigurationController). */
export const taxApi = {
  flaggedTdsCases: (period: string, page: number, size: number) =>
    api<PageResult<FlaggedTdsCase>>(
      `/v1/tax/flagged-tds-cases?period=${encodeURIComponent(period)}&page=${page}&size=${size}`,
    ),
  sections: (page: number, size: number) =>
    api<PageResult<SectionVersion>>(`/v1/tax/sections?page=${page}&size=${size}`),
  pendingSections: () => api<PendingSection[]>('/v1/tax/sections/pending'),
  proposeSection: (body: SectionProposal & { reason: string }, otp?: OtpProof) =>
    api<PendingSection>('/v1/tax/sections', { method: 'POST', body: withOtp(body, otp) }),
  states: (page: number, size: number) =>
    api<PageResult<PtState>>(`/v1/tax/pt-states?page=${page}&size=${size}`),
  pendingStates: () => api<PendingState[]>('/v1/tax/pt-states/pending'),
  createState: (body: StateProposal & { reason: string }, otp?: OtpProof) =>
    api<PendingState>('/v1/tax/pt-states', { method: 'POST', body: withOtp(body, otp) }),
  changeState: (body: StateProposal & { reason: string }, otp?: OtpProof) =>
    api<PendingState>(`/v1/tax/pt-states/${encodeURIComponent(body.code)}`, {
      method: 'PUT',
      body: withOtp(body, otp),
    }),
  myDeclarations: () => api<MyDeclarations>('/v1/tax/declarations/mine'),
  submitDeclarations: (body: NewDeclaration) =>
    api<DeclarationRound>('/v1/tax/declarations/mine', { method: 'POST', body: declarationForm(body) }),
  declarations: (finYear: string, status: string, page: number, size: number) =>
    api<PageResult<DeclarationRound>>(
      `/v1/tax/declarations?finYear=${encodeURIComponent(finYear)}${status ? `&status=${status}` : ''}&page=${page}&size=${size}`,
    ),
  declaration: (id: number) => api<DeclarationRound>(`/v1/tax/declarations/${id}`),
  verifyDeclaration: (id: number, lines: { lineId: number; verified: string }[]) =>
    api<DeclarationRound>(`/v1/tax/declarations/${id}/verify`, { method: 'POST', body: { lines } }),
  returnDeclaration: (id: number, reason: string) =>
    api<DeclarationRound>(`/v1/tax/declarations/${id}/return`, { method: 'POST', body: { reason } }),
  resetRegime: (employeeId: number, finYear: string, reason: string) =>
    api<{ approvalId: number }>('/v1/tax/regimes/reset', {
      method: 'POST',
      body: { employeeId, finYear, reason },
    }),
  proofUrl: (id: number, proofId: number) => `${API_BASE}/v1/tax/declarations/${id}/proofs/${proofId}`,
  savingsEntries: (finYear: string, status: string, page: number, size: number) =>
    api<PageResult<SavingsEntry>>(
      `/v1/tax/savings?finYear=${encodeURIComponent(finYear)}${status ? `&status=${status}` : ''}&page=${page}&size=${size}`,
    ),
  /** I-18: one member's year; the year chosen on the screen (UI audit S5), never the server's default. */
  memberSavings: (eis: string, finYear: string) =>
    api<MemberSavings>(
      `/v1/tax/savings/members/${encodeURIComponent(eis)}?finYear=${encodeURIComponent(finYear)}`,
    ),
  saveEntry: (eis: string, body: NewSavingsEntry) =>
    api<SavingsEntry>(`/v1/tax/savings/members/${encodeURIComponent(eis)}/entries`, {
      method: 'POST',
      body: entryForm(body),
    }),
  submitEntry: (id: number) =>
    api<SavingsEntry>(`/v1/tax/savings/entries/${id}/submit`, { method: 'POST', body: {} }),
  cancelEntry: (id: number, reason: string) =>
    api<SavingsEntry>(`/v1/tax/savings/entries/${id}/cancel`, { method: 'POST', body: { reason } }),
  proposeRebate: (eis: string, months: Record<string, string>, reason: string) =>
    api<{ approvalId: number }>(`/v1/tax/savings/members/${encodeURIComponent(eis)}/transport-rebate`, {
      method: 'POST',
      body: { months, reason },
    }),
  entryDocumentUrl: (id: number, documentId: number) =>
    `${API_BASE}/v1/tax/savings/entries/${id}/documents/${documentId}`,
};
