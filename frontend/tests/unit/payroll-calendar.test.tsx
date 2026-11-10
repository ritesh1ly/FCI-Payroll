import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { PayrollCalendar } from '@/modules/jobs';
import { blankPeriod, monthsOf, move } from '@/modules/jobs/business/calendarRules';
import { backend, problem, sent, t, wrap } from './support/harness';

/**
 * M-10 Payroll Calendar & Cycle Setup (plan 05 phase 05f; SRS-15-33, SRS M-10, SRS-15-1): the year's
 * periods, a period proposed by the Administrator, a period in use fixed (D-12), the run order
 * re-ordered and proposed (D-13).
 */

afterEach(() => vi.unstubAllGlobals());

const april = {
  month: '2027-04',
  start: '2027-04-01',
  end: '2027-04-30',
  freezeTarget: '2027-04-20',
  computeBy: null,
  verifyBy: null,
  lockTarget: '2027-04-28',
  disbursement: null,
  supplementaryFrom: '2027-04-28',
  supplementaryTo: '2027-04-30',
  mdpFrom: '2027-04-28',
  mdpTo: '2027-04-30',
};

const STEPS = [
  {
    sequence: 10,
    code: 'STAFF_PAYROLL',
    name: 'Employee payroll',
    dependsOn: null,
    ownerRole: 'PAYROLL_MAKER',
    payrollType: 'STAFF',
  },
  {
    sequence: 20,
    code: 'DEPT_LABOUR_WAGES',
    name: 'Departmental wages',
    dependsOn: null,
    ownerRole: 'LABOUR_DEALING_HAND',
    payrollType: 'DEPT_LABOUR',
  },
  {
    sequence: 30,
    code: 'LABOUR_INCENTIVE',
    name: 'Labour incentive',
    dependsOn: 'DEPT_LABOUR_WAGES',
    ownerRole: 'LABOUR_DEALING_HAND',
    payrollType: null,
  },
];

function routes(
  over: Record<string, (url: string, init?: RequestInit) => unknown> = {},
  pending: number | null = null,
) {
  return backend({
    // The current financial year is not offered here, so M-10 opens on the newest (UI audit S4).
    'GET /v1/payroll-calendars': () => ['2027-28', '2020-21'],
    'GET /v1/payroll-calendars/2027-28': () => ({
      finYear: '2027-28',
      periods: [
        { period: april, initiated: true, cycleStatus: 'LOCKED', windowOpen: true },
        {
          period: { ...april, month: '2027-05', start: '2027-05-01', end: '2027-05-31' },
          initiated: false,
          cycleStatus: null,
          windowOpen: false,
        },
      ],
      pendingApprovalId: pending,
    }),
    'GET /v1/run-order-steps': () => ({ steps: STEPS, pendingApprovalId: null }),
    ...over,
  });
}

const ADMIN = ['M-10:VIEW', 'M-10:EDIT', 'M-10:REORDER'];

