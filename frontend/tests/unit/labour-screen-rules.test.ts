import { describe, expect, it } from 'vitest';
import type {
  AttendanceCode,
  AttendanceGrid,
  CorrectionEntry,
  MemberDetail,
  MemberEntry,
  MemberGrid,
  NamedPerson,
  NewGang,
  NormProposal,
  NwnpRateOptions,
  Operation,
  ReinstatementEntry,
  RowEntry,
  Slip,
  SlipEntry,
  SlipRow,
} from '@/modules/labour/api/labourApi';
import {
  GANG_ROLES,
  GRAINS,
  PAYROLL_TYPES,
  RATE_PAYROLL_TYPES,
  correctionBlocked,
  dayBlocked,
  dayStrength,
  daysOf,
  deptRateBlocked,
  detailEntry,
  emptyEntry,
  emptyRow,
  entryBlocked,
  frozenDay,
  gangBlocked,
  heightBand,
  markable,
  nextSlip,
  nominationBlocked,
  normBlocked,
  nwnpBlocked,
  orderBlocked,
  previousMonth,
  rateBlocked,
  reasonOk,
  reinstatementBlocked,
  rowIsEmpty,
  slipBlocked,
  slipEditable,
  slipRows,
  slipStatusKey,
  srsSlabs,
  strengthBlocked,
  toAmendRequest,
  toDeptRateRequest,
  toNwnpRequest,
  toRateRequest,
  toRequest,
  toSlipRequest,
  type DeptRateDraft,
  type NwnpDraft,
  type RateDraft,
} from '@/modules/labour/business/labourRules';

/**
 * Labour screen rules, each pinned with its boundaries (plan 20; SRS-2-1 … SRS-2-59, SRS-3-6,
 * SRS-3-13, SRS-4-11, SRS-5-3; BR-LABM-001, BR-LABM-005, BR-LABM-011, BR-LABR-002, BR-FNF-014):
 * the rules file's own tests, so mutation testing (vitest.mutation.config.ts runs only *rules*
 * tests) reaches the logic the screen tests exercise.
 */

const TODAY = '2026-10-28';
const NBSP = ' ';
const FILE = {} as File;

function entry(over: Partial<MemberEntry> = {}): MemberEntry {
  return {
    ...emptyEntry(),
    firstName: 'Ram',
    lastName: 'Kumar',
    birthDate: '1990-05-10',
    joiningDate: '2010-07-01',
    designationId: 3,
    depotLocationId: 7,
    ifsc: 'SBIN0001234',
    bankAccount: '12345678',
    reason: 'Appointment order',
    ...over,
  };
}

describe('constants', () => {
  it('SRS-2-1: the payroll types, gang roles, rate payroll types and wage-floor grains', () => {
    expect(PAYROLL_TYPES).toEqual(['DEPT_LABOUR', 'DPS_LABOUR', 'NWNP_LABOUR']);
    expect(GANG_ROLES).toEqual(['SARDAR', 'MANDAL', 'HANDLING', 'ANCILLARY']);
    expect(RATE_PAYROLL_TYPES).toEqual(['DPS_LABOUR', 'DEPT_LABOUR', 'NWNP_LABOUR']);
    expect(GRAINS).toEqual(['PER_DAY', 'PER_PERIOD', 'PER_LABOUR']);
  });
});

describe('reason and order', () => {
  it('R-ASCII: a reason is trimmed, 1 to 500 printable characters with line breaks', () => {
    expect(reasonOk('x'.repeat(500))).toBe(true);
    expect(reasonOk(`  ${'x'.repeat(500)}  `)).toBe(true);
    expect(reasonOk('x'.repeat(501))).toBe(false);
    expect(reasonOk('   ')).toBe(false);
    expect(reasonOk('a\tb\r\nc')).toBe(true);
    expect(reasonOk(`ok${NBSP}`)).toBe(true);
    expect(reasonOk('okअ')).toBe(false);
  });

  it('SRS-2-6: the order reference and issuing authority are measured trimmed', () => {
    const order = { orderReference: 'TO/7', orderDate: TODAY, issuingAuthority: 'Area Manager' };
    expect(orderBlocked({ ...order, issuingAuthority: `${'A'.repeat(150)}  ` }, TODAY)).toBeNull();
    expect(orderBlocked({ ...order, orderReference: `  ${'R'.repeat(100)}` }, TODAY)).toBeNull();
    expect(orderBlocked({ ...order, orderDate: '2026-10-27' }, TODAY)).toBeNull();
  });
});

describe('SRS-2-30 M-02 entry', () => {
  it('SRS-2-30: fields are checked trimmed', () => {
    expect(entryBlocked(entry({ firstName: ' Ram', lastName: ' Kumar ' }), TODAY)).toBeNull();
    expect(entryBlocked(entry({ panNumber: ' abcpk1234d ' }), TODAY)).toBeNull();
    expect(entryBlocked(entry({ ifsc: ' sbin0001234 ' }), TODAY)).toBeNull();
    expect(entryBlocked(entry({ bankAccount: ' 12345678 ' }), TODAY)).toBeNull();
    expect(entryBlocked(entry({ mobile: ' 9876543210 ' }), TODAY)).toBeNull();
    expect(entryBlocked(entry({ pfApplicable: true, cpfCode: ' dl/1 ' }), TODAY)).toBeNull();
  });

  it('SRS-2-30: the joining date may be today and the birth date the joining date', () => {
    expect(entryBlocked(entry({ joiningDate: TODAY }), TODAY)).toBeNull();
    expect(entryBlocked(entry({ birthDate: '2010-07-01' }), TODAY)).toBeNull();
  });

  it('SRS-2-30: each identifier must match whole, not only a part', () => {
    expect(entryBlocked(entry({ firstName: '1Ram' }), TODAY)).toBe('m02.why.name');
    expect(entryBlocked(entry({ firstName: 'Ram1' }), TODAY)).toBe('m02.why.name');
    expect(entryBlocked(entry({ firstName: `R${'a'.repeat(99)}` }), TODAY)).toBeNull();
    expect(entryBlocked(entry({ firstName: `R${'a'.repeat(100)}` }), TODAY)).toBe('m02.why.name');
    expect(entryBlocked(entry({ firstName: "D'Souza-Ram K." }), TODAY)).toBeNull();
    expect(entryBlocked(entry({ panNumber: 'XABCPK1234D' }), TODAY)).toBe('m02.why.pan');
    expect(entryBlocked(entry({ panNumber: 'ABCPK1234DX' }), TODAY)).toBe('m02.why.pan');
    expect(entryBlocked(entry({ ifsc: 'XSBIN0001234' }), TODAY)).toBe('m02.why.ifsc');
    expect(entryBlocked(entry({ ifsc: 'SBIN0001234X' }), TODAY)).toBe('m02.why.ifsc');
    expect(entryBlocked(entry({ bankAccount: 'ab123456' }), TODAY)).toBe('m02.why.account');
    expect(entryBlocked(entry({ bankAccount: '123456ab' }), TODAY)).toBe('m02.why.account');
    expect(entryBlocked(entry({ bankAccount: '123456' }), TODAY)).toBeNull();
    expect(entryBlocked(entry({ bankAccount: '1'.repeat(20) }), TODAY)).toBeNull();
    expect(entryBlocked(entry({ bankAccount: '1'.repeat(21) }), TODAY)).toBe('m02.why.account');
    expect(entryBlocked(entry({ bankAccount: '' }), TODAY)).toBe('m02.why.account');
    expect(entryBlocked(entry({ mobile: 'a9876543210' }), TODAY)).toBe('m02.why.mobile');
    expect(entryBlocked(entry({ mobile: '98765432101' }), TODAY)).toBe('m02.why.mobile');
    expect(entryBlocked(entry({ mobile: '6000000000' }), TODAY)).toBeNull();
    expect(entryBlocked(entry({ pfApplicable: true, cpfCode: '#DL/1' }), TODAY)).toBe('m02.why.cpf');
    expect(entryBlocked(entry({ pfApplicable: true, cpfCode: 'DL/1#' }), TODAY)).toBe('m02.why.cpf');
    expect(entryBlocked(entry({ pfApplicable: true, cpfCode: 'A'.repeat(20) }), TODAY)).toBeNull();
    expect(entryBlocked(entry({ pfApplicable: true, cpfCode: 'A'.repeat(21) }), TODAY)).toBe('m02.why.cpf');
    expect(entryBlocked(entry({ pfApplicable: false, cpfCode: '#' }), TODAY)).toBeNull();
  });

  it('SRS-2-59: an amendment keeps the name, birth date, designation, depot and masked account', () => {
    const kept = entry({ firstName: '', birthDate: '', designationId: null, depotLocationId: null });
    expect(entryBlocked({ ...kept, bankAccount: '' }, TODAY, true)).toBeNull();
    expect(entryBlocked({ ...kept, bankAccount: '12' }, TODAY, true)).toBe('m02.why.account');
    expect(entryBlocked({ ...kept, joiningDate: '' }, TODAY, true)).toBe('m02.why.joining');
    expect(entryBlocked(entry({ lastName: '9' }), TODAY, true)).toBeNull();
  });

  it('an empty entry starts blank on the Departmental payroll', () => {
    expect(emptyEntry()).toEqual({
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
    });
  });

  it('SRS-2-30: the request trims every field, upper-cases identifiers and sends blanks as null', () => {
    expect(
      toRequest(
        entry({
          lastName: ' K ',
          fatherName: '',
          gender: 'M',
          panNumber: '',
          ifsc: ' sbin0001234 ',
          bankAccount: ' 123456 ',
          mobile: ' 9876543210 ',
          uanNumber: ' 100200300400 ',
          reason: ' Order ',
        }),
      ),
    ).toEqual({
      ...entry(),
      lastName: 'K',
      fatherName: null,
      gender: 'M',
      panNumber: null,
      ifsc: 'SBIN0001234',
      bankAccount: '123456',
      mobile: '9876543210',
      cpfCode: null,
      uanNumber: '100200300400',
      reason: 'Order',
    });
    expect(
      toRequest(entry({ firstName: ' Ram ', fatherName: ' F ', mobile: '', uanNumber: '' })),
    ).toMatchObject({
      firstName: 'Ram',
      fatherName: 'F',
      mobile: null,
      uanNumber: null,
    });
  });
});

