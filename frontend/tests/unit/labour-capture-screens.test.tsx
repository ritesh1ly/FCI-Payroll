import { fireEvent, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AttendanceCapture, WorkSlips } from '@/modules/labour';
import type { DayRow, GangSummaryRow, RegisterRow } from '@/modules/labour/api/captureApi';
import {
  dayMarkable,
  daySource,
  engineType,
  fourthCode,
  gangTone,
  marksByGang,
  registerStatus,
} from '@/modules/labour/business/captureRules';
import { previousMonth } from '@/modules/labour/business/labourRules';
import { businessToday } from '@/shared/time';
import { backend, me, paged, sent, t, wrap } from './support/harness';

/** I-01 and I-02 as Wireframes v6.1 draws them, one view per payroll type (OD-121; SRS-2-2, SRS-2-34). */

afterEach(() => vi.unstubAllGlobals());

const search = vi.hoisted(() => ({ current: '' }));
vi.mock('next/navigation', () => ({
  useSearchParams: () => new URLSearchParams(search.current),
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
}));

const ANNA = { received: 12, confirmed: 10, returned: 1 };
const GANGS = [{ id: 8, code: 'GNG-01', name: 'Okhla Shed A Gang 1', type: 'HANDLING' }];

function overview(over = {}) {
  return {
    month: '2026-10',
    records: 124,
    withSlip: 124,
    lwpDays: 3,
    exceptions: 15,
    pastRetirement: 0,
    overCalendar: 0,
    anna: ANNA,
    codes: [{ code: 'W', meaning: 'On work' }],
    gangs: GANGS,
    ...over,
  };
}

