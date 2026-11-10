/**
 * Labour Master and gang screen rules (plan 20 phase 20a; SRS-2-1, SRS-2-30, SRS-2-46). The server
 * checks each again; these only decide what the screens offer.
 */
import type {
  AttendanceGrid,
  CorrectionEntry,
  GangRole,
  MemberDetail,
  MemberEntry,
  MemberGrid,
  NewGang,
  NormProposal,
  Operation,
  NamedPerson,
  NwnpRateOptions,
  ReinstatementEntry,
  NwnpRateProposal,
  RateDesignation,
  RateSetProposal,
  RowEntry,
  Slab,
  Slip,
  SlipEntry,
} from '../api/labourApi';

export const PAYROLL_TYPES = ['DEPT_LABOUR', 'DPS_LABOUR', 'NWNP_LABOUR'] as const;

export const GANG_ROLES: GangRole[] = ['SARDAR', 'MANDAL', 'HANDLING', 'ANCILLARY'];

const NAME = /^[A-Za-z][A-Za-z .'-]{0,99}$/;
const PAN = /^[A-Z]{5}\d{4}[A-Z]$/;
const IFSC = /^[A-Z]{4}0[A-Z0-9]{6}$/;
const ACCOUNT = /^\d{6,20}$/;
const MOBILE = /^[6-9]\d{9}$/;
const CPF = /^[A-Z0-9/-]{1,20}$/;
const PRINTABLE = /^[\x20-\x7E]+$/;
/** Text of a text area: printable ASCII with tab and line breaks (R-ASCII, as @PrintableAscii). */
const TEXT_LINES = /^[\x20-\x7E\t\r\n]*$/;

function blank(value: string): boolean {
  return value.trim().length === 0;
}

/** A printable reason of 1 to 500 characters. */
export function reasonOk(reason: string): boolean {
  const r = reason.trim();
  return r.length > 0 && r.length <= 500 && TEXT_LINES.test(r);
}

/**
 * SRS l.4429 / l.4937 / l.4971: the order a transfer or separation is started with: a reference of at
 * most 100 characters, an order date not after today and an issuing authority of at most 150 (the
 * server's ORDER / ORDER_DATE refusals).
 */
export function orderBlocked(
  o: { orderReference: string; orderDate: string; issuingAuthority: string },
  today: string,
): string | null {
  const ref = o.orderReference.trim();
  const authority = o.issuingAuthority.trim();
  if (!ref || ref.length > 100 || !authority || authority.length > 150 || !o.orderDate) {
    return 'm02.why.order';
  }
  return o.orderDate > today ? 'm02.why.orderDate' : null;
}

/** SRS-2-6: a transfer names the receiving gang, a date, its order and a reason. */
export function transferBlocked(
  e: {
    toGangId: number | null;
    transferDate: string;
    orderReference: string;
    orderDate: string;
    issuingAuthority: string;
    reason: string;
  },
  today: string,
): string | null {
  if (e.toGangId === null || !e.transferDate || !reasonOk(e.reason)) {
    return 'm09.why.move';
  }
  return orderBlocked(e, today);
}

/**
 * SRS-2-30: the first thing wrong with the entry, as a label key, or null. An amendment keeps the
 * name, date of birth, designation and depot (SRS-2-59 correction, designation change, transfer).
 */
export function entryBlocked(e: MemberEntry, today: string, amend = false): string | null {
  if (!amend && (!NAME.test(e.firstName.trim()) || (!blank(e.lastName) && !NAME.test(e.lastName.trim())))) {
    return 'm02.why.name';
  }
  if (!e.joiningDate || e.joiningDate > today) return 'm02.why.joining';
  if (!amend && (!e.birthDate || e.birthDate > e.joiningDate)) return 'm02.why.birth';
  if (!amend && e.designationId === null) return 'm02.why.designation';
  if (!amend && e.depotLocationId === null) return 'm02.why.depot';
  if (!blank(e.panNumber) && !PAN.test(e.panNumber.trim().toUpperCase())) return 'm02.why.pan';
  if (!IFSC.test(e.ifsc.trim().toUpperCase())) return 'm02.why.ifsc';
  // SRS-2-30: on an amendment the masked account is left empty to keep it.
  if (!(amend && blank(e.bankAccount)) && !ACCOUNT.test(e.bankAccount.trim())) return 'm02.why.account';
  if (!blank(e.mobile) && !MOBILE.test(e.mobile.trim())) return 'm02.why.mobile';
  if (e.pfApplicable && !CPF.test(e.cpfCode.trim().toUpperCase())) return 'm02.why.cpf';
  if (!reasonOk(e.reason)) return 'm02.why.reason';
  return null;
}

/** SRS-2-1 / BR-LABM-001: a handling gang has exactly one Sardar and one Mandal. */
export function gangBlocked(g: NewGang): string | null {
  const name = g.gangName.trim();
  if (name.length === 0 || name.length > 150 || !PRINTABLE.test(name)) return 'm09.why.name';
  const sardars = g.members.filter((m) => m.role === 'SARDAR').length;
  const mandals = g.members.filter((m) => m.role === 'MANDAL').length;
  if (sardars !== 1 || mandals !== 1) return 'm09.why.roles';
  if (new Set(g.members.map((m) => m.employeeId)).size !== g.members.length) return 'm09.why.twice';
  // BR-LABM-011 / M-09 l.4498: a handling gang has its Sanctioned Strength (1 to 9999).
  if (g.sanctionedStrength === null || g.sanctionedStrength < 1 || g.sanctionedStrength > 9999) {
    return 'm09.why.strength';
  }
  if (!reasonOk(g.reason)) return 'm09.why.reason';
  return null;
}

/** SRS-2-48: a new Sanctioned Strength from today or later. */
export function strengthBlocked(
  strength: number,
  from: string,
  reason: string,
  today: string,
): string | null {
  if (!Number.isInteger(strength) || strength < 1 || strength > 9999) return 'm09.why.strength';
  if (!from || from < today) return 'm09.why.effective';
  if (!reasonOk(reason)) return 'm09.why.reason';
  return null;
}

/** An empty M-02 entry. */
export function emptyEntry(): MemberEntry {
  return {
    payrollType: 'DEPT_LABOUR',
    firstName: '',
    lastName: '',
    fatherName: '',
    gender: '',
    birthDate: '',
    joiningDate: '',
    designationId: null,
    depotLocationId: null,
    panNumber: '',
    ifsc: '',
    bankAccount: '',
    mobile: '',
    pfApplicable: false,
    cpfCode: '',
    uanNumber: '',
    casualLabour: false,
    reason: '',
  };
}

/** SRS-2-59: why the correction cannot be sent, as a label key, or null. */
export function correctionBlocked(c: CorrectionEntry, today: string): string | null {
  if (c.type === 'NAME') {
    if (!NAME.test(c.firstName.trim()) || (!blank(c.lastName) && !NAME.test(c.lastName.trim()))) {
      return 'm02.why.name';
    }
  } else if (!c.birthDate || c.birthDate > today) {
    return 'm02.why.birth';
  }
  if (!c.orderDate || c.orderDate > today || blank(c.issuingAuthority)) return 'm02.why.document';
  if (c.reasonCode === 'AS_PER_ORDER' && blank(c.orderReference)) return 'm02.why.document';
  if (!blank(c.reasonText) && !TEXT_LINES.test(c.reasonText.trim())) return 'm02.why.reason';
  if (!c.effectiveDate) return 'm02.why.effective';
  if (!c.document) return 'm02.why.file';
  return null;
}

/** The entry as the server takes it: identifiers upper-cased, blanks as null. */
export function toRequest(e: MemberEntry): Record<string, unknown> {
  const orNull = (v: string) => (blank(v) ? null : v.trim());
  return {
    ...e,
    firstName: e.firstName.trim(),
    lastName: orNull(e.lastName),
    fatherName: orNull(e.fatherName),
    gender: e.gender || null,
    panNumber: orNull(e.panNumber.toUpperCase()),
    ifsc: e.ifsc.trim().toUpperCase(),
    bankAccount: e.bankAccount.trim(),
    mobile: orNull(e.mobile),
    cpfCode: e.pfApplicable ? e.cpfCode.trim().toUpperCase() : null,
    uanNumber: orNull(e.uanNumber),
    reason: e.reason.trim(),
  };
}

/** An amendment as the server takes it: the kept fields are not sent (SRS-2-59). */
const KEPT_ON_AMEND = [
  'payrollType',
  'firstName',
  'lastName',
  'birthDate',
  'designationId',
  'depotLocationId',
];

/** R-PII: masked on the form; left empty, the value in force is kept. */
const MASKED_ON_AMEND = ['panNumber', 'bankAccount', 'mobile'];

/** SRS-2-30: the amendment form opens with the record as it stands (masked fields empty). */
export function detailEntry(d: MemberDetail): MemberEntry {
  return {
    payrollType: d.payrollType,
    firstName: d.firstName,
    lastName: d.lastName ?? '',
    fatherName: d.fatherName ?? '',
    gender: d.gender ?? '',
    birthDate: d.birthDate,
    joiningDate: d.joiningDate,
    designationId: d.designationId,
    depotLocationId: d.depotLocationId,
    panNumber: '',
    ifsc: d.ifsc ?? '',
    bankAccount: '',
    mobile: '',
    pfApplicable: d.pfApplicable,
    cpfCode: d.cpfCode ?? '',
    uanNumber: d.uanNumber ?? '',
    casualLabour: d.casualLabour,
    reason: '',
  };
}

/**
 * SRS-2-30 "keep the old value in force": an amendment carries only the fields the maker changed
 * against the record as it opened, plus the reason. A cleared optional field is sent empty (the
 * server clears it); a masked field left empty is not sent (kept).
 */
export function toAmendRequest(e: MemberEntry, original: MemberEntry): Record<string, unknown> {
  const now = toRequest(e);
  const before = toRequest(original);
  const body: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(now)) {
    if (k === 'reason' || KEPT_ON_AMEND.includes(k)) continue;
    if (MASKED_ON_AMEND.includes(k) && (v === null || v === '')) continue;
    if (v === before[k]) continue;
    body[k] = v === null ? '' : v;
  }
  if (Object.keys(body).length === 0) return body;
  return { ...body, reason: now.reason };
}

