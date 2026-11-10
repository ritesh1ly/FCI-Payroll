'use client';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  type CorrectionEntry,
  type GangSearch,
  type MemberEntry,
  type NewGang,
  type NormProposal,
  type NamedPerson,
  type NwnpRateProposal,
  type OrderEntry,
  type PayrollType,
  type ReinstatementEntry,
  type RateSetProposal,
  type SeparationEntry,
  type SlipEntry,
  labourApi,
} from '../api/labourApi';
import { toAmendRequest, toRequest, toSlipRequest } from '../business/labourRules';

/** Labour use cases (plan 20 phase 20a: M-02 Labour Master, M-09 Gang / Shed Structure). */
export const labourKeys = {
  options: ['labour-options'] as const,
  members: (type: string, text: string, gang = '', depot: number | null = null, asOf = '') =>
    ['labour-members', type, text, gang, depot, asOf] as const,
  gangs: (depot: number | null) => ['labour-gangs', depot] as const,
  gangMembers: (id: number, date = '') => ['labour-gang-members', id, date] as const,
  gangSearch: (search: GangSearch) => ['labour-gang-search', search] as const,
  attendance: (gangId: number, month: string) => ['labour-attendance', gangId, month] as const,
};

export function useOptions() {
  return useQuery({ queryKey: labourKeys.options, queryFn: labourApi.options, staleTime: 300_000 });
}

export function membersPage(type: string, text: string, gang = '', depot: number | null = null, asOf = '') {
  return (page: number, size: number) =>
    labourApi.members(
      type || undefined,
      text || undefined,
      page,
      size,
      gang.trim() || undefined,
      depot ?? undefined,
      asOf || undefined,
    );
}

export function gangsPage(depot: number | null) {
  return (page: number, size: number) => labourApi.gangs(depot ?? undefined, page, size);
}

/** A labour member of the type by exact labour code (M-02 register), or undefined. */
export async function findLabour(type: string, code: string) {
  const wanted = code.trim().toUpperCase();
  const found = await labourApi.members(type, wanted, 0, 10);
  return found.items.find((r) => r.labourCode === wanted);
}

/** SRS-2-30: one labour member as the amendment form opens it. */
export function fetchMember(id: number) {
  return labourApi.member(id);
}

/** A gang's members today, or on a day (I-02 l.4632: active on the slip date). */
export function useGangMembers(id: number | null, date = '') {
  const day = /^\d{4}-\d{2}-\d{2}$/.test(date) ? date : '';
  return useQuery({
    queryKey: labourKeys.gangMembers(id ?? 0, day),
    queryFn: () => labourApi.gangMembers(id ?? 0, day || undefined),
    enabled: id !== null,
  });
}

/** M-02: a create (no id) or an amendment, sent to the Payroll Checker. */
export function useSubmitMember() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({
      id,
      entry,
      original,
    }: {
      id: number | null;
      entry: MemberEntry;
      original?: MemberEntry;
    }) =>
      id === null
        ? labourApi.create(toRequest(entry))
        : labourApi.amend(id, toAmendRequest(entry, original ?? entry)),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['labour-members'] }),
  });
}

export function useCreateGang() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (gang: NewGang) =>
      labourApi.createGang({ ...gang, gangName: gang.gangName.trim(), reason: gang.reason.trim() }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['labour-gangs'] }),
  });
}

export function useStrength() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({
      id,
      strength,
      effectiveFrom,
      reason,
    }: {
      id: number;
      strength: number;
      effectiveFrom: string;
      reason: string;
    }) => labourApi.strength(id, { strength, effectiveFrom, reason: reason.trim() }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['labour-gangs'] }),
  });
}

/** The matches a gang picker lists at once; a longer list is narrowed by the search text. */
export const GANG_PICKER_SIZE = 20;

/**
 * A gang picker's matches, searched on the server (I-01 l.4541, I-02, M-09 target gang): within the
 * session's depots, by depot, payroll type, gang type, status and code or name text.
 */
export function useGangSearch(search: GangSearch, enabled = true) {
  return useQuery({
    queryKey: labourKeys.gangSearch(search),
    queryFn: () => labourApi.gangs(search.depot, 0, GANG_PICKER_SIZE, search),
    enabled,
  });
}

/**
 * SRS-2-6 / OD-88: the receiving gangs a transfer may name, in any office, of the member's payroll
 * type (asked only by a user who may start a transfer).
 */
