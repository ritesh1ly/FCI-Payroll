import { describe, expect, it } from 'vitest';
import type { DayRow, RegisterRow } from '@/modules/labour/api/captureApi';
import {
  dayMarkable,
  daySource,
  engineType,
  fourthCode,
  gangTone,
  marksByGang,
  registerStatus,
} from '@/modules/labour/business/captureRules';

/**
 * Capture rules of I-01 (attendance) and I-02 (work-slip register) as drawn (wireframe v6.1, OD-121): the
 * engine a sidebar entry opens, the gang pill, the Annexure A.7 code columns, the day entry (SRS-2-35: the
 * day is saved whole per gang), the Source column and the register status pill (BS-6 for DPS).
 */

function day(over: Partial<DayRow> = {}): DayRow {
  return {
    employeeId: 1,
    labourCode: 'L0001',
    name: 'Ram Kumar',
    gangId: 10,
    gangCode: 'G-10',
    code: null,
    captureMode: null,
    depotConfirmed: false,
    workSlipFiled: false,
    sourceMode: 'MANUAL',
    punchIn: null,
    punchOut: null,
    exception: null,
    otherCode: null,
    otherGang: null,
    otherDepot: null,
    pastRetirement: false,
    ...over,
  };
}

function slip(over: Partial<RegisterRow> = {}): RegisterRow {
  return {
    slipId: 1,
    rowOrder: 1,
    number: 'WS-1',
    gangId: 10,
    gangCode: 'G-10',
    date: '2026-11-02',
    bagType: null,
    operationCode: null,
    operation: null,
    bags: null,
    start: null,
    end: null,
    normType: null,
    continueWork: false,
    labours: 5,
    status: 'DRAFT',
    source: 'ENT',
    duplicateOf: null,
    reason: null,
    enteredBy: null,
    decidedBy: null,
    decidedAt: null,
    ...over,
  };
}

describe('engine of a sidebar entry', () => {
  it('SRS one screen per engine: DEPT, DPS and NWNP open their payroll type; anything else none', () => {
    expect(engineType('DEPT')).toBe('DEPT_LABOUR');
    expect(engineType('DPS')).toBe('DPS_LABOUR');
    expect(engineType('NWNP')).toBe('NWNP_LABOUR');
    expect(engineType('dps')).toBeNull();
    expect(engineType('')).toBeNull();
    expect(engineType(null)).toBeNull();
    expect(engineType(undefined)).toBeNull();
  });
});

describe('I-01 gang summary', () => {
  it('I-01: a ready month is green, an exception red, a missing work-slip or unmarked day amber', () => {
    expect(gangTone('READY')).toBe('p-ok');
    expect(gangTone('EXCEPTION')).toBe('p-bad');
    expect(gangTone('WORK_SLIP_MISSING')).toBe('p-warn');
    expect(gangTone('NOT_MARKED')).toBe('p-warn');
  });

  it('Annexure A.7: the fourth code column is P only for DPS Labour, S for the other engines', () => {
    expect(fourthCode('DPS_LABOUR')).toBe('P');
    expect(fourthCode('DEPT_LABOUR')).toBe('S');
    expect(fourthCode('NWNP_LABOUR')).toBe('S');
  });
});

describe('I-01 day entry', () => {
  it('SRS-2-35: a blank manual row may be marked; BOTH mode is marked by hand too', () => {
    expect(dayMarkable(day())).toBe(true);
    expect(dayMarkable(day({ sourceMode: 'BOTH' }))).toBe(true);
  });

  it('SRS-2-35: a held code, a code in another gang, a date past retirement or biometric capture blocks it', () => {
    expect(dayMarkable(day({ code: 'P' }))).toBe(false);
    expect(dayMarkable(day({ otherCode: 'P' }))).toBe(false);
    expect(dayMarkable(day({ pastRetirement: true }))).toBe(false);
    expect(dayMarkable(day({ sourceMode: 'BIOMETRIC' }))).toBe(false);
  });

  it('SRS-2-35: the day is saved whole per gang, chosen marks over held ones, only gangs with a choice', () => {
    const rows = [
      day({ employeeId: 1, gangId: 10 }),
      day({ employeeId: 2, gangId: 10, code: 'A' }),
      day({ employeeId: 3, gangId: 10 }),
      day({ employeeId: 4, gangId: 10, code: 'P', pastRetirement: true }),
      day({ employeeId: 5, gangId: 10, code: 'P', otherCode: 'P' }),
      day({ employeeId: 6, gangId: 20, code: 'P' }),
      day({ employeeId: 7, gangId: 30, code: 'L' }),
      day({ employeeId: 8, gangId: 30 }),
    ];
    const marks = marksByGang(rows, { 1: 'P', 2: 'L', 4: 'P', 5: 'A', 8: 'S' });
    expect([...marks.keys()].sort()).toEqual([10, 30]);
    expect(marks.get(10)).toEqual({ 1: 'P', 2: 'L' });
    expect(marks.get(30)).toEqual({ 7: 'L', 8: 'S' });
  });

  it('SRS-2-35: an empty choice neither opens a gang nor keeps a held code; no choice sends nothing', () => {
    const rows = [day({ employeeId: 1, gangId: 10, code: 'P' }), day({ employeeId: 2, gangId: 10 })];
    expect(marksByGang(rows, { 1: '' }).size).toBe(0);
    expect(marksByGang(rows, {}).size).toBe(0);
    expect(marksByGang(rows, { 1: '', 2: 'A' }).get(10)).toEqual({ 2: 'A' });
  });

  it('SRS-2-35: a gang whose only chosen row is past retirement is sent with its other rows only', () => {
    const rows = [
      day({ employeeId: 1, gangId: 10, pastRetirement: true }),
      day({ employeeId: 2, gangId: 10, code: 'P' }),
    ];
    expect(marksByGang(rows, { 1: 'P' }).get(10)).toEqual({ 2: 'P' });
  });
});

