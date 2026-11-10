import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AttendanceCapture } from '@/modules/labour';
import type { DayRow, GangSummaryRow } from '@/modules/labour/api/captureApi';
import { previousMonth } from '@/modules/labour/business/labourRules';
import { businessToday } from '@/shared/time';
import { backend, me, paged, problem, t, wrap, type Route } from './support/harness';

/**
 * I-01 Labour Attendance Capture as drawn (Wireframes v6.1, OD-121; SRS-2-35, SRS-2-37, SRS-2-38, SRS-2-48,
 * SRS-2-57; Annexure A.7) coverage gaps: a refused day save, the Day-wise grid toggle, the Checker's ✔, the Check
 * column of every capture kind, the leave origins and states, the source states and a month with every slip in.
 */

afterEach(() => vi.unstubAllGlobals());

vi.mock('next/navigation', () => ({
  useSearchParams: () => new URLSearchParams(''),
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
}));

const MONTH = previousMonth(businessToday());

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

const GANG: GangSummaryRow = {
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
};

const leave = (over: Record<string, unknown>) => ({
  id: 1,
  labourCode: 'DL-20115',
  name: 'Jagdish Yadav',
  origin: 'APPLICATION',
  from: `${MONTH}-09`,
  to: `${MONTH}-09`,
  fromCode: null,
  toCode: 'EOL',
  backDated: false,
  raisedOn: `${MONTH}-09`,
  status: 'SUBMITTED',
  ...over,
});

function routes(days: DayRow[], over: Record<string, Route> = {}) {
  return backend({
    'GET /v1/labour/attendance/overview': () => ({
      month: MONTH,
      records: 124,
      withSlip: 124,
      lwpDays: 0,
      exceptions: 0,
      pastRetirement: 0,
      overCalendar: 0,
      anna: { received: 0, confirmed: 0, returned: 0 },
      codes: [{ code: 'W', meaning: 'On work' }],
      gangs: [{ id: 8, code: 'GNG-01', name: 'Okhla Shed A Gang 1', type: 'HANDLING' }],
    }),
    'GET /v1/labour/attendance/gang-summaries': () => paged([GANG]),
    'GET /v1/labour/attendance/days': () => paged(days),
    'GET /v1/labour/attendance/leave': () =>
      paged([
        leave({ id: 1 }),
        leave({
          id: 2,
          labourCode: 'DL-20116',
          origin: 'REGULARISATION',
          fromCode: 'EOL',
          status: 'APPROVED',
        }),
      ]),
    'GET /v1/labour/attendance/sources': () =>
      paged([
        {
          depot: 'DP-DL02 - Depot Narela',
          mode: 'MANUAL',
          annaDarpan: false,
          status: 'PILOT',
          effectiveFrom: '2026-06-07',
          enrolled: 0,
        },
      ]),
    'GET /v1/labour/gangs': () => paged([]),
    ...over,
  });
}

const LDH = ['I-01:VIEW', 'I-01:CREATE'];

describe('I-01 the day entry', () => {
  it('SRS-2-35: a refused day save shows the reason and keeps the marks', async () => {
    const calls = routes([day()], {
      'POST /v1/labour/gangs/8/attendance-days': () => problem(409, 'LBR-409-ATTENDANCE-FROZEN'),
    });
    wrap(<AttendanceCapture />, me(LDH));
    const table = await screen.findByRole('table', { name: t('i01w.daily') });
    const code = await within(table).findByLabelText(t('i01.codeOf', ['DL-20180']));
    await waitFor(() => expect(within(code).queryByText('W')).toBeTruthy());
    await userEvent.selectOptions(code, 'W');
    await userEvent.click(screen.getByRole('button', { name: t('i01w.save') }));
    expect(await screen.findByRole('alert')).toBeInTheDocument();
    expect(calls.filter((c) => c.method === 'POST')).toHaveLength(1);
    expect((code as HTMLSelectElement).value).toBe('W');
    expect(screen.queryByText(t('i01.saved', [businessToday()]))).not.toBeInTheDocument();
  });

  it('SRS-2-37, SRS-2-38: the Check column reads retirement, another gang, an exception, the feed and the punches', async () => {
    routes([
      day({ employeeId: 1, labourCode: 'DL-1', pastRetirement: true }),
      day({ employeeId: 2, labourCode: 'DL-2', otherCode: 'W', otherGang: null, otherDepot: null }),
      day({
        employeeId: 3,
        labourCode: 'DL-3',
        code: 'W',
        exception: 'LATE_PUNCH',
        captureMode: 'BIOMETRIC',
      }),
      day({ employeeId: 4, labourCode: 'DL-4', code: 'W', captureMode: 'ANNA_DARPAN', depotConfirmed: true }),
      day({
        employeeId: 5,
        labourCode: 'DL-5',
        code: 'W',
        captureMode: 'ANNA_DARPAN',
        depotConfirmed: false,
      }),
      day({ employeeId: 6, labourCode: 'DL-6', code: 'W', captureMode: 'BIOMETRIC' }),
      day({ employeeId: 7, labourCode: 'DL-7', code: 'A', captureMode: 'MANUAL' }),
    ]);
    wrap(<AttendanceCapture />, me(['I-01:VIEW']));
    const table = await screen.findByRole('table', { name: t('i01w.daily') });
    const row = async (code: string) =>
      (await within(table).findByText(new RegExp(`^${code} `))).closest('tr') as HTMLElement;
    expect(within(await row('DL-1')).getByText(t('i01w.chk.retired'))).toBeInTheDocument();
    expect(within(await row('DL-1')).getByText(t('i01w.blocked'))).toBeInTheDocument();
    const other = within(await row('DL-2')).getAllByRole('cell')[4] as HTMLElement;
    expect(other.textContent).toBe(t('i01w.chk.other', ['W', '', '']));
    expect(within(await row('DL-3')).getByText(t('i01w.chk.exception'))).toHaveClass('p-warn');
    expect(within(await row('DL-4')).getByText(t('i01w.chk.confirmed'))).toHaveClass('p-ok');
    expect(within(await row('DL-5')).getByText(t('i01w.chk.received'))).toHaveClass('p-warn');
    expect(within(await row('DL-6')).getByText(t('i01w.chk.punches'))).toHaveClass('p-ok');
    const manual = await row('DL-7');
    expect(within(manual).getByText('A')).toBeInTheDocument();
    expect(within(manual).getByText('—')).toBeInTheDocument();
    // A viewer gets no code selector.
    expect(within(table).queryByLabelText(t('i01.codeOf', ['DL-7']))).not.toBeInTheDocument();
  });
});