/**
 * I-01 Gang Strength Check (l.4545, SRS-2-48): the members present on the day, counted from the
 * day's marks (a code with a present weight, never a leave code), against the Sanctioned Strength;
 * short is how many fewer are present. Null for a gang without a strength (the Ancillary Pool,
 * SRS-2-46).
 */
export function dayStrength(
  grid: AttendanceGrid,
  marks: Record<number, string>,
): { sanctioned: number; present: number; short: number } | null {
  if (grid.sanctionedStrength === null) return null;
  const present = Object.values(marks).filter((code) => {
    const c = grid.codes.find((x) => x.code === code);
    return c !== undefined && !c.leave && c.weight > 0;
  }).length;
  return {
    sanctioned: grid.sanctionedStrength,
    present,
    short: Math.max(0, grid.sanctionedStrength - present),
  };
}

/** SRS-2-15 / SRS-2-4: an amendment opens with the saved slip's rows, never a blank one. */
export function slipRows(slip: Slip): RowEntry[] {
  if (slip.rows.length === 0) return [emptyRow()];
  return slip.rows.map((r) => ({
    operationId: r.operationId,
    bagTypeId: r.bagTypeId,
    bags: r.bags,
    stackHeight: r.stackHeight,
    start: r.start,
    end: r.end,
    pbnwReason: r.pbnwReason,
    pbnwText: r.pbnwText ?? '',
    rakeReference: r.rakeReference ?? '',
  }));
}

