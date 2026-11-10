'use client';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useGoverned } from '@/modules/workflow';
import {
  cpfApi,
  type CorrectionEvidence,
  type Declaration,
  type NewCorrection,
  type NewDeputationEntry,
  type MemberLookup,
  type NewRun,
  type PurposeProposal,
  type NewRate,
  type NewSanction,
  type TierDecision,
  type UnitCertificate,
} from '../api/cpfApi';

/**
 * Use cases of the CPF screens (Module 07): server state through TanStack Query; every change is a
 * maker-checker submission and changes nothing in force until the Rule Approver approves it.
 */
export const cpfKeys = {
  interestRates: ['cpf', 'interest-rates'] as const,
  openingBalances: ['cpf', 'opening-balance'] as const,
  openingBalance: (eis: string, asAt: string, by: MemberLookup = 'eis') =>
    ['cpf', 'opening-balance', by, eis, asAt] as const,
  corrections: ['cpf', 'opening-balance-corrections'] as const,
  interestRuns: ['cpf', 'interest-runs'] as const,
  purposes: ['cpf', 'withdrawal-purposes'] as const,
  interestRun: (finYear: string) => ['cpf', 'interest-runs', finYear] as const,
  notCalculated: (finYear: string) => ['cpf', 'interest-runs', finYear, 'not-calculated'] as const,
  sanctions: ['cpf', 'sanctions'] as const,
  sanctionList: (stage: string) => ['cpf', 'sanctions', 'list', stage] as const,
  sanction: (id: number) => ['cpf', 'sanctions', 'one', id] as const,
  mySanctions: ['cpf', 'sanctions', 'mine'] as const,
  sanctionPurposes: ['cpf', 'sanctions', 'purposes'] as const,
  deputations: ['cpf', 'deputation'] as const,
  deputedMember: (eis: string) => ['cpf', 'deputation', 'member', eis] as const,
  deputationEntries: ['cpf', 'deputation', 'entries'] as const,
  borrowingOrganisations: ['cpf', 'deputation', 'organisations'] as const,
};

export function useInterestRates() {
  return useQuery({ queryKey: cpfKeys.interestRates, queryFn: cpfApi.interestRates });
}

export function useRecordRate() {
  const run = useGoverned();
  const client = useQueryClient();
  return useMutation({
    mutationFn: (body: NewRate) => run((otp) => cpfApi.recordRate(body, otp)),
    onSuccess: () => client.invalidateQueries({ queryKey: cpfKeys.interestRates }),
  });
}

export function useDeclareRate() {
  const run = useGoverned();
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: { id: number; body: Declaration }) =>
      run((otp) => cpfApi.declareRate(input.id, input.body, otp)),
    onSuccess: () => client.invalidateQueries({ queryKey: cpfKeys.interestRates }),
  });
}

/** I-22: the member and the opening balance of the year containing {@code asAt}. */
export function useOpeningBalance(eis: string, asAt: string, by: MemberLookup = 'eis') {
  return useQuery({
    queryKey: cpfKeys.openingBalance(eis, asAt, by),
    queryFn: () => cpfApi.openingBalance(eis, asAt, by),
    enabled: eis !== '' && asAt !== '',
  });
}

/** I-22: the Opening Balance Correction Register of the user's scope. */

/**
 * I-22: raises a correction with its evidence. The Manager (CPF) gives no OTP; the AGM (CPF) and
 * the Headquarters CPF approver each give theirs in the Approvals Inbox (SRS-6-43).
 */
export function useRaiseCorrection() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: { body: NewCorrection; evidence: CorrectionEvidence }) =>
      cpfApi.raiseCorrection(input.body, input.evidence),
    onSuccess: async () => {
      await client.invalidateQueries({ queryKey: cpfKeys.corrections });
      await client.invalidateQueries({ queryKey: cpfKeys.openingBalances });
    },
  });
}