function day(over: Partial<DayRow> = {}): DayRow {
  return {
    employeeId: 11,
    labourCode: 'DL-20180',
    name: 'Prem Chand',
    gangId: 8,
    gangCode: 'GNG-01',
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

function gang(over: Partial<GangSummaryRow> = {}): GangSummaryRow {
  return {
    gangId: 8,
    gangCode: 'GNG-01',
    gangName: 'Okhla Shed A Gang 1',
    depot: 'DP-DL01 - Depot Okhla',
    labour: 4,
    calendarDays: 31,
    counts: { W: 104, A: 2, H: 16, S: 2 },
    presentDays: 122,
    overCalendar: false,
    status: 'READY',
    ...over,
  };
}

function slip(over: Partial<RegisterRow> = {}): RegisterRow {
  return {
    slipId: 7,
    rowOrder: 1,
    number: 'WS-88402',
    gangId: 8,
    gangCode: 'GNG-01',
    date: '2026-10-02',
    bagType: 'Katta 50 kg',
    operationCode: 'W2P',
    operation: 'Wagon-to-Platform',
    bags: 1920,
    start: '09:00',
    end: '13:30',
    normType: 'HANDLING',
    continueWork: false,
    labours: 4,
    status: 'APPROVED',
    source: 'ENT',
    duplicateOf: null,
    reason: null,
    enteredBy: 'ldh.depot@fci-demo.test',
    decidedBy: null,
    decidedAt: null,
    ...over,
  };
}

function attendanceRoutes(days: DayRow[], gangs: GangSummaryRow[] = [gang()]) {
  return backend({
    'GET /v1/labour/attendance/overview': () => overview(),
    'GET /v1/labour/attendance/gang-summaries': () => paged(gangs),
    'GET /v1/labour/attendance/days': () => paged(days),
    'GET /v1/labour/attendance/leave': () =>
      paged([
        {
          id: 1,
          labourCode: 'DL-20115',
          name: 'Jagdish Yadav',
          origin: 'CONVERSION',
          from: '2026-10-09',
          to: '2026-10-09',
          fromCode: 'PSL',
          toCode: 'EOL',
          backDated: true,
          raisedOn: '2026-11-07',
          status: 'APPROVED',
        },
      ]),
    'GET /v1/labour/attendance/sources': () =>
      paged([
        {
          depot: 'DP-DL01 - Depot Okhla',
          mode: 'BOTH',
          annaDarpan: true,
          status: 'LIVE',
          effectiveFrom: '2026-06-07',
          enrolled: 5,
        },
      ]),
    'GET /v1/labour/gangs': () => paged([]),
    'GET /v1/labour/gangs/8/attendance': () => ({
      gangId: 8,
      gangName: 'Okhla Shed A Gang 1',
      depot: 'DP-DL01 - Depot Okhla',
      payrollType: 'DEPT_LABOUR',
      yymm: '202610',
      lastMarkable: '2026-10-31',
      batchState: 'OPEN',
      approvalId: null,
      sanctionedStrength: 4,
      codes: [],
      members: [],
    }),
    'POST /v1/labour/gangs/8/attendance-days': () => ({
      date: '',
      marked: 1,
      added: 1,
      changed: 0,
      markedAbsent: [],
    }),
  });
}

function slipRoutes(rows: RegisterRow[], over = {}) {
  return backend({
    'GET /v1/labour/work-slips/overview': () => ({
      month: '2026-10',
      saved: 29,
      rejected: 1,
      pendingBooking: 2,
      anna: ANNA,
      gangs: GANGS,
      dates: ['2026-10-02'],
      ...over,
    }),
    'GET /v1/labour/work-slips': () => paged(rows),
    'GET /v1/labour/overtime': () =>
      paged([
        {
          key: 'OT-1',
          labourCode: 'DL-20180',
          name: 'Prem Chand',
          gangCode: 'GNG-01',
          pool: false,
          operationCode: 'W2P',
          operation: 'Wagon-to-Platform',
          workingAs: 'HANDLING',
          start: '16:00',
          end: '19:00',
          hours: 3,
          bags: 200,
          minimumMet: 'N',
          ancillary: false,
          pbnwReason: null,
        },
        {
          key: 'PB-1',
          labourCode: 'DL-20114',
          name: 'Hemant Kumar',
          gangCode: 'DP-DL01-G001',
          pool: true,
          operationCode: 'PBNW',
          operation: 'Present But No Work',
          workingAs: null,
          start: null,
          end: null,
          hours: null,
          bags: null,
          minimumMet: null,
          ancillary: false,
          pbnwReason: 'RAKE_DELAYED',
        },
      ]),
    'GET /v1/labour/gangs': () => paged([]),
  });
}

const LDH = ['I-01:VIEW', 'I-01:CREATE', 'I-02:VIEW', 'I-02:CREATE', 'I-02:EDIT'];

describe('I-01 as drawn', () => {
  it('SRS-2-35: the day opens blank, the labour dealing hand marks and saves the gang', async () => {
    search.current = '';
    const calls = attendanceRoutes([day()]);
    wrap(<AttendanceCapture />, me(LDH));
    const table = await screen.findByRole('table', { name: t('i01w.daily') });
    const code = await within(table).findByLabelText(t('i01.codeOf', ['DL-20180']));
    await vi.waitFor(() => expect(within(code).queryByText('W')).toBeTruthy());
    await userEvent.selectOptions(code, 'W');
    await userEvent.click(screen.getByRole('button', { name: t('i01w.save') }));
    await vi.waitFor(() =>
      expect(sent(calls, 'POST', '/attendance-days')[0]).toMatchObject({
        date: businessToday(),
        marks: [{ employeeId: 11, code: 'W' }],
      }),
    );
  });

  it('SRS-2-35: saving with nothing marked sends nothing and says why', async () => {
    search.current = '';
    const calls = attendanceRoutes([day()]);
    wrap(<AttendanceCapture />, me(LDH));
    await screen.findByRole('table', { name: t('i01w.daily') });
    await userEvent.click(screen.getByRole('button', { name: t('i01w.save') }));
    expect(await screen.findByText(t('i01w.why.nothing'))).toBeTruthy();
    expect(calls.some((c) => c.method === 'POST')).toBe(false);
  });

  it('SRS-2-38: a labour marked elsewhere is blocked and named', async () => {
    search.current = '';
    attendanceRoutes([day({ otherCode: 'W', otherGang: 'GNG-02', otherDepot: 'DP-DL01' })]);
    wrap(<AttendanceCapture />, me(LDH));
    expect(await screen.findByText(t('i01w.chk.other', ['W', 'GNG-02', 'DP-DL01']))).toBeTruthy();
    expect(screen.getByText(t('i01w.blocked'))).toBeTruthy();
  });

  it('OD-121: a viewer sees every drawn action locked with its reason', async () => {
    search.current = '';
    attendanceRoutes([day()], [gang({ status: 'EXCEPTION', overCalendar: true })]);
    wrap(<AttendanceCapture />, me(['I-01:VIEW', 'I-01:APPROVE']));
    expect(await screen.findByText(t('i01w.overCalendar'))).toBeTruthy();
    for (const label of ['i01w.upload', 'i01w.correct', 'i01w.save', 'i01w.reprocess', 'i01w.l.enter']) {
      expect(screen.getByRole('button', { name: t(label) })).toBeDisabled();
    }
    expect(await screen.findByText(t('i01w.l.effectBack', ['NOV 2026']))).toBeTruthy();
    expect(screen.getByText('✔')).toBeTruthy();
  });

  it('SRS-2-38: a row is viewed, and Edit opens the month grid on the gang', async () => {
    search.current = '';
    attendanceRoutes([]);
    wrap(<AttendanceCapture />, me(LDH));
    await userEvent.click(await screen.findByRole('button', { name: `${t('common.view')} GNG-01` }));
    expect(await screen.findByRole('dialog')).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('common.close') }));
    await userEvent.click(screen.getByRole('button', { name: `${t('common.edit')} GNG-01` }));
    expect(screen.getByTestId('i01-grid').hasAttribute('open')).toBe(true);
  });

  it('OD-121: the DPS view asks for its type and draws code P; the selector moves the view', async () => {
    search.current = 'engine=DPS';
    const calls = attendanceRoutes([]);
    wrap(<AttendanceCapture />, me(LDH));
    await screen.findByRole('table', { name: t('i01w.summaryName') });
    await vi.waitFor(() => expect(calls.some((c) => c.url.includes('payrollType=DPS_LABOUR'))).toBe(true));
    expect(screen.getByRole('columnheader', { name: /^P\s*I?$/ })).toBeTruthy();
    fireEvent.change(screen.getByLabelText(t('i01w.typeOf')), { target: { value: 'NWNP_LABOUR' } });
    await vi.waitFor(() => expect(calls.some((c) => c.url.includes('payrollType=NWNP_LABOUR'))).toBe(true));
  });

  it('SRS-G-13: export without the right is explained', async () => {
    search.current = '';
    attendanceRoutes([]);
    wrap(<AttendanceCapture />, me(['I-01:VIEW']));
    await userEvent.click(await screen.findByRole('button', { name: t('wf.export') }));
    expect(await screen.findByText(t('i01w.why.export'))).toBeTruthy();
  });
});

