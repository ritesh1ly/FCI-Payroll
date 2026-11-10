import { type PageResult } from '@/shared/ui';
import { api } from '@/shared/api';

/** DDL labour_profile.payroll_type. */
export type PayrollType = 'DEPT_LABOUR' | 'DPS_LABOUR' | 'NWNP_LABOUR';

/** DDL labour_gang_membership.gang_role. */
export type GangRole = 'SARDAR' | 'MANDAL' | 'HANDLING' | 'ANCILLARY';

/** One labour member as M-02 lists it (PAN and bank account masked by the server). */
export interface LabourRow {
  employeeId: number;
  labourCode: string;
  name: string;
  payrollType: PayrollType;
  designation: string | null;
  depot: string | null;
  status: string;
  joiningDate: string;
  retirementDate: string | null;
  panMasked: string | null;
  bankAccountMasked: string | null;
  /** SRS-G-55: Casual Labour, processed in the same payroll. */
  casualLabour: boolean;
}

/**
 * One labour member as the M-02 amendment form opens it (SRS-2-30). R-PII: PAN, bank account and
 * mobile come masked; the form leaves them empty and an empty one is kept.
 */
export interface MemberDetail {
  employeeId: number;
  labourCode: string;
  payrollType: PayrollType;
  firstName: string;
  lastName: string | null;
  fatherName: string | null;
  gender: 'M' | 'F' | 'O' | null;
  birthDate: string;
  joiningDate: string;
  designationId: number | null;
  depotLocationId: number | null;
  panMasked: string | null;
  ifsc: string | null;
  bankAccountMasked: string | null;
  mobileMasked: string | null;
  pfApplicable: boolean;
  cpfCode: string | null;
  uanNumber: string | null;
  casualLabour: boolean;
}

/** A gang search on the server (I-01, I-02, M-09 pickers); each criterion optional. */
export interface GangSearch {
  depot?: number;
  payrollType?: string;
  gangType?: GangRow['gangType'];
  status?: 'ACTIVE' | 'END_DATED';
  text?: string;
}

/** The M-02 fields of a create or an amendment. */
export interface MemberEntry {
  payrollType: PayrollType;
  firstName: string;
  lastName: string;
  fatherName: string;
  gender: '' | 'M' | 'F' | 'O';
  birthDate: string;
  joiningDate: string;
  designationId: number | null;
  depotLocationId: number | null;
  panNumber: string;
  ifsc: string;
  bankAccount: string;
  mobile: string;
  pfApplicable: boolean;
  cpfCode: string;
  uanNumber: string;
  casualLabour: boolean;
  reason: string;
}

/** SRS-2-59: a name or date-of-birth correction with the service-book page or order. */
export interface CorrectionEntry {
  type: 'NAME' | 'DOB';
  firstName: string;
  lastName: string;
  birthDate: string;
  reasonCode: 'AS_PER_SERVICE_BOOK' | 'AS_PER_ORDER';
  reasonText: string;
  orderReference: string;
  orderDate: string;
  issuingAuthority: string;
  effectiveDate: string;
  document: File | null;
}

export interface Option {
  id: number;
  code: string;
  name: string;
  family: string | null;
}

export interface Options {
  depots: Option[];
  designations: Option[];
}

/** One gang as M-09 lists it. */
export interface GangRow {
  gangId: number;
  gangCode: string;
  gangName: string;
  depot: string;
  payrollType: PayrollType | null;
  gangType: 'HANDLING' | 'ANCILLARY_POOL' | 'LEGACY_DUMMY_ANCILLARY';
  status: string;
  sanctionedStrength: number | null;
  members: number;
  effectiveFrom: string;
  /** M-09 l.4499: the end date of an end-dated gang. */
  effectiveTo?: string | null;
}

export interface GangMember {
  employeeId: number;
  labourCode: string;
  name: string;
  role: GangRole;
}

export interface NewGang {
  depotLocationId: number;
  payrollType: PayrollType;
  gangName: string;
  sanctionedStrength: number | null;
  effectiveFrom: string;
  members: { employeeId: number; role: GangRole }[];
  reason: string;
}