/** SRS-2-2: a member is marked on a day it belongs to the gang and is not past retirement. */
export function markable(m: MemberGrid, day: string): boolean {
  return (
    m.from <= day && (m.to === null || m.to >= day) && (m.retirementDate === null || m.retirementDate >= day)
  );
}

/** SRS-2-35 / SRS-2-29: why a gang's day cannot be saved, as a label key, or null. */
export function dayBlocked(grid: AttendanceGrid, day: string, marks: Record<number, string>): string | null {
  if (grid.batchState === 'SUBMITTED' || grid.batchState === 'APPROVED') return 'i01.why.batch';
  if (grid.codes.length === 0) return 'i01.why.codes';
  if (!day || day > grid.lastMarkable || day.slice(0, 7).replace('-', '') !== grid.yymm) return 'i01.why.day';
  // SRS-2-3 / BR-LABM-005: a mark of a day whose work-slip is filed changes only by the back-dated
  // correction (saving it again unchanged is accepted, as on the server).
  const changedFrozen = grid.members.some((m) =>
    m.marks.some(
      (x) =>
        x.date === day &&
        !x.otherGang &&
        x.workSlipFiled &&
        marks[m.employeeId] !== undefined &&
        marks[m.employeeId] !== x.code,
    ),
  );
  if (changedFrozen) return 'i01.why.frozen';
  const expected = grid.members.filter((m) => markable(m, day));
  if (expected.length === 0) return 'i01.why.noMembers';
  if (expected.some((m) => !marks[m.employeeId])) return 'i01.why.unmarked';
  return null;
}