describe('I-02 as drawn', () => {
  it('SRS-2-4: the register, overtime and the import queue of the month', async () => {
    search.current = '';
    slipRoutes([
      slip(),
      slip({ slipId: 8, number: 'WS-88461', status: 'DUPLICATE_HELD', duplicateOf: 'WS-88402' }),
    ]);
    wrap(<WorkSlips />, me(LDH));
    expect(await screen.findByText(t('i02w.status.duplicateOf', ['WS-88402']))).toBeTruthy();
    expect(await screen.findByText(t('i02w.chk.below'))).toBeTruthy();
    expect(screen.getByText(t('i02w.chk.pbnw', [t('i02.pbnw.RAKE_DELAYED')]))).toBeTruthy();
    expect(screen.getByText(t('i02w.pool', ['DP-DL01-G001']))).toBeTruthy();
    expect(screen.queryByRole('table', { name: t('i02w.booking') })).toBeNull();
  });

  it('SRS-2-34: only the DPS view draws the booking-slip register, with the pending count', async () => {
    search.current = 'engine=DPS';
    slipRoutes([slip({ number: 'DWS-88402', status: 'SUBMITTED', decidedBy: null })]);
    wrap(<WorkSlips />, me(LDH));
    const register = await screen.findByRole('table', { name: t('i02w.booking') });
    expect(await within(register).findByText(t('i02.dpsStatus.SUBMITTED'))).toBeTruthy();
    expect(await screen.findByText(t('i02w.bookingNote', [2]))).toBeTruthy();
  });

  it('SRS-5-14: the NWNP view draws Continue work and its own controls', async () => {
    search.current = 'engine=NWNP';
    slipRoutes([slip({ continueWork: true })]);
    wrap(<WorkSlips />, me(LDH));
    expect(await screen.findByText(t('i02w.continueYes'))).toBeTruthy();
    expect(screen.getByText(t('i02w.nwnp'))).toBeTruthy();
  });

  it('SRS-2-15: an entry action with no entry says what to do; New Work-Slip opens the section', async () => {
    search.current = '';
    slipRoutes([slip()]);
    wrap(<WorkSlips />, me(LDH));
    await userEvent.click(await screen.findByRole('button', { name: t('i02w.save') }));
    expect(await screen.findByText(t('i02w.why.noEntry'))).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('i02w.new') }));
    expect(screen.getByTestId('i02-capture').hasAttribute('open')).toBe(true);
  });

  it('OD-121: a viewer sees the capture actions locked and views a row', async () => {
    search.current = '';
    slipRoutes([slip()]);
    wrap(<WorkSlips />, me(['I-02:VIEW']));
    await userEvent.click(await screen.findByRole('button', { name: `${t('common.view')} WS-88402` }));
    expect(await screen.findByRole('dialog')).toBeTruthy();
    expect(screen.getByRole('button', { name: t('i02w.new') })).toBeDisabled();
    expect(screen.getByRole('button', { name: t('i02w.saveNext') })).toBeDisabled();
  });
});