/** The order a transfer or separation is started with (SRS l.4429, l.4937, l.4971). */
export interface OrderEntry {
  orderReference: string;
  orderDate: string;
  issuingAuthority: string;
}

export interface TransferEntry extends OrderEntry {
  toGangId: number;
  transferDate: string;
  reason: string;
}

export interface SeparationEntry extends OrderEntry {
  type: string;
  date: string;
  reason: string;
}

/** SRS-2-48: one day of the Gang Strength Report. */
export interface StrengthDay {
  date: string;
  sanctioned: number;
  present: number;
  shortBy: number;
  absent: string[];
  attached: string[];
  alertOpen: boolean;
}

/** SRS-2-48: the Gang Strength Report of a gang's month. */
export interface StrengthReport {
  gangId: number;
  gangName: string;
  depot: string;
  yymm: string;
  days: StrengthDay[];
}

export interface Submitted {
  id: number;
  status: string;
}

/** One code of the gang's set (I-01). */
export interface AttendanceCode {
  code: string;
  meaning: string;
  weight: number;
  leave: boolean;
  confirmed: boolean;
}

export interface DayMark {
  date: string;
  code: string;
  otherGang: boolean;
  workSlipFiled: boolean;
}

export interface MemberGrid {
  employeeId: number;
  labourCode: string;
  name: string;
  role: GangRole;
  from: string;
  to: string | null;
  retirementDate: string | null;
  marks: DayMark[];
  marked: number;
  presentDays: number;
  leaveDays: number;
  absentDays: number;
}

export type BatchState = 'OPEN' | 'SUBMITTED' | 'RETURNED' | 'APPROVED';

/** The I-01 grid of a gang's month. */
export interface AttendanceGrid {
  gangId: number;
  gangName: string;
  depot: string;
  payrollType: PayrollType;
  yymm: string;
  lastMarkable: string;
  batchState: BatchState;
  approvalId: number | null;
  /** SRS-2-29: the Payroll Checker's reason of a RETURNED batch. */
  returnReason?: string | null;
  sanctionedStrength: number | null;
  codes: AttendanceCode[];
  members: MemberGrid[];
  /** SRS-2-36 (I-01 l.4543): the depot's Attendance Source in force (HQ master); null when not configured. */
  attendanceSource?: AttendanceSource | null;
}

/** SRS-2-36: one row of the HQ Attendance Source master (DDL attendance_source_master), read-only on I-01. */
export interface AttendanceSource {
  mode: 'MANUAL' | 'BIOMETRIC' | 'BOTH';
  annaDarpan: boolean;
  status: 'PILOT' | 'LIVE' | 'SUSPENDED';
  fallbackToManual: boolean;
  effectiveFrom: string;
}

export interface SavedDay {
  date: string;
  marked: number;
  added: number;
  changed: number;
  /** SRS-4-5 (DPS): the labour codes whose first Sunday of a split week was marked absent. */
  markedAbsent: string[];
}

/** An operation of the payroll type (M-27). */
export interface Operation {
  id: number;
  code: string;
  name: string;
  bagBased: boolean;
  normType: string | null;
  pbnw: boolean;
}

export interface BagType {
  id: number;
  code: string;
  name: string;
  weightKg: number | null;
  band: string | null;
}

export interface SlipRow {
  order: number;
  operation: string;
  bagType: string | null;
  bags: number | null;
  stackHeight: number | null;
  heightBand: string | null;
  start: string;
  end: string;
  pbnwReason: string | null;
  /** The ids and texts an amendment opens with (SRS-2-15). */
  operationId: number;
  bagTypeId: number | null;
  pbnwText: string | null;
  rakeReference: string | null;
}