/** SRS-2-3: the day of the gang holds a filed work-slip (its marks are frozen). */
export function frozenDay(grid: AttendanceGrid, day: string): boolean {
  return grid.members.some((m) => m.marks.some((x) => x.date === day && !x.otherGang && x.workSlipFiled));
}

/** The days of a month "YYYY-MM" up to the last markable day, as "YYYY-MM-DD". */
export function daysOf(month: string, lastMarkable: string): string[] {
  const [y, m] = month.split('-').map(Number) as [number, number];
  const count = new Date(Date.UTC(y, m, 0)).getUTCDate();
  const days: string[] = [];
  for (let d = 1; d <= count; d++) {
    const day = `${month}-${String(d).padStart(2, '0')}`;
    if (day <= lastMarkable) days.push(day);
  }
  return days;
}

/** The previous month of "YYYY-MM-DD" as "YYYY-MM". */
export function previousMonth(today: string): string {
  const [y, m] = today.split('-').map(Number) as [number, number];
  return m === 1 ? `${y - 1}-12` : `${y}-${String(m - 1).padStart(2, '0')}`;
}

/**
 * BR-LABR-002 / SRS-2-15 / SRS-2-17 Save and Next: the next entry keeps the gang and the last
 * operation and moves to the next open date of the month: a later day of the same month, not after
 * today, on which the gang has no live work-slip yet (`taken`); none left gives an empty date. The
 * day's attendance is checked by the server on save (SRS-2-4).
 */
export function nextSlip(
  saved: SlipEntry,
  participants: number[],
  taken: readonly string[] = [],
  today = '9999-12-31',
): SlipEntry {
  const [y, m, d] = saved.date.split('-').map(Number) as [number, number, number];
  const last = saved.rows.at(-1);
  let date = '';
  for (let next = d + 1; ; next++) {
    const day = new Date(Date.UTC(y, m - 1, next)).toISOString().slice(0, 10);
    if (day.slice(0, 7) !== saved.date.slice(0, 7) || day > today) break;
    if (!taken.includes(day)) {
      date = day;
      break;
    }
  }
  return {
    date,
    continueWork: false,
    rows: [{ ...emptyRow(), operationId: last ? last.operationId : null }],
    participants,
  };
}

/** The last day of a month "YYYY-MM" as "YYYY-MM-DD". */
export function monthEnd(month: string): string {
  const [y, m] = month.split('-').map(Number) as [number, number];
  return new Date(Date.UTC(y, m, 0)).toISOString().slice(0, 10);
}

/** SRS-2-15: the dates of the month that already hold a live slip (not amended or cancelled). */
export function slipDates(slips: readonly { date: string; status: string }[]): string[] {
  return slips.filter((s) => s.status !== 'AMENDED' && s.status !== 'CANCELLED').map((s) => s.date);
}

