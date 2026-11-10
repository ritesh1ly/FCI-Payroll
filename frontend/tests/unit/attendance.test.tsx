import { fireEvent, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AttendanceCapture } from '@/modules/labour';
import {
  daysOf,
  dayBlocked,
  frozenDay,
  markable,
  previousMonth,
} from '@/modules/labour/business/labourRules';
import type { AttendanceGrid, MemberGrid } from '@/modules/labour/api/labourApi';
import { backend, me, paged, problem, sent, t, wrap } from './support/harness';

/** I-01 Labour Attendance Capture (plan 20 phase 20b; SRS-2-2, SRS-2-29, SRS-2-35, SRS-2-38). */

afterEach(() => vi.unstubAllGlobals());

const GANG = {
  gangId: 5,
  gangCode: 'DP-DL01-G002',
  gangName: 'Okhla Gang 1',
  depot: 'DP-DL01 - Depot Okhla',
  payrollType: 'DEPT_LABOUR',
  gangType: 'HANDLING',
  status: 'ACTIVE',
  sanctionedStrength: 6,
  members: 2,
  effectiveFrom: '2025-07-01',
};

function member(over: Partial<MemberGrid> = {}): MemberGrid {
  return {
    employeeId: 11,
    labourCode: 'L10701',
    name: 'Harpal Sandhu',
    role: 'SARDAR',
    from: '2025-07-01',
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
    gangId: 5,
    gangName: 'Okhla Gang 1',
    depot: 'DP-DL01 - Depot Okhla',
    payrollType: 'DEPT_LABOUR',
    yymm: '202609',
    lastMarkable: '2026-09-30',
    batchState: 'OPEN',
    approvalId: null,
    sanctionedStrength: 6,
    codes: [
      { code: 'W', meaning: 'On work', weight: 1, leave: false, confirmed: true },
      { code: 'A', meaning: 'Absent', weight: 0, leave: false, confirmed: true },
    ],
    members: [
      member({
        marks: [{ date: '2026-09-01', code: 'W', otherGang: false, workSlipFiled: false }],
        marked: 1,
        presentDays: 1,
      }),
      member({
        employeeId: 12,
        labourCode: 'L10702',
        name: 'Mohan Rawat',
        role: 'MANDAL',
        marks: [{ date: '2026-09-01', code: 'W', otherGang: true, workSlipFiled: false }],
      }),
    ],
    ...over,
  };
}

describe('I-01 rules', () => {
  it('SRS-2-2: a member is marked while in the gang and not past retirement', () => {
    expect(markable(member(), '2026-09-01')).toBe(true);
    expect(markable(member({ from: '2026-09-02' }), '2026-09-01')).toBe(false);
    expect(markable(member({ to: '2026-08-31' }), '2026-09-01')).toBe(false);
    expect(markable(member({ retirementDate: '2026-08-31' }), '2026-09-01')).toBe(false);
  });

  it('SRS-2-35 / SRS-2-29: a day is saved whole, in an open batch, in the month', () => {
    const g = grid();
    expect(dayBlocked(g, '2026-09-02', { 11: 'W', 12: 'A' })).toBeNull();
    expect(dayBlocked(g, '2026-09-02', { 11: 'W' })).toBe('i01.why.unmarked');
    expect(dayBlocked(g, '', {})).toBe('i01.why.day');
    expect(dayBlocked(g, '2026-10-01', {})).toBe('i01.why.day');
    expect(dayBlocked(grid({ lastMarkable: '2026-09-10' }), '2026-09-11', {})).toBe('i01.why.day');
    expect(dayBlocked(grid({ batchState: 'SUBMITTED' }), '2026-09-02', {})).toBe('i01.why.batch');
    expect(dayBlocked(grid({ batchState: 'APPROVED' }), '2026-09-02', {})).toBe('i01.why.batch');
    expect(dayBlocked(grid({ codes: [] }), '2026-09-02', {})).toBe('i01.why.codes');
    expect(dayBlocked(grid({ members: [member({ from: '2026-09-20' })] }), '2026-09-02', {})).toBe(
      'i01.why.noMembers',
    );
  });

  it('the days of a month up to the last markable day', () => {
    expect(daysOf('2026-09', '2026-09-30')).toHaveLength(30);
    expect(daysOf('2028-02', '2028-03-31')).toHaveLength(29);
    expect(daysOf('2026-10', '2026-10-05')).toEqual([
      '2026-10-01',
      '2026-10-02',
      '2026-10-03',
      '2026-10-04',
      '2026-10-05',
    ]);
    expect(previousMonth('2026-01-15')).toBe('2025-12');
    expect(previousMonth('2026-10-28')).toBe('2026-09');
  });
});