describe('SRS-2-59 correction', () => {
  const name: CorrectionEntry = {
    type: 'NAME',
    firstName: 'Ram',
    lastName: 'Kumar',
    birthDate: '',
    reasonCode: 'AS_PER_SERVICE_BOOK',
    reasonText: '',
    orderReference: '',
    orderDate: TODAY,
    issuingAuthority: 'Area Manager',
    effectiveDate: TODAY,
    document: FILE,
  };
  const dob: CorrectionEntry = { ...name, type: 'DOB', firstName: '', birthDate: '1990-01-01' };

  it('SRS-2-59: a name correction checks the name, trimmed', () => {
    expect(correctionBlocked(name, TODAY)).toBeNull();
    expect(correctionBlocked({ ...name, firstName: ' Ram ', lastName: ' Kumar ' }, TODAY)).toBeNull();
    expect(correctionBlocked({ ...name, lastName: '' }, TODAY)).toBeNull();
    expect(correctionBlocked({ ...name, firstName: '' }, TODAY)).toBe('m02.why.name');
    expect(correctionBlocked({ ...name, lastName: 'K#' }, TODAY)).toBe('m02.why.name');
    expect(correctionBlocked({ ...name, birthDate: '2099-01-01' }, TODAY)).toBeNull();
  });

  it('SRS-2-59: a birth-date correction needs a date not after today', () => {
    expect(correctionBlocked(dob, TODAY)).toBeNull();
    expect(correctionBlocked({ ...dob, birthDate: TODAY }, TODAY)).toBeNull();
    expect(correctionBlocked({ ...dob, birthDate: '' }, TODAY)).toBe('m02.why.birth');
    expect(correctionBlocked({ ...dob, birthDate: '2026-10-29' }, TODAY)).toBe('m02.why.birth');
  });

  it('SRS-2-59: the document, its order, the reason text, the effective date and the file', () => {
    expect(correctionBlocked({ ...name, orderDate: '' }, TODAY)).toBe('m02.why.document');
    expect(correctionBlocked({ ...name, orderDate: '2026-10-29' }, TODAY)).toBe('m02.why.document');
    expect(correctionBlocked({ ...name, issuingAuthority: ' ' }, TODAY)).toBe('m02.why.document');
    expect(correctionBlocked({ ...name, reasonCode: 'AS_PER_ORDER' }, TODAY)).toBe('m02.why.document');
    expect(
      correctionBlocked({ ...name, reasonCode: 'AS_PER_ORDER', orderReference: 'O/1' }, TODAY),
    ).toBeNull();
    expect(correctionBlocked({ ...name, reasonText: 'Service book' }, TODAY)).toBeNull();
    expect(correctionBlocked({ ...name, reasonText: `Book${NBSP}` }, TODAY)).toBeNull();
    expect(correctionBlocked({ ...name, reasonText: 'अ' }, TODAY)).toBe('m02.why.reason');
    expect(correctionBlocked({ ...name, effectiveDate: '' }, TODAY)).toBe('m02.why.effective');
    expect(correctionBlocked({ ...name, document: null }, TODAY)).toBe('m02.why.file');
  });
});

describe('SRS-2-30 amendment', () => {
  const detail: MemberDetail = {
    employeeId: 9,
    labourCode: 'L9',
    payrollType: 'DPS_LABOUR',
    firstName: 'Ram',
    lastName: null,
    fatherName: null,
    gender: null,
    birthDate: '1990-01-01',
    joiningDate: '2010-01-01',
    designationId: 3,
    depotLocationId: 7,
    panMasked: 'XXXXX1234X',
    ifsc: null,
    bankAccountMasked: 'XXXX5678',
    mobileMasked: 'XXXXXX3210',
    pfApplicable: false,
    cpfCode: null,
    uanNumber: null,
    casualLabour: true,
  };

  it('SRS-2-30: the form opens with the record, masked fields and the reason empty', () => {
    expect(detailEntry(detail)).toEqual({
      payrollType: 'DPS_LABOUR',
      firstName: 'Ram',
      lastName: '',
      fatherName: '',
      gender: '',
      birthDate: '1990-01-01',
      joiningDate: '2010-01-01',
      designationId: 3,
      depotLocationId: 7,
      panNumber: '',
      ifsc: '',
      bankAccount: '',
      mobile: '',
      pfApplicable: false,
      cpfCode: '',
      uanNumber: '',
      casualLabour: true,
      reason: '',
    });
    expect(
      detailEntry({
        ...detail,
        lastName: 'K',
        fatherName: 'F',
        gender: 'F',
        ifsc: 'SBIN0001234',
        pfApplicable: true,
        cpfCode: 'DL/1',
        uanNumber: '1001',
      }),
    ).toMatchObject({
      lastName: 'K',
      fatherName: 'F',
      gender: 'F',
      ifsc: 'SBIN0001234',
      pfApplicable: true,
      cpfCode: 'DL/1',
      uanNumber: '1001',
    });
  });

  it('SRS-2-30: only the changed fields are sent, with the reason; kept fields never', () => {
    const original = entry({ fatherName: 'Father', mobile: '' });
    expect(toAmendRequest(original, original)).toEqual({});
    expect(toAmendRequest({ ...original, reason: 'Only a reason' }, original)).toEqual({});
    expect(toAmendRequest({ ...original, firstName: 'Shyam', reason: 'x' }, original)).toEqual({});
    expect(toAmendRequest({ ...original, ifsc: 'hdfc0000001', reason: ' Bank ' }, original)).toEqual({
      ifsc: 'HDFC0000001',
      reason: 'Bank',
    });
    expect(toAmendRequest({ ...original, fatherName: ' ', reason: 'Cleared' }, original)).toEqual({
      fatherName: '',
      reason: 'Cleared',
    });
    expect(toAmendRequest({ ...original, mobile: '9876543210', reason: 'New' }, original)).toEqual({
      mobile: '9876543210',
      reason: 'New',
    });
  });

  it('R-PII: a masked field left empty is kept, never cleared', () => {
    const original = entry({ panNumber: 'ABCPK1234D', mobile: '9876543210' });
    expect(
      toAmendRequest({ ...original, panNumber: '', bankAccount: '', mobile: '', reason: 'x' }, original),
    ).toEqual({});
  });
});