export function useTransferTargets(payrollType: string | undefined, text: string, enabled = true) {
  return useQuery({
    queryKey: ['labour-transfer-targets', payrollType, text] as const,
    queryFn: () => labourApi.transferTargets(payrollType as PayrollType, text, 0, GANG_PICKER_SIZE),
    enabled: enabled && payrollType !== undefined,
  });
}

export function useAttendance(gangId: number | null, month: string) {
  return useQuery({
    queryKey: labourKeys.attendance(gangId ?? 0, month),
    queryFn: () => labourApi.attendance(gangId ?? 0, month),
    enabled: gangId !== null && /^\d{4}-\d{2}$/.test(month),
  });
}

export function useSaveDay() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ gangId, date, marks }: { gangId: number; date: string; marks: Record<number, string> }) =>
      labourApi.saveDay(gangId, {
        date,
        marks: Object.entries(marks).map(([employeeId, code]) => ({ employeeId: Number(employeeId), code })),
      }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['labour-attendance'] }),
  });
}

export function useSubmitBatch() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ gangId, month }: { gangId: number; month: string }) =>
      labourApi.submitBatch(gangId, month),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['labour-attendance'] }),
  });
}

export function useWorkSlips(gangId: number | null, month: string) {
  return useQuery({
    queryKey: ['labour-work-slips', gangId ?? 0, month],
    queryFn: () => labourApi.workSlips(gangId ?? 0, month),
    enabled: gangId !== null && /^\d{4}-\d{2}$/.test(month),
  });
}

/** I-02: a new slip, or an amendment of a saved one with the reason. */
export function useSaveSlip() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({
      gangId,
      slip,
      amend,
    }: {
      gangId: number;
      slip: SlipEntry;
      amend?: { id: number; reason: string };
    }) =>
      amend
        ? labourApi.amendSlip(amend.id, toSlipRequest(slip), amend.reason)
        : labourApi.saveSlip(gangId, toSlipRequest(slip)),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['labour-work-slips'] }),
  });
}

export function useCancelSlip() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, reason }: { id: number; reason: string }) => labourApi.cancelSlip(id, reason),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['labour-work-slips'] }),
  });
}

/** SRS-2-34 (DPS): a booking-slip to the Depot Manager. */
export function useSubmitSlip() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => labourApi.submitSlip(id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['labour-work-slips'] }),
  });
}

export function useSubmitSlips() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ gangId, month }: { gangId: number; month: string }) =>
      labourApi.submitSlips(gangId, month),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['labour-work-slips'] }),
  });
}

export function rateSetsPage(payrollType: string) {
  return (page: number, size: number) => labourApi.rateSets(payrollType, page, size);
}

export function useRateDesignations(payrollType: string, enabled = true) {
  return useQuery({
    queryKey: ['labour-rate-designations', payrollType],
    queryFn: () => labourApi.rateDesignations(payrollType),
    enabled,
    staleTime: 300_000,
  });
}

export function useProposeRateSet() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: RateSetProposal) => labourApi.proposeRateSet(body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['labour-rate-sets'] }),
  });
}

export function nwnpRateSetsPage() {
  return (page: number, size: number) => labourApi.nwnpRateSets(page, size);
}

export function useNwnpRateOptions(enabled: boolean) {
  return useQuery({
    queryKey: ['labour-nwnp-options'],
    queryFn: labourApi.nwnpRateOptions,
    enabled,
    staleTime: 300_000,
  });
}

export function useProposeNwnpRateSet() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: NwnpRateProposal) => labourApi.proposeNwnpRateSet(body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['labour-nwnp-rate-sets'] }),
  });
}

export function normSetsPage(depot: number | null) {
  return (page: number, size: number) => labourApi.normSets(depot ?? undefined, page, size);
}

export function useNormDepots() {
  return useQuery({ queryKey: ['labour-norm-depots'], queryFn: labourApi.normDepots, staleTime: 300_000 });
}

/** SRS M-06: the Active circulars a norm set may link as its MoU (only for a proposer). */
export function useMouCirculars(enabled: boolean) {
  return useQuery({ queryKey: ['labour-mou-circulars'], queryFn: labourApi.circulars, enabled });
}

export function useProposeNorms() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: NormProposal) =>
      labourApi.proposeNorms({ ...body, reason: body.reason.trim(), mouReference: body.mouReference.trim() }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['labour-norm-sets'] }),
  });
}

