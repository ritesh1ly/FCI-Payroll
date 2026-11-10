import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { DirectPayroll, PayrollCalendar, ReleaseSuspensions } from '@/modules/jobs';
import type { Halt, Job } from '@/modules/jobs/api/types';
import { BatchJobs } from '@/modules/jobs/components/BatchJobs';
import { JobActions } from '@/modules/jobs/components/JobActions';
import { JobApproval } from '@/modules/jobs/components/JobApproval';
import { JobHistory } from '@/modules/jobs/components/JobHistory';
import { JobStatusBadge } from '@/modules/jobs/components/JobStatusBadge';
import { backend, me, paged, problem, referenceLists, sent, t, wrap } from './support/harness';

vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn(), replace: vi.fn() }) }));

/**
 * The paths of the module 05 screens the happy-path tests leave out (plan 05): every dialog closed
 * without a change, a refusal shown, the M-10 year and run order moved, a pending run order, the
 * C-13 history filtered and paged, a halt refused, the release asked, a command refused and an
 * unknown job status.
 */

afterEach(() => vi.unstubAllGlobals());

const REFERENCE = referenceLists({
  JOB_STATUS: [
    ['RUNNING', 'Running'],
    ['FAILED', 'Failed'],
  ],
  JOB_TYPE: [['PAYROLL_DRY_RUN', 'Payroll dry run']],
});

const job = (over: Partial<Job> = {}): Job => ({
  id: 12,
  jobType: 'PAYROLL_DRY_RUN',
  params: { locationId: '3', payPeriod: '2026-07' },
  priority: 5,
  status: 'RUNNING',
  source: 'UI',
  requestedBy: 'maker.delhi@fci-demo.test',
  locationId: 3,
  locationLabel: 'DO — Delhi HQ (DH001)',
  payPeriod: '2026-07',
  requestedAt: '2026-07-15T10:00:00Z',
  startedAt: '2026-07-15T10:00:05Z',
  finishedAt: null,
  durationSeconds: 65,
  attemptNo: 1,
  jobExecutionId: 4,
  approvalId: null,
  canDecide: false,
  readCount: 10,
  writeCount: 8,
  skipCount: 1,
  resultSummary: null,
  errorMessage: null,
  pendingCommand: null,
  allowedCommands: ['STOP', 'PAUSE'],
  ...over,
});