const CODES: AttendanceCode[] = [
  { code: 'A', meaning: 'Absent', weight: 0, leave: false, confirmed: true },
  { code: 'L', meaning: 'Leave', weight: 1, leave: true, confirmed: true },
  { code: 'P', meaning: 'Present', weight: 1, leave: false, confirmed: true },
  { code: 'H', meaning: 'Half', weight: 0.5, leave: false, confirmed: true },
];

function member(over: Partial<MemberGrid> = {}): MemberGrid {
  return {
    employeeId: 1,
    labourCode: 'L1',
    name: 'Ram',
    role: 'HANDLING',
    from: '2026-09-01',
    to: null,
    retirementDate: null,
    marks: [],
    marked: 0,
    presentDays: 0,
    leaveDays: 0,
    absentDays: 0,
    ...over,
  };
}

function grid(over: Partial<AttendanceGrid> = {}): AttendanceGrid {
  return {
    gangId: 1,
    gangName: 'G1',
    depot: 'Okhla',
    payrollType: 'DEPT_LABOUR',
    yymm: '202609',
    lastMarkable: '2026-09-30',
    batchState: 'OPEN',
    approvalId: null,
    sanctionedStrength: 4,
    codes: CODES,
    members: [member(), member({ employeeId: 2 })],
    ...over,
  };
}

describe('SRS-2-48 I-01 attendance', () => {
  it('SRS-2-48: present counts codes with a present weight, never a leave code or unknown code', () => {
    expect(dayStrength(grid(), { 1: 'P', 2: 'L', 3: 'A', 4: 'H', 5: 'X' })).toEqual({
      sanctioned: 4,
      present: 2,
      short: 2,
    });
    expect(dayStrength(grid(), { 1: 'A' })).toEqual({ sanctioned: 4, present: 0, short: 4 });
    expect(dayStrength(grid({ sanctionedStrength: 1 }), { 1: 'P', 2: 'P' })).toEqual({
      sanctioned: 1,
      present: 2,
      short: 0,
    });
    expect(dayStrength(grid({ sanctionedStrength: 2 }), { 1: 'P', 2: 'P' })).toMatchObject({ short: 0 });
    expect(dayStrength(grid({ sanctionedStrength: null }), { 1: 'P' })).toBeNull();
  });

  it('SRS-2-2: a member is marked from joining the gang to leaving it or retiring, both days included', () => {
    const day = '2026-09-10';
    expect(markable(member({ from: day }), day)).toBe(true);
    expect(markable(member({ from: '2026-09-11' }), day)).toBe(false);
    expect(markable(member({ to: day }), day)).toBe(true);
    expect(markable(member({ to: '2026-09-09' }), day)).toBe(false);
    expect(markable(member({ retirementDate: day }), day)).toBe(true);
    expect(markable(member({ retirementDate: '2026-09-09' }), day)).toBe(false);
    expect(markable(member(), day)).toBe(true);
  });

  it('SRS-2-35 / SRS-2-29: a submitted or approved batch is closed, an open or returned one is not', () => {
    const marks = { 1: 'P', 2: 'P' };
    expect(dayBlocked(grid({ batchState: 'SUBMITTED' }), '2026-09-10', marks)).toBe('i01.why.batch');
    expect(dayBlocked(grid({ batchState: 'APPROVED' }), '2026-09-10', marks)).toBe('i01.why.batch');
    expect(dayBlocked(grid({ batchState: 'RETURNED' }), '2026-09-10', marks)).toBeNull();
    expect(dayBlocked(grid(), '2026-09-10', marks)).toBeNull();
    expect(dayBlocked(grid({ codes: [] }), '2026-09-10', marks)).toBe('i01.why.codes');
  });

  it('SRS-2-35: the day lies in the batch month, not after the last markable day', () => {
    const marks = { 1: 'P', 2: 'P' };
    expect(dayBlocked(grid(), '', marks)).toBe('i01.why.day');
    expect(dayBlocked(grid({ lastMarkable: '2026-09-09' }), '2026-09-10', marks)).toBe('i01.why.day');
    expect(dayBlocked(grid({ lastMarkable: '2026-09-10' }), '2026-09-10', marks)).toBeNull();
    expect(dayBlocked(grid({ lastMarkable: '2026-10-31' }), '2026-10-01', marks)).toBe('i01.why.day');
    expect(dayBlocked(grid({ yymm: '202610', lastMarkable: '2026-10-31' }), '2026-10-01', marks)).toBeNull();
  });

  it('SRS-2-3 / BR-LABM-005: a filed day changes only by the correction; unchanged is accepted', () => {
    const day = '2026-09-10';
    const filed = { date: day, code: 'P', otherGang: false, workSlipFiled: true };
    const g = grid({ members: [member({ marks: [filed] }), member({ employeeId: 2 })] });
    expect(dayBlocked(g, day, { 1: 'A', 2: 'P' })).toBe('i01.why.frozen');
    expect(dayBlocked(g, day, { 1: 'P', 2: 'P' })).toBeNull();
    expect(dayBlocked(g, day, { 2: 'P' })).toBe('i01.why.unmarked');
    const other = grid({ members: [member({ marks: [{ ...filed, otherGang: true }] })] });
    expect(dayBlocked(other, day, { 1: 'A' })).toBeNull();
    const notFiled = grid({ members: [member({ marks: [{ ...filed, workSlipFiled: false }] })] });
    expect(dayBlocked(notFiled, day, { 1: 'A' })).toBeNull();
    const otherDay = grid({ members: [member({ marks: [{ ...filed, date: '2026-09-11' }] })] });
    expect(dayBlocked(otherDay, day, { 1: 'A' })).toBeNull();
    expect(frozenDay(g, day)).toBe(true);
    expect(frozenDay(g, '2026-09-11')).toBe(false);
    expect(frozenDay(other, day)).toBe(false);
    expect(frozenDay(notFiled, day)).toBe(false);
    expect(frozenDay(grid({ members: [] }), day)).toBe(false);
  });

  it('SRS-2-35: every member markable on the day is marked; none markable is refused', () => {
    const day = '2026-09-10';
    expect(dayBlocked(grid(), day, { 1: 'P' })).toBe('i01.why.unmarked');
    expect(dayBlocked(grid(), day, { 1: 'P', 2: '' })).toBe('i01.why.unmarked');
    const one = grid({ members: [member(), member({ employeeId: 2, to: '2026-09-09' })] });
    expect(dayBlocked(one, day, { 1: 'P' })).toBeNull();
    expect(dayBlocked(grid({ members: [member({ from: '2026-09-11' })] }), day, {})).toBe(
      'i01.why.noMembers',
    );
    expect(dayBlocked(grid({ members: [] }), day, {})).toBe('i01.why.noMembers');
  });

  it('the days of a month up to the last markable day', () => {
    const all = daysOf('2026-09', '2026-09-30');
    expect(all).toHaveLength(30);
    expect(all[0]).toBe('2026-09-01');
    expect(all[29]).toBe('2026-09-30');
    expect(daysOf('2026-09', '2026-09-03')).toEqual(['2026-09-01', '2026-09-02', '2026-09-03']);
    expect(daysOf('2028-02', '2028-12-31')).toHaveLength(29);
    expect(daysOf('2026-09', '2026-08-31')).toEqual([]);
  });

  it('the previous month, across the year end', () => {
    expect(previousMonth('2026-01-15')).toBe('2025-12');
    expect(previousMonth('2026-10-03')).toBe('2026-09');
    expect(previousMonth('2026-12-31')).toBe('2026-11');
    expect(previousMonth('2026-02-01')).toBe('2026-01');
  });
});

