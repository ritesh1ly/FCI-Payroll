import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  BatchJobs,
  CycleControl,
  CycleStatusHome,
  DirectPayroll,
  PayrollCalendar,
  PayrollCycle,
  ReleaseSuspensions,
  SimulationRun,
} from '@/modules/jobs';
import { jobsApi } from '@/modules/jobs/api/jobsApi';
import type { Halt, Job, JobDef, JobDetail } from '@/modules/jobs/api/types';
import { CycleParameters } from '@/modules/jobs/components/CycleParameters';
import { JobDetailView } from '@/modules/jobs/components/JobDetailView';
import { JobHistory } from '@/modules/jobs/components/JobHistory';
import { SubmitJobForm } from '@/modules/jobs/components/SubmitJobForm';
import { useCycleAction } from '@/modules/jobs/orchestration/cycleQueries';
import { periodLabel } from '@/shared/session';
import { backend, currentPeriod, me, paged, problem, referenceLists, sent, t, wrap } from './support/harness';

vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn(), replace: vi.fn() }) }));

/**
 * Edge cases of the payroll cycle and batch screens (SRS-15-7, SRS-15-11, SRS-15-14, SRS-15-16,
 * SRS-15-17, SRS-15-19, SRS-15-25, SRS-15-30, SRS-15-33, SRS-15-35, SRS-15-39, SRS-NF-7): a cycle
 * not initiated yet, the G5 members named or not, a register entry without a period, a quarter left
 * unchosen, the halt release outside Headquarters, an audit entry with a status only before, a
 * suspension of every unit, a back-date authority without OTP, optional calendar dates given, a
 * return to an office not named, a result without a pay pattern, a run still going, a selection
 * limit not configured, a job list the server refuses.
 */

afterEach(() => vi.unstubAllGlobals());

const gate = (code: string, met: boolean, over: object = {}) => ({
  code,
  met,
  overridable: !['G0', 'G1', 'G3', 'G4'].includes(code),
  acceptable: false,
  detail: `${code} detail`,
  acceptedBy: null,
  acceptedAt: null,
  acceptedReason: null,
  ...over,
});

const GATES = {
  cycleId: 7,
  status: 'VERIFIED',
  gates: [gate('G0', true), gate('G5', false, { acceptable: true })],
  checks: [],
  blocking: ['G5'],
  lockable: false,
};

const REGISTER_NO_PERIOD = {
  id: 8,
  type: 'UNDO',
  period: null,
  entity: 'mdp_transaction',
  key: '42',
  stage: null,
  reason: 'Entered twice',
  requestedBy: 'maker.delhi',
  checkedBy: null,
  authorisedBy: 'sysadmin',
  approvalId: 91,
  executedAt: '2026-10-20T10:00:00Z',
};

const CONTROL_OVERVIEW = {
  period: '2026-10',
  finYear: '2026-27',
  unitCode: 'DH001',
  unitLabel: 'Delhi',
  unitLevel: 'DO',
  cycleId: null,
  status: null,
  frozenBy: null,
  frozenAt: null,
  postedToEbs: false,
  supplementaryOpen: false,
  tiles: {
    disbursement: null,
    daysToDisbursement: null,
    engines: {},
    blockingExceptions: 0,
    verifiedBy: null,
    verifiedAt: null,
  },
  preRun: [],
  run: null,
  lease: null,
  levels: [],
  fallback: [],
};

function controlRoutes(gates: () => unknown, post: () => unknown = () => GATES) {
  return backend({
    'GET /v1/tax/flagged-tds-cases?': (url) => paged([], 0, url),
    'GET /v1/cycles/2026-10/overview': () => CONTROL_OVERVIEW,
    'GET /v1/cycles/period-register': (url) => paged([], 0, url),
    'GET /v1/cycles/2026-10/orders': () => [],
    'GET /v1/document-types': () => [],
    'GET /v1/cycles/2026-10/gates': gates,
    'GET /v1/cycles/2026-10/quarantine': () => paged([]),
    'GET /v1/cycle-register-entries?': (url) => paged([REGISTER_NO_PERIOD], 1, url),
    'POST /v1/cycles/2026-10/': post,
  });
}