export interface Slip {
  id: number;
  number: string;
  date: string;
  status: 'DRAFT' | 'SUBMITTED' | 'APPROVED' | 'AMENDED' | 'CANCELLED';
  continueWork: boolean;
  holiday: boolean;
  labourStrength: number | null;
  amendmentOf: number | null;
  rows: SlipRow[];
  participants: { employeeId: number; labourCode: string; name: string; role: GangRole; present: boolean }[];
  /** DPS register l.4669: the user who entered the slip and the one who decided it, with the time. */
  enteredBy?: string | null;
  decidedBy?: string | null;
  decidedAt?: string | null;
  /** l.4650: the Depot Manager's reason of a returned booking-slip. */
  returnReason?: string | null;
}

/** The I-02 view of a gang's month. */
export interface SlipMonth {
  gangId: number;
  gangName: string;
  depot: string;
  payrollType: PayrollType;
  yymm: string;
  batchState: BatchState;
  approvalId: number | null;
  /** SRS-2-29: the Payroll Checker's reason of a RETURNED month batch. */
  returnReason?: string | null;
  operations: Operation[];
  bagTypes: BagType[];
  slips: Slip[];
}

/** One row of work as entered (I-02). */
export interface RowEntry {
  operationId: number | null;
  bagTypeId: number | null;
  bags: number | null;
  stackHeight: number | null;
  start: string;
  end: string;
  pbnwReason: string | null;
  pbnwText: string;
  rakeReference: string;
}

export interface SlipEntry {
  date: string;
  continueWork: boolean;
  rows: RowEntry[];
  participants: number[];
}

/** One above-norm slab band (M-06). */
export interface Slab {
  table: 'BAND_1' | 'BAND_2';
  order: number;
  from: number;
  to: number | null;
  ratePercent: number;
}

/** A depot's norm set (M-06). */
export interface NormSet {
  id: number;
  /** The depot's location code: the key of its History record (SRS-20-29). */
  depotCode: string;
  depot: string;
  versionNo: number;
  effectiveFrom: string;
  effectiveTo: string | null;
  handling: number;
  height: number;
  lead: number;
  minLabourNumber: number;
  minSlabWeight: number;
  mouReference: string | null;
  approvalState: string;
  slabs: Slab[];
  /** The union MoU circular (M-25) linked once the set was approved. */
  mouCircular: string | null;
}

/** An Active M-25 circular a norm set may link as its union MoU (SRS M-06). */
export interface MouCircular {
  id: number;
  number: string;
  subject: string | null;
}

export interface NormProposal {
  depotLocationId: number | null;
  effectiveFrom: string;
  handling: number | null;
  height: number | null;
  lead: number | null;
  minLabourNumber: number | null;
  minSlabWeight: number | null;
  mouReference: string;
  slabs: Slab[];
  reason: string;
  /** SRS M-06 use case: the depot's union MoU from M-25 (mandatory). */
  mouCircularId: number | null;
}

