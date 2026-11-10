import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { InterestRun } from '@/modules/cpf/components/InterestRun';
import type { InterestRunSummary, NotCalculatedMember } from '@/modules/cpf/api/cpfApi';
import { backend, me, problem, sent, t, wrap, paged } from './support/harness';

/**
 * C-12 CPF Interest Run (SRS-6-45, SRS-6-46, SRS C-12 field table): the tiles and run audit list,
 * a run and a re-run for one member, the not-yet-calculated report, the freeze request, and what
 * each role may do.
 */

/** SRS l.2358: "Calculate Interest", or "Re-Run Interest" once the year has a run. */
const RUN_BUTTON = new RegExp(`^(${t('action.runInterest')}|${t('action.reRunInterest')})$`);

afterEach(() => vi.unstubAllGlobals());

const SUMMARY: InterestRunSummary = {
  finYear: '2026-27',
  zone: 'NA11',
  members: 2,
  calculated: 0,
  notYetCalculated: 2,
  reRuns: 1,
  freezeStatus: 'OPEN',
  frozenBy: null,
  frozenAt: null,
  runs: [
    {
      run: 2,
      scope: 'INDIVIDUAL',
      eis: '104882',
      initiatedBy: 'cpf.manager.north@fci-demo.test',
      initiatedAt: '2027-04-05T05:00:00Z',
      members: 1,
      calculated: 0,
      notCalculated: 1,
      stopped: 0,
      reason: 'Re-run for one member',
    },
    {
      run: 1,
      scope: 'ALL_MEMBERS',
      eis: null,
      initiatedBy: 'cpf.manager.north@fci-demo.test',
      initiatedAt: '2027-04-04T05:00:00Z',
      members: 2,
      calculated: 0,
      notCalculated: 2,
      stopped: 1,
      reason: 'First calculation',
    },
  ],
};

const NOT_CALCULATED: NotCalculatedMember[] = [
  {
    eis: '104882',
    name: 'Member One',
    memberStatus: 'ACTIVE',
    reason: 'INTEREST_METHOD_NOT_CONFIGURED',
    interestEndDate: null,
    interestStopped: false,
  },
  {
    eis: '105461',
    name: 'Member Two',
    memberStatus: 'EX_MEMBER',
    reason: 'CPF_CODE_MISSING',
    interestEndDate: '2026-12-31',
    interestStopped: true,
  },
];

const MANAGER = me(['C-12:VIEW', 'C-12:CREATE', 'C-12:SUBMIT'], {
  role: 'CPF_MANAGER',
  location: { code: 'NA11', label: 'ZO — North (NA11)' },
});

function routes(over: Record<string, (url: string, init?: RequestInit) => unknown> = {}) {
  return backend({
    'GET /v1/cpf/interest-runs/not-calculated': () => paged(NOT_CALCULATED),
    'GET /v1/cpf/interest-runs': () => SUMMARY,
    ...over,
  });
}