describe('C-01 Payroll Cycle Control edges', () => {
  it('SRS-15-14: a cycle not initiated yet is an empty state; any other refusal is shown as such', async () => {
    controlRoutes(() => problem(404, 'CYC-404-NONE'));
    const first = wrap(<CycleControl />, ['C-01:VIEW']);
    expect(await screen.findByText(t('c01.noCycle', [periodLabel('2026-10')]))).toBeTruthy();
    first.unmount();
    controlRoutes(() => problem(500, 'COM-500-INTERNAL'));
    wrap(<CycleControl />, ['C-01:VIEW']);
    expect(await screen.findByRole('alert')).toBeTruthy();
    expect(screen.queryByText(t('c01.noCycle', [periodLabel('2026-10')]))).toBeNull();
  });

  it('SRS-15-16 (SRS l.1524): G5 is accepted with the members named, or with none', async () => {
    const calls = controlRoutes(() => GATES);
    wrap(<CycleControl />, ['C-01:VIEW', 'C-01:APPROVE']);
    await userEvent.click(await screen.findByRole('button', { name: t('action.acceptGate') }));
    let dialog = screen.getByRole('dialog');
    expect(within(dialog).getByText(t('c01.g5MembersHint'))).toBeTruthy();
    await userEvent.type(within(dialog).getByLabelText(t('c01.g5Members')), 'E101, E102  E103');
    await userEvent.type(within(dialog).getAllByRole('textbox').at(-1) as HTMLElement, 'Excluded by order');
    await userEvent.click(within(dialog).getAllByRole('button').at(-1) as HTMLElement);
    await screen.findByText(t('c01.accepted', ['G5']));
    expect(sent(calls, 'POST', '/gates/G5/accept')[0]).toEqual({
      reason: 'Excluded by order',
      members: ['E101', 'E102', 'E103'],
    });

    await userEvent.click(screen.getByRole('button', { name: t('action.acceptGate') }));
    dialog = screen.getByRole('dialog');
    await userEvent.type(within(dialog).getAllByRole('textbox').at(-1) as HTMLElement, 'No member excluded');
    await userEvent.click(within(dialog).getAllByRole('button').at(-1) as HTMLElement);
    await waitFor(() => expect(sent(calls, 'POST', '/gates/G5/accept')).toHaveLength(2));
    expect(sent(calls, 'POST', '/gates/G5/accept')[1]).toEqual({ reason: 'No member excluded' });
  });

  it('SRS-15-17: a register entry without a period shows a dash', async () => {
    controlRoutes(() => GATES);
    wrap(<CycleControl />, ['C-01:VIEW']);
    const row = (await screen.findByText('Entered twice')).closest('tr') as HTMLElement;
    expect(within(row).getAllByText('—').length).toBeGreaterThanOrEqual(2);
  });

  it('SRS-15-14: a session without a current period asks nothing for a period', async () => {
    const calls = controlRoutes(() => GATES);
    wrap(<CycleControl />, me(['C-01:VIEW'], { period: undefined as unknown as string }));
    expect(await screen.findByRole('button', { name: t('c01w.run') })).toBeTruthy();
    expect(calls.some((c) => c.url.includes('/v1/cycles/2026-10/gates'))).toBe(false);
  });
});

const partB: JobDef = {
  type: 'TAX_FORM16_PART_B',
  params: [
    { name: 'locationId', kind: 'LOCATION', required: true },
    { name: 'finYear', kind: 'FIN_YEAR', required: true },
  ],
};
const quarterly: JobDef = {
  type: 'TAX_24Q_RETURN',
  params: [...partB.params, { name: 'quarter', kind: 'QUARTER', required: true }],
};