const HHMM = /^([01]\d|2[0-3]):[0-5]\d$/;

/** An empty row of work. */
export function emptyRow(): RowEntry {
  return {
    operationId: null,
    bagTypeId: null,
    bags: null,
    stackHeight: null,
    start: '',
    end: '',
    pbnwReason: null,
    pbnwText: '',
    rakeReference: '',
  };
}

/** SRS-5-7 / SRS-G-37: only an empty row not yet saved may be removed from the slip being entered. */
export function rowIsEmpty(r: RowEntry): boolean {
  const e = emptyRow();
  return (Object.keys(e) as (keyof RowEntry)[]).every((k) => r[k] === e[k]);
}

/** SRS-2-5: a stack height above 20 is Above-20, never Up-to-20. */
export function heightBand(stack: number | null): string | null {
  if (stack === null) return null;
  return stack > 20 ? 'ABOVE_20' : 'UPTO_20';
}

/** SRS-2-4 / SRS-2-42: why a work-slip cannot be saved, as a label key, or null. */
export function slipBlocked(slip: SlipEntry, operations: Operation[], today: string): string | null {
  if (!slip.date || slip.date > today) return 'i02.why.date';
  if (slip.rows.length === 0) return 'i02.why.rows';
  for (const r of slip.rows) {
    const op = operations.find((o) => o.id === r.operationId);
    if (!op) return 'i02.why.operation';
    if (!HHMM.test(r.start) || !HHMM.test(r.end)) return 'i02.why.time';
    if (!slip.continueWork && r.end <= r.start) return 'i02.why.timeOrder';
    if (op.bagBased && (r.bagTypeId === null || r.bags === null || r.bags < 1 || r.bags > 99999)) {
      return 'i02.why.bags';
    }
    if (op.pbnw && (!r.pbnwReason || (r.pbnwReason === 'OTHER' && r.pbnwText.trim() === ''))) {
      return 'i02.why.pbnw';
    }
  }
  if (slip.participants.length === 0) return 'i02.why.participants';
  return null;
}

/** The slip as the server takes it: fields of other operation kinds dropped. */
export function toSlipRequest(slip: SlipEntry): SlipEntry {
  return {
    ...slip,
    rows: slip.rows.map((r) => ({
      ...r,
      pbnwText: r.pbnwText.trim(),
      rakeReference: r.rakeReference.trim(),
    })),
  };
}

/** SRS-3-6 Band 1 and Band 2 bag steps, the bands a new norm set starts from (rates as the SRS states). */
export function srsSlabs(): Slab[] {
  return [
    { table: 'BAND_1', order: 1, from: 1, to: 30, ratePercent: 8 },
    { table: 'BAND_1', order: 2, from: 31, to: 60, ratePercent: 15 },
    { table: 'BAND_1', order: 3, from: 61, to: 90, ratePercent: 35 },
    { table: 'BAND_1', order: 4, from: 91, to: null, ratePercent: 50 },
    { table: 'BAND_2', order: 1, from: 1, to: 20, ratePercent: 8 },
    { table: 'BAND_2', order: 2, from: 21, to: 40, ratePercent: 15 },
    { table: 'BAND_2', order: 3, from: 41, to: 60, ratePercent: 35 },
    { table: 'BAND_2', order: 4, from: 61, to: null, ratePercent: 50 },
  ];
}

/**
 * M-06 (l.4276 "List (rows)", US-20-22 AC3): a band added at the end of a slab table. It starts the bag
 * after the table's last band; an open last band is closed at its own start so the bands stay
 * contiguous. The new band is open and has no rate yet (-1: the Rule Administrator types it).
 */
export function addSlab(slabs: Slab[], table: Slab['table']): Slab[] {
  const bands = slabs.filter((s) => s.table === table).sort((a, b) => a.order - b.order);
  const last = bands.at(-1);
  if (!last) return [...slabs, { table, order: 1, from: 1, to: null, ratePercent: -1 }];
  const end = last.to ?? last.from;
  return [
    ...slabs.map((s) => (s === last && s.to === null ? { ...s, to: end } : s)),
    { table, order: last.order + 1, from: end + 1, to: null, ratePercent: -1 },
  ];
}