describe('C-12 CPF interest run', () => {
  it('SRS_6_45_theTilesAndTheRunAuditListOfTheZonesYear', async () => {
    const calls = routes();
    wrap(<InterestRun />, MANAGER);
    expect(await screen.findByText(t('c12.ofMembers', [0, 2]))).toBeInTheDocument();
    expect(screen.getByText(t('c12.zone', ['NA11']))).toBeInTheDocument();
    expect(screen.getByText(t('c12.freeze.OPEN'))).toHaveClass('p-ok');
    expect(screen.getByText(t('c12.methodOpen'))).toBeInTheDocument();
    expect(calls.some((c) => c.url.includes('/v1/cpf/interest-runs?finYear=2026-27'))).toBe(true);
    const rerun = screen.getByText('Re-run for one member').closest('tr') as HTMLElement;
    expect(within(rerun).getByText(t('c12.scope.INDIVIDUAL'))).toBeInTheDocument();
    expect(within(rerun).getByText('104882')).toBeInTheDocument();
    const first = screen.getByText('First calculation').closest('tr') as HTMLElement;
    expect(within(first).getByText(t('c12.runCounts', [0, 2, 1]))).toBeInTheDocument();
  });

  it('SRS_6_45_aReRunForOneMemberIsSentWithItsReason', async () => {
    const calls = routes({
      'POST /v1/cpf/interest-runs': () => ({ status: 201, body: { ...SUMMARY.runs[1], run: 3, members: 1 } }),
    });
    wrap(<InterestRun />, MANAGER);
    await screen.findByText(t('c12.ofMembers', [0, 2]));
    await userEvent.click(screen.getByRole('button', { name: RUN_BUTTON }));
    const runNow = screen.getByRole('button', { name: t('c12.runNow') });
    expect(runNow).toBeDisabled();
    await userEvent.click(screen.getByLabelText(t('c12.scope.INDIVIDUAL')));
    const problems = screen.getByRole('list', { name: t('c12.problems') });
    expect(within(problems).getByText(t('error.CPF-422-RUN-SCOPE'))).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText(t('c12.eis')), '104882');
    await userEvent.type(screen.getByLabelText(t('c12.reason')), 'Corrected opening balance');
    expect(runNow).toBeEnabled();
    await userEvent.click(runNow);
    expect(await screen.findByText(t('c12.ran', [3, 1]))).toBeInTheDocument();
    expect(sent(calls, 'POST', '/v1/cpf/interest-runs')).toEqual([
      { finYear: '2026-27', scope: 'INDIVIDUAL', eis: '104882', reason: 'Corrected opening balance' },
    ]);
  });

  it('SRS_6_45_anAllMembersRunSendsNoEisAndARefusalIsShown', async () => {
    const calls = routes({
      'POST /v1/cpf/interest-runs': () => problem(409, 'CPF-409-RATE-UNDECLARED', ['2026-27', 'RPFC']),
    });
    wrap(<InterestRun />, MANAGER);
    await screen.findByText(t('c12.ofMembers', [0, 2]));
    await userEvent.click(screen.getByRole('button', { name: RUN_BUTTON }));
    await userEvent.click(screen.getByLabelText(t('c12.scope.INDIVIDUAL')));
    await userEvent.type(screen.getByLabelText(t('c12.eis')), '104882');
    await userEvent.click(screen.getByLabelText(t('c12.scope.ALL_MEMBERS')));
    await userEvent.type(screen.getByLabelText(t('c12.reason')), 'Year-end');
    await userEvent.click(screen.getByRole('button', { name: t('c12.runNow') }));
    expect(
      await screen.findByText(t('error.CPF-409-RATE-UNDECLARED', ['2026-27', 'RPFC'])),
    ).toBeInTheDocument();
    expect(sent(calls, 'POST', '/v1/cpf/interest-runs')).toEqual([
      { finYear: '2026-27', scope: 'ALL_MEMBERS', eis: null, reason: 'Year-end' },
    ]);
    await userEvent.click(screen.getByRole('button', { name: t('common.cancel') }));
    expect(screen.queryByRole('button', { name: t('c12.runNow') })).not.toBeInTheDocument();
  });

  it('SRS_6_45_theReportListsEachMemberNotYetCalculatedAndWhy', async () => {
    routes();
    wrap(<InterestRun />, MANAGER);
    await userEvent.click(await screen.findByRole('button', { name: t('c12.showReport') }));
    const report = await screen.findByRole('table', { name: t('c12.report') });
    const two = within(report).getByText('Member Two (105461)').closest('tr') as HTMLElement;
    expect(within(two).getByText(t('c12.reason.CPF_CODE_MISSING'))).toBeInTheDocument();
    expect(within(two).getByText(t('c12.stopped'))).toBeInTheDocument();
    expect(within(two).getByText(t('c12.memberStatus.EX_MEMBER'))).toBeInTheDocument();
    const one = within(report).getByText('Member One (104882)').closest('tr') as HTMLElement;
    expect(within(one).getByText(t('c12.reason.INTEREST_METHOD_NOT_CONFIGURED'))).toBeInTheDocument();
    expect(within(one).getByText('—')).toBeInTheDocument();
  });

  it('SRS_6_45_anEmptyYearSaysSoAndAWrongYearIsNamed', async () => {
    routes({
      'GET /v1/cpf/interest-runs/not-calculated': () => paged([]),
      'GET /v1/cpf/interest-runs': () => ({
        ...SUMMARY,
        runs: [],
        reRuns: 0,
        notYetCalculated: 0,
        members: 0,
      }),
    });
    wrap(<InterestRun />, MANAGER);
    expect(await screen.findByText(t('c12.noRuns'))).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: t('c12.showReport') }));
    expect(await screen.findByText(t('c12.allCalculated'))).toBeInTheDocument();
    const year = screen.getByLabelText(t('c12.finYear'));
    await userEvent.clear(year);
    await userEvent.type(year, '2026-28');
    expect(screen.getByText(t('error.CPF-422-FIN-YEAR'))).toBeInTheDocument();
    expect(screen.getByRole('button', { name: RUN_BUTTON })).toBeDisabled();
    expect(screen.getByRole('button', { name: t('action.freezeInterest') })).toBeDisabled();
    expect(screen.getByRole('button', { name: t('c12.showReport') })).toBeDisabled();
  });

  it('SRS_6_46_theManagerRequestsTheFreezeWithAReason', async () => {
    const calls = routes({
      'POST /v1/cpf/interest-runs/freeze': () => ({
        status: 202,
        body: { approvalId: 9, finYear: '2026-27', zone: 'NA11' },
      }),
    });
    wrap(<InterestRun />, MANAGER);
    await screen.findByText(t('c12.ofMembers', [0, 2]));
    await userEvent.click(screen.getByRole('button', { name: t('action.freezeInterest') }));
    const dialog = screen.getByRole('dialog');
    await userEvent.click(within(dialog).getByRole('button', { name: t('common.cancel') }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: t('action.freezeInterest') }));
    await userEvent.type(within(screen.getByRole('dialog')).getByRole('textbox'), 'All runs complete');
    await userEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: t('action.freezeInterest') }),
    );
    expect(await screen.findByText(t('c12.freezeRequested', ['2026-27']))).toBeInTheDocument();
    expect(sent(calls, 'POST', '/freeze')).toEqual([{ finYear: '2026-27', reason: 'All runs complete' }]);
  });

  it('SRS_6_46_aRefusedFreezeRequestIsShown', async () => {
    routes({ 'POST /v1/cpf/interest-runs/freeze': () => problem(409, 'WF-409-PENDING') });
    wrap(<InterestRun />, MANAGER);
    await screen.findByText(t('c12.ofMembers', [0, 2]));
    await userEvent.click(screen.getByRole('button', { name: t('action.freezeInterest') }));
    await userEvent.type(within(screen.getByRole('dialog')).getByRole('textbox'), 'Year end');
    await userEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: t('action.freezeInterest') }),
    );
    expect(await screen.findByText(t('error.WF-409-PENDING'))).toBeInTheDocument();
  });

  it('SRS_6_46_aPendingFreezeBlocksASecondRequestButNotARun', async () => {
    routes({ 'GET /v1/cpf/interest-runs': () => ({ ...SUMMARY, freezeStatus: 'PENDING' }) });
    wrap(<InterestRun />, MANAGER);
    expect(await screen.findByText(t('c12.freeze.PENDING'))).toHaveClass('p-warn');
    expect(screen.getByRole('button', { name: t('action.freezeInterest') })).toHaveAttribute(
      'title',
      t('c12.freezePending'),
    );
    expect(screen.getByRole('button', { name: RUN_BUTTON })).toBeEnabled();
  });

  it('SRS_6_46_afterTheFreezeNothingRunsAndTheFreezeIsShown', async () => {
    routes({
      'GET /v1/cpf/interest-runs': () => ({
        ...SUMMARY,
        freezeStatus: 'FROZEN',
        frozenBy: 'agm.cpf.north@fci-demo.test',
        frozenAt: '2027-04-10T05:00:00Z',
      }),
    });
    wrap(<InterestRun />, MANAGER);
    expect(await screen.findByText(t('c12.freeze.FROZEN'))).toBeInTheDocument();
    expect(screen.getByText(/agm\.cpf\.north@fci-demo\.test/)).toBeInTheDocument();
    for (const action of ['action.runInterest', 'action.freezeInterest']) {
      expect(
        screen.getByRole('button', { name: action === 'action.runInterest' ? RUN_BUTTON : t(action) }),
      ).toHaveAttribute('title', t('error.CPF-409-INTEREST-FROZEN'));
    }
  });

  it('SRS_22_21_theCpfDivisionAndTheAgmOnlyRead', async () => {
    routes();
    wrap(<InterestRun />, me(['C-12:VIEW'], { role: 'CPF_DIVISION' }));
    await screen.findByText(t('c12.ofMembers', [0, 2]));
    expect(screen.queryByRole('button', { name: RUN_BUTTON })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: t('action.freezeInterest') })).not.toBeInTheDocument();
  });

  it('SRS_6_45_anUnknownYearAnswerIsShown', async () => {
    routes({ 'GET /v1/cpf/interest-runs': () => problem(403, 'AUTHZ-403-PERMISSION', ['C-12', 'VIEW']) });
    wrap(<InterestRun />, MANAGER);
    await waitFor(() =>
      expect(screen.getByText(t('error.AUTHZ-403-PERMISSION', ['C-12', 'VIEW']))).toBeInTheDocument(),
    );
  });
});
