import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { PayrollCalendar } from '@/modules/jobs';
import { date } from '@/shared/ui';
import { backend, me, sent, t, wrap } from './support/harness';

/**
 * M-10 Payroll Calendar & Cycle Setup as Wireframes v6.1 draws it (OD-121; SRS M-10, SRS-15-1, SRS-15-12, SRS-15-33,
 * D-12, D-13): the reason a period's Edit is off (in use, a year waiting, not the Administrator), the approver sent to
 * W-01 only while a proposal waits, the engine state of each step, a fully defined period's view, the re-order
 * dialog closed without a proposal and a year whose every month is in use.
 */
afterEach(() => vi.unstubAllGlobals());

const april = {
  month: '2027-04',
  start: '2027-04-01',
  end: '2027-04-30',
  freezeTarget: '2027-04-20',
  computeBy: '2027-04-22',
  verifyBy: '2027-04-24',
  lockTarget: '2027-04-28',
  disbursement: '2027-05-01',
  supplementaryFrom: null,
  supplementaryTo: null,
  mdpFrom: '2027-04-28',
  mdpTo: '2027-04-30',
};
const may = {
  ...april,
  month: '2027-05',
  start: '2027-05-01',
  end: '2027-05-31',
  mdpFrom: null,
  mdpTo: null,
};

const STEPS = [
  {
    sequence: 10,
    code: 'STAFF_PAYROLL',
    name: 'Employee payroll',
    dependsOn: null,
    ownerRole: null,
    payrollType: 'STAFF',
    status: 'COMPLETE',
  },
  {
    sequence: 20,
    code: 'DEPT_LABOUR_WAGES',
    name: 'Departmental wages',
    dependsOn: 'STAFF_PAYROLL',
    ownerRole: 'LABOUR_DEALING_HAND',
    payrollType: 'DEPT_LABOUR',
    status: 'RUNNING',
  },
  {
    sequence: 30,
    code: 'LABOUR_INCENTIVE',
    name: 'Labour incentive',
    dependsOn: null,
    ownerRole: null,
    payrollType: null,
    status: 'QUEUED',
  },
];

function routes(periods: unknown[], pending: number | null = null, orderPending: number | null = null) {
  return backend({
    'GET /v1/payroll-calendars': () => ['2027-28'],
    'GET /v1/payroll-calendars/2027-28': () => ({ finYear: '2027-28', periods, pendingApprovalId: pending }),
    'GET /v1/run-order-steps': () => ({ steps: STEPS, pendingApprovalId: orderPending }),
  });
}

const IN_USE = { period: may, initiated: true, cycleStatus: 'COMPUTED', windowOpen: false };
const LOCKED = { period: april, initiated: true, cycleStatus: 'LOCKED', windowOpen: false };

