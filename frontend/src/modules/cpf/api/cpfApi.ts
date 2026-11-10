import { PageResult } from '@/shared/ui';
import { api, API_BASE } from '@/shared/api';
import type { OtpProof } from '@/modules/workflow';

/** pf_interest_rate.rate_source (DDL CHECK): FCI_TRUST = FCI-declared, RPFC = EPFO-declared. */
export type RateSource = 'FCI_TRUST' | 'RPFC';

/** pf_interest_rate.fund_type (DDL CHECK). */
export type FundType = 'CPF' | 'PF' | 'VPF' | 'GPF';

// ---------------------------------------------------------------- M-19 PF interest rates

export interface InterestRate {
  id: number;
  finYear: string;
  fundType: FundType;
  rateSource: RateSource;
  memberPopulation: 'ACTIVE_AND_DEPUTED' | 'INACTIVE';
  ratePercent: number;
  appliesFrom: string;
  appliesTo: string | null;
  applicabilityNote: string | null;
  bonusInterestPct: number | null;
  declared: boolean;
  status: 'ACTIVE' | 'SUPERSEDED';
  approvalId: number | null;
}

/** A change awaiting the Rule Approver: a new rate (RECORD) or the declaration of a recorded one. */
export interface PendingRateChange {
  approvalId: number;
  kind: 'RECORD' | 'DECLARE';
  rateId: number | null;
  finYear: string;
  fundType: FundType;
  rateSource: RateSource;
  ratePercent: number;
  appliesFrom: string;
  appliesTo: string | null;
  bonusInterestPct: number | null;
  declared: boolean;
  proposedBy: string;
  proposedAt: string;
}

export interface InterestRates {
  rates: InterestRate[];
  pending: PendingRateChange[];
}

/** M-19 Edit/Entry: a rate to record (amounts as typed, sent as text so no decimal is lost). */
export interface NewRate {
  finYear: string;
  fundType: FundType;
  rateSource: RateSource;
  ratePercent: string;
  appliesFrom: string;
  appliesTo: string | null;
  applicabilityNote: string | null;
  bonusInterestPct: string | null;
  declared: boolean;
  reason: string;
}

export interface Declaration {
  ratePercent: string;
  reason: string;
}

// ---------------------------------------------------------------- M-19 withdrawal purposes

export type WithdrawalCategory = 'ADVANCE' | 'PART_FINAL' | 'FINAL';
export type VerificationTier = 'UNIT_CPF_ADMIN' | 'ZONAL_HQ_CPF_DIVISION' | 'COMPETENT_AUTHORITY';

export interface ChecklistDocument {
  documentType: string;
  mandatory: boolean;
  tier: VerificationTier;
}

/** One version of a withdrawal purpose with its checklist. */
export interface WithdrawalPurpose {
  id: number;
  code: string;
  name: string;
  category: WithdrawalCategory;
  limitRuleKey: string | null;
  maxOccurrences: number | null;
  noCasteDispute: boolean;
  propertyDetails: boolean;
  effectiveFrom: string;
  effectiveTo: string | null;
  confirmed: boolean;
  inForce: boolean;
  documents: ChecklistDocument[];
}

/** A purpose version as proposed (and as it waits for the Rule Approver). */
export interface PurposeProposal {
  code: string;
  name: string;
  category: WithdrawalCategory;
  limitRuleKey: string | null;
  maxOccurrences: number | null;
  noCasteDispute: boolean;
  propertyDetails: boolean;
  effectiveFrom: string;
  confirmed: boolean;
  documents: ChecklistDocument[];
}

export interface PendingPurpose {
  approvalId: number;
  proposal: PurposeProposal & { proposedBy: string; proposedAt: string };
}

export interface WithdrawalPurposes {
  purposes: WithdrawalPurpose[];
  pending: PendingPurpose[];
}

// ---------------------------------------------------------------- I-22 opening-balance correction