describe('I-19, M-10 and O-02 dialogs and refusals', () => {
  it('SRS-15-19: every I-19 dialog closes without a change', async () => {
    const entry = {
      id: 41,
      reference: 'MDP-202607-41',
      period: '2026-07',
      eis: '100041',
      member: 'Member 41',
      payCode: 901,
      head: 'Professional Tax',
      amount: 200,
      status: 'APPROVED',
      reason: 'PT',
      enteredBy: 'maker.chennai',
      enteredAt: '2026-10-20T10:00:00Z',
      checkedBy: 'ddo.chennai',
      decidedAt: '2026-10-21T10:00:00Z',
      pendingApprovalId: null,
    };
    const calls = backend({
      'GET /v1/mdp-transactions/heads': () => [{ payCode: 901, name: 'Professional Tax' }],
      'GET /v1/mdp-transactions/open-periods': () => ['2026-07'],
      'GET /v1/mdp-transactions/summary': () => ({
        period: '2026-07',
        windowOpen: true,
        lockedAt: '2026-08-01T05:00:00Z',
        approved: 1,
        pending: 0,
        heads: 1,
        value: 200,
        approvedValue: 200,
      }),
      'GET /v1/mdp-transactions?': (url) => paged([entry], 1, url),
    });
    wrap(<DirectPayroll />, ['I-19:VIEW', 'I-19:CREATE', 'I-19:EDIT', 'I-19:CANCEL']);
    await waitFor(() => expect(screen.getByRole('button', { name: t('i19.newEntry') })).toBeEnabled());
    await userEvent.click(screen.getByRole('button', { name: t('i19.newEntry') }));
    await userEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: t('common.cancel') }),
    );
    await userEvent.click(await screen.findByRole('button', { name: `${t('common.edit')} MDP-202607-41` }));
    await userEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: t('common.cancel') }),
    );
    await userEvent.click(screen.getByRole('button', { name: `${t('common.view')} MDP-202607-41` }));
    await userEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: t('i19.cancelEntry') }),
    );
    await userEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: t('common.cancel') }),
    );
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(calls.filter((c) => c.method === 'POST')).toEqual([]);
  });

  it('SRS-15-33: another year is read; the run order moved down and up, proposed with a refusal, or kept', async () => {
    const steps = [
      { sequence: 10, code: 'A', name: 'Step A', dependsOn: null, ownerRole: null, payrollType: null },
      {
        sequence: 20,
        code: 'B',
        name: 'Step B',
        dependsOn: 'A',
        ownerRole: 'PAYROLL_MAKER',
        payrollType: 'STAFF',
      },
    ];
    const calls = backend({
      'GET /v1/payroll-calendars': () => ['2027-28', '2026-27'],
      'GET /v1/payroll-calendars/2027-28': () => ({
        finYear: '2027-28',
        periods: [],
        pendingApprovalId: null,
      }),
      'GET /v1/payroll-calendars/2026-27': () => ({
        finYear: '2026-27',
        periods: [],
        pendingApprovalId: null,
      }),
      'GET /v1/run-order-steps': () => ({ steps, pendingApprovalId: null }),
      'POST /v1/run-order-steps/proposals': () => problem(422, 'CAL-422-ORDER', ['B', 'BEFORE_DEPENDENCY']),
    });
    wrap(<PayrollCalendar />, ['M-10:VIEW', 'M-10:REORDER']);
    await userEvent.click(await screen.findByRole('button', { name: t('m10w.fyCalendar') }));
    await screen.findByRole('option', { name: '2026-27' });
    await userEvent.selectOptions(screen.getByLabelText(t('m10.finYear')), '2026-27');
    await waitFor(() =>
      expect(calls.some((c) => c.url.endsWith('/v1/payroll-calendars/2026-27'))).toBe(true),
    );
    expect(screen.getAllByText('—').length).toBeGreaterThan(0);
    await userEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: t('common.close') }),
    );
    await userEvent.click(screen.getByRole('button', { name: t('m10w.reorder') }));
    await userEvent.click(screen.getByRole('button', { name: t('m10.down', ['Step A']) }));
    await userEvent.click(screen.getByRole('button', { name: t('m10.up', ['Step A']) }));
    await userEvent.click(screen.getByRole('button', { name: t('m10.proposeOrder') }));
    await userEvent.click(
      within(screen.getAllByRole('dialog').at(-1) as HTMLElement).getByRole('button', {
        name: t('common.cancel'),
      }),
    );
    await userEvent.click(screen.getByRole('button', { name: t('m10.proposeOrder') }));
    const reason = screen.getAllByRole('dialog').at(-1) as HTMLElement;
    await userEvent.type(within(reason).getByRole('textbox'), 'Reorder');
    await userEvent.click(within(reason).getByRole('button', { name: t('common.submit') }));
    expect(await screen.findByRole('alert')).toBeTruthy();
    expect(sent(calls, 'POST', '/v1/run-order-steps/proposals')).toEqual([
      { order: ['A', 'B'], reason: 'Reorder' },
    ]);
  });

  it('SRS-15-1: a run order waiting for the Checker is shown and cannot be moved', async () => {
    backend({
      'GET /v1/payroll-calendars': () => ['2027-28'],
      'GET /v1/payroll-calendars/2027-28': () => ({
        finYear: '2027-28',
        periods: [],
        pendingApprovalId: null,
      }),
      'GET /v1/run-order-steps': () => ({
        steps: [
          { sequence: 10, code: 'A', name: 'Step A', dependsOn: null, ownerRole: null, payrollType: null },
        ],
        pendingApprovalId: 88,
      }),
    });
    wrap(<PayrollCalendar />, ['M-10:VIEW', 'M-10:REORDER']);
    expect(await screen.findByText(t('m10.orderPending', [88]))).toBeTruthy();
    // No dialog to move a step in: Reorder is drawn, disabled with the waiting proposal as the reason.
    expect(screen.getByRole('button', { name: t('m10w.reorder') })).toHaveAttribute(
      'data-why',
      t('m10.orderPending', [88]),
    );
  });

  it('SRS-15-35: the O-02 dialogs close without a change; a refused suspension is shown; a unit is needed', async () => {
    const calls = backend({
      'GET /v1/release-suspensions?': (url) =>
        paged(
          [
            {
              id: 5,
              scope: 'UNIT',
              unitCode: 'DH001',
              channel: 'BANK_FILE',
              suspendedBy: 'finance.hq',
              suspendedAt: '2026-10-25T06:00:00Z',
              reason: 'Mismatch',
              resumedBy: null,
              resumedAt: null,
              resumeReason: null,
              pendingApprovalId: null,
            },
          ],
          1,
          url,
        ),
      'POST /v1/release-suspensions': () => problem(422, 'SUS-422-SCOPE', ['UNIT_NOT_A_CYCLE_UNIT']),
    });
    wrap(<ReleaseSuspensions />, me(['O-02:VIEW', 'O-02:SUSPEND', 'O-02:RESUME'], { atHeadquarters: true }));
    await userEvent.click(await screen.findByRole('button', { name: t('o02.resume', [5]) }));
    await userEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: t('common.cancel') }),
    );
    await userEvent.click(screen.getByRole('button', { name: t('o02.suspend') }));
    const dialog = screen.getByRole('dialog');
    await userEvent.click(within(dialog).getByRole('button', { name: t('common.cancel') }));
    await userEvent.click(screen.getByRole('button', { name: t('o02.suspend') }));
    const again = screen.getByRole('dialog');
    await userEvent.selectOptions(within(again).getByLabelText(t('o02.scope')), 'UNIT');
    await userEvent.type(within(again).getByLabelText(t('common.reason')), 'Stop now');
    const submit = within(again).getByRole('button', { name: t('o02.suspend') });
    expect(submit).toBeDisabled();
    await userEvent.type(within(again).getByLabelText(t('o02.unitCode')), 'DP-WB01');
    await userEvent.click(submit);
    expect(await screen.findByRole('alert')).toBeTruthy();
    expect(sent(calls, 'POST', '/v1/release-suspensions')).toHaveLength(1);
  });
});