function slipRow(over: Partial<SlipRow> = {}): SlipRow {
  return {
    order: 1,
    operation: 'Loading',
    bagType: null,
    bags: 50,
    stackHeight: 12,
    heightBand: 'UPTO_20',
    start: '09:00',
    end: '13:00',
    pbnwReason: 'RAIN',
    operationId: 5,
    bagTypeId: 2,
    pbnwText: null,
    rakeReference: null,
    ...over,
  };
}

describe('SRS-2-15 work-slips', () => {
  it('SRS-2-15: an amendment opens with the saved rows, never a blank one', () => {
    const slip = {
      id: 1,
      number: 'S1',
      date: '2026-09-02',
      status: 'DRAFT',
      continueWork: false,
      holiday: false,
      labourStrength: null,
      amendmentOf: null,
      rows: [] as SlipRow[],
      participants: [],
    } satisfies Slip;
    expect(slipRows(slip)).toEqual([emptyRow()]);
    expect(
      slipRows({ ...slip, rows: [slipRow(), slipRow({ pbnwText: 'Wet', rakeReference: 'RK1' })] }),
    ).toEqual([
      {
        operationId: 5,
        bagTypeId: 2,
        bags: 50,
        stackHeight: 12,
        start: '09:00',
        end: '13:00',
        pbnwReason: 'RAIN',
        pbnwText: '',
        rakeReference: '',
      },
      {
        operationId: 5,
        bagTypeId: 2,
        bags: 50,
        stackHeight: 12,
        start: '09:00',
        end: '13:00',
        pbnwReason: 'RAIN',
        pbnwText: 'Wet',
        rakeReference: 'RK1',
      },
    ]);
  });

  it('BR-LABR-002 / SRS-2-17: Save and Next keeps the last operation and the participants', () => {
    const rows: RowEntry[] = [
      { ...emptyRow(), operationId: 4 },
      { ...emptyRow(), operationId: 6 },
      { ...emptyRow(), operationId: 8 },
    ];
    const saved: SlipEntry = { date: '2026-09-02', continueWork: true, rows, participants: [1] };
    expect(nextSlip(saved, [3, 4])).toEqual({
      date: '2026-09-03',
      continueWork: false,
      rows: [{ ...emptyRow(), operationId: 8 }],
      participants: [3, 4],
    });
    expect(nextSlip({ ...saved, rows: [] }, []).rows).toEqual([emptyRow()]);
    expect(nextSlip({ ...saved, date: '2026-09-30' }, []).date).toBe('');
    expect(nextSlip({ ...saved, date: '2026-12-30' }, []).date).toBe('2026-12-31');
    expect(nextSlip(saved, [], ['2026-09-03'], '2026-09-04').date).toBe('2026-09-04');
  });

  it('SRS-5-7 / SRS-G-37: an empty row is one that differs from the blank row in no field', () => {
    expect(emptyRow()).toEqual({
      operationId: null,
      bagTypeId: null,
      bags: null,
      stackHeight: null,
      start: '',
      end: '',
      pbnwReason: null,
      pbnwText: '',
      rakeReference: '',
    });
    expect(rowIsEmpty(emptyRow())).toBe(true);
    const changes: Partial<RowEntry>[] = [
      { operationId: 1 },
      { bagTypeId: 1 },
      { bags: 0 },
      { stackHeight: 0 },
      { start: '09:00' },
      { end: '10:00' },
      { pbnwReason: 'RAIN' },
      { pbnwText: 'x' },
      { rakeReference: 'R' },
    ];
    for (const c of changes) expect(rowIsEmpty({ ...emptyRow(), ...c })).toBe(false);
  });

  it('SRS-2-5: a stack height above 20 is Above-20, 20 itself is Up-to-20', () => {
    expect(heightBand(null)).toBeNull();
    expect(heightBand(0)).toBe('UPTO_20');
    expect(heightBand(20)).toBe('UPTO_20');
    expect(heightBand(20.5)).toBe('ABOVE_20');
    expect(heightBand(21)).toBe('ABOVE_20');
  });

  const ops: Operation[] = [
    { id: 1, code: 'LOAD', name: 'Loading', bagBased: true, normType: null, pbnw: false },
    { id: 2, code: 'CLEAN', name: 'Cleaning', bagBased: false, normType: null, pbnw: true },
    { id: 3, code: 'MISC', name: 'Misc', bagBased: false, normType: null, pbnw: false },
  ];
  const bagRow: RowEntry = {
    ...emptyRow(),
    operationId: 1,
    bagTypeId: 2,
    bags: 10,
    start: '09:00',
    end: '10:00',
  };
  const slip = (rows: RowEntry[], over: Partial<SlipEntry> = {}): SlipEntry => ({
    date: TODAY,
    continueWork: false,
    rows,
    participants: [1],
    ...over,
  });

  it('SRS-2-4: the date not after today, at least one row and a known operation', () => {
    expect(slipBlocked(slip([bagRow]), ops, TODAY)).toBeNull();
    expect(slipBlocked(slip([bagRow], { date: '' }), ops, TODAY)).toBe('i02.why.date');
    expect(slipBlocked(slip([bagRow], { date: '2026-10-29' }), ops, TODAY)).toBe('i02.why.date');
    expect(slipBlocked(slip([]), ops, TODAY)).toBe('i02.why.rows');
    expect(slipBlocked(slip([{ ...bagRow, operationId: 9 }]), ops, TODAY)).toBe('i02.why.operation');
    expect(slipBlocked(slip([{ ...bagRow, operationId: null }]), ops, TODAY)).toBe('i02.why.operation');
    expect(slipBlocked(slip([bagRow, { ...bagRow, operationId: 9 }]), ops, TODAY)).toBe('i02.why.operation');
    expect(slipBlocked(slip([bagRow], { participants: [] }), ops, TODAY)).toBe('i02.why.participants');
  });

  it('SRS-2-4: times are HH:MM of a day and the end after the start unless work continues', () => {
    const at = (start: string, end: string) => slipBlocked(slip([{ ...bagRow, start, end }]), ops, TODAY);
    expect(at('00:00', '23:59')).toBeNull();
    expect(at('19:59', '20:00')).toBeNull();
    expect(at('09:30', '10:45')).toBeNull();
    for (const bad of ['24:00', '12:60', '9:00', '12:5', 'x12:00', '12:00x', '', '1a:00', '30:00']) {
      expect(at(bad, '23:00')).toBe('i02.why.time');
      expect(at('00:30', bad)).toBe('i02.why.time');
    }
    expect(at('10:00', '10:00')).toBe('i02.why.timeOrder');
    expect(at('10:00', '09:59')).toBe('i02.why.timeOrder');
    expect(
      slipBlocked(slip([{ ...bagRow, start: '22:00', end: '02:00' }], { continueWork: true }), ops, TODAY),
    ).toBeNull();
  });

  it('SRS-2-42: a bag operation needs its bag type and 1 to 99999 bags', () => {
    const bags = (r: Partial<RowEntry>) => slipBlocked(slip([{ ...bagRow, ...r }]), ops, TODAY);
    expect(bags({ bagTypeId: null })).toBe('i02.why.bags');
    expect(bags({ bags: null })).toBe('i02.why.bags');
    expect(bags({ bags: 0 })).toBe('i02.why.bags');
    expect(bags({ bags: 1 })).toBeNull();
    expect(bags({ bags: 99999 })).toBeNull();
    expect(bags({ bags: 100000 })).toBe('i02.why.bags');
    const misc: RowEntry = { ...emptyRow(), operationId: 3, start: '09:00', end: '10:00' };
    expect(slipBlocked(slip([misc]), ops, TODAY)).toBeNull();
  });

  it('SRS-2-42: a PBNW operation needs its reason, and the text for OTHER', () => {
    const pbnw: RowEntry = {
      ...emptyRow(),
      operationId: 2,
      start: '09:00',
      end: '10:00',
      pbnwReason: 'RAIN',
    };
    const at = (r: Partial<RowEntry>) => slipBlocked(slip([{ ...pbnw, ...r }]), ops, TODAY);
    expect(at({})).toBeNull();
    expect(at({ pbnwReason: null })).toBe('i02.why.pbnw');
    expect(at({ pbnwReason: '' })).toBe('i02.why.pbnw');
    expect(at({ pbnwReason: 'OTHER', pbnwText: '  ' })).toBe('i02.why.pbnw');
    expect(at({ pbnwReason: 'OTHER', pbnwText: 'Fumigation' })).toBeNull();
    expect(at({ pbnwReason: 'RAIN', pbnwText: '' })).toBeNull();
    expect(slipBlocked(slip([{ ...pbnw, operationId: 3, pbnwReason: null }]), ops, TODAY)).toBeNull();
  });

  it('the slip request trims the free texts and keeps the rest', () => {
    const s = slip([{ ...bagRow, pbnwText: ' t ', rakeReference: ' R1 ' }], { continueWork: true });
    expect(toSlipRequest(s)).toEqual({ ...s, rows: [{ ...bagRow, pbnwText: 't', rakeReference: 'R1' }] });
  });

  it('SRS-2-15 / SRS-2-34 / D21-3: a draft, or a returned DPS booking-slip, is editable', () => {
    expect(slipEditable('DRAFT', 'DEPT_LABOUR')).toBe(true);
    expect(slipEditable('DRAFT', 'DPS_LABOUR')).toBe(true);
    expect(slipEditable('REJECTED', 'DPS_LABOUR')).toBe(true);
    expect(slipEditable('REJECTED', 'DEPT_LABOUR')).toBe(false);
    expect(slipEditable('SUBMITTED', 'DPS_LABOUR')).toBe(false);
    expect(slipEditable('APPROVED', 'NWNP_LABOUR')).toBe(false);
  });

  it('BS-6: a DPS booking-slip shows its Depot Manager status', () => {
    expect(slipStatusKey('DRAFT', 'DPS_LABOUR')).toBe('i02.dpsStatus.DRAFT');
    expect(slipStatusKey('SUBMITTED', 'DPS_LABOUR')).toBe('i02.dpsStatus.SUBMITTED');
    expect(slipStatusKey('REJECTED', 'DPS_LABOUR')).toBe('i02.dpsStatus.REJECTED');
    expect(slipStatusKey('APPROVED', 'DPS_LABOUR')).toBe('i02.status.APPROVED');
    expect(slipStatusKey('DRAFT', 'DEPT_LABOUR')).toBe('i02.status.DRAFT');
  });
});