/** The parts of a CPF balance (employee_pf_detail / opening_balance_correction.balance_part). */
export type LedgerPart = 'EMPLOYEE' | 'EMPLOYER' | 'VPF';

export interface Balances {
  employee: number;
  employer: number;
  vpf: number;
}

/** The member and the opening balance of the financial year containing the as-at date. */
export interface OpeningBalance {
  eis: string;
  name: string;
  cpfCode: string;
  finYear: string;
  opening: Balances;
  /** The CPF final settlement is sanctioned: no correction (SRS-6-44). */
  settled: boolean;
  /** A correction of the year is awaiting a decision or the CPF Division's review. */
  pending: boolean;
  /** SRS I-22 field table (l.2234): the member's UAN, HRMS, read-only; null when none. */
  uan?: string | null;
}

/** SRS I-22 "Member (CPF Code / UAN)" (l.2234): how the member is looked up. */
export type MemberLookup = 'eis' | 'cpfCode' | 'uan';

export type CorrectionStatus =
  | 'DRAFT'
  | 'PENDING_AGM_CPF'
  | 'PENDING_HQ_CPF'
  | 'RETURNED'
  | 'APPROVED'
  | 'REJECTED'
  | 'BLOCKED_CPF_DIVISION'
  | 'APPLIED'
  | 'CANCELLED';

export interface CorrectionPart {
  part: LedgerPart;
  oldAmount: number;
  newAmount: number;
  difference: number;
}

/** A correction as the Opening Balance Correction Register shows it. */
export interface Correction {
  ref: string;
  eis: string;
  finYear: string;
  asAtDate: string;
  parts: CorrectionPart[];
  reasonCode: string;
  remarks: string;
  interestEffectiveDate: string;
  interestEffect: number | null;
  status: CorrectionStatus;
  statusReason: string | null;
  raisedBy: string;
  raisedAt: string;
  checker: string | null;
  hqApprover: string | null;
  appliedAt: string | null;
  approvalId: number | null;
  evidenceId: number | null;
}

/** I-22 Edit/Entry: the corrected parts as typed (a part left null is not corrected). */
export interface NewCorrection {
  eis: string;
  asAtDate: string;
  employee: string | null;
  employer: string | null;
  vpf: string | null;
  reasonCode: string;
  interestEffectiveDate: string;
  remarks: string;
}

/** The evidence through the standard order-copy control (SRS-G-30). */
export interface CorrectionEvidence {
  documentType: string;
  reference: string;
  orderDate: string;
  issuingAuthority: string;
  file: File;
}

// ---------------------------------------------------------------- C-12 interest run

export type RunScope = 'ALL_MEMBERS' | 'INDIVIDUAL';
export type FreezeStatus = 'OPEN' | 'PENDING' | 'FROZEN';

/** One run of the Interest Run Audit list. */
export interface InterestRun {
  run: number;
  scope: RunScope;
  eis: string | null;
  initiatedBy: string;
  initiatedAt: string;
  members: number;
  calculated: number;
  notCalculated: number;
  stopped: number;
  /** Null, with eis, for an individual run of a member outside the session's data scope (SRS-G-29). */
  reason: string | null;
}

/** The C-12 tiles and run list of the zone's financial year. */
export interface InterestRunSummary {
  finYear: string;
  zone: string;
  members: number;
  calculated: number;
  notYetCalculated: number;
  reRuns: number;
  freezeStatus: FreezeStatus;
  frozenBy: string | null;
  frozenAt: string | null;
  runs: InterestRun[];
}

/** A member whose interest is not yet calculated, and why (SRS-6-45 report). */
export interface NotCalculatedMember {
  eis: string | null;
  name: string | null;
  memberStatus: string;
  reason: string;
  interestEndDate: string | null;
  interestStopped: boolean;
}

export interface NewRun {
  finYear: string;
  scope: RunScope;
  eis: string | null;
  reason: string;
}

export interface FreezeRequested {
  approvalId: number;
  finYear: string;
  zone: string;
}