/** M-06: the last band of a slab table removed; a table keeps at least one band (null when it has one). */
export function removeSlab(slabs: Slab[], table: Slab['table']): Slab[] | null {
  const bands = slabs.filter((s) => s.table === table).sort((a, b) => a.order - b.order);
  if (bands.length < 2) return null;
  const last = bands.at(-1);
  return slabs.filter((s) => s !== last);
}

/** M-06: why a norm set cannot be proposed, as a label key, or null (the server checks again). */
export function normBlocked(p: NormProposal, today: string): string | null {
  if (p.depotLocationId === null) return 'm06.why.depot';
  if (!p.effectiveFrom || p.effectiveFrom < today) return 'm06.why.effective';
  for (const n of [p.handling, p.height, p.lead]) {
    if (n === null || !Number.isInteger(n) || n < 0 || n > 999999) return 'm06.why.norm';
  }
  if (p.minLabourNumber === null || !Number.isInteger(p.minLabourNumber) || p.minLabourNumber < 1)
    return 'm06.why.mln';
  if (p.minSlabWeight === null || p.minSlabWeight <= 0) return 'm06.why.mlsw';
  for (const table of ['BAND_1', 'BAND_2']) {
    const bands = p.slabs.filter((s) => s.table === table).sort((a, b) => a.order - b.order);
    let next = 1;
    for (const [i, b] of bands.entries()) {
      if (b.from !== next) return 'm06.why.slabs';
      if (b.ratePercent < 0 || b.ratePercent > 100) return 'm06.why.rate';
      if (b.to === null) {
        if (i !== bands.length - 1) return 'm06.why.slabs';
      } else {
        if (b.to < b.from) return 'm06.why.slabs';
        next = b.to + 1;
      }
    }
  }
  if (p.slabs.length === 0) return 'm06.why.slabs';
  if (p.mouCircularId === null) return 'm06.why.mou';
  if (!reasonOk(p.reason)) return 'm06.why.reason';
  return null;
}

/**
 * A slip is amended, cancelled or (DPS) sent while it is a draft; a DPS booking-slip returned by
 * the Depot Manager is corrected the same way (SRS-2-15, SRS-2-34, D21-3).
 */
export function slipEditable(status: string, payrollType: string): boolean {
  return status === 'DRAFT' || (payrollType === 'DPS_LABOUR' && status === 'REJECTED');
}

/** The status label of a slip: a DPS booking-slip is with the Depot Manager or returned (BS-6). */
export function slipStatusKey(status: string, payrollType: string): string {
  return payrollType === 'DPS_LABOUR' && ['DRAFT', 'SUBMITTED', 'REJECTED'].includes(status)
    ? `i02.dpsStatus.${status}`
    : `i02.status.${status}`;
}

/**
 * M-05: the payroll types whose rate sets are held: DPS (MGW day rates), Departmental (Basic wage and DA,
 * SRS-3-13) and NWNP (its own depot-wise SOR / ASOR sets).
 */
export const RATE_PAYROLL_TYPES = ['DPS_LABOUR', 'DEPT_LABOUR', 'NWNP_LABOUR'] as const;

/** M-05 comparison grain of the wage floor (DDL labour_min_wage.comparison_grain). */
export const GRAINS = ['PER_DAY', 'PER_PERIOD', 'PER_LABOUR'] as const;

/** A rate set as typed on M-05: amounts as text until they are numbers. */
export interface RateDraft {
  effectiveFrom: string;
  mgw: Record<number, string>;
  interimRelief: string;
  goiRate: string;
  goiRateSource: string;
  comparisonGrain: string;
  reason: string;
}

const WHOLE = /^\d{1,10}$/;
const FRACTION = /^\d{1,2}$/;