describe('SRS-3-6 M-06 incentive norms', () => {
  it('SRS-3-6: the Band 1 and Band 2 bag steps as the SRS states them', () => {
    expect(srsSlabs()).toEqual([
      { table: 'BAND_1', order: 1, from: 1, to: 30, ratePercent: 8 },
      { table: 'BAND_1', order: 2, from: 31, to: 60, ratePercent: 15 },
      { table: 'BAND_1', order: 3, from: 61, to: 90, ratePercent: 35 },
      { table: 'BAND_1', order: 4, from: 91, to: null, ratePercent: 50 },
      { table: 'BAND_2', order: 1, from: 1, to: 20, ratePercent: 8 },
      { table: 'BAND_2', order: 2, from: 21, to: 40, ratePercent: 15 },
      { table: 'BAND_2', order: 3, from: 41, to: 60, ratePercent: 35 },
      { table: 'BAND_2', order: 4, from: 61, to: null, ratePercent: 50 },
    ]);
  });

  const norm = (over: Partial<NormProposal> = {}): NormProposal => ({
    depotLocationId: 7,
    effectiveFrom: TODAY,
    handling: 100,
    height: 0,
    lead: 999999,
    minLabourNumber: 1,
    minSlabWeight: 0.5,
    mouReference: '',
    slabs: srsSlabs(),
    reason: 'New norms',
    mouCircularId: 4,
    ...over,
  });

  it('M-06: the depot, an effective date from today and whole norms of 0 to 999999', () => {
    expect(normBlocked(norm(), TODAY)).toBeNull();
    expect(normBlocked(norm({ depotLocationId: null }), TODAY)).toBe('m06.why.depot');
    expect(normBlocked(norm({ effectiveFrom: '' }), TODAY)).toBe('m06.why.effective');
    expect(normBlocked(norm({ effectiveFrom: '2026-10-27' }), TODAY)).toBe('m06.why.effective');
    expect(normBlocked(norm({ effectiveFrom: '2026-10-29' }), TODAY)).toBeNull();
    for (const k of ['handling', 'height', 'lead'] as const) {
      expect(normBlocked(norm({ [k]: null }), TODAY)).toBe('m06.why.norm');
      expect(normBlocked(norm({ [k]: 1.5 }), TODAY)).toBe('m06.why.norm');
      expect(normBlocked(norm({ [k]: -1 }), TODAY)).toBe('m06.why.norm');
      expect(normBlocked(norm({ [k]: 1000000 }), TODAY)).toBe('m06.why.norm');
      expect(normBlocked(norm({ [k]: 0 }), TODAY)).toBeNull();
      expect(normBlocked(norm({ [k]: 999999 }), TODAY)).toBeNull();
    }
  });

  it('M-06: the minimum labour number is a whole 1 or more, the minimum slab weight above 0', () => {
    expect(normBlocked(norm({ minLabourNumber: null }), TODAY)).toBe('m06.why.mln');
    expect(normBlocked(norm({ minLabourNumber: 0 }), TODAY)).toBe('m06.why.mln');
    expect(normBlocked(norm({ minLabourNumber: 1.5 }), TODAY)).toBe('m06.why.mln');
    expect(normBlocked(norm({ minLabourNumber: 2 }), TODAY)).toBeNull();
    expect(normBlocked(norm({ minSlabWeight: null }), TODAY)).toBe('m06.why.mlsw');
    expect(normBlocked(norm({ minSlabWeight: 0 }), TODAY)).toBe('m06.why.mlsw');
    expect(normBlocked(norm({ minSlabWeight: -1 }), TODAY)).toBe('m06.why.mlsw');
    expect(normBlocked(norm({ minSlabWeight: 0.01 }), TODAY)).toBeNull();
  });

  it('SRS-3-6: each band runs from 1 without gaps, open-ended only last, rates 0 to 100', () => {
    const b1 = (rows: { from: number; to: number | null; ratePercent?: number }[]) =>
      norm({
        slabs: rows.map((r, i) => ({ table: 'BAND_1' as const, order: i + 1, ratePercent: 10, ...r })),
      });
    expect(
      normBlocked(
        b1([
          { from: 1, to: 5 },
          { from: 6, to: null },
        ]),
        TODAY,
      ),
    ).toBeNull();
    expect(
      normBlocked(
        b1([
          { from: 1, to: 5 },
          { from: 6, to: 9 },
        ]),
        TODAY,
      ),
    ).toBeNull();
    expect(
      normBlocked(
        b1([
          { from: 1, to: 1 },
          { from: 2, to: null },
        ]),
        TODAY,
      ),
    ).toBeNull();
    expect(normBlocked(b1([{ from: 2, to: 5 }]), TODAY)).toBe('m06.why.slabs');
    expect(
      normBlocked(
        b1([
          { from: 1, to: 5 },
          { from: 7, to: null },
        ]),
        TODAY,
      ),
    ).toBe('m06.why.slabs');
    expect(
      normBlocked(
        b1([
          { from: 1, to: 5 },
          { from: 5, to: null },
        ]),
        TODAY,
      ),
    ).toBe('m06.why.slabs');
    expect(
      normBlocked(
        b1([
          { from: 1, to: null },
          { from: 1, to: 5 },
        ]),
        TODAY,
      ),
    ).toBe('m06.why.slabs');
    expect(normBlocked(b1([{ from: 1, to: 0 }]), TODAY)).toBe('m06.why.slabs');
    expect(normBlocked(b1([{ from: 1, to: null, ratePercent: -1 }]), TODAY)).toBe('m06.why.rate');
    expect(normBlocked(b1([{ from: 1, to: null, ratePercent: 101 }]), TODAY)).toBe('m06.why.rate');
    expect(normBlocked(b1([{ from: 1, to: null, ratePercent: 0 }]), TODAY)).toBeNull();
    expect(normBlocked(b1([{ from: 1, to: null, ratePercent: 100 }]), TODAY)).toBeNull();
    const band2Gap = norm({
      slabs: [
        { table: 'BAND_1', order: 1, from: 1, to: null, ratePercent: 5 },
        { table: 'BAND_2', order: 1, from: 3, to: null, ratePercent: 5 },
      ],
    });
    expect(normBlocked(band2Gap, TODAY)).toBe('m06.why.slabs');
    const band2Rate = norm({
      slabs: [
        { table: 'BAND_1', order: 1, from: 1, to: null, ratePercent: 5 },
        { table: 'BAND_2', order: 1, from: 1, to: null, ratePercent: 101 },
      ],
    });
    expect(normBlocked(band2Rate, TODAY)).toBe('m06.why.rate');
    expect(normBlocked(norm({ slabs: [] }), TODAY)).toBe('m06.why.slabs');
  });

  it('SRS-3-6: the bands are taken in their order, whatever order they arrive in', () => {
    expect(normBlocked(norm({ slabs: [...srsSlabs()].reverse() }), TODAY)).toBeNull();
    const shuffled = norm({
      slabs: [
        { table: 'BAND_1', order: 2, from: 6, to: null, ratePercent: 5 },
        { table: 'BAND_1', order: 3, from: 11, to: null, ratePercent: 5 },
        { table: 'BAND_1', order: 1, from: 1, to: 5, ratePercent: 5 },
      ],
    });
    expect(normBlocked(shuffled, TODAY)).toBe('m06.why.slabs');
  });

  it('M-06: the union MoU and the reason', () => {
    expect(normBlocked(norm({ mouCircularId: null }), TODAY)).toBe('m06.why.mou');
    expect(normBlocked(norm({ reason: '' }), TODAY)).toBe('m06.why.reason');
  });
});