describe('M-10 Payroll Calendar & Cycle Setup', () => {
  it('SRS M-10: one row per defined period, with its dates and the state of the session unit', async () => {
    routes();
    wrap(<PayrollCalendar />, ['M-10:VIEW']);
    const table = await screen.findByRole('table', { name: t('m10w.calendar', ['2027-28']) });
    expect(await within(table).findByText(t('m10w.lockedMdp'))).toBeTruthy();
    expect(within(table).getByText(t('d01.state.NOT_INITIATED'))).toBeTruthy();
    expect(within(table).getByText('202704')).toBeTruthy();
    expect(within(table).queryByText(/JUN 2027/)).toBeNull();
    // A viewer neither proposes nor re-orders: every control is drawn, disabled with its reason.
    expect(screen.getByRole('button', { name: t('m10w.maintain') })).toHaveAttribute(
      'data-why',
      t('m10w.why.maintain'),
    );
    expect(screen.getByRole('button', { name: t('m10w.reorder') })).toHaveAttribute(
      'data-why',
      t('m10w.why.reorder'),
    );
    expect(screen.getByRole('button', { name: t('m10w.initiate') })).toHaveAttribute(
      'data-why',
      t('m10w.why.initiate'),
    );
    expect(screen.queryByRole('button', { name: t('m10.proposeOrder') })).toBeNull();
    expect(screen.getByRole('button', { name: t('m10.edit', ['APR 2027']) })).toHaveAttribute(
      'data-why',
      t('m10w.why.locked'),
    );
    // SRS-20-29 (US-03-07 AC1): a defined period opens its change history from its View.
    await userEvent.click(screen.getByRole('button', { name: t('m10w.viewOf', ['APR 2027']) }));
    const view = screen.getByRole('dialog');
    expect(within(view).getByRole('button', { name: t('history.for', ['APR 2027']) })).toBeTruthy();
    // SRS l.1634: the mandatory Period Start and End are not drawn on the table: they are in View.
    expect(view).toHaveTextContent(t('m10.field.start'));
  });

  it('SRS-15-33 / D-12: the Administrator proposes a period; a period in use cannot be changed', async () => {
    const calls = routes({
      'POST /v1/payroll-calendars/2027-28/proposals': () => ({ status: 202, body: { approvalId: 91 } }),
    });
    wrap(<PayrollCalendar />, ADMIN);
    const inUse = await screen.findByRole('button', { name: t('m10.edit', ['APR 2027']) }, { timeout: 5000 });
    expect(inUse).toBeDisabled();
    await userEvent.click(screen.getByRole('button', { name: t('m10w.maintain') }));
    const pick = screen.getByRole('dialog', { name: t('m10w.maintain') });
    // D-12: a period a cycle was initiated for is not offered.
    expect(within(pick).queryByRole('option', { name: /APR 2027/ })).toBeNull();
    await userEvent.selectOptions(within(pick).getByLabelText(t('m10.month')), '2027-06');
    await userEvent.click(within(pick).getByRole('button', { name: t('m10w.continue') }));
    const dialog = screen.getByRole('dialog');
    const submit = within(dialog).getByRole('button', { name: t('m10.submit') });
    expect(submit).toBeDisabled();
    for (const [key, value] of [
      ['freezeTarget', '2027-06-20'],
      ['lockTarget', '2027-06-28'],
      ['supplementaryFrom', '2027-06-28'],
      ['supplementaryTo', '2027-06-30'],
      ['mdpFrom', '2027-06-28'],
      ['mdpTo', '2027-06-30'],
    ] as const) {
      await userEvent.type(within(dialog).getByLabelText(t(`m10.field.${key}`)), value);
    }
    await userEvent.type(within(dialog).getByLabelText(t('common.reason')), 'Calendar 2027-28');
    await userEvent.click(submit);
    expect(await screen.findByText(t('m10.proposed'))).toBeTruthy();
    expect(sent(calls, 'POST', '/proposals')).toEqual([
      {
        periods: [
          {
            month: '2027-06',
            start: '2027-06-01',
            end: '2027-06-30',
            freezeTarget: '2027-06-20',
            computeBy: null,
            verifyBy: null,
            lockTarget: '2027-06-28',
            disbursement: null,
            supplementaryFrom: '2027-06-28',
            supplementaryTo: '2027-06-30',
            mdpFrom: '2027-06-28',
            mdpTo: '2027-06-30',
          },
        ],
        reason: 'Calendar 2027-28',
      },
    ]);
  });

  it('a refused proposal shows its reason; cancelling sends nothing', async () => {
    const calls = routes({
      'POST /v1/payroll-calendars/2027-28/proposals': () =>
        problem(422, 'CAL-422-PERIOD', ['2027-05', 'OVERLAP']),
    });
    wrap(<PayrollCalendar />, ADMIN);
    // Every month first renders as "Define"; the year's periods turn May into "Change" once loaded.
    await userEvent.click(
      await screen.findByRole('button', { name: t('m10.edit', ['MAY 2027']) }, { timeout: 5000 }),
    );
    await userEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: t('common.cancel') }),
    );
    expect(sent(calls, 'POST', '/proposals')).toHaveLength(0);
    await userEvent.click(await screen.findByRole('button', { name: t('m10.edit', ['MAY 2027']) }));
    await userEvent.type(within(screen.getByRole('dialog')).getByLabelText(t('common.reason')), 'Fix May');
    await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: t('m10.submit') }));
    expect(await screen.findByText(t('error.CAL-422-PERIOD', ['2027-05', 'OVERLAP']))).toBeTruthy();
  });

  it('a waiting proposal is shown and holds every change of the year', async () => {
    routes({}, 77);
    wrap(<PayrollCalendar />, ADMIN);
    expect(await screen.findByText(t('m10.yearPending', [77]))).toBeTruthy();
    expect(screen.getByRole('button', { name: t('m10.edit', ['MAY 2027']) })).toBeDisabled();
  });

  it('SRS-15-1 / D-13: the Administrator re-orders the steps and proposes the order with a reason', async () => {
    const calls = routes({
      'POST /v1/run-order-steps/proposals': () => ({ status: 202, body: { approvalId: 92 } }),
    });
    wrap(<PayrollCalendar />, ADMIN);
    await screen.findByText('Employee payroll');
    await userEvent.click(screen.getByRole('button', { name: t('m10w.reorder') }));
    const propose = screen.getByRole('button', { name: t('m10.proposeOrder') });
    expect(propose).toBeDisabled();
    expect(screen.getByRole('button', { name: t('m10.up', ['Employee payroll']) })).toBeDisabled();
    await userEvent.click(screen.getByRole('button', { name: t('m10.down', ['Employee payroll']) }));
    await userEvent.click(screen.getByRole('button', { name: t('m10.resetOrder') }));
    await userEvent.click(screen.getByRole('button', { name: t('m10.down', ['Employee payroll']) }));
    await userEvent.click(propose);
    const reason = screen.getAllByRole('dialog').at(-1) as HTMLElement;
    await userEvent.type(within(reason).getByRole('textbox'), 'Labour first');
    await userEvent.click(within(reason).getByRole('button', { name: t('common.submit') }));
    expect(await screen.findByText(t('m10.orderProposed'))).toBeTruthy();
    expect(sent(calls, 'POST', '/run-order-steps/proposals')).toEqual([
      { order: ['DEPT_LABOUR_WAGES', 'STAFF_PAYROLL', 'LABOUR_INCENTIVE'], reason: 'Labour first' },
    ]);
  });
});