/** The only place the labour module talks to /api/v1/labour. */
export const labourApi = {
  options: () => api<Options>('/v1/labour/options'),
  /**
   * M-02 search (SRS NWNP Master Search, SRS-2-40): code or name, gang and depot, within the scope; the gang
   * membership as of a date (today when not given).
   */
  members: (
    payrollType: string | undefined,
    text: string | undefined,
    page: number,
    size: number,
    gang?: string,
    depot?: number,
    asOf?: string,
  ) => {
    const p = new URLSearchParams();
    if (payrollType) p.set('payrollType', payrollType);
    if (text) p.set('text', text);
    if (gang) p.set('gang', gang);
    if (gang && asOf) p.set('asOf', asOf);
    if (depot !== undefined) p.set('depot', String(depot));
    p.set('page', String(page));
    p.set('size', String(size));
    return api<PageResult<LabourRow>>(`/v1/labour/members?${p.toString()}`);
  },
  create: (body: Record<string, unknown>) => api<Submitted>('/v1/labour/members', { method: 'POST', body }),
  member: (id: number) => api<MemberDetail>(`/v1/labour/members/${id}`),
  amend: (id: number, body: Record<string, unknown>) =>
    api<Submitted>(`/v1/labour/members/${id}/amendments`, { method: 'POST', body }),
  gangs: (depot: number | undefined, page: number, size: number, search: GangSearch = {}) => {
    const p = new URLSearchParams();
    if (depot) p.set('depot', String(depot));
    if (search.payrollType) p.set('payrollType', search.payrollType);
    if (search.gangType) p.set('gangType', search.gangType);
    if (search.status) p.set('status', search.status);
    if (search.text) p.set('text', search.text);
    p.set('page', String(page));
    p.set('size', String(size));
    return api<PageResult<GangRow>>(`/v1/labour/gangs?${p.toString()}`);
  },
  /** A gang's members today, or on a day (I-02: the slip date). */
  gangMembers: (id: number, date?: string) =>
    api<GangMember[]>(`/v1/labour/gangs/${id}/members${date ? `?date=${encodeURIComponent(date)}` : ''}`),
  createGang: (body: NewGang) => api<Submitted>('/v1/labour/gangs', { method: 'POST', body }),
  attendance: (gangId: number, month: string) =>
    api<AttendanceGrid>(`/v1/labour/gangs/${gangId}/attendance?month=${encodeURIComponent(month)}`),
  saveDay: (gangId: number, body: { date: string; marks: { employeeId: number; code: string }[] }) =>
    api<SavedDay>(`/v1/labour/gangs/${gangId}/attendance-days`, { method: 'POST', body }),
  submitBatch: (gangId: number, month: string) =>
    api<Submitted>(`/v1/labour/gangs/${gangId}/attendance-batches`, { method: 'POST', body: { month } }),
  workSlips: (gangId: number, month: string) =>
    api<SlipMonth>(`/v1/labour/gangs/${gangId}/work-slips?month=${encodeURIComponent(month)}`),
  saveSlip: (gangId: number, body: SlipEntry) =>
    api<Slip>(`/v1/labour/gangs/${gangId}/work-slips`, { method: 'POST', body }),
  amendSlip: (slipId: number, slip: SlipEntry, reason: string) =>
    api<Slip>(`/v1/labour/work-slips/${slipId}/amendments`, { method: 'POST', body: { slip, reason } }),
  cancelSlip: (slipId: number, reason: string) =>
    api<Slip>(`/v1/labour/work-slips/${slipId}/cancellations`, { method: 'POST', body: { reason } }),
  submitSlip: (slipId: number) =>
    api<Submitted>(`/v1/labour/work-slips/${slipId}/submissions`, { method: 'POST' }),
  submitSlips: (gangId: number, month: string) =>
    api<Submitted>(`/v1/labour/gangs/${gangId}/work-slip-batches`, { method: 'POST', body: { month } }),
  normSets: (depot: number | undefined, page: number, size: number) => {
    const p = new URLSearchParams();
    if (depot) p.set('depot', String(depot));
    p.set('page', String(page));
    p.set('size', String(size));
    return api<PageResult<NormSet>>(`/v1/labour/norm-sets?${p.toString()}`);
  },
  rateSets: (payrollType: string, page: number, size: number) =>
    api<PageResult<RateSet>>(
      `/v1/labour/rate-sets?payrollType=${encodeURIComponent(payrollType)}&page=${page}&size=${size}`,
    ),
  rateDesignations: (payrollType: string) =>
    api<RateDesignation[]>(
      `/v1/labour/rate-sets/designations?payrollType=${encodeURIComponent(payrollType)}`,
    ),
  proposeRateSet: (body: RateSetProposal) => api<Submitted>('/v1/labour/rate-sets', { method: 'POST', body }),
  nwnpRateSets: (page: number, size: number) =>
    api<PageResult<NwnpRateSet>>(`/v1/labour/nwnp-rate-sets?page=${page}&size=${size}`),
  nwnpRateOptions: () => api<NwnpRateOptions>('/v1/labour/nwnp-rate-sets/options'),
  proposeNwnpRateSet: (body: NwnpRateProposal) =>
    api<Submitted>('/v1/labour/nwnp-rate-sets', { method: 'POST', body }),
  normDepots: () => api<{ id: number; code: string; name: string }[]>('/v1/labour/norm-sets/depots'),
  /** The Active M-25 circulars (a choice list, not paged). */
  circulars: () => api<MouCircular[]>('/v1/masters/circulars/current'),
  proposeNorms: (body: NormProposal) => api<Submitted>('/v1/labour/norm-sets', { method: 'POST', body }),
  /** SRS-2-6 / l.4429: a transfer with its order. */
  transfer: (employeeId: number, body: TransferEntry) =>
    api<Submitted>(`/v1/labour/members/${employeeId}/transfers`, { method: 'POST', body }),
  /** SRS-2-48: the Gang Strength Report of a gang's month (YYYY-MM). */
  strengthReport: (gangId: number, month: string) =>
    api<StrengthReport>(`/v1/labour/gangs/${gangId}/strength-report?month=${month}`),
  /** SRS-2-48: the Depot Manager closes a day's shortfall alert with a reason. */
  closeShortfall: (gangId: number, date: string, reason: string) =>
    api<{ closed: number }>(`/v1/labour/gangs/${gangId}/shortfalls/${date}/closures`, {
      method: 'POST',
      body: { reason },
    }),
  /** SRS-2-6 / OD-88: receiving gangs of the payroll type in any office (M-02 EDIT). */
  transferTargets: (payrollType: PayrollType, text: string, page: number, size: number) =>
    api<PageResult<GangRow>>(
      `/v1/labour/transfer-targets?payrollType=${payrollType}&text=${encodeURIComponent(text)}&page=${page}&size=${size}`,
    ),
  attach: (employeeId: number, body: { toGangId: number; from: string; to: string | null; reason: string }) =>
    api<Submitted>(`/v1/labour/members/${employeeId}/attachments`, { method: 'POST', body }),
  separate: (employeeId: number, body: SeparationEntry) =>
    api<Submitted>(`/v1/labour/members/${employeeId}/separations`, { method: 'POST', body }),
  lightDuty: (employeeId: number, body: Record<string, unknown>) =>
    api<Submitted>(`/v1/labour/members/${employeeId}/light-duty-orders`, { method: 'POST', body }),
  /** NWNP Reinstate Details (multipart: the fields and the order). */
  reinstate: (employeeId: number, r: ReinstatementEntry) => {
    const form = new FormData();
    form.append('date', r.date);
    if (r.orderReference.trim()) form.append('orderReference', r.orderReference.trim());
    form.append('orderDate', r.orderDate);
    form.append('issuingAuthority', r.issuingAuthority.trim());
    form.append('reason', r.reason.trim());
    if (r.document) form.append('document', r.document);
    return api<Submitted>(`/v1/labour/members/${employeeId}/reinstatements`, { method: 'POST', body: form });
  },
  nominations: (employeeId: number) => api<Nominations>(`/v1/labour/members/${employeeId}/nominations`),
  nominate: (
    employeeId: number,
    body: { nominees: NamedPerson[]; dependents: NamedPerson[]; reason: string },
  ) => api<Submitted>(`/v1/labour/members/${employeeId}/nominations`, { method: 'POST', body }),
  /** SRS-2-59 (multipart: the fields and the document). */
  correct: (employeeId: number, c: CorrectionEntry) => {
    const form = new FormData();
    form.append('type', c.type);
    if (c.type === 'NAME') {
      form.append('firstName', c.firstName.trim());
      if (c.lastName.trim()) form.append('lastName', c.lastName.trim());
    } else {
      form.append('birthDate', c.birthDate);
    }
    form.append('reasonCode', c.reasonCode);
    if (c.reasonText.trim()) form.append('reasonText', c.reasonText.trim());
    if (c.orderReference.trim()) form.append('orderReference', c.orderReference.trim());
    form.append('orderDate', c.orderDate);
    form.append('issuingAuthority', c.issuingAuthority.trim());
    form.append('effectiveDate', c.effectiveDate);
    if (c.document) form.append('document', c.document);
    return api<Submitted>(`/v1/labour/members/${employeeId}/corrections`, { method: 'POST', body: form });
  },
  joinGang: (gangId: number, body: { employeeId: number; from: string; reason: string }) =>
    api<Submitted>(`/v1/labour/gangs/${gangId}/members`, { method: 'POST', body }),
  changeLeader: (
    gangId: number,
    body: { role: GangRole; employeeId: number; from: string; reason: string },
  ) => api<Submitted>(`/v1/labour/gangs/${gangId}/leaders`, { method: 'POST', body }),
  endGang: (gangId: number, body: { endDate: string; reason: string }) =>
    api<Submitted>(`/v1/labour/gangs/${gangId}/end-dates`, { method: 'POST', body }),
  strength: (id: number, body: { strength: number; effectiveFrom: string; reason: string }) =>
    api<Submitted>(`/v1/labour/gangs/${id}/sanctioned-strengths`, { method: 'POST', body }),
};