function correctionForm(body: NewCorrection, evidence: CorrectionEvidence): FormData {
  const form = new FormData();
  for (const [key, value] of Object.entries(body)) {
    if (value !== null && value !== '') form.append(key, value);
  }
  form.append('documentType', evidence.documentType);
  if (evidence.reference) form.append('reference', evidence.reference);
  form.append('orderDate', evidence.orderDate);
  form.append('issuingAuthority', evidence.issuingAuthority);
  form.append('file', evidence.file);
  return form;
}

// ---------------------------------------------------------------- I-09 deputation adjustment (07g)

/** cpf_deputation_recovery.entry_type (DDL CHECK; SRS-6-27 … 6-29). */
export type DeputationEntryType = 'RECOVERY_INSTALMENT' | 'CONTRIBUTION_RECEIPT' | 'EXCESS_REFUND';

/** cpf_deputation_recovery.entry_status (DDL CHECK). */
export type DeputationStatus = 'MAKER_SAVED' | 'PENDING_CHECKER' | 'APPROVED' | 'REJECTED';

export interface BorrowingOrganisation {
  id: number;
  code: string;
  name: string;
  contact: string | null;
  active: boolean;
}

/** A sanctioned advance of the deputed member with what is outstanding (SRS-6-28). */
export interface DeputationAdvance {
  sanctionId: number;
  sanctionNumber: string | null;
  sanctioned: number;
  outstanding: number;
}

export interface DeputationEntry {
  id: number;
  eis: string;
  organisation: string;
  type: DeputationEntryType;
  sanctionId: number | null;
  payrollYymm: string;
  instalment: number;
  employee: number;
  employer: number;
  vpf: number;
  outstandingBefore: number | null;
  balanceAfter: number | null;
  status: DeputationStatus;
  approvalId: number | null;
  evidenceId: number | null;
  /** I-09 Remittance Evidence: the active files, up to five (SRS-G-31: added, never replaced). */
  evidence: DeputationEvidenceFile[];
  /** SRS l.2167: the sanctioned advance an instalment repays (its sanction number). */
  sanctionNumber?: string | null;
  /** SRS-G-2: the AGM (CPF)'s reason of a rejection or a return to the Maker. */
  decisionReason?: string | null;
}

/** An active remittance evidence file of an I-09 entry. */
export interface DeputationEvidenceFile {
  id: number;
  fileName: string;
  uploadedBy: string;
  uploadedAt: string;
}

/** The deputed member I-09 works on: the deputation is HRMS's, read-only. */
export interface DeputedMember {
  eis: string;
  name: string;
  deputationFrom: string;
  deputationTo: string | null;
  advances: DeputationAdvance[];
  monthsWithoutReceipt: string[];
  entries: DeputationEntry[];
}

/** What the Manager (CPF) keys; amounts as typed. */
export interface NewDeputationEntry {
  eis: string;
  borrowingOrgId: string;
  type: DeputationEntryType;
  sanctionId: string;
  payrollYymm: string;
  instalment: string;
  employee: string;
  employer: string;
  vpf: string;
}

function deputationForm(body: NewDeputationEntry, evidence: CorrectionEvidence): FormData {
  const form = new FormData();
  for (const [key, value] of Object.entries(body)) {
    if (value !== '') form.append(key, value);
  }
  form.append('documentType', evidence.documentType);
  if (evidence.reference) form.append('reference', evidence.reference);
  form.append('orderDate', evidence.orderDate);
  form.append('issuingAuthority', evidence.issuingAuthority);
  form.append('file', evidence.file);
  return form;
}

// ---------------------------------------------------------------- I-04 / E-01 CPF withdrawal chain (07f-2)

/** pf_sanction.workflow_stage (DDL CHECK; SRS-6-4). */
export type SanctionStage =
  | 'UNIT_VERIFICATION'
  | 'ZONAL_HQ_VALIDATION'
  | 'COMPETENT_AUTHORITY'
  | 'SANCTIONED'
  | 'RETURNED'
  | 'REJECTED'
  | 'PAYMENT_BLOCKED'
  | 'PAID'
  | 'CANCELLED';