describe('M-10 rows and reasons', () => {
  it('D_12_aPeriodInUseIsOffWithTheInUseReasonAndALockedOneReadsLocked', async () => {
    routes([LOCKED, IN_USE]);
    wrap(<PayrollCalendar />, ['M-10:VIEW', 'M-10:EDIT']);
    const edit = await screen.findByRole('button', { name: t('m10.edit', ['MAY 2027']) }, { timeout: 5000 });
    await waitFor(() => expect(edit).toHaveAttribute('data-why', t('error.CAL-409-IN-USE', ['2027-05'])));
    const table = screen.getByRole('table', { name: t('m10w.calendar', ['2027-28']) });
    // locked with no window open: just LOCKED
    expect(within(table).getByText(t('d01.state.LOCKED'))).toHaveClass('p-info');
    expect(within(table).getByText(t('d01.state.COMPUTED'))).toHaveClass('p-warn');
    // no supplementary window: a dash; compute / verify / disbursement as entered
    const row = within(table).getByText('202704').closest('tr') as HTMLElement;
    expect(within(row).getByText(date('2027-04-22'))).toBeInTheDocument();
    expect(within(row).getByText(date('2027-05-01'))).toBeInTheDocument();
    expect(within(row).getByText('—')).toBeInTheDocument();
  });

  it('SRS_M10_aYearWaitingOnApprovalHoldsEditAndTheApproverIsSentToW01', async () => {
    routes([{ period: may, initiated: false, cycleStatus: null, windowOpen: false }], 77);
    const assign = vi.fn();
    vi.stubGlobal('location', { ...window.location, assign });
    const user = userEvent.setup();
    wrap(<PayrollCalendar />, ['M-10:VIEW', 'M-10:EDIT', 'M-10:APPROVE']);
    const edit = await screen.findByRole('button', { name: t('m10.edit', ['MAY 2027']) }, { timeout: 5000 });
    await waitFor(() => expect(edit).toHaveAttribute('data-why', t('m10.yearPending', [77])));
    // the editor's Maintain names the waiting proposal
    expect(screen.getByRole('button', { name: t('m10w.maintain') })).toHaveAttribute(
      'data-why',
      t('m10.yearPending', [77]),
    );
    const row = screen.getByText('202705').closest('tr') as HTMLElement;
    await user.click(within(row).getByTitle(t('wf.approveTitle')));
    expect(assign).toHaveBeenCalledWith(expect.stringContaining('s=W-01'));
  });

  it('SRS_M10_withNothingProposedTheApproversTickIsOffWithTheReason', async () => {
    routes([{ period: may, initiated: false, cycleStatus: null, windowOpen: false }]);
    wrap(<PayrollCalendar />, ['M-10:VIEW', 'M-10:APPROVE']);
    const row = (await screen.findByText('202705', {}, { timeout: 5000 })).closest('tr') as HTMLElement;
    const tick = within(row).getByTitle(t('m10w.why.noProposal'));
    expect(tick).toBeDisabled();
    // not the Administrator: Edit names the Administrator
    expect(within(row).getByRole('button', { name: t('m10.edit', ['MAY 2027']) })).toHaveAttribute(
      'data-why',
      t('m10w.why.admin'),
    );
  });

  it('SRS_l1637_eachStepShowsItsEngineStateDependencyAndOwner', async () => {
    routes([]);
    wrap(<PayrollCalendar />, ['M-10:VIEW']);
    const table = await screen.findByRole('table', { name: t('m10.runOrder') });
    const staff = (await within(table).findByText('Employee payroll')).closest('tr') as HTMLElement;
    expect(within(staff).getByText(t('m10w.engine.COMPLETE'))).toHaveClass('p-ok');
    expect(within(staff).getAllByText('—')).toHaveLength(2);
    const wages = within(table).getByText('Departmental wages').closest('tr') as HTMLElement;
    expect(within(wages).getByText(t('m10w.engine.RUNNING'))).toHaveClass('p-warn');
    expect(within(wages).getByText('STAFF_PAYROLL')).toBeInTheDocument();
    expect(within(wages).getByText(t('role.LABOUR_DEALING_HAND'))).toBeInTheDocument();
    const incentive = within(table).getByText('Labour incentive').closest('tr') as HTMLElement;
    expect(within(incentive).getByText(t('m10w.engine.QUEUED'))).toHaveClass('p-gray');
    // a year with no period defined says so
    expect(screen.getByText(t('m10w.noPeriods', ['2027-28']))).toBeInTheDocument();
  });

  it('D_13_aWaitingOrderLocksReorderWithItsApprovalAndShowsTheBanner', async () => {
    routes([], null, 92);
    wrap(<PayrollCalendar />, ['M-10:VIEW', 'M-10:REORDER']);
    await screen.findByText('Employee payroll');
    await waitFor(() =>
      expect(screen.getByRole('button', { name: t('m10w.reorder') })).toHaveAttribute(
        'data-why',
        t('m10.orderPending', [92]),
      ),
    );
    expect(screen.getAllByText(t('m10.orderPending', [92])).length).toBeGreaterThanOrEqual(1);
  });

  it('D_13_closingTheReorderDialogDropsTheDraftAndProposesNothing', async () => {
    const calls = routes([]);
    const user = userEvent.setup();
    wrap(<PayrollCalendar />, ['M-10:VIEW', 'M-10:REORDER']);
    await screen.findByText('Employee payroll');
    await user.click(screen.getByRole('button', { name: t('m10w.reorder') }));
    const dialog = screen.getByRole('dialog', { name: t('m10w.reorder') });
    await user.click(within(dialog).getByRole('button', { name: t('m10.down', ['Employee payroll']) }));
    await user.click(within(dialog).getByRole('button', { name: t('common.close') }));
    expect(screen.queryByRole('dialog')).toBeNull();
    // the draft is dropped: the table shows the order as stored
    const table = screen.getByRole('table', { name: t('m10.runOrder') });
    expect(within(table).getAllByRole('row')[1]).toHaveTextContent('Employee payroll');
    expect(sent(calls, 'POST', '/run-order-steps/proposals')).toHaveLength(0);
  });

  it('SRS_M10_theViewOfAFullyDefinedPeriodShowsEveryDateAndCloses', async () => {
    routes([LOCKED]);
    const user = userEvent.setup();
    wrap(<PayrollCalendar />, ['M-10:VIEW']);
    await user.click(
      await screen.findByRole('button', { name: t('m10w.viewOf', ['APR 2027']) }, { timeout: 5000 }),
    );
    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getByText(date('2027-04-24'))).toBeInTheDocument();
    expect(within(dialog).getAllByText(date('2027-04-28')).length).toBeGreaterThanOrEqual(2);
    await user.click(within(dialog).getByRole('button', { name: t('common.close') }));
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('SRS_M10_aPeriodWithoutMdpWindowAndNoSessionPeriodReadDashes', async () => {
    routes([{ period: may, initiated: false, cycleStatus: null, windowOpen: false }]);
    const user = userEvent.setup();
    wrap(<PayrollCalendar />, me(['M-10:VIEW'], { period: undefined as never }));
    await user.click(
      await screen.findByRole('button', { name: t('m10w.viewOf', ['MAY 2027']) }, { timeout: 5000 }),
    );
    const dialog = screen.getByRole('dialog');
    // supplementary, MDP from and MDP to: three dashes
    expect(within(dialog).getAllByText('—')).toHaveLength(3);
  });

  it('D_12_aYearWhoseMonthsAreAllInUseOffersNoMaintain', async () => {
    const months = ['04', '05', '06', '07', '08', '09', '10', '11', '12'].map((m) => `2027-${m}`);
    const all = [...months, '2028-01', '2028-02', '2028-03'].map((month) => ({
      period: { ...april, month },
      initiated: true,
      cycleStatus: 'LOCKED',
      windowOpen: true,
    }));
    routes(all);
    wrap(<PayrollCalendar />, ['M-10:VIEW', 'M-10:EDIT']);
    await screen.findByText('202803', {}, { timeout: 5000 });
    expect(screen.getByRole('button', { name: t('m10w.maintain') })).toBeDisabled();
  });
});