describe('I-01 Source column', () => {
  it('I-01: an Anna Darpan row reads so even when it is held elsewhere or past retirement', () => {
    expect(daySource(day({ captureMode: 'ANNA_DARPAN', otherCode: 'P', pastRetirement: true }))).toEqual([
      'i01w.src.anna',
      [],
    ]);
  });

  it('I-01: a row held in another gang or past retirement has no source', () => {
    expect(daySource(day({ otherCode: 'P', punchIn: '08:00', punchOut: '16:00' }))).toEqual([
      'i01w.src.none',
      [],
    ]);
    expect(daySource(day({ pastRetirement: true, sourceMode: 'BIOMETRIC' }))).toEqual(['i01w.src.none', []]);
  });

  it('I-01: manual mode reads manual even with punches; no punches and no exception read manual', () => {
    expect(daySource(day({ sourceMode: 'MANUAL', punchIn: '08:00', punchOut: '16:00' }))).toEqual([
      'i01w.src.manual',
      [],
    ]);
    expect(daySource(day({ sourceMode: 'BIOMETRIC' }))).toEqual(['i01w.src.manual', []]);
    expect(daySource(day({ sourceMode: 'BOTH', captureMode: 'BIOMETRIC' }))).toEqual(['i01w.src.manual', []]);
  });

  it('I-01: both punches read as punches with their times', () => {
    expect(daySource(day({ sourceMode: 'BIOMETRIC', punchIn: '08:00', punchOut: '16:05' }))).toEqual([
      'i01w.src.punches',
      ['08:00', '16:05'],
    ]);
  });

  it('I-01: one punch missing reads the missing side; an exception without punches reads missing out', () => {
    expect(daySource(day({ sourceMode: 'BIOMETRIC', punchIn: '08:00' }))).toEqual([
      'i01w.src.missingOut',
      [],
    ]);
    expect(daySource(day({ sourceMode: 'BIOMETRIC', punchOut: '16:00' }))).toEqual([
      'i01w.src.missingIn',
      [],
    ]);
    expect(daySource(day({ sourceMode: 'BOTH', exception: 'NO_PUNCH' }))).toEqual([
      'i01w.src.missingOut',
      [],
    ]);
    expect(daySource(day({ sourceMode: 'BOTH', punchIn: '08:00', exception: 'NO_OUT' }))).toEqual([
      'i01w.src.missingOut',
      [],
    ]);
    expect(daySource(day({ sourceMode: 'BOTH', punchOut: '16:00', exception: 'NO_IN' }))).toEqual([
      'i01w.src.missingIn',
      [],
    ]);
  });
});

describe('I-02 register status', () => {
  it('SRS-2-15: approved and processed slips read accepted', () => {
    expect(registerStatus(slip({ status: 'APPROVED' }), false)).toEqual(['p-ok', 'i02w.status.accepted', []]);
    expect(registerStatus(slip({ status: 'PROCESSED' }), true)).toEqual(['p-ok', 'i02w.status.accepted', []]);
  });

  it('SRS-2-42: a held duplicate names the slip it duplicates when known', () => {
    expect(registerStatus(slip({ status: 'DUPLICATE_HELD', duplicateOf: 'WS-9' }), false)).toEqual([
      'p-bad',
      'i02w.status.duplicateOf',
      ['WS-9'],
    ]);
    expect(registerStatus(slip({ status: 'DUPLICATE_HELD' }), false)).toEqual([
      'p-bad',
      'i02w.status.duplicate',
      [],
    ]);
    expect(registerStatus(slip({ status: 'DUPLICATE_HELD', duplicateOf: '' }), true)).toEqual([
      'p-bad',
      'i02w.status.duplicate',
      [],
    ]);
  });

  it('SRS-2-34: a rejected import reads so; a rejected entry reads per engine (DPS returned, BS-6)', () => {
    expect(registerStatus(slip({ status: 'REJECTED', source: 'INTF' }), true)).toEqual([
      'p-bad',
      'i02w.status.rejectedImport',
      [],
    ]);
    expect(registerStatus(slip({ status: 'REJECTED' }), true)).toEqual([
      'p-bad',
      'i02.dpsStatus.REJECTED',
      [],
    ]);
    expect(registerStatus(slip({ status: 'REJECTED' }), false)).toEqual(['p-bad', 'i02.status.REJECTED', []]);
  });

  it('SRS-2-15: submitted and draft slips read per engine', () => {
    expect(registerStatus(slip({ status: 'SUBMITTED' }), true)).toEqual([
      'p-warn',
      'i02.dpsStatus.SUBMITTED',
      [],
    ]);
    expect(registerStatus(slip({ status: 'SUBMITTED' }), false)).toEqual([
      'p-warn',
      'i02.status.SUBMITTED',
      [],
    ]);
    expect(registerStatus(slip({ status: 'DRAFT' }), true)).toEqual(['p-info', 'i02.dpsStatus.DRAFT', []]);
    expect(registerStatus(slip({ status: 'DRAFT' }), false)).toEqual(['p-info', 'i02.status.DRAFT', []]);
  });

  it('I-02: any other status reads its own label without a tone', () => {
    expect(registerStatus(slip({ status: 'CANCELLED' }), true)).toEqual(['', 'i02.status.CANCELLED', []]);
  });
});