describe('I-01 summary, leave and sources', () => {
  it('SRS-2-48: a month with every slip in reads complete; the Day-wise grid button opens and closes the grid', async () => {
    routes([]);
    wrap(<AttendanceCapture />, me(['I-01:VIEW']));
    expect(await screen.findByText('124 / 124')).toBeInTheDocument();
    expect(screen.getByText(t('i01w.tile.complete'))).toBeInTheDocument();
    const grid = screen.getByTestId('i01-grid');
    expect(grid.hasAttribute('open')).toBe(false);
    fireEvent.click(screen.getByRole('button', { name: t('i01w.dayGrid') }));
    expect(grid.hasAttribute('open')).toBe(true);
    fireEvent.click(screen.getByRole('button', { name: t('i01w.dayGrid') }));
    expect(grid.hasAttribute('open')).toBe(false);
  });

  it('SRS-2-48: a month with gangs still without slips reads how many block the batch', async () => {
    routes([], {
      'GET /v1/labour/attendance/overview': () => ({
        month: MONTH,
        records: 124,
        withSlip: 120,
        lwpDays: 3,
        exceptions: 2,
        pastRetirement: 1,
        overCalendar: 0,
        anna: { received: 0, confirmed: 0, returned: 0 },
        codes: [],
        gangs: [],
      }),
    });
    wrap(<AttendanceCapture />, me(['I-01:VIEW']));
    expect(await screen.findByText(t('i01w.tile.blocked', [4]))).toBeInTheDocument();
    expect(screen.queryByText(t('i01w.tile.complete'))).not.toBeInTheDocument();
  });

  it('SRS-2-29: the Payroll Checker’s ✔ on a gang goes to W-01', async () => {
    const assign = vi.fn();
    vi.stubGlobal('location', { ...window.location, assign });
    routes([]);
    wrap(<AttendanceCapture />, me(['I-01:VIEW', 'I-01:APPROVE']));
    const summary = await screen.findByRole('table', { name: t('i01w.summaryName') });
    await within(summary).findByText(/GNG-01/);
    fireEvent.click(within(summary).getByRole('button', { name: '✔' }));
    expect(assign).toHaveBeenCalledWith(expect.stringContaining('?s=W-01'));
  });

  it('SRS-2-57: a leave application reads its own effect and a pending state; a regularisation reads A', async () => {
    routes([]);
    wrap(<AttendanceCapture />, me(['I-01:VIEW']));
    const table = await screen.findByRole('table', { name: t('i01w.leave') });
    const application = (await within(table).findByText(/DL-20115/)).closest('tr') as HTMLElement;
    expect(within(application).getByText(t('i01w.l.effect.APPLICATION'))).toBeInTheDocument();
    expect(within(application).getByText('— → EOL')).toBeInTheDocument();
    expect(within(application).getByText(t('i01w.l.status.SUBMITTED'))).toHaveClass('p-warn');
    const regularised = within(table)
      .getByText(/DL-20116/)
      .closest('tr') as HTMLElement;
    expect(within(regularised).getByText('A → EOL')).toBeInTheDocument();
    expect(within(regularised).getByText(t('i01w.l.status.APPROVED'))).toHaveClass('p-ok');
  });

  it('SRS-2-37: a pilot source without Anna Darpan reads the mode alone, amber', async () => {
    routes([]);
    wrap(<AttendanceCapture />, me(['I-01:VIEW']));
    const cell = await screen.findByText(t('i01.mode.MANUAL'));
    expect(cell.textContent).not.toContain(t('i01.annaDarpan'));
    const tr = cell.closest('tr') as HTMLElement;
    expect(within(tr).getByText(t('i01.sourceStatus.PILOT'))).toHaveClass('p-warn');
  });
});