const DESIGNATIONS = [
  { id: 1, code: 'SAR', name: 'Sardar', gangRole: 'SARDAR' },
  { id: 2, code: 'HL', name: 'Handling', gangRole: null },
];

describe('SRS-4-11 M-05 DPS rate sets', () => {
  const draft = (over: Partial<RateDraft> = {}): RateDraft => ({
    effectiveFrom: TODAY,
    mgw: { 1: '600', 2: '550.5' },
    interimRelief: '',
    goiRate: '',
    goiRateSource: '',
    comparisonGrain: '',
    reason: 'Revision',
    ...over,
  });

  it('SRS-4-11: designations, the date and every day rate positive with at most 10.2 digits', () => {
    expect(rateBlocked(draft(), DESIGNATIONS)).toBeNull();
    expect(rateBlocked(draft(), [])).toBe('m05.why.designations');
    expect(rateBlocked(draft({ effectiveFrom: '' }), DESIGNATIONS)).toBe('m05.why.effective');
    const mgw = (v: string) => rateBlocked(draft({ mgw: { 1: '600', 2: v } }), DESIGNATIONS);
    for (const ok of ['1', '0.01', '1234567890', '1234567890.99', ' 12.5 ', '12.50'])
      expect(mgw(ok)).toBeNull();
    for (const bad of [
      '',
      '0',
      '0.00',
      '12345678901',
      '1.234',
      '1.2.3',
      '.5',
      '5.',
      '-5',
      'a5',
      '5a',
      '5.a',
    ]) {
      expect(mgw(bad)).toBe('m05.why.mgw');
    }
    expect(rateBlocked(draft({ mgw: { 1: '600' } }), DESIGNATIONS)).toBe('m05.why.mgw');
  });

  it('SRS-4-11: the Interim Relief is zero or more; a GoI rate is positive and has its source', () => {
    expect(rateBlocked(draft({ interimRelief: '0' }), DESIGNATIONS)).toBeNull();
    expect(rateBlocked(draft({ interimRelief: ' ' }), DESIGNATIONS)).toBeNull();
    expect(rateBlocked(draft({ interimRelief: 'x' }), DESIGNATIONS)).toBe('m05.why.ir');
    expect(rateBlocked(draft({ goiRate: '0' }), DESIGNATIONS)).toBe('m05.why.goi');
    expect(rateBlocked(draft({ goiRate: 'x' }), DESIGNATIONS)).toBe('m05.why.goi');
    expect(rateBlocked(draft({ goiRate: '5' }), DESIGNATIONS)).toBe('m05.why.goiSource');
    expect(rateBlocked(draft({ goiRate: '5', goiRateSource: '  ' }), DESIGNATIONS)).toBe('m05.why.goiSource');
    expect(rateBlocked(draft({ goiRate: '5', goiRateSource: 'Gazette' }), DESIGNATIONS)).toBeNull();
    expect(rateBlocked(draft({ goiRateSource: 'Gazette' }), DESIGNATIONS)).toBeNull();
    expect(rateBlocked(draft({ goiRateSource: ` ${'s'.repeat(200)} ` }), DESIGNATIONS)).toBeNull();
    expect(rateBlocked(draft({ goiRateSource: 's'.repeat(201) }), DESIGNATIONS)).toBe('m05.why.goiSource');
    expect(rateBlocked(draft({ goiRateSource: 'Line 1\nLine 2' }), DESIGNATIONS)).toBeNull();
    expect(rateBlocked(draft({ goiRateSource: 'अ' }), DESIGNATIONS)).toBe('m05.why.goiSource');
    expect(rateBlocked(draft({ reason: '' }), DESIGNATIONS)).toBe('m05.why.reason');
  });

  it('SRS-4-11: the request has every designation, blanks as null, for all depots', () => {
    expect(
      toRateRequest(
        draft({
          mgw: { 1: ' 600 ' },
          interimRelief: ' 10 ',
          goiRate: ' 5.5 ',
          goiRateSource: ' G ',
          comparisonGrain: 'PER_DAY',
          reason: ' R ',
        }),
        'DPS_LABOUR',
        DESIGNATIONS,
      ),
    ).toEqual({
      payrollType: 'DPS_LABOUR',
      depotLocationId: null,
      effectiveFrom: TODAY,
      rates: [
        { designationId: 1, mgw: 600 },
        { designationId: 2, mgw: 0 },
      ],
      interimRelief: 10,
      goiRate: 5.5,
      goiRateSource: 'G',
      comparisonGrain: 'PER_DAY',
      reason: 'R',
    });
    expect(toRateRequest(draft(), 'DPS_LABOUR', DESIGNATIONS)).toMatchObject({
      interimRelief: null,
      goiRate: null,
      goiRateSource: null,
      comparisonGrain: null,
    });
  });
});