/** A non-negative amount of at most ten digits and two decimals, or null. */
function amount(value: string): number | null {
  const [whole = '', fraction, extra] = value.trim().split('.');
  if (extra !== undefined || !WHOLE.test(whole) || (fraction !== undefined && !FRACTION.test(fraction)))
    return null;
  return Number(value.trim());
}

/**
 * M-05 (SRS-4-11): why the set cannot be sent, or null: every designation's day rate mandatory and
 * positive, the Interim Relief zero or more, a GoI rate positive with its source, the date and the reason.
 */
export function rateBlocked(d: RateDraft, designations: RateDesignation[]): string | null {
  if (designations.length === 0) return 'm05.why.designations';
  if (d.effectiveFrom === '') return 'm05.why.effective';
  for (const x of designations) {
    const v = amount(d.mgw[x.id] ?? '');
    if (v === null || v <= 0) return 'm05.why.mgw';
  }
  if (!blank(d.interimRelief) && amount(d.interimRelief) === null) return 'm05.why.ir';
  if (!blank(d.goiRate)) {
    const g = amount(d.goiRate);
    if (g === null || g <= 0) return 'm05.why.goi';
  }
  const source = d.goiRateSource.trim();
  if (
    (!blank(d.goiRate) && source === '') ||
    source.length > 200 ||
    (source !== '' && !TEXT_LINES.test(source))
  )
    return 'm05.why.goiSource';
  if (!reasonOk(d.reason)) return 'm05.why.reason';
  return null;
}

/** The request of a typed set (all depots until OQ-14 (v0.4) says rates differ by location). */
export function toRateRequest(
  d: RateDraft,
  payrollType: string,
  designations: RateDesignation[],
): RateSetProposal {
  return {
    payrollType,
    depotLocationId: null,
    effectiveFrom: d.effectiveFrom,
    rates: designations.map((x) => ({ designationId: x.id, mgw: Number((d.mgw[x.id] ?? '').trim()) })),
    interimRelief: blank(d.interimRelief) ? null : Number(d.interimRelief.trim()),
    goiRate: blank(d.goiRate) ? null : Number(d.goiRate.trim()),
    goiRateSource: blank(d.goiRateSource) ? null : d.goiRateSource.trim(),
    comparisonGrain: d.comparisonGrain === '' ? null : d.comparisonGrain,
    reason: d.reason.trim(),
  };
}

/** A Departmental Labour Basic wage and DA set as typed on M-05 (SRS-3-13, US-GAP-61). */
export interface DeptRateDraft {
  effectiveFrom: string;
  basic: Record<number, string>;
  da: Record<number, string>;
  reason: string;
}

/**
 * M-05 (SRS-3-13): why a Departmental set cannot be sent, or null: every designation's Basic wage
 * positive and its DA zero or more, the date and the reason.
 */
export function deptRateBlocked(d: DeptRateDraft, designations: RateDesignation[]): string | null {
  if (designations.length === 0) return 'm05.why.designations';
  if (d.effectiveFrom === '') return 'm05.why.effective';
  for (const x of designations) {
    const b = amount(d.basic[x.id] ?? '');
    if (b === null || b <= 0) return 'm05.why.basic';
    if (amount(d.da[x.id] ?? '') === null) return 'm05.why.da';
  }
  if (!reasonOk(d.reason)) return 'm05.why.reason';
  return null;
}

/** The request of a typed Departmental set (all depots). */
export function toDeptRateRequest(d: DeptRateDraft, designations: RateDesignation[]): RateSetProposal {
  return {
    payrollType: 'DEPT_LABOUR',
    depotLocationId: null,
    effectiveFrom: d.effectiveFrom,
    rates: designations.map((x) => ({
      designationId: x.id,
      basicWage: Number((d.basic[x.id] ?? '').trim()),
      da: Number((d.da[x.id] ?? '').trim()),
    })),
    interimRelief: null,
    goiRate: null,
    goiRateSource: null,
    comparisonGrain: null,
    reason: d.reason.trim(),
  };
}