describe('I-01 Labour Attendance Capture', () => {
  it('SRS-2-35: the Labour Dealing Hand marks a day for every member and submits the month', async () => {
    const calls = backend({
      'GET /v1/labour/gangs?': (url) =>
        paged([GANG, { ...GANG, gangId: 6, gangType: 'ANCILLARY_POOL', gangName: 'Pool' }], 2, url),
      'GET /v1/labour/gangs/5/attendance?': () => grid(),
      'POST /v1/labour/gangs/5/attendance-days': () => ({
        date: '2026-09-02',
        marked: 2,
        added: 2,
        changed: 0,
        markedAbsent: [],
      }),
      'POST /v1/labour/gangs/5/attendance-batches': () => ({
        status: 202,
        body: { id: 50, status: 'PENDING_APPROVAL' },
      }),
    });
    wrap(<AttendanceCapture />, me(['I-01:VIEW', 'I-01:CREATE', 'I-01:SUBMIT']));
    expect(await screen.findByText(t('i01.chooseGang'))).toBeTruthy();
    const gang = screen.getByLabelText(new RegExp(`^${t('m09.gang')}`), { selector: 'select' });
    await vi.waitFor(() => expect(within(gang).queryByText('DP-DL01-G002 - Okhla Gang 1')).toBeTruthy());
    // SRS-2-46: the depot's Ancillary Pool is marked like any gang (it has no strength check).
    expect(within(gang).getByText(`DP-DL01-G002 - Pool (${t('m09.type.ANCILLARY_POOL')})`)).toBeTruthy();
    // The gangs are searched on the server, active only.
    expect(calls.some((c) => c.url.includes('/v1/labour/gangs?') && c.url.includes('status=ACTIVE'))).toBe(
      true,
    );
    await userEvent.selectOptions(gang, '5');
    await userEvent.click(screen.getByRole('button', { name: t('i01.lastMonth') }));
    const month = screen.getByLabelText(new RegExp(`^${t('i01.month')}`), {
      selector: 'input',
    }) as HTMLInputElement;
    fireEvent.change(month, { target: { value: '2026-09' } });
    const table = await screen.findByRole('table', { name: t('i01.grid') });
    expect(within(table).getByText('(W)')).toBeTruthy();
    expect(screen.getByRole('status').textContent).toContain(t('i01.batch.OPEN'));
    await userEvent.click(screen.getByRole('button', { name: t('i01.markDay', ['2026-09-02']) }));
    const save = screen.getByRole('button', { name: t('action.i01.save') });
    expect(save.getAttribute('title')).toBe(t('i01.why.unmarked'));
    // I-01 l.4545 / SRS-2-48: the day's present strength against the Sanctioned Strength.
    const check = screen.getByRole('status', { name: t('i01.strengthCheck') });
    expect(check.textContent).toContain(t('i01.strength', [0, 6]));
    expect(check.textContent).toContain(t('i01.short', [6]));
    await userEvent.selectOptions(screen.getByLabelText(t('i01.codeOf', ['L10701'])), 'W');
    await userEvent.selectOptions(
      screen.getAllByLabelText(t('i01.codeOf', ['L10702']))[0] as HTMLElement,
      'A',
    );
    // Present is counted from the marks with a present weight: W counts, A does not.
    expect(check.textContent).toContain(t('i01.strength', [1, 6]));
    expect(check.textContent).toContain(t('i01.short', [5]));
    await userEvent.click(save);
    expect(await screen.findByText(t('i01.saved', ['2026-09-02']))).toBeTruthy();
    expect(screen.queryByText(new RegExp(t('i01.markedAbsent', ['']).slice(0, 20)))).toBeNull();
    expect(sent(calls, 'POST', '/attendance-days').at(-1)).toMatchObject({
      date: '2026-09-02',
      marks: [
        { employeeId: 11, code: 'W' },
        { employeeId: 12, code: 'A' },
      ],
    });
    await userEvent.click(screen.getByRole('button', { name: t('i01.markDay', ['2026-09-01']) }));
    expect((screen.getByLabelText(t('i01.codeOf', ['L10701'])) as HTMLSelectElement).value).toBe('W');
    await userEvent.click(screen.getByRole('button', { name: t('action.i01.submit') }));
    expect(await screen.findByText(t('i01.submitted'))).toBeTruthy();
    expect(sent(calls, 'POST', '/attendance-batches').at(-1)).toMatchObject({ month: '2026-09' });
  });

  it('SRS-2-38: a refused day shows the reason; a viewer cannot mark or submit', async () => {
    backend({
      'GET /v1/labour/gangs?': (url) => paged([GANG], 1, url),
      'GET /v1/labour/gangs/5/attendance?': () => grid({ batchState: 'SUBMITTED' }),
    });
    wrap(<AttendanceCapture />, me(['I-01:VIEW']));
    const gang = screen.getByLabelText(new RegExp(`^${t('m09.gang')}`), { selector: 'select' });
    await vi.waitFor(() => expect(within(gang).queryByText('DP-DL01-G002 - Okhla Gang 1')).toBeTruthy());
    await userEvent.selectOptions(gang, '5');
    const month = screen.getByLabelText(new RegExp(`^${t('i01.month')}`), {
      selector: 'input',
    }) as HTMLInputElement;
    fireEvent.change(month, { target: { value: '2026-09' } });
    await screen.findByRole('table', { name: t('i01.grid') });
    expect(screen.getByRole('status').textContent).toContain(t('i01.batch.SUBMITTED'));
    const submit = screen.getByRole('button', { name: t('action.i01.submit') });
    expect(submit).toBeDisabled();
    expect(submit.getAttribute('title')).toBe(t('reason.ldhOnly'));
    await userEvent.click(screen.getByRole('button', { name: t('i01.markDay', ['2026-09-02']) }));
    expect(screen.getByRole('button', { name: t('action.i01.save') }).getAttribute('title')).toBe(
      t('reason.ldhOnly'),
    );
  });

  it('SRS-2-38: the server refusal is shown', async () => {
    backend({
      'GET /v1/labour/gangs?': (url) => paged([GANG], 1, url),
      'GET /v1/labour/gangs/5/attendance?': () => grid({ members: [member()] }),
      'POST /v1/labour/gangs/5/attendance-days': () =>
        problem(409, 'LBR-409-MARKED', ['L10701', 'W', '2026-09-02', 'Pool', 'DP-DL01']),
      'POST /v1/labour/gangs/5/attendance-batches': () =>
        problem(422, 'LBR-422-ATTENDANCE', ['UNMARKED_DAYS 3']),
    });
    wrap(<AttendanceCapture />, me(['I-01:VIEW', 'I-01:CREATE', 'I-01:SUBMIT']));
    const gang = screen.getByLabelText(new RegExp(`^${t('m09.gang')}`), { selector: 'select' });
    await vi.waitFor(() => expect(within(gang).queryByText('DP-DL01-G002 - Okhla Gang 1')).toBeTruthy());
    await userEvent.selectOptions(gang, '5');
    const month = screen.getByLabelText(new RegExp(`^${t('i01.month')}`), {
      selector: 'input',
    }) as HTMLInputElement;
    fireEvent.change(month, { target: { value: '2026-09' } });
    await screen.findByRole('table', { name: t('i01.grid') });
    await userEvent.click(screen.getByRole('button', { name: t('i01.markDay', ['2026-09-02']) }));
    await userEvent.selectOptions(screen.getByLabelText(t('i01.codeOf', ['L10701'])), 'W');
    await userEvent.click(screen.getByRole('button', { name: t('action.i01.save') }));
    expect(await screen.findByRole('alert')).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('action.i01.submit') }));
    expect(await screen.findByRole('alert')).toBeTruthy();
  });

  // SC-21-0005: the first Sunday of a split week is marked absent on save (DPS).
  it('SRS-4-5: the members whose first Sunday of a split week was marked absent are named', async () => {
    backend({
      'GET /v1/labour/gangs?': (url) => paged([GANG], 1, url),
      'GET /v1/labour/gangs/5/attendance?': () => grid(),
      'POST /v1/labour/gangs/5/attendance-days': () => ({
        date: '2026-09-02',
        marked: 2,
        added: 0,
        changed: 1,
        markedAbsent: ['L10702'],
      }),
    });
    wrap(<AttendanceCapture />, me(['I-01:VIEW', 'I-01:CREATE', 'I-01:SUBMIT']));
    const gang = screen.getByLabelText(new RegExp(`^${t('m09.gang')}`), { selector: 'select' });
    await vi.waitFor(() => expect(within(gang).queryByText('DP-DL01-G002 - Okhla Gang 1')).toBeTruthy());
    await userEvent.selectOptions(gang, '5');
    fireEvent.change(screen.getByLabelText(new RegExp(`^${t('i01.month')}`), { selector: 'input' }), {
      target: { value: '2026-09' },
    });
    await screen.findByRole('table', { name: t('i01.grid') });
    await userEvent.click(screen.getByRole('button', { name: t('i01.markDay', ['2026-09-02']) }));
    await userEvent.selectOptions(screen.getByLabelText(t('i01.codeOf', ['L10701'])), 'W');
    await userEvent.selectOptions(
      screen.getAllByLabelText(t('i01.codeOf', ['L10702']))[0] as HTMLElement,
      'W',
    );
    await userEvent.click(screen.getByRole('button', { name: t('action.i01.save') }));
    expect(await screen.findByText(t('i01.markedAbsent', ['L10702']))).toBeTruthy();
  });
});