describe('SRS-3-13 M-05 Departmental rate sets', () => {
  const draft = (over: Partial<DeptRateDraft> = {}): DeptRateDraft => ({
    effectiveFrom: TODAY,
    basic: { 1: '500', 2: '450' },
    da: { 1: '0', 2: '12.5' },
    reason: 'Revision',
    ...over,
  });

  it('SRS-3-13: every Basic wage positive, its DA zero or more, the date and the reason', () => {
    expect(deptRateBlocked(draft(), DESIGNATIONS)).toBeNull();
    expect(deptRateBlocked(draft(), [])).toBe('m05.why.designations');
    expect(deptRateBlocked(draft({ effectiveFrom: '' }), DESIGNATIONS)).toBe('m05.why.effective');
    expect(deptRateBlocked(draft({ basic: { 1: '500', 2: '0' } }), DESIGNATIONS)).toBe('m05.why.basic');
    expect(deptRateBlocked(draft({ basic: { 1: '500' } }), DESIGNATIONS)).toBe('m05.why.basic');
    expect(deptRateBlocked(draft({ basic: { 1: '500', 2: 'x' } }), DESIGNATIONS)).toBe('m05.why.basic');
    expect(deptRateBlocked(draft({ basic: { 1: '500', 2: '0.01' } }), DESIGNATIONS)).toBeNull();
    expect(deptRateBlocked(draft({ da: { 1: '0', 2: 'x' } }), DESIGNATIONS)).toBe('m05.why.da');
    expect(deptRateBlocked(draft({ da: { 1: '0' } }), DESIGNATIONS)).toBe('m05.why.da');
    expect(deptRateBlocked(draft({ reason: ' ' }), DESIGNATIONS)).toBe('m05.why.reason');
  });

  it('SRS-3-13: the Departmental request carries Basic and DA only, for all depots', () => {
    expect(
      toDeptRateRequest(draft({ basic: { 1: ' 500 ' }, da: { 1: ' 7 ' }, reason: ' R ' }), DESIGNATIONS),
    ).toEqual({
      payrollType: 'DEPT_LABOUR',
      depotLocationId: null,
      effectiveFrom: TODAY,
      rates: [
        { designationId: 1, basicWage: 500, da: 7 },
        { designationId: 2, basicWage: 0, da: 0 },
      ],
      interimRelief: null,
      goiRate: null,
      goiRateSource: null,
      comparisonGrain: null,
      reason: 'R',
    });
  });
});

describe('SRS-5-3 M-05 NWNP rate sets', () => {
  const options: NwnpRateOptions = {
    operations: [
      { code: 'LOAD', name: 'Loading', m27: null },
      { code: 'UNLOAD', name: 'Unloading', m27: 'X' },
    ],
    designations: DESIGNATIONS,
    unitsOfMeasure: [
      { code: 'BAG', label: 'Bag' },
      { code: 'MT', label: 'Tonne' },
    ],
    depots: [],
  };
  const draft = (over: Partial<NwnpDraft> = {}): NwnpDraft => ({
    depotLocationId: 7,
    version: 'V1',
    effectiveFrom: TODAY,
    rates: { LOAD: '2.5', UNLOAD: '3' },
    units: { LOAD: 'BAG', UNLOAD: 'MT' },
    mgw: { 1: '600', 2: '550' },
    reason: 'Rates',
    ...over,
  });

  it('SRS-5-3: the depot, the date, each operation rate above 0 with its unit, each MGW and the reason', () => {
    expect(nwnpBlocked(draft(), options)).toBeNull();
    expect(nwnpBlocked(draft({ depotLocationId: null }), options)).toBe('m05.why.depot');
    expect(nwnpBlocked(draft({ effectiveFrom: '' }), options)).toBe('m05.why.effective');
    expect(nwnpBlocked(draft(), { ...options, operations: [] })).toBe('m05.why.designations');
    expect(nwnpBlocked(draft(), { ...options, designations: [] })).toBe('m05.why.designations');
    expect(nwnpBlocked(draft({ rates: { LOAD: '2.5', UNLOAD: '0' } }), options)).toBe('m05.why.pieceOfRate');
    expect(nwnpBlocked(draft({ rates: { LOAD: '2.5' } }), options)).toBe('m05.why.pieceOfRate');
    expect(nwnpBlocked(draft({ rates: { LOAD: '2.5', UNLOAD: 'x' } }), options)).toBe('m05.why.pieceOfRate');
    expect(nwnpBlocked(draft({ units: { LOAD: 'BAG' } }), options)).toBe('m05.why.unit');
    expect(nwnpBlocked(draft({ units: { LOAD: 'BAG', UNLOAD: 'KG' } }), options)).toBe('m05.why.unit');
    expect(nwnpBlocked(draft({ mgw: { 1: '600' } }), options)).toBe('m05.why.mgw');
    expect(nwnpBlocked(draft({ mgw: { 1: '600', 2: '0' } }), options)).toBe('m05.why.mgw');
    expect(nwnpBlocked(draft({ mgw: { 1: '600', 2: '0.01' } }), options)).toBeNull();
    expect(nwnpBlocked(draft({ reason: '' }), options)).toBe('m05.why.reason');
  });

  it('SRS-5-3: the request lists every operation and designation', () => {
    expect(
      toNwnpRequest(
        draft({ rates: { LOAD: ' 2.5 ' }, units: { LOAD: 'BAG' }, mgw: { 2: ' 550 ' }, reason: ' R ' }),
        options,
      ),
    ).toEqual({
      depotLocationId: 7,
      version: 'V1',
      effectiveFrom: TODAY,
      lines: [
        { operationCode: 'LOAD', pieceOfRate: 2.5, unitOfMeasure: 'BAG' },
        { operationCode: 'UNLOAD', pieceOfRate: 0, unitOfMeasure: '' },
      ],
      mgw: [
        { designationId: 1, mgw: 0 },
        { designationId: 2, mgw: 550 },
      ],
      reason: 'R',
    });
    expect(toNwnpRequest(draft({ depotLocationId: null }), options).depotLocationId).toBe(0);
  });
});