/** I-22: the evidence's download link (through the browser's session, never fetched into memory). */
export const evidenceUrl = cpfApi.evidenceUrl;

/** C-12: the tiles and run list of the zone's year (a valid year only). */
export function useInterestRuns(finYear: string, enabled: boolean) {
  return useQuery({
    queryKey: cpfKeys.interestRun(finYear),
    queryFn: () => cpfApi.interestRuns(finYear),
    enabled,
  });
}

/** C-12: the members not yet calculated, loaded when the report is opened. */
/** I-22 register and the C-12 report of members not calculated, a page at a time (docs/plan/X-pagination.md). */
export const cpfPages = {
  corrections: cpfApi.corrections,
  deputationEntries: cpfApi.deputationEntries,
  notCalculated: (finYear: string) => (page: number, size: number) =>
    cpfApi.notCalculated(finYear, page, size),
};

/** C-12: a run or re-run (audited; not itself approved, SRS-6-45). */
export function useRunInterest() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (body: NewRun) => cpfApi.runInterest(body),
    onSuccess: () => client.invalidateQueries({ queryKey: cpfKeys.interestRuns }),
  });
}

/** C-12: the year-end freeze request, decided by the AGM (CPF) without OTP (SRS-6-46). */
export function useRequestFreeze() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: { finYear: string; reason: string }) =>
      cpfApi.requestFreeze(input.finYear, input.reason),
    onSuccess: () => client.invalidateQueries({ queryKey: cpfKeys.interestRuns }),
  });
}

/** M-19: the withdrawal purposes (every version) and the proposals awaiting the Rule Approver. */
export function useWithdrawalPurposes() {
  return useQuery({ queryKey: cpfKeys.purposes, queryFn: cpfApi.withdrawalPurposes });
}

/** M-19: a new purpose version with its checklist, for the Rule Approver. */
export function useProposePurpose() {
  const run = useGoverned();
  const client = useQueryClient();
  return useMutation({
    mutationFn: (body: PurposeProposal & { reason: string }) =>
      run((otp) => cpfApi.proposePurpose(body, otp)),
    onSuccess: () => client.invalidateQueries({ queryKey: cpfKeys.purposes }),
  });
}

// ---------------------------------------------------------------- E-01 / I-04 withdrawal chain (07f-2)

/** I-04: the register a page at a time (SRS-6-55, SRS-NF-3). */
export const sanctionPages = {
  list: (stage: string) => (page: number, size: number) => cpfApi.sanctions(stage, page, size),
};

/** I-04: the tile of the requests waiting at a tier. */
export function useSanctionsPending() {
  return useQuery({
    queryKey: [...cpfKeys.sanctionList(''), 'pending'],
    queryFn: () => cpfApi.sanctionsPending(),
  });
}

/** I-04: one request with its tiers and documents; nothing is read while none is chosen. */
export function useSanction(id: number | null) {
  return useQuery({
    queryKey: cpfKeys.sanction(id ?? 0),
    queryFn: () => cpfApi.sanction(id ?? 0),
    enabled: id !== null,
  });
}

/** E-01: the purposes in force with their checklists. */
export function useSanctionPurposes() {
  return useQuery({ queryKey: cpfKeys.sanctionPurposes, queryFn: cpfApi.sanctionPurposes });
}

/** E-01: the member's own requests (personal data: not kept in the cache). */
export function useMySanctions() {
  return useQuery({ queryKey: cpfKeys.mySanctions, queryFn: cpfApi.mySanctions, staleTime: 0, gcTime: 0 });
}

function useInvalidateSanctions() {
  const client = useQueryClient();
  return () => client.invalidateQueries({ queryKey: cpfKeys.sanctions });
}

export function useRaiseSanction() {
  const refresh = useInvalidateSanctions();
  return useMutation({ mutationFn: (body: NewSanction) => cpfApi.raiseSanction(body), onSuccess: refresh });
}