export interface SanctionTier {
  pass: number;
  tier: 1 | 2 | 3;
  tierCode: string;
  action: 'FORWARDED' | 'RECOMMENDED' | 'SANCTIONED' | 'RETURNED' | 'REJECTED';
  actedBy: string;
  actedAt: string;
  remark: string | null;
  ceiling: number | null;
  capped: number | null;
}

export interface SanctionDocument {
  id: number;
  documentTypeCode: string;
  documentTypeName: string;
  referenceNumber: string | null;
  orderDate: string | null;
  issuingAuthority: string | null;
  fileName: string;
}

/** The I-04 tile: the requests waiting at a tier, by type (SRS-6-4). */
export interface SanctionPending {
  pending: number;
  advance: number;
  partFinal: number;
  finalPayment: number;
}

export interface Sanction {
  /** BR-CPF-029: the name of the purpose's limit rule; null when the purpose has none. */
  limitRule?: string | null;
  id: number;
  eis: string;
  memberName: string;
  type: 'ADVANCE' | 'PART_FINAL' | 'FINAL';
  purposeCode: string | null;
  purposeName: string | null;
  requested: number | null;
  ceiling: number | null;
  sanctioned: number | null;
  capped: boolean;
  stage: SanctionStage;
  sanctionNumber: string | null;
  sanctionDate: string | null;
  propertyAddress: string | null;
  propertyDescription: string | null;
  approvalId: number | null;
  cancelReason: string | null;
  tiers: SanctionTier[];
  documents: SanctionDocument[];
  /** HRMS; verified by the Unit CPF Administrator at tier 1 (SRS I-04 field table). */
  uan: string | null;
  /** HRMS; the last four digits only (SRS-6-51). */
  aadhaarMasked: string | null;
  /** SRS-6-14 (US-GAP-28): the purpose needs the No Caste Dispute Certificate at tier 1. */
  noCasteDisputeRequired: boolean;
  /**
   * SRS I-04 "Withdrawal Count" (l.2279; BR-CPF-010): the member's other withdrawals that count, by
   * type and date. On the detail only (null on a register row).
   */
  earlierWithdrawals?: EarlierWithdrawal[] | null;
}

/** One earlier withdrawal of the member on the withdrawal register (SRS l.2279). */
export interface EarlierWithdrawal {
  id: number;
  type: 'ADVANCE' | 'PART_FINAL' | 'FINAL';
  purposeCode: string | null;
  stage: SanctionStage;
  sanctionNumber: string | null;
  sanctionDate: string | null;
}

/** SRS-6-14: the certificate the Unit CPF Administrator attaches at tier 1. */
export interface UnitCertificate {
  documentDate: string;
  issuingAuthority: string;
  file: File;
}

/** The document type of the No Caste Dispute Certificate (V067_1). */
export const NO_CASTE_DISPUTE = 'NO_CASTE_DISPUTE';

export interface PurposeOption {
  code: string;
  name: string;
  category: 'ADVANCE' | 'PART_FINAL' | 'FINAL';
  propertyDetails: boolean;
  documents: { documentType: string; mandatory: boolean }[];
}

/** One document of the member's request with its particulars (SRS-G-30). */
export interface SanctionFile {
  documentType: string;
  reference: string;
  documentDate: string;
  issuingAuthority: string;
  file: File;
}

export interface NewSanction {
  purposeCode: string;
  amount: string;
  propertyAddress: string;
  propertyDescription: string;
  documents: SanctionFile[];
}

/** A tier decision; REJECT is tier 2's only (FRS V0.5 §13.4 Step 3, US-GAP-164). */
export type TierDecision = 'FORWARD' | 'RETURN' | 'REJECT';