describe('capture rules', () => {
  it('SRS-2-36: a view maps to its payroll type; an unknown one to none', () => {
    expect(engineType('DEPT')).toBe('DEPT_LABOUR');
    expect(engineType('DPS')).toBe('DPS_LABOUR');
    expect(engineType('NWNP')).toBe('NWNP_LABOUR');
    expect(engineType('X')).toBeNull();
    expect(engineType(null)).toBeNull();
  });

  it('Annexure A.7: code P exists only in the DPS set', () => {
    expect(fourthCode('DPS_LABOUR')).toBe('P');
    expect(fourthCode('DEPT_LABOUR')).toBe('S');
  });

  it('SRS-2-35: only a blank day at a depot marking by hand can be marked', () => {
    expect(dayMarkable(day())).toBe(true);
    expect(dayMarkable(day({ code: 'W' }))).toBe(false);
    expect(dayMarkable(day({ otherCode: 'W' }))).toBe(false);
    expect(dayMarkable(day({ pastRetirement: true }))).toBe(false);
    expect(dayMarkable(day({ sourceMode: 'BIOMETRIC' }))).toBe(false);
    expect(dayMarkable(day({ sourceMode: 'BOTH' }))).toBe(true);
  });

  it('SRS-2-37: the Source column reads the punches, the feed or the manual depot', () => {
    expect(daySource(day())).toEqual(['i01w.src.manual', []]);
    expect(daySource(day({ captureMode: 'ANNA_DARPAN' }))).toEqual(['i01w.src.anna', []]);
    expect(daySource(day({ pastRetirement: true }))).toEqual(['i01w.src.none', []]);
    expect(daySource(day({ sourceMode: 'BOTH', punchIn: '07:52', punchOut: '16:10' }))).toEqual([
      'i01w.src.punches',
      ['07:52', '16:10'],
    ]);
    expect(daySource(day({ sourceMode: 'BOTH', punchIn: '07:52' }))[0]).toBe('i01w.src.missingOut');
    expect(daySource(day({ sourceMode: 'BOTH', punchOut: '16:10' }))[0]).toBe('i01w.src.missingIn');
    expect(daySource(day({ sourceMode: 'BIOMETRIC' }))[0]).toBe('i01w.src.manual');
    expect(daySource(day({ sourceMode: 'BIOMETRIC', exception: 'MISSING_PUNCH' }))[0]).toBe(
      'i01w.src.missingOut',
    );
  });

  it('SRS-2-35: the marks of a gang go together with the marks it already holds', () => {
    const rows = [
      day(),
      day({ employeeId: 12, code: 'A' }),
      day({ employeeId: 13, otherCode: 'W' }),
      day({ employeeId: 14, gangId: 9 }),
    ];
    const out = marksByGang(rows, { 11: 'W' });
    expect([...out.keys()]).toEqual([8]);
    expect(out.get(8)).toEqual({ 11: 'W', 12: 'A' });
  });

  it('SRS-2-34: the status of a register row, DPS and otherwise', () => {
    expect(registerStatus(slip(), false)[1]).toBe('i02w.status.accepted');
    expect(registerStatus(slip({ status: 'PROCESSED' }), true)[1]).toBe('i02w.status.accepted');
    expect(registerStatus(slip({ status: 'DUPLICATE_HELD' }), true)[1]).toBe('i02w.status.duplicate');
    expect(registerStatus(slip({ status: 'REJECTED', source: 'INTF' }), false)[1]).toBe(
      'i02w.status.rejectedImport',
    );
    expect(registerStatus(slip({ status: 'REJECTED' }), true)[1]).toBe('i02.dpsStatus.REJECTED');
    expect(registerStatus(slip({ status: 'REJECTED' }), false)[1]).toBe('i02.status.REJECTED');
    expect(registerStatus(slip({ status: 'SUBMITTED' }), false)[1]).toBe('i02.status.SUBMITTED');
    expect(registerStatus(slip({ status: 'DRAFT' }), true)[1]).toBe('i02.dpsStatus.DRAFT');
    expect(registerStatus(slip({ status: 'DRAFT' }), false)[1]).toBe('i02.status.DRAFT');
    expect(registerStatus(slip({ status: 'CANCELLED' }), false)[1]).toBe('i02.status.CANCELLED');
  });

  it('the tone of a gang and the month shown', () => {
    expect(gangTone('READY')).toBe('p-ok');
    expect(gangTone('EXCEPTION')).toBe('p-bad');
    expect(gangTone('NOT_MARKED')).toBe('p-warn');
    expect(previousMonth('2026-01-15')).toBe('2025-12');
  });
});