describe('D22-7 reinstatement and D22-8 nomination', () => {
  const r: ReinstatementEntry = {
    date: TODAY,
    orderReference: '',
    orderDate: TODAY,
    issuingAuthority: 'Area Manager',
    reason: 'Reinstated',
    document: FILE,
  };

  it('D22-7: the date, the order not after today with its printable authority, the reason and the file', () => {
    expect(reinstatementBlocked(r, TODAY)).toBeNull();
    expect(reinstatementBlocked({ ...r, orderReference: 'O/1', orderDate: '2026-10-27' }, TODAY)).toBeNull();
    expect(reinstatementBlocked({ ...r, issuingAuthority: ` AM${NBSP}` }, TODAY)).toBeNull();
    expect(reinstatementBlocked({ ...r, orderReference: ` O/1${NBSP}` }, TODAY)).toBeNull();
    expect(reinstatementBlocked({ ...r, date: '' }, TODAY)).toBe('m02.why.reinstateDate');
    expect(reinstatementBlocked({ ...r, orderDate: '' }, TODAY)).toBe('m02.why.document');
    expect(reinstatementBlocked({ ...r, orderDate: '2026-10-29' }, TODAY)).toBe('m02.why.document');
    expect(reinstatementBlocked({ ...r, issuingAuthority: ' ' }, TODAY)).toBe('m02.why.document');
    expect(reinstatementBlocked({ ...r, issuingAuthority: 'अ' }, TODAY)).toBe('m02.why.document');
    expect(reinstatementBlocked({ ...r, issuingAuthority: 'AMअ' }, TODAY)).toBe('m02.why.document');
    expect(reinstatementBlocked({ ...r, issuingAuthority: 'अAM' }, TODAY)).toBe('m02.why.document');
    expect(reinstatementBlocked({ ...r, orderReference: 'Oअ' }, TODAY)).toBe('m02.why.document');
    expect(reinstatementBlocked({ ...r, reason: '' }, TODAY)).toBe('m02.why.reason');
    expect(reinstatementBlocked({ ...r, document: null }, TODAY)).toBe('m02.why.file');
  });

  const person = (over: Partial<NamedPerson> = {}): NamedPerson => ({
    name: 'Sita',
    relationship: 'SPOUSE',
    birthDate: '1992-01-01',
    share: 100,
    nominee: true,
    ...over,
  });
  const dependent = person({ share: null, nominee: false, name: 'Ravi', relationship: 'SON' });

  it('BR-FNF-014: somebody is named, each with a printable name, a relationship and a past birth date', () => {
    expect(nominationBlocked([person()], [dependent], 'Nomination', TODAY)).toBeNull();
    expect(nominationBlocked([], [dependent], 'Nomination', TODAY)).toBeNull();
    expect(nominationBlocked([], [], 'Nomination', TODAY)).toBe('m02.why.nobody');
    expect(nominationBlocked([person({ name: ' ' })], [], 'x', TODAY)).toBe('m02.why.personName');
    expect(nominationBlocked([person({ name: 'n'.repeat(150) })], [], 'x', TODAY)).toBeNull();
    expect(nominationBlocked([person({ name: ` ${'n'.repeat(150)} ` })], [], 'x', TODAY)).toBeNull();
    expect(nominationBlocked([person({ name: 'n'.repeat(151) })], [], 'x', TODAY)).toBe('m02.why.personName');
    expect(nominationBlocked([person()], [{ ...dependent, name: 'Raviअ' }], 'x', TODAY)).toBe(
      'm02.why.personName',
    );
    expect(nominationBlocked([person({ relationship: '' })], [], 'x', TODAY)).toBe('m02.why.relationship');
    expect(nominationBlocked([person({ birthDate: '2026-10-29' })], [], 'x', TODAY)).toBe('m02.why.birth');
    expect(nominationBlocked([person({ birthDate: TODAY })], [], 'x', TODAY)).toBeNull();
    expect(nominationBlocked([person({ birthDate: null })], [], 'x', TODAY)).toBeNull();
    expect(nominationBlocked([person()], [], ' ', TODAY)).toBe('m02.why.reason');
  });

  it('BR-FNF-014: each nominee share is above 0, at most 100 with two decimals, and they total 100', () => {
    const shares = (...s: (number | null)[]) =>
      nominationBlocked(
        s.map((share) => person({ share })),
        [],
        'x',
        TODAY,
      );
    expect(shares(null)).toBe('m02.why.share');
    expect(shares(0)).toBe('m02.why.share');
    expect(shares(-1)).toBe('m02.why.share');
    expect(shares(Number.NaN)).toBe('m02.why.share');
    expect(shares(100.01)).toBe('m02.why.share');
    expect(shares(33.333, 66.667)).toBe('m02.why.share');
    expect(shares(0.01, 99.99)).toBeNull();
    expect(shares(33.33, 33.33, 33.34)).toBeNull();
    expect(shares(50, 50)).toBeNull();
    expect(shares(60, 30)).toBe('m02.why.shareTotal');
    expect(shares(60, 50)).toBe('m02.why.shareTotal');
    expect(shares(99.99)).toBe('m02.why.shareTotal');
  });
});

describe('SRS-2-1 gang rules, boundaries', () => {
  const gang = (over: Partial<NewGang> = {}): NewGang => ({
    depotLocationId: 7,
    payrollType: 'DEPT_LABOUR',
    gangName: 'Okhla Gang 2',
    sanctionedStrength: 6,
    effectiveFrom: TODAY,
    members: [
      { employeeId: 1, role: 'SARDAR' },
      { employeeId: 2, role: 'MANDAL' },
    ],
    reason: 'New gang',
    ...over,
  });

  it('SRS-2-1: the gang name is 1 to 150 printable characters, measured trimmed', () => {
    expect(gangBlocked(gang({ gangName: 'g'.repeat(150) }))).toBeNull();
    expect(gangBlocked(gang({ gangName: ` ${'g'.repeat(150)} ` }))).toBeNull();
    expect(gangBlocked(gang({ gangName: 'Gangé' }))).toBe('m09.why.name');
    expect(gangBlocked(gang({ gangName: 'éGang' }))).toBe('m09.why.name');
  });

  it('BR-LABM-001: support members are counted, not as Sardar or Mandal', () => {
    expect(
      gangBlocked(
        gang({
          members: [
            { employeeId: 1, role: 'SARDAR' },
            { employeeId: 2, role: 'MANDAL' },
            { employeeId: 3, role: 'HANDLING' },
            { employeeId: 4, role: 'ANCILLARY' },
          ],
        }),
      ),
    ).toBeNull();
    expect(gangBlocked(gang({ members: [{ employeeId: 2, role: 'MANDAL' }] }))).toBe('m09.why.roles');
  });

  it('SRS-2-48: a strength of 1 and 9999 is accepted, 10000 is not', () => {
    expect(strengthBlocked(1, TODAY, 'x', TODAY)).toBeNull();
    expect(strengthBlocked(9999, TODAY, 'x', TODAY)).toBeNull();
    expect(strengthBlocked(10000, TODAY, 'x', TODAY)).toBe('m09.why.strength');
    expect(strengthBlocked(8, '2026-10-29', 'x', TODAY)).toBeNull();
  });
});

describe('labour rules, kill-checks of the boundaries', () => {
  it('SRS-2-59: no kept field is ever sent on an amendment', () => {
    const original = entry();
    const kept: Partial<MemberEntry>[] = [
      { payrollType: 'DPS_LABOUR' },
      { firstName: 'Shyam' },
      { lastName: 'Lal' },
      { birthDate: '1991-01-01' },
      { designationId: 9 },
      { depotLocationId: 9 },
    ];
    for (const k of kept) expect(toAmendRequest({ ...original, ...k, reason: 'x' }, original)).toEqual({});
  });

  it('SRS-3-6: Band 1 is checked before Band 2, each against its own rows', () => {
    const p: NormProposal = {
      depotLocationId: 7,
      effectiveFrom: TODAY,
      handling: 1,
      height: 1,
      lead: 1,
      minLabourNumber: 1,
      minSlabWeight: 1,
      mouReference: '',
      reason: 'x',
      mouCircularId: 4,
      slabs: [
        { table: 'BAND_1', order: 1, from: 2, to: null, ratePercent: 5 },
        { table: 'BAND_2', order: 1, from: 1, to: null, ratePercent: 101 },
      ],
    };
    expect(normBlocked(p, TODAY)).toBe('m06.why.slabs');
  });

  it('D22-7: an order reference of blanks is no reference', () => {
    const r: ReinstatementEntry = {
      date: TODAY,
      orderReference: '   ',
      orderDate: TODAY,
      issuingAuthority: 'Area Manager',
      reason: 'Reinstated',
      document: FILE,
    };
    expect(reinstatementBlocked(r, TODAY)).toBeNull();
  });
});