/** An NWNP rate set as typed on M-05 (SRS-5-2, SRS-5-3). */
export interface NwnpDraft {
  depotLocationId: number | null;
  version: string;
  effectiveFrom: string;
  rates: Record<string, string>;
  units: Record<string, string>;
  mgw: Record<number, string>;
  reason: string;
}

/**
 * M-05 ▸ NWNP (SRS-5-3): why the set cannot be sent, or null: the depot, the date, every operation's Piece
 * of Rate (above 0) with its Unit of Measure, every designation's MGW and the reason.
 */
export function nwnpBlocked(d: NwnpDraft, o: NwnpRateOptions): string | null {
  if (d.depotLocationId === null) return 'm05.why.depot';
  if (d.effectiveFrom === '') return 'm05.why.effective';
  if (o.operations.length === 0 || o.designations.length === 0) return 'm05.why.designations';
  for (const op of o.operations) {
    const v = amount(d.rates[op.code] ?? '');
    if (v === null || v <= 0) return 'm05.why.pieceOfRate';
    if (!o.unitsOfMeasure.some((u) => u.code === d.units[op.code])) return 'm05.why.unit';
  }
  for (const x of o.designations) {
    const v = amount(d.mgw[x.id] ?? '');
    if (v === null || v <= 0) return 'm05.why.mgw';
  }
  if (!reasonOk(d.reason)) return 'm05.why.reason';
  return null;
}

/** The request of a typed NWNP set. */
export function toNwnpRequest(d: NwnpDraft, o: NwnpRateOptions): NwnpRateProposal {
  return {
    depotLocationId: d.depotLocationId ?? 0,
    version: d.version,
    effectiveFrom: d.effectiveFrom,
    lines: o.operations.map((op) => ({
      operationCode: op.code,
      pieceOfRate: Number((d.rates[op.code] ?? '').trim()),
      unitOfMeasure: d.units[op.code] ?? '',
    })),
    mgw: o.designations.map((x) => ({ designationId: x.id, mgw: Number((d.mgw[x.id] ?? '').trim()) })),
    reason: d.reason.trim(),
  };
}

/** NWNP Reinstate Details (D22-7): why the reinstatement cannot be sent, or null. */
export function reinstatementBlocked(r: ReinstatementEntry, today: string): string | null {
  if (r.date === '') return 'm02.why.reinstateDate';
  if (
    r.orderDate === '' ||
    r.orderDate > today ||
    blank(r.issuingAuthority) ||
    !PRINTABLE.test(r.issuingAuthority.trim())
  )
    return 'm02.why.document';
  if (r.orderReference.trim() !== '' && !PRINTABLE.test(r.orderReference.trim())) return 'm02.why.document';
  if (!reasonOk(r.reason)) return 'm02.why.reason';
  if (!r.document) return 'm02.why.file';
  return null;
}

/** BR-FNF-014 / D22-8: why the nominees and dependents cannot be sent, or null. */
export function nominationBlocked(
  nominees: NamedPerson[],
  dependents: NamedPerson[],
  reason: string,
  today: string,
): string | null {
  if (nominees.length === 0 && dependents.length === 0) return 'm02.why.nobody';
  for (const p of [...nominees, ...dependents]) {
    const n = p.name.trim();
    if (n === '' || n.length > 150 || !PRINTABLE.test(n)) return 'm02.why.personName';
    if (p.relationship === '') return 'm02.why.relationship';
    if (p.birthDate && p.birthDate > today) return 'm02.why.birth';
  }
  let total = 0;
  for (const p of nominees) {
    if (
      p.share === null ||
      !(p.share > 0) ||
      p.share > 100 ||
      // D22-8, BR-FNF-014: two decimals at most, exactly as the server checks it (a BigDecimal scale of 2):
      // a floating-point tolerance let a share of 0.00000001 pass here that the server then refuses.
      Number(p.share.toFixed(2)) !== p.share
    )
      return 'm02.why.share';
    total += Math.round(p.share * 100);
  }
  if (nominees.length > 0 && total !== 10000) return 'm02.why.shareTotal';
  if (!reasonOk(reason)) return 'm02.why.reason';
  return null;
}