describe('C-13 submission, halt, detail and history edges', () => {
  it('SRS-NF-7: a quarter left unchosen is flagged on its field', async () => {
    backend({
      'GET /v1/jobs/types': () => ({
        types: [{ type: 'PAYROLL_DRY_RUN', params: [] }, quarterly],
        currentPeriod: '2026-10',
        locations: [{ id: 3, code: 'DH001', label: 'DO - Delhi HQ (DH001)' }],
      }),
      'GET /v1/reference/lists': () => referenceLists({}),
    });
    wrap(<SubmitJobForm onSubmitted={vi.fn()} />, ['C-13:VIEW', 'C-13:CREATE']);
    await userEvent.selectOptions(await screen.findByLabelText(t('c13.jobType')), 'TAX_24Q_RETURN');
    const quarter = document.querySelector('#job-quarter') as HTMLSelectElement;
    expect(quarter.value).toBe('');
    expect(quarter).not.toHaveAttribute('aria-describedby');
    await userEvent.type(screen.getByLabelText(/Reason/), 'Quarter return');
    await userEvent.click(screen.getByRole('button', { name: t('c13.submit.review') }));
    await waitFor(() => expect(quarter).toHaveAttribute('aria-describedby', 'job-quarter-err'));
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  const HALTED: Halt = {
    engaged: true,
    haltId: 5,
    haltedBy: 'sysadmin@fci-demo.test',
    haltedAt: '2026-10-24T06:00:00Z',
    reason: 'Wrong DA rate activated',
    releaseApprovalId: null,
    releaseRequestedBy: null,
    releaseReason: null,
  };

  it('OD-02: outside Headquarters the halt release is disabled with the reason', async () => {
    backend({
      'GET /v1/jobs/halt': () => HALTED,
      'GET /v1/jobs?': (url) => paged([], 0, url),
      'GET /v1/reference/lists': () => referenceLists({}),
    });
    wrap(<BatchJobs />, me(['C-13:VIEW', 'C-13:HALT'], { atHeadquarters: false }));
    const release = await screen.findByRole('button', { name: t('c13.halt.release') });
    expect(release).toBeDisabled();
    expect(release).toHaveAttribute('title', t('error.AUTHZ-403-HQ-ONLY'));
  });

  const job = (over: Partial<Job> = {}): Job => ({
    id: 12,
    jobType: 'PAYROLL_DRY_RUN',
    params: { locationId: '3', payPeriod: '2026-07' },
    priority: 5,
    status: 'COMPLETED',
    source: 'UI',
    requestedBy: 'maker.delhi@fci-demo.test',
    locationId: 3,
    locationLabel: 'DO — Delhi HQ (DH001)',
    payPeriod: '2026-07',
    requestedAt: '2026-07-15T10:00:00Z',
    startedAt: '2026-07-15T10:00:05Z',
    finishedAt: '2026-07-15T10:01:10Z',
    durationSeconds: 65,
    attemptNo: 1,
    jobExecutionId: 4,
    approvalId: null,
    canDecide: false,
    readCount: 10,
    writeCount: 10,
    skipCount: 0,
    resultSummary: null,
    errorMessage: null,
    pendingCommand: null,
    allowedCommands: [],
    ...over,
  });

  it('SRS-15-25: an audit entry with a status only before its change shows a dash after it', async () => {
    const detail: JobDetail = {
      job: job(),
      attempts: [],
      controls: [],
      audit: [
        {
          id: 3,
          event: 'HALTED',
          actor: 'node-1',
          at: '2026-07-15T10:00:30Z',
          fromStatus: 'RUNNING',
          toStatus: null,
          jobExecutionId: 4,
          detail: null,
        },
      ],
      skippedTotal: 0,
    };
    backend({ 'GET /v1/jobs/12': () => detail, 'GET /v1/reference/lists': () => referenceLists({}) });
    wrap(
      <JobDetailView
        id={12}
        access={{ view: true, submit: true, control: true, halt: false }}
        onBack={vi.fn()}
      />,
      ['C-13:VIEW'],
    );
    expect(await screen.findByText('RUNNING → —')).toBeTruthy();
  });

  it('SRS-NF-2: a job list the server refuses is shown, with no rows and no empty-list message', async () => {
    backend({
      'GET /v1/jobs?': () => problem(500, 'COM-500-INTERNAL'),
      'GET /v1/reference/lists': () => referenceLists({}),
    });
    wrap(<JobHistory onOpen={vi.fn()} />, ['C-13:VIEW']);
    expect(await screen.findByRole('alert')).toBeTruthy();
    expect(screen.queryByText(t('c13.history.none'))).toBeNull();
    expect(screen.getByRole('table').querySelectorAll('tbody tr')).toHaveLength(0);
  });

  it('SRS-15-26: the Checker decides with a reason, or with none', async () => {
    const calls = backend({ 'POST /v1/approvals/': () => ({}) });
    await jobsApi.decide(5, 'APPROVE', 'Checked the period');
    await jobsApi.decide(6, 'APPROVE', '');
    await jobsApi.decide(7, 'REJECT', 'Wrong unit', { challengeId: 3, code: '123456' });
    expect(sent(calls, 'POST', '/v1/approvals/5/decision')).toEqual([
      { decision: 'APPROVE', reason: 'Checked the period' },
    ]);
    expect(sent(calls, 'POST', '/v1/approvals/6/decision')).toEqual([{ decision: 'APPROVE' }]);
    expect(sent(calls, 'POST', '/v1/approvals/7/decision')).toEqual([
      { decision: 'REJECT', reason: 'Wrong unit', otp: { challengeId: 3, code: '123456' } },
    ]);
  });
});

describe('C-02 Payroll Cycle edges', () => {
  const board = (over: object = {}) => ({
    period: '2026-10',
    unitCode: 'DH001',
    unitLabel: 'DO Delhi',
    unitLevel: 'DO',
    inCalendar: true,
    computeBy: '2026-10-24',
    lockTarget: '2026-10-28',
    id: 9,
    status: 'COMPUTED',
    initiatedBy: 'maker.delhi@fci-demo.test',
    initiatedAt: '2026-10-19T09:00:00Z',
    frozenBy: 'maker.delhi@fci-demo.test',
    frozenAt: '2026-10-19T10:00:00Z',
    verifiedBy: null,
    verifiedAt: null,
    lockedBy: null,
    lockedAt: null,
    currentRunId: 31,
    runs: [],
    resultsRunId: 31,
    resultsRunType: 'ACTUAL',
    totals: { members: 1, gross: 50000, deductions: 8000, net: 42000, components: [] },
    lockApprovalId: null,
    lockApprovalStatus: null,
    postedToEbs: false,
    supplementaryOpen: false,
    lease: null,
    leaseReleaseApprovalId: null,
    openReturn: null,
    unfreezeApprovalId: null,
    ...over,
  });

  function cycleRoutes(view: unknown) {
    return backend({
      'GET /v1/cycles/2026-10': () => view,
      'GET /v1/cycles/2026-10/pre-run-checks': () => [],
      'GET /v1/cycles/2026-10/results-period': () => ({ status: 204, body: null }),
      'GET /v1/cycles/2026-10/pli-payments': (url) => paged([], 0, url),
      'GET /v1/cycles/2026-10/results/1/trace': () => ({ status: 404, body: {} }),
      'GET /v1/cycles/2026-10/results/summary': () => ({
        ida: { members: 0, exceptions: 0 },
        cda: { members: 0, exceptions: 0 },
        gross: 0,
        deductions: 0,
        net: 0,
        held: 0,
        shown: { members: 0, gross: 0, deductions: 0, net: 0 },
      }),
      'GET /v1/cycles/2026-10/results?': (url) =>
        paged(
          [
            {
              resultId: 1,
              eis: '100001',
              name: 'Employee 1',
              gross: 50000,
              deductions: 8000,
              net: 42000,
              status: 'COMPUTED',
              locked: false,
              payPattern: null,
              basic: 30000,
              da: 15000,
              cityClass: 'X',
              scale: null,
              hra: 1000,
              other: 4000,
              rowStatus: 'COMPUTED',
              statusGroup: 'COMPUTED',
            },
          ],
          1,
          url,
        ),
    });
  }

  it('C-02 l.3686-3692: a result without a pay pattern or scale shows a dash', async () => {
    cycleRoutes(board());
    wrap(<PayrollCycle />, ['C-02:VIEW']);
    const row = (await screen.findByText('Employee 1')).closest('tr') as HTMLElement;
    expect(within(row).getByText('—')).toBeTruthy();
  });

  it('SRS-15-30: a return to an office not named still says it returned an office', async () => {
    cycleRoutes(
      board({
        status: 'FROZEN',
        resultsRunId: null,
        currentRunId: null,
        openReturn: {
          id: 12,
          scope: 'ORG_UNIT',
          orgUnitCode: null,
          members: [],
          fromStatus: 'VERIFIED',
          reason: 'Attendance not matched',
          returnedBy: 'ddo.delhi@fci-demo.test',
          returnedAt: '2026-10-21T09:00:00Z',
          runId: 31,
        },
      }),
    );
    wrap(<PayrollCycle />, me(['C-02:VIEW'], { atHeadquarters: false }));
    expect((await screen.findByTestId('cycle-return')).textContent).toContain(t('cyc.returnedOffice', ['']));
  });

  it('SRS-15-11: a session without a current period asks for no cycle', async () => {
    const calls = backend({
      'GET /v1/cycles/2026-10': () => board(),
      'GET /v1/cycles/': () => problem(404, 'CYC-404-NONE'),
    });
    wrap(<PayrollCycle />, me(['C-02:VIEW'], { period: undefined as unknown as string }));
    await waitFor(() => expect(calls.length).toBeGreaterThan(0));
    expect(calls.some((c) => c.url.includes('/v1/cycles/2026-10'))).toBe(false);
  });

  function Withdraw() {
    const action = useCycleAction('2026-10');
    const [done, setDone] = useState(false);
    return (
      <button
        type="button"
        onClick={() =>
          void action.mutateAsync({ action: 'withdraw', reason: 'Raised in error' }).then(() => setDone(true))
        }
      >
        {done ? 'withdrawn' : 'withdraw'}
      </button>
    );
  }

  it('SRS-15-30: a withdrawal without a return named goes to return 0, which the server refuses', async () => {
    const calls = backend({ 'POST /v1/cycles/2026-10/returns/': () => board() });
    wrap(<Withdraw />, ['C-02:VIEW', 'C-02:RETURN']);
    await userEvent.click(screen.getByRole('button', { name: 'withdraw' }));
    expect(await screen.findByRole('button', { name: 'withdrawn' })).toBeTruthy();
    expect(sent(calls, 'POST', '/returns/0/withdraw')).toEqual([{ reason: 'Raised in error' }]);
  });
});

describe('I-19, M-10, O-02, D-01 and C-09 edges', () => {
  it('SRS-G-37: an amendment opens with the heads unavailable, its head kept as it was', async () => {
    backend({
      'GET /v1/mdp-transactions/heads': () => problem(500, 'COM-500-INTERNAL'),
      'GET /v1/mdp-transactions/open-periods': () => ['2026-07'],
      'GET /v1/mdp-transactions/summary': () => problem(500, 'COM-500-INTERNAL'),
      'GET /v1/mdp-transactions?': (url) =>
        paged(
          [
            {
              id: 41,
              reference: 'MDP-202607-41',
              period: '2026-07',
              eis: '100041',
              member: 'Member 41',
              payCode: 901,
              head: 'Professional Tax',
              amount: 200,
              status: 'APPROVED',
              reason: 'PT short-deducted',
              enteredBy: 'maker.chennai',
              enteredAt: '2026-10-20T10:00:00Z',
              checkedBy: 'ddo.chennai',
              decidedAt: '2026-10-21T10:00:00Z',
              pendingApprovalId: null,
            },
          ],
          1,
          url,
        ),
    });
    wrap(<DirectPayroll />, ['I-19:VIEW', 'I-19:CREATE', 'I-19:EDIT']);
    // As drawn (OD-121): ✎ Edit of the row amends the entry.
    await userEvent.click(await screen.findByRole('button', { name: `${t('common.edit')} MDP-202607-41` }));
    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getByText(t('i19.amendTitle', ['MDP-202607-41']))).toBeTruthy();
    const head = dialog.querySelector('select[name="payCode"]') as HTMLSelectElement;
    expect(head.options).toHaveLength(0);
    expect(head).toBeDisabled();
    expect((dialog.querySelector('input[name="amount"]') as HTMLInputElement).value).toBe('200');
  });

  it('SRS M-10: the optional dates of a period are proposed as given', async () => {
    const may = {
      month: '2027-05',
      start: '2027-05-01',
      end: '2027-05-31',
      freezeTarget: '2027-05-20',
      computeBy: '2027-05-22',
      verifyBy: '2027-05-24',
      lockTarget: '2027-05-28',
      disbursement: '2027-05-31',
      supplementaryFrom: '2027-05-28',
      supplementaryTo: '2027-05-31',
      mdpFrom: '2027-05-28',
      mdpTo: '2027-05-31',
    };
    const calls = backend({
      'GET /v1/payroll-calendars': () => ['2027-28'],
      'GET /v1/payroll-calendars/2027-28': () => ({
        finYear: '2027-28',
        periods: [{ period: may, initiated: false }],
        pendingApprovalId: null,
      }),
      'GET /v1/run-order-steps': () => ({ steps: [], pendingApprovalId: null }),
      'GET /v1/cycle-parameters': () => ({
        authorities: [],
        mdpHeads: [],
        backDateWindowMonths: null,
        staffNetPayFloor: null,
        checks: [],
      }),
      'POST /v1/payroll-calendars/2027-28/proposals': () => ({ status: 202, body: { approvalId: 92 } }),
    });
    wrap(<PayrollCalendar />, ['M-10:VIEW', 'M-10:EDIT', 'M-10:REORDER']);
    await userEvent.click(
      await screen.findByRole('button', { name: t('m10.edit', ['MAY 2027']) }, { timeout: 5000 }),
    );
    const dialog = screen.getByRole('dialog');
    await userEvent.type(within(dialog).getByLabelText(t('common.reason')), 'Dates confirmed');
    await userEvent.click(within(dialog).getByRole('button', { name: t('m10.submit') }));
    expect(await screen.findByText(t('m10.proposed'))).toBeTruthy();
    expect(sent(calls, 'POST', '/proposals')).toEqual([{ periods: [may], reason: 'Dates confirmed' }]);
  });

  it('SRS-15-35: a suspension of every unit names no unit', async () => {
    const calls = backend({
      'GET /v1/release-suspensions?': (url) => paged([], 0, url),
      'POST /v1/release-suspensions': () => ({ status: 201, body: { id: 6 } }),
    });
    wrap(<ReleaseSuspensions />, me(['O-02:VIEW', 'O-02:SUSPEND', 'O-02:RESUME'], { atHeadquarters: true }));
    await userEvent.click(await screen.findByRole('button', { name: t('o02.suspend') }));
    const dialog = screen.getByRole('dialog');
    await userEvent.type(within(dialog).getByLabelText(t('common.reason')), 'Bank file mismatch');
    await userEvent.click(within(dialog).getByRole('button', { name: t('o02.suspend') }));
    expect(await screen.findByText(t('o02.suspendedDone'))).toBeTruthy();
    expect(sent(calls, 'POST', '/release-suspensions')).toEqual([
      { scope: 'ALL_UNITS', channel: 'ALL', reason: 'Bank file mismatch' },
    ]);
  });

  it('SRS-15-39: an undo authority without the OTP is listed without it', async () => {
    backend({
      'GET /v1/cycle-parameters': () => ({
        authorities: [
          {
            action: 'C02_UNDO',
            level: 1,
            levelName: 'MAKER',
            role: 'PAYROLL_MAKER',
            office: 'HQ',
            otp: false,
          },
        ],
        mdpHeads: [],
        backDateWindowMonths: null,
        staffNetPayFloor: null,
        checks: [],
      }),
    });
    wrap(<CycleParameters />, me(['M-10:VIEW']));
    const undo = await screen.findByText(/1\. PAYROLL_MAKER/);
    expect(undo.textContent).not.toContain(t('m10.params.otp'));
  });

  it('SRS l.1604, SRS-15-31: a state the server did not count is taken as none', async () => {
    backend({
      'GET /v1/cycle-status': () => ({
        period: currentPeriod(),
        scopeCode: 'DH001',
        scopeLabel: 'Delhi',
        rollUp: false,
        units: 1,
        byStatus: { FROZEN: 1 },
        kpis: {
          payable: 0,
          gross: 0,
          deductions: 0,
          net: 0,
          exceptions: { count: 0, unavailable: null },
          pendingApproval: { count: 0, unavailable: null },
          retroEvents: { count: 0, unavailable: null },
          interfaceHealth: { count: 0, unavailable: null },
        },
        types: [],
        blockers: [],
      }),
    });
    wrap(<CycleStatusHome />, me(['D-01:VIEW', 'C-02:CREATE']));
    expect(await screen.findByText(periodLabel(currentPeriod()))).toBeTruthy();
    const offered =
      screen.queryByRole('link', { name: t('d01.unfreeze') }) ??
      screen.queryByRole('button', { name: t('d01.unfreeze') });
    expect(offered).toBeTruthy();
    expect(offered?.getAttribute('title')).not.toBe(t('d01.why.unfreeze'));
  });

  it('SRS l.1604: a unit with no cycle state counted offers no un-freeze', async () => {
    backend({
      'GET /v1/cycle-status': () => ({
        period: currentPeriod(),
        scopeCode: 'DH001',
        scopeLabel: 'Delhi',
        rollUp: false,
        units: 1,
        byStatus: {},
        kpis: {
          payable: 0,
          gross: 0,
          deductions: 0,
          net: 0,
          exceptions: { count: 0, unavailable: null },
          pendingApproval: { count: 0, unavailable: null },
          retroEvents: { count: 0, unavailable: null },
          interfaceHealth: { count: 0, unavailable: null },
        },
        types: [],
        blockers: [],
      }),
    });
    wrap(<CycleStatusHome />, me(['D-01:VIEW', 'C-02:CREATE']));
    expect(await screen.findByText(periodLabel(currentPeriod()))).toBeTruthy();
    expect(screen.queryByRole('link', { name: t('d01.unfreeze') })).toBeNull();
  });

  const tiles = (over: object = {}) => ({
    period: '2026-10',
    unitCode: 'DH001',
    unitLabel: 'Delhi',
    maxSelection: 1000,
    interactiveLimit: 5,
    runsThisCycle: 0,
    mineThisCycle: 0,
    largestThisCycle: 0,
    retained: 0,
    ...over,
  });

  it('SRS-15-7: while a run is going on the form says so and runs nothing more', async () => {
    let release: () => void = () => undefined;
    const held = new Promise<void>((resolve) => {
      release = resolve;
    });
    backend({
      'GET /v1/simulations/tiles': () => tiles(),
      'GET /v1/simulations?': (url) => paged([], 0, url),
      'POST /v1/simulations': () => ({ status: 500, body: { errorCode: 'COM-500-INTERNAL' } }),
    });
    const base = globalThis.fetch;
    vi.stubGlobal('fetch', async (url: string, init?: RequestInit) => {
      if (init?.method === 'POST' && url.includes('/v1/simulations')) await held;
      return base(url, init);
    });
    wrap(<SimulationRun />, me(['C-09:VIEW', 'C-09:RUN']));
    await userEvent.click(await screen.findByRole('button', { name: t('c09w.request') }));
    const form = await screen.findByRole('dialog', { name: t('c09.new') });
    const run = within(form).getByRole('button', { name: t('c09.run') });
    await waitFor(() => expect(run).toBeEnabled());
    await userEvent.click(run);
    await waitFor(() => expect(run).toHaveAttribute('title', t('c09.running')));
    expect(run).toBeDisabled();
    release();
    await waitFor(() => expect(run).not.toHaveAttribute('title', t('c09.running')));
  });

  it('SRS-15-7: a member list while the selection limit is not configured takes one member at most', async () => {
    backend({
      'GET /v1/simulations/tiles': () => tiles({ maxSelection: null }),
      'GET /v1/simulations?': (url) => paged([], 0, url),
    });
    wrap(<SimulationRun />, me(['C-09:VIEW', 'C-09:RUN']));
    await userEvent.click(await screen.findByRole('button', { name: t('c09w.request') }));
    const form = await screen.findByRole('dialog', { name: t('c09.new') });
    await waitFor(() =>
      expect(within(form).getByRole('button', { name: t('c09.run') })).toHaveAttribute(
        'title',
        t('c09.notConfigured'),
      ),
    );
    await userEvent.selectOptions(within(form).getByLabelText(t('c09.population')), 'MEMBER_LIST');
    expect(within(form).getByLabelText(t('c09.members'))).toHaveAttribute('maxLength', '21');
  });
});