/** M-02 transfer or M-09 attachment of a gang member, for the Payroll Checker (SRS-2-6, SRS-2-47). */
export function useMoveMember() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({
      kind,
      employeeId,
      toGangId,
      from,
      to,
      reason,
      order,
    }: {
      kind: 'TRANSFER' | 'ATTACH';
      employeeId: number;
      toGangId: number;
      from: string;
      to: string | null;
      reason: string;
      /** SRS l.4429: the transfer order (a transfer only). */
      order?: OrderEntry;
    }) =>
      kind === 'TRANSFER'
        ? labourApi.transfer(employeeId, {
            toGangId,
            transferDate: from,
            reason: reason.trim(),
            orderReference: (order?.orderReference ?? '').trim(),
            orderDate: order?.orderDate ?? '',
            issuingAuthority: (order?.issuingAuthority ?? '').trim(),
          })
        : labourApi.attach(employeeId, { toGangId, from, to, reason: reason.trim() }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['labour-gang-members'] }),
  });
}

/** SRS-2-7: the gang ends at a date, for the Payroll Checker. */
export function useEndGang() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ gangId, endDate, reason }: { gangId: number; endDate: string; reason: string }) =>
      labourApi.endGang(gangId, { endDate, reason }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['labour-gangs'] }),
  });
}

/** M-02 separation (SRS-2-30) or Permanent Light Duty order (SRS-2-49), for the Payroll Checker. */
export function useLifecycle() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({
      kind,
      employeeId,
      body,
    }: {
      kind: 'SEPARATE' | 'LIGHT_DUTY';
      employeeId: number;
      body: Record<string, unknown>;
    }) =>
      kind === 'SEPARATE'
        ? labourApi.separate(employeeId, body as unknown as SeparationEntry)
        : labourApi.lightDuty(employeeId, body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['labour-members'] }),
  });
}

/** M-02 reinstatement of a separated member, with its order (NWNP Reinstate Details). */
export function useReinstate() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ employeeId, entry }: { employeeId: number; entry: ReinstatementEntry }) =>
      labourApi.reinstate(employeeId, entry),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['labour-members'] }),
  });
}

export function useNominations(employeeId: number) {
  return useQuery({
    queryKey: ['labour-nominations', employeeId],
    queryFn: () => labourApi.nominations(employeeId),
  });
}

/** M-02 nominees and dependents for the Payroll Checker (BR-FNF-014). */
export function useNominate() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({
      employeeId,
      body,
    }: {
      employeeId: number;
      body: { nominees: NamedPerson[]; dependents: NamedPerson[]; reason: string };
    }) => labourApi.nominate(employeeId, body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['labour-nominations'] }),
  });
}

/** M-02 name or date-of-birth correction for the Payroll Checker (SRS-2-59). */
export function useCorrection() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ employeeId, entry }: { employeeId: number; entry: CorrectionEntry }) =>
      labourApi.correct(employeeId, entry),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['labour-members'] }),
  });
}

/** M-09 gang assignment (SRS-2-31) or a new Sardar / Mandal (SRS-2-1), for the Payroll Checker. */
export function useGangChange() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({
      gangId,
      role,
      employeeId,
      from,
      reason,
    }: {
      gangId: number;
      role: 'JOIN' | 'SARDAR' | 'MANDAL';
      employeeId: number;
      from: string;
      reason: string;
    }) =>
      role === 'JOIN'
        ? labourApi.joinGang(gangId, { employeeId, from, reason: reason.trim() })
        : labourApi.changeLeader(gangId, { role, employeeId, from, reason: reason.trim() }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['labour-gang-members'] }),
  });
}

/** SRS-2-48: the Gang Strength Report of a gang's month. */
export function useStrengthReport(gangId: number | null, month: string) {
  return useQuery({
    queryKey: ['labour-strength-report', gangId ?? 0, month] as const,
    queryFn: () => labourApi.strengthReport(gangId ?? 0, month),
    enabled: gangId !== null && /^\d{4}-\d{2}$/.test(month),
  });
}

/** SRS-2-48: the Depot Manager closes a day's shortfall alert with a reason. */
export function useCloseShortfall() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ gangId, date, reason }: { gangId: number; date: string; reason: string }) =>
      labourApi.closeShortfall(gangId, date, reason.trim()),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['labour-strength-report'] }),
  });
}