/** One designation's Minimum Guaranteed Wage day rate of a set (M-05). */
export interface DayRate {
  designationId: number;
  designation: string;
  gangRole: string | null;
  /** DPS: the Minimum Guaranteed Wage day rate; null in a Departmental set. */
  mgw: number | null;
  /** Departmental (SRS-3-13): the Basic wage and DA; null in a DPS set. */
  basicWage: number | null;
  da: number | null;
}

/** A labour rate set as M-05 lists it (SRS-4-11). */
export interface RateSet {
  id: number;
  payrollType: string;
  depotId: number | null;
  depot: string | null;
  versionNo: number;
  effectiveFrom: string;
  effectiveTo: string | null;
  interimRelief: number | null;
  goiRate: number | null;
  goiRateSource: string | null;
  comparisonGrain: string | null;
  approvalState: string;
  rates: DayRate[];
}

/** A designation the set gives a day rate to. */
export interface RateDesignation {
  id: number;
  code: string;
  name: string;
  gangRole: string | null;
}

/** A proposed rate set; amounts as typed (a decimal being typed is not yet a number). */
export interface RateSetProposal {
  payrollType: string;
  depotLocationId: number | null;
  effectiveFrom: string;
  rates: { designationId: number; mgw?: number; basicWage?: number; da?: number }[];
  interimRelief: number | null;
  goiRate: number | null;
  goiRateSource: string | null;
  comparisonGrain: string | null;
  reason: string;
}