export function useWithdrawSanction() {
  const refresh = useInvalidateSanctions();
  return useMutation({
    mutationFn: (input: { id: number; reason: string }) => cpfApi.withdrawSanction(input.id, input.reason),
    onSuccess: refresh,
  });
}

export function useResubmitSanction() {
  const refresh = useInvalidateSanctions();
  return useMutation({
    mutationFn: (input: {
      id: number;
      amount: string;
      propertyAddress: string;
      propertyDescription: string;
    }) =>
      cpfApi.resubmitSanction(input.id, {
        amount: input.amount,
        propertyAddress: input.propertyAddress,
        propertyDescription: input.propertyDescription,
      }),
    onSuccess: refresh,
  });
}

/** I-04 tier 1 (unit) or tier 2 (CPF Division); tier 2 goes to the Competent Authority with OTP. */
export function useTierAction() {
  const refresh = useInvalidateSanctions();
  const run = useGoverned();
  return useMutation({
    mutationFn: (input: {
      id: number;
      step: 'verify' | 'recommend';
      decision: TierDecision;
      remark: string;
    }) =>
      input.step === 'verify'
        ? cpfApi.verifySanction(input.id, input.decision, input.remark)
        : run((otp) => cpfApi.recommendSanction(input.id, input.decision, input.remark, otp)),
    onSuccess: refresh,
  });
}

/** SRS-6-14 (US-GAP-28): tier 1 attaches the No Caste Dispute Certificate. */
export function useAttachUnitCertificate() {
  const refresh = useInvalidateSanctions();
  return useMutation({
    mutationFn: (input: { id: number; certificate: UnitCertificate }) =>
      cpfApi.attachUnitCertificate(input.id, input.certificate),
    onSuccess: refresh,
  });
}

/** A document's download link (through the browser's session). */
export const sanctionDocumentUrl = cpfApi.sanctionDocumentUrl;

/** SRS-6-14: the No Caste Dispute Certificate's document type, for the I-04 form. */
export { NO_CASTE_DISPUTE } from '../api/cpfApi';

/** I-09: the deputed member (HRMS deputation, advances, months without a receipt, entries). */
export function useDeputedMember(eis: string) {
  return useQuery({
    queryKey: cpfKeys.deputedMember(eis),
    queryFn: () => cpfApi.deputedMember(eis),
    enabled: eis !== '',
  });
}

export function useBorrowingOrganisations() {
  return useQuery({ queryKey: cpfKeys.borrowingOrganisations, queryFn: cpfApi.borrowingOrganisations });
}

/** I-09: record a remittance and submit it to the AGM (CPF). */
export function useRecordDeputation() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: { body: NewDeputationEntry; evidence: CorrectionEvidence }) =>
      cpfApi.recordDeputation(input.body, input.evidence),
    onSuccess: () => client.invalidateQueries({ queryKey: cpfKeys.deputations }),
  });
}

/** I-09 Remittance Evidence (SRS-G-31): add a file to an entry after submission. */
export function useAddDeputationEvidence() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: { id: number; evidence: CorrectionEvidence }) =>
      cpfApi.addDeputationEvidence(input.id, input.evidence),
    onSuccess: () => client.invalidateQueries({ queryKey: cpfKeys.deputations }),
  });
}

/** I-09 Remittance Evidence (SRS-G-31): cancel a wrong file with the reason. */
export function useCancelDeputationEvidence() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: { id: number; fileId: number; reason: string }) =>
      cpfApi.cancelDeputationEvidence(input.id, input.fileId, input.reason),
    onSuccess: () => client.invalidateQueries({ queryKey: cpfKeys.deputations }),
  });
}

export function useAddBorrowingOrganisation() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (body: { code: string; name: string; contact: string | null }) =>
      cpfApi.addBorrowingOrganisation(body),
    onSuccess: () => client.invalidateQueries({ queryKey: cpfKeys.borrowingOrganisations }),
  });
}

export const deputationEvidenceUrl = cpfApi.deputationEvidenceUrl;