/** The documents as parallel lists: the n-th file with the n-th particulars. */
function sanctionForm(body: NewSanction): FormData {
  // The particulars travel as one JSON part (CpfSanctionController.SanctionRequest): as form
  // fields, a lone value with a comma would be split into a list by the server.
  const request = {
    purposeCode: body.purposeCode,
    amount: body.amount,
    propertyAddress: body.propertyAddress || null,
    propertyDescription: body.propertyDescription || null,
    documents: body.documents.map(({ documentType, reference, documentDate, issuingAuthority }) => ({
      documentType,
      reference,
      documentDate,
      issuingAuthority,
    })),
  };
  const form = new FormData();
  form.append('request', new Blob([JSON.stringify(request)], { type: 'application/json' }));
  for (const d of body.documents) form.append('file', d.file);
  return form;
}

const withOtp = <T extends object>(body: T, otp?: OtpProof) => ({ ...body, ...(otp ? { otp } : {}) });

export const cpfApi = {
  interestRates: () => api<InterestRates>('/v1/cpf/interest-rates'),
  recordRate: (body: NewRate, otp?: OtpProof) =>
    api<PendingRateChange>('/v1/cpf/interest-rates', { method: 'POST', body: withOtp(body, otp) }),
  declareRate: (id: number, body: Declaration, otp?: OtpProof) =>
    api<PendingRateChange>(`/v1/cpf/interest-rates/${id}/declare`, {
      method: 'POST',
      body: withOtp(body, otp),
    }),
  withdrawalPurposes: () => api<WithdrawalPurposes>('/v1/cpf/withdrawal-purposes'),
  proposePurpose: (body: PurposeProposal & { reason: string }, otp?: OtpProof) =>
    api<PendingPurpose>('/v1/cpf/withdrawal-purposes', { method: 'POST', body: withOtp(body, otp) }),
  openingBalance: (value: string, asAt: string, by: MemberLookup = 'eis') =>
    api<OpeningBalance>(
      `/v1/cpf/opening-balances?${by}=${encodeURIComponent(value)}&asAt=${encodeURIComponent(asAt)}`,
    ),
  corrections: (page: number, size: number) =>
    api<PageResult<Correction>>(`/v1/cpf/opening-balance-corrections?page=${page}&size=${size}`),
  raiseCorrection: (body: NewCorrection, evidence: CorrectionEvidence) =>
    api<Correction>('/v1/cpf/opening-balance-corrections', {
      method: 'POST',
      body: correctionForm(body, evidence),
    }),
  interestRuns: (finYear: string) =>
    api<InterestRunSummary>(`/v1/cpf/interest-runs?finYear=${encodeURIComponent(finYear)}`),
  notCalculated: (finYear: string, page: number, size: number) =>
    api<PageResult<NotCalculatedMember>>(
      `/v1/cpf/interest-runs/not-calculated?finYear=${encodeURIComponent(finYear)}&page=${page}&size=${size}`,
    ),
  runInterest: (body: NewRun) => api<InterestRun>('/v1/cpf/interest-runs', { method: 'POST', body }),
  requestFreeze: (finYear: string, reason: string) =>
    api<FreezeRequested>('/v1/cpf/interest-runs/freeze', { method: 'POST', body: { finYear, reason } }),
  /** I-09 (07g): deputation adjustments. */
  borrowingOrganisations: () => api<BorrowingOrganisation[]>('/v1/cpf/borrowing-organisations'),
  addBorrowingOrganisation: (body: { code: string; name: string; contact: string | null }) =>
    api<BorrowingOrganisation>('/v1/cpf/borrowing-organisations', { method: 'POST', body }),
  deputedMember: (eis: string) => api<DeputedMember>(`/v1/cpf/deputations/${encodeURIComponent(eis)}`),
  deputationEntries: (page: number, size: number) =>
    api<PageResult<DeputationEntry>>(`/v1/cpf/deputation-entries?page=${page}&size=${size}`),
  recordDeputation: (body: NewDeputationEntry, evidence: CorrectionEvidence) =>
    api<DeputationEntry>('/v1/cpf/deputation-entries', {
      method: 'POST',
      body: deputationForm(body, evidence),
    }),
  /** SRS-G-31: evidence files added to an entry after submission. */
  addDeputationEvidence: (id: number, evidence: CorrectionEvidence) => {
    const form = new FormData();
    form.append('documentType', evidence.documentType);
    if (evidence.reference) form.append('reference', evidence.reference);
    form.append('orderDate', evidence.orderDate);
    form.append('issuingAuthority', evidence.issuingAuthority);
    form.append('file', evidence.file);
    return api<DeputationEvidenceFile[]>(`/v1/cpf/deputation-entries/${id}/evidence`, {
      method: 'POST',
      body: form,
    });
  },
  /** SRS-G-31: a wrong evidence file is cancelled with the reason, never deleted. */
  cancelDeputationEvidence: (id: number, fileId: number, reason: string) =>
    api<DeputationEvidenceFile[]>(`/v1/cpf/deputation-entries/${id}/evidence/${fileId}/cancel`, {
      method: 'POST',
      body: { reason },
    }),
  deputationEvidenceUrl: (id: number, fileId: number) =>
    `${API_BASE}/v1/cpf/deputation-entries/${id}/evidence/${fileId}`,
  /** The evidence is downloaded through the browser's session (a link, not a fetch). */
  evidenceUrl: (ref: string, fileId: number) =>
    `${API_BASE}/v1/cpf/opening-balance-corrections/${encodeURIComponent(ref)}/evidence/${fileId}`,
  /** E-01 / I-04 (07f-2): the CPF withdrawal chain. */
  sanctionPurposes: () => api<PurposeOption[]>('/v1/cpf/sanctions/purposes'),
  mySanctions: () => api<Sanction[]>('/v1/cpf/sanctions/mine'),
  raiseSanction: (body: NewSanction) =>
    api<Sanction>('/v1/cpf/sanctions', { method: 'POST', body: sanctionForm(body) }),
  withdrawSanction: (id: number, reason: string) =>
    api<Sanction>(`/v1/cpf/sanctions/${id}/withdraw`, { method: 'POST', body: { reason } }),
  resubmitSanction: (
    id: number,
    body: { amount: string; propertyAddress: string; propertyDescription: string },
  ) => api<Sanction>(`/v1/cpf/sanctions/${id}/resubmit`, { method: 'POST', body }),
  sanctions: (stage: string, page: number, size: number) =>
    api<PageResult<Sanction>>(
      `/v1/cpf/sanctions?${stage ? `stage=${encodeURIComponent(stage)}&` : ''}page=${page}&size=${size}`,
    ),
  sanction: (id: number) => api<Sanction>(`/v1/cpf/sanctions/${id}`),
  sanctionsPending: () => api<SanctionPending>('/v1/cpf/sanctions/pending-summary'),
  attachUnitCertificate: (id: number, c: UnitCertificate) => {
    const form = new FormData();
    const document = {
      documentType: NO_CASTE_DISPUTE,
      documentDate: c.documentDate,
      issuingAuthority: c.issuingAuthority,
    };
    form.append('document', new Blob([JSON.stringify(document)], { type: 'application/json' }));
    form.append('file', c.file);
    return api<Sanction>(`/v1/cpf/sanctions/${id}/documents`, { method: 'POST', body: form });
  },
  verifySanction: (id: number, decision: TierDecision, remark: string) =>
    api<Sanction>(`/v1/cpf/sanctions/${id}/verify`, { method: 'POST', body: { decision, remark } }),
  recommendSanction: (id: number, decision: TierDecision, remark: string, otp?: OtpProof) =>
    api<Sanction>(`/v1/cpf/sanctions/${id}/recommend`, {
      method: 'POST',
      body: withOtp({ decision, remark }, otp),
    }),
  sanctionDocumentUrl: (id: number, fileId: number) =>
    `${API_BASE}/v1/cpf/sanctions/${id}/documents/${fileId}`,
};