/** A depot's NWNP rate set as M-05 lists it (SRS-5-2, SRS-5-3). */
export interface NwnpRateSet {
  id: number;
  code: string;
  depot: string;
  version: string;
  versionNo: number;
  effectiveFrom: string;
  effectiveTo: string | null;
  approvalState: string;
  lines: {
    operationCode: string;
    operation: string;
    pieceOfRate: number;
    unitOfMeasure: string;
    unitLabel: string | null;
  }[];
  mgw: { designationId: number; designation: string; gangRole: string | null; mgw: number }[];
}

/** The choices of the NWNP rate form. */
export interface NwnpRateOptions {
  operations: { code: string; name: string; m27: string | null }[];
  designations: RateDesignation[];
  unitsOfMeasure: { code: string; label: string }[];
  depots: RateDesignation[];
}

/** A proposed NWNP rate set. */
export interface NwnpRateProposal {
  depotLocationId: number;
  version: string;
  effectiveFrom: string;
  lines: { operationCode: string; pieceOfRate: number; unitOfMeasure: string }[];
  mgw: { designationId: number; mgw: number }[];
  reason: string;
}

/** A labour member's reinstatement as typed, with its order (NWNP Reinstate Details). */
export interface ReinstatementEntry {
  date: string;
  orderReference: string;
  orderDate: string;
  issuingAuthority: string;
  reason: string;
  document: File | null;
}

/** A nominee (with a share) or a dependent of a labour member. */
export interface NamedPerson {
  name: string;
  relationship: string;
  birthDate: string | null;
  share: number | null;
  nominee: boolean;
}

/** The nominees and dependents in force and whether a change waits (BR-FNF-014). */
export interface Nominations {
  nominees: NamedPerson[];
  dependents: NamedPerson[];
  pending: boolean;
}