describe('I-01 group g5 fixes', () => {
  const frozen = grid({
    batchState: 'RETURNED',
    returnReason: 'Day 3 of the Sardar is a holiday',
    members: [
      member({
        marks: [{ date: '2026-09-01', code: 'W', otherGang: false, workSlipFiled: true }],
        marked: 1,
      }),
    ],
  });

  // SRS-2-3 / BR-LABM-005: a day with a filed work-slip is frozen.
  it('SRS-2-3: a day with a filed work-slip is frozen and refused before the server', () => {
    expect(frozenDay(frozen, '2026-09-01')).toBe(true);
    expect(frozenDay(frozen, '2026-09-02')).toBe(false);
    expect(frozenDay(grid(), '2026-09-01')).toBe(false);
    expect(dayBlocked(frozen, '2026-09-01', { 11: 'A' })).toBe('i01.why.frozen');
    expect(dayBlocked(frozen, '2026-09-01', { 11: 'W' })).toBeNull();
    expect(dayBlocked(frozen, '2026-09-02', { 11: 'W' })).toBeNull();
  });

  // I-01 l.4539-4541: depot and payroll type narrow the gangs; SRS-2-29: the return reason is shown;
  // SRS-2-39 / SRS-2-57: the parked correction and leave panel are shown disabled with the reason.
  it('I-01 l.4539: the depot and payroll type narrow the gangs; a returned batch shows the reason', async () => {
    const calls = backend({
      'GET /v1/labour/options': () => ({
        depots: [{ id: 7, code: 'DP-DL01', name: 'Depot Okhla', family: null }],
        designations: [],
      }),
      'GET /v1/labour/gangs?': (url) => paged([GANG], 1, url),
      'GET /v1/labour/gangs/5/attendance?': () => frozen,
    });
    wrap(<AttendanceCapture />, me(['I-01:VIEW', 'I-01:CREATE', 'I-01:SUBMIT']));
    const depot = await screen.findByLabelText(new RegExp(`^${t('m02.depot')}`), { selector: 'select' });
    await vi.waitFor(() => expect(within(depot).queryByText('DP-DL01 - Depot Okhla')).toBeTruthy());
    await userEvent.selectOptions(depot, '7');
    await userEvent.selectOptions(
      screen.getByLabelText(new RegExp(`^${t('m02.payrollType')}`), { selector: 'select' }),
      'DEPT_LABOUR',
    );
    await vi.waitFor(() =>
      expect(
        calls.some(
          (c) =>
            c.url.includes('/v1/labour/gangs?') && c.url.includes('depot=7') && c.url.includes('DEPT_LABOUR'),
        ),
      ).toBe(true),
    );
    const gang = screen.getByLabelText(new RegExp(`^${t('m09.gang')}`), { selector: 'select' });
    await vi.waitFor(() => expect(within(gang).queryByText('DP-DL01-G002 - Okhla Gang 1')).toBeTruthy());
    await userEvent.selectOptions(gang, '5');
    fireEvent.change(screen.getByLabelText(new RegExp(`^${t('i01.month')}`), { selector: 'input' }), {
      target: { value: '2026-09' },
    });
    const table = await screen.findByRole('table', { name: t('i01.grid') });
    expect(within(table).getByTitle(t('i01.frozen'))).toBeTruthy();
    expect(screen.getByText(t('i01.frozenNote'))).toBeTruthy();
    expect(screen.getByLabelText(t('i01.returnReason')).textContent).toBe(
      t('i01.returnedWith', ['Day 3 of the Sardar is a holiday']),
    );
    const correct = screen.getByRole('button', { name: t('action.i01.correct') });
    expect(correct).toBeDisabled();
    expect(correct.getAttribute('title')).toBe(t('i01.why.correction'));
    expect(screen.getByRole('button', { name: t('action.i01.leave') }).getAttribute('title')).toBe(
      t('i01.why.leave'),
    );
    await userEvent.click(screen.getByRole('button', { name: t('i01.markDay', ['2026-09-01']) }));
    await userEvent.selectOptions(screen.getByLabelText(t('i01.codeOf', ['L10701'])), 'A');
    expect(screen.getByRole('button', { name: t('action.i01.save') }).getAttribute('title')).toBe(
      t('i01.why.frozen'),
    );
  });
});