describe('M-10 Initiate Period and FY Calendar', () => {
  const MAKER = ['M-10:VIEW', 'C-02:CREATE', 'C-02:VIEW'];
  const check = (status: string, members: string[] = []) => ({
    code: 'P1_RETIREMENT',
    name: 'P1 - No member past the retirement date',
    status,
    failures: members.length,
    members,
    correctionScreen: null,
  });

  it('SRS-15-23: Initialise stays disabled while a check fails and names the failing records', async () => {
    const calls = routes({
      'GET /v1/cycles/2026-10/pre-run-checks': () => [check('FAIL', ['E001'])],
    });
    wrap(<PayrollCalendar />, MAKER);
    await userEvent.click(await screen.findByRole('button', { name: t('m10w.initiate') }));
    const dialog = screen.getByRole('dialog');
    expect(await within(dialog).findByText('E001')).toBeTruthy();
    expect(within(dialog).getByRole('button', { name: t('m10w.initialise') })).toBeDisabled();
    await userEvent.click(within(dialog).getByRole('button', { name: t('common.cancel') }));
    expect(sent(calls, 'POST', '/initiate')).toHaveLength(0);
  });

  it('SRS-15-33: the Maker initiates its own instance once the checks clear; a refusal stays in the dialog', async () => {
    let refuse = true;
    const calls = routes({
      'GET /v1/cycles/2026-10/pre-run-checks': () => [check('PASS')],
      'POST /v1/cycles/2026-10/initiate': () =>
        refuse ? problem(409, 'CYC-409-PRIOR', ['2026-09']) : { status: 201, body: {} },
    });
    wrap(<PayrollCalendar />, MAKER);
    await userEvent.click(await screen.findByRole('button', { name: t('m10w.initiate') }));
    const dialog = screen.getByRole('dialog');
    const go = await within(dialog).findByRole('button', { name: t('m10w.initialise') });
    await waitFor(() => expect(go).toBeEnabled());
    await userEvent.click(go);
    expect(await within(dialog).findByRole('alert')).toBeTruthy();
    refuse = false;
    await userEvent.click(go);
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(sent(calls, 'POST', '/initiate')).toHaveLength(2);
  });

  it('SRS l.1633: FY Calendar picks the year from the list', async () => {
    const calls = routes({
      'GET /v1/payroll-calendars/2020-21': () => ({
        finYear: '2020-21',
        periods: [],
        pendingApprovalId: null,
      }),
    });
    wrap(<PayrollCalendar />, ['M-10:VIEW']);
    await userEvent.click(await screen.findByRole('button', { name: t('m10w.fyCalendar') }));
    const dialog = screen.getByRole('dialog');
    await userEvent.selectOptions(within(dialog).getByLabelText(t('m10.finYear')), '2020-21');
    await waitFor(() =>
      expect(calls.some((c) => c.url.endsWith('/v1/payroll-calendars/2020-21'))).toBe(true),
    );
    await userEvent.click(within(dialog).getByRole('button', { name: t('common.close') }));
    expect(await screen.findByText(t('m10w.noPeriods', ['2020-21']))).toBeTruthy();
  });

  it('SRS-15-33: the Checker sees the approve mark disabled until a proposal waits', async () => {
    routes();
    wrap(<PayrollCalendar />, ['M-10:VIEW', 'M-10:APPROVE']);
    const row = (await screen.findByText('202705')).closest('tr') as HTMLElement;
    expect(within(row).getByRole('button', { name: '✔' })).toHaveAttribute(
      'data-why',
      t('m10w.why.noProposal'),
    );
  });
});

describe('M-10 calendar helpers', () => {
  it('a financial year runs April to March; anything else has no months', () => {
    expect(monthsOf('2027-28')).toEqual([
      '2027-04',
      '2027-05',
      '2027-06',
      '2027-07',
      '2027-08',
      '2027-09',
      '2027-10',
      '2027-11',
      '2027-12',
      '2028-01',
      '2028-02',
      '2028-03',
    ]);
    expect(monthsOf('2027')).toEqual([]);
    expect(monthsOf('abcd-ef')).toEqual([]);
  });

  it('a blank period spans its calendar month, leap years included', () => {
    expect(blankPeriod('2028-02')).toMatchObject({ start: '2028-02-01', end: '2028-02-29', lockTarget: '' });
    expect(blankPeriod('2027-12').end).toBe('2027-12-31');
  });

  it('a step moves one place and never off the ends', () => {
    expect(move(['a', 'b', 'c'], 0, 1)).toEqual(['b', 'a', 'c']);
    expect(move(['a', 'b', 'c'], 2, -1)).toEqual(['a', 'c', 'b']);
    expect(move(['a', 'b'], 0, -1)).toEqual(['a', 'b']);
    expect(move(['a', 'b'], 1, 1)).toEqual(['a', 'b']);
    expect(move(['a', 'b'], 5, 1)).toEqual(['a', 'b']);
  });
});