describe('C-13 history, halt and commands', () => {
  it('SRS-NF-3: the history is filtered by status, type, dates and requester, and paged', async () => {
    const calls = backend({
      'GET /v1/jobs?': (url) => paged([job()], 45, url),
      'GET /v1/reference/lists': () => REFERENCE,
    });
    wrap(<JobHistory onOpen={vi.fn()} />, ['C-13:VIEW']);
    await screen.findByRole('option', { name: 'Failed' });
    await userEvent.selectOptions(screen.getByLabelText(t('c13.col.status')), 'FAILED');
    await userEvent.selectOptions(screen.getByLabelText(t('c13.jobType')), 'PAYROLL_DRY_RUN');
    await userEvent.type(screen.getByLabelText(t('c13.filter.from')), '2026-07-01');
    await userEvent.type(screen.getByLabelText(t('c13.filter.to')), '2026-07-31');
    await userEvent.type(screen.getByLabelText(t('c13.col.requestedBy')), 'maker');
    await waitFor(() => {
      const last = calls.filter((c) => c.url.includes('/v1/jobs?')).at(-1)?.url ?? '';
      expect(last).toContain('status=FAILED');
      expect(last).toContain('jobType=PAYROLL_DRY_RUN');
      expect(last).toContain('requestedBy=maker');
    });
    await userEvent.click(screen.getByRole('button', { name: t('page.next') }));
    await waitFor(() => expect(calls.some((c) => c.url.includes('page=1'))).toBe(true));
    await userEvent.selectOptions(screen.getByLabelText(t('page.size')), '50');
    await waitFor(() => expect(calls.some((c) => c.url.includes('size=50'))).toBe(true));
  });

  it('SRS-15-25: a refused halt is said; halted, the administrator asks for the release', async () => {
    const halted: Halt = {
      engaged: true,
      haltId: 3,
      haltedBy: 'sysadmin',
      haltedAt: '2026-10-25T06:00:00Z',
      reason: 'Wrong rate',
      releaseApprovalId: null,
      releaseRequestedBy: null,
      releaseReason: null,
    };
    let state: Halt = { ...halted, engaged: false, haltId: null };
    const calls = backend({
      'GET /v1/jobs/halt': () => state,
      'GET /v1/jobs?': (url) => paged([], 0, url),
      'GET /v1/reference/lists': () => REFERENCE,
      'POST /v1/jobs/halt': () => problem(409, 'JOB-409-HALTED'),
      'POST /v1/jobs/halt/release': () => ({ ...halted, releaseApprovalId: 70 }),
    });
    const view = wrap(
      <BatchJobs />,
      me(['C-13:VIEW', 'C-13:HALT'], { role: 'SYSTEM_ADMINISTRATOR', atHeadquarters: true }),
    );
    await userEvent.click(await screen.findByRole('button', { name: t('c13.halt.button') }));
    await userEvent.type(within(screen.getByRole('dialog')).getByRole('textbox'), 'Stop');
    await userEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: t('c13.halt.button') }),
    );
    expect(await screen.findByText(t('error.JOB-409-HALTED'))).toBeTruthy();
    view.unmount();

    state = halted;
    wrap(
      <BatchJobs />,
      me(['C-13:VIEW', 'C-13:HALT'], { role: 'SYSTEM_ADMINISTRATOR', atHeadquarters: true }),
    );
    await userEvent.click(await screen.findByRole('button', { name: t('c13.halt.release') }));
    await userEvent.type(within(screen.getByRole('dialog')).getByRole('textbox'), 'Rate fixed');
    await userEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: t('c13.halt.release') }),
    );
    expect(await screen.findByText(t('c13.halt.releaseRequested'))).toBeTruthy();
    expect(sent(calls, 'POST', '/v1/jobs/halt/release')).toEqual([{ reason: 'Rate fixed' }]);
  });

  it('SRS-15-25: a refused command is said; a command or a decision dialog closes without a change', async () => {
    const calls = backend({ 'POST /v1/jobs/12/pause': () => problem(409, 'JOB-409-STATE', ['RUNNING']) });
    const view = wrap(
      <JobActions job={job()} access={{ view: true, submit: true, control: true, halt: false }} />,
      ['C-13:VIEW', 'C-13:EDIT'],
    );
    await userEvent.click(screen.getByRole('button', { name: t('c13.command.STOP') }));
    await userEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: t('common.cancel') }),
    );
    await userEvent.click(screen.getByRole('button', { name: t('c13.command.PAUSE') }));
    await userEvent.type(within(screen.getByRole('dialog')).getByRole('textbox'), 'hold');
    await userEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: t('c13.command.PAUSE') }),
    );
    expect(await screen.findByText(t('error.JOB-409-STATE', ['RUNNING']))).toBeTruthy();
    expect(sent(calls, 'POST', '/v1/jobs/12/stop')).toEqual([]);
    view.unmount();

    wrap(
      <JobApproval
        job={job({ status: 'AWAITING_APPROVAL', approvalId: 77, canDecide: true, allowedCommands: [] })}
      />,
      ['C-13:VIEW'],
    );
    await userEvent.click(screen.getByRole('button', { name: t('c13.approval.reject') }));
    await userEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: t('common.cancel') }),
    );
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('SRS-NF-2: a submitted job opens on its own detail', async () => {
    backend({
      'GET /v1/jobs/halt': () => ({
        engaged: false,
        haltId: null,
        haltedBy: null,
        haltedAt: null,
        reason: null,
        releaseApprovalId: null,
        releaseRequestedBy: null,
        releaseReason: null,
      }),
      'GET /v1/jobs?': (url) => paged([], 0, url),
      'GET /v1/jobs/types': () => ({
        types: [
          { type: 'PAYROLL_DRY_RUN', params: [{ name: 'locationId', kind: 'LOCATION', required: true }] },
        ],
        currentPeriod: '2026-07',
        locations: [{ id: 3, code: 'DH001', label: 'DO - Delhi HQ (DH001)' }],
      }),
      'POST /v1/jobs': () => job(),
      'GET /v1/jobs/12': () => ({ job: job(), attempts: [], controls: [], audit: [], skippedTotal: 0 }),
      'GET /v1/reference/lists': () => REFERENCE,
    });
    wrap(<BatchJobs />, me(['C-13:VIEW', 'C-13:CREATE']));
    await userEvent.click(await screen.findByRole('tab', { name: t('c13.tab.submit') }));
    await userEvent.type(await screen.findByLabelText(/Reason/), 'Dry run before verification');
    await userEvent.click(screen.getByRole('button', { name: 'Review & submit' }));
    await userEvent.click(
      within(await screen.findByRole('dialog')).getByRole('button', { name: 'Submit job' }),
    );
    expect(await screen.findByRole('button', { name: new RegExp(t('c13.back')) })).toBeTruthy();
  });

  it('SRS-NF-2: a job opened from the history shows its detail and goes back; the halt dialog closes without a change', async () => {
    const calls = backend({
      'GET /v1/jobs/halt': () => ({
        engaged: false,
        haltId: null,
        haltedBy: null,
        haltedAt: null,
        reason: null,
        releaseApprovalId: null,
        releaseRequestedBy: null,
        releaseReason: null,
      }),
      'GET /v1/jobs?': (url) => paged([job()], 1, url),
      'GET /v1/jobs/12': () => ({ job: job(), attempts: [], controls: [], audit: [], skippedTotal: 0 }),
      'GET /v1/reference/lists': () => REFERENCE,
    });
    wrap(
      <BatchJobs />,
      me(['C-13:VIEW', 'C-13:HALT'], { role: 'SYSTEM_ADMINISTRATOR', atHeadquarters: true }),
    );
    await userEvent.click(await screen.findByRole('button', { name: t('c13.open', [12]) }));
    await userEvent.click(await screen.findByRole('button', { name: new RegExp(t('c13.back')) }));
    expect(await screen.findByRole('button', { name: t('c13.open', [12]) })).toBeTruthy();
    await userEvent.click(screen.getByRole('button', { name: t('c13.halt.button') }));
    await userEvent.click(
      within(screen.getByRole('dialog')).getByRole('button', { name: t('common.cancel') }),
    );
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(sent(calls, 'POST', '/v1/jobs/halt')).toEqual([]);
  });

  it('a status the screen does not know yet is shown grey with its code', async () => {
    backend({ 'GET /v1/reference/lists': () => REFERENCE });
    wrap(<JobStatusBadge status={'NEW_STATE' as Job['status']} />, ['C-13:VIEW']);
    const pill = await screen.findByText(/NEW_STATE/);
    expect(pill.className).toContain('p-gray');
  });
});
